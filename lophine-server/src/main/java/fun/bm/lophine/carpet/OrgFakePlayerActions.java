// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.bot.ServerBot;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Org item-operation actions run entirely on the fake player's owner, through native menus.
 */
public final class OrgFakePlayerActions {
    public record Filter(String expression, Predicate<ItemStack> predicate) implements Predicate<ItemStack> {
        @Override
        public boolean test(ItemStack stack) {
            return predicate.test(stack);
        }
    }

    public static final Filter EMPTY = new Filter("minecraft:air", ItemStack::isEmpty);
    public static final Filter ANY = new Filter("*", stack -> !stack.isEmpty());

    public record Librarian(BlockPos jobSite, int minLevel, int maxPrice) {
        public Librarian {
            jobSite = jobSite.immutable();
        }
    }

    public record Action(String kind, List<Filter> filters, int index, String name, Holder<Enchantment> enchantment,
                         boolean dropOther, boolean moreContainer, boolean voidTrade, Vec3 selected, Vec3 other,
                         Librarian librarian) {
        public Action(String kind, List<Filter> filters, int index, String name, Holder<Enchantment> enchantment, boolean dropOther, boolean moreContainer, boolean voidTrade, Vec3 selected, Vec3 other) {
            this(kind, filters, index, name, enchantment, dropOther, moreContainer, voidTrade, selected, other, null);
        }

        public Action {
            filters = List.copyOf(filters);
        }

        public static Action simple(String kind, List<Filter> filters) {
            return new Action(kind, filters, 0, "", null, false, false, false, Vec3.ZERO, Vec3.ZERO);
        }
    }

    private static final class State {
        final Action action;
        int timer, mergeTimer = 40, refreshCount;
        boolean pendingArea, digging, notifiedLocked, notifiedMaterials, notifiedExperience;
        long areaEpoch;
        long started;
        CarpetPlayerActionPack.Action miningAction;

        State(Action action) {
            this.action = action;
        }
    }

    // Values contain no player reference: retiring players and their actions can be collected.
    private static final carpet.script.external.WeakIdentityMap<ServerPlayer, State> STATES = new carpet.script.external.WeakIdentityMap<>();
    // Keep accepted work separate from the selected action. Replacing/stopping an action
    // cannot hide the native effects already accepted by its previous state.
    private static final carpet.script.external.WeakIdentityMap<ServerPlayer, CarpetActionCompletion> COMPLETIONS = new carpet.script.external.WeakIdentityMap<>();

    private OrgFakePlayerActions() {
    }

    private static CarpetActionCompletion completion(ServerPlayer player) {
        synchronized (COMPLETIONS) {
            return COMPLETIONS.computeIfAbsent(player, ignored -> new CarpetActionCompletion());
        }
    }

    public static CompletableFuture<Void> pendingCompletion(ServerPlayer player) {
        return completion(player).pendingCompletion();
    }

    public static <T> CompletableFuture<T> whenIdle(ServerPlayer player, Supplier<T> snapshot) {
        TickThread.ensureTickThread(player, "Org action snapshot requires its owner");
        if (!(player instanceof ServerBot)) return nativePlayerSnapshot(player, snapshot, false);
        // Pause public, hidden and native-pack producers before closing the general inventory
        // gate. Otherwise a hidden job could wait for that gate while its snapshot waits for it.
        return completion(player).whenIdle(work -> owned(player, () ->
                OrgHiddenPlayerActions.whenIdle(player, () -> carpet.script.external.ScarpetPlayerInventoryGate.whenIdle(player, work)))
                .thenCompose(Function.identity()).thenCompose(Function.identity()), snapshot);
    }

    /**
     * Mandatory removal waits real termination, including old failure; new save failures remain failures.
     */
    public static <T> CompletableFuture<T> whenIdleForRemoval(ServerPlayer player, Supplier<T> snapshot) {
        TickThread.ensureTickThread(player, "Mandatory Org snapshot requires its owner");
        if (!(player instanceof ServerBot)) return nativePlayerSnapshot(player, snapshot, true);
        return completion(player).whenIdleAfterTermination(work -> owned(player, () ->
                OrgHiddenPlayerActions.whenIdleForRemoval(player, () -> carpet.script.external.ScarpetPlayerInventoryGate.whenIdleForRemoval(player, work)))
                .thenCompose(Function.identity()).thenCompose(Function.identity()), snapshot);
    }

    private static <T> CompletableFuture<T> nativePlayerSnapshot(ServerPlayer player, Supplier<T> snapshot, boolean mandatory) {
        var actual = new CompletableFuture<T>();
        carpet.script.external.ScarpetNativeWork.record(actual);
        carpet.script.external.ScarpetNativeWork.trackNative(player.level().getServer(), actual);
        try {
            Supplier<CompletableFuture<T>> inventory = () -> mandatory
                    ? carpet.script.external.ScarpetPlayerInventoryGate.whenIdleForRemoval(player, snapshot)
                    : carpet.script.external.ScarpetPlayerInventoryGate.whenIdle(player, snapshot);
            CompletableFuture<T> body = player.carpetActionPack == null ? inventory.get()
                    : (mandatory ? player.carpetActionPack.whenIdleForRemoval(inventory) : player.carpetActionPack.whenIdle(inventory)).thenCompose(Function.identity());
            carpet.script.external.ScarpetNativeWork.aliasDependency(actual, body);
            body.whenComplete((value, failure) -> {
                if (failure == null) actual.complete(value);
                else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        var caller = actual.copy();
        carpet.script.external.ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }

    static <T> CompletableFuture<T> owned(ServerPlayer player, Supplier<T> work) {
        var result = new CompletableFuture<T>();
        java.util.function.Consumer<ServerPlayer> run = carpet.script.external.ScarpetRuntime.captureNativeConsumer(owner -> {
            try {
                if (owner != player || owner.isRemoved()) throw new IllegalStateException("Fake-player owner retired before its snapshot");
                result.complete(work.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        if (TickThread.isTickThreadFor(player)) run.accept(player);
        else if (!player.getBukkitEntity().taskScheduler.schedule(run, carpet.script.external.ScarpetRuntime.captureNativeConsumer(retired -> result.completeExceptionally(new IllegalStateException("Fake-player owner retired before its snapshot"))), 1L))
            result.completeExceptionally(new IllegalStateException("Fake-player owner retired before its snapshot"));
        return result;
    }

    public static void set(ServerPlayer player, Action action) {
        if (!(player instanceof ServerBot) || !TickThread.isTickThreadFor(player))
            throw new IllegalStateException("Fake action assignment requires its owner");
        OrgHiddenPlayerActions.stopOwner(player);
        State previous = STATES.remove(player);
        if (previous != null && previous.miningAction != null && player.carpetActionPack.getAction(CarpetPlayerActionPack.ActionType.ATTACK) == previous.miningAction)
            player.carpetActionPack.start(CarpetPlayerActionPack.ActionType.ATTACK, null);
        if (!action.kind.equals("stop")) STATES.put(player, new State(action));
    }

    public static Action get(ServerPlayer player) {
        if (!TickThread.isTickThreadFor(player)) throw new IllegalStateException("Fake action read requires its owner");
        State state = STATES.get(player);
        return state == null ? Action.simple("stop", List.of()) : state.action;
    }

    public static void tick(ServerPlayer player) {
        if (!(player instanceof ServerBot bot) || !TickThread.isTickThreadFor(player)) return;
        if (player.isRemoved() || player.isDeadOrDying()) {
            STATES.remove(player);
            return;
        }
        if (carpet.script.external.ScarpetPlayerInventoryGate.paused(player)) return;
        var completion = completion(player);
        if (completion.paused() || completion.hasPending()) return;
        State state = STATES.get(player);
        if (state == null) return;
        AbstractContainerMenu menu = state.action.kind.equals("craft_inventory") ? player.inventoryMenu : player.containerMenu;
        if (!menuOwned(menu)) return;
        var accepted = completion.begin();
        var actual = carpet.script.external.ScarpetNativeWork.observeNative(player, () -> {
            try (var admitted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)) {
                var scope = OrgItemShadowGroups.inventory(player, menu.slots);
                var result = OrgItemShadowGroups.attempt(scope, () -> {
                    try {
                        OrgGameplayHelper.withOrgAction(() -> execute(bot, state, menu));
                    } catch (RuntimeException failure) {
                        STATES.remove(player);
                        announce(player, Component.literal("Org action stopped: " + failure.getMessage()));
                    }
                    return true;
                });
                return null;
            }
        });
        carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(player, actual);
        actual.whenComplete((ignored, failure) -> accepted.finish(failure));
    }

    private static boolean menuOwned(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) if (!containerOwned(slot.container)) return false;
        var location = menu.getBukkitView().getTopInventory().getLocation();
        return location == null || TickThread.isTickThreadFor(((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle(), BlockPos.containing(location.getX(), location.getY(), location.getZ()));
    }

    private static boolean containerOwned(net.minecraft.world.Container container) {
        if (container instanceof net.minecraft.world.CompoundContainer compound)
            return containerOwned(compound.container1) && containerOwned(compound.container2);
        if (container instanceof Inventory inventory) return TickThread.isTickThreadFor(inventory.player);
        if (container instanceof Entity || container instanceof net.minecraft.world.level.block.entity.BlockEntity)
            return OrgItemShadowGroups.owned(container);
        var location = container.getLocation();
        return location == null || TickThread.isTickThreadFor(((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle(), BlockPos.containing(location.getX(), location.getY(), location.getZ()));
    }

    private static void execute(ServerBot player, State state, AbstractContainerMenu menu) {
        var steps = new MenuSteps(player, state, menu);
        var prepared = steps.effect(() -> {
            if (menu instanceof AbstractCraftingMenu crafting) menu.slotsChanged(crafting.craftSlots);
            else if (menu instanceof AnvilMenu || menu instanceof StonecutterMenu || menu instanceof MerchantMenu)
                menu.slotsChanged(menu.getSlot(0).container);
        });
        var action = TisCommandContinuations.then(prepared, ignored -> switch (state.action.kind) {
            case "craft_inventory", "craft_table" -> steps.craft();
            case "stonecutting" -> steps.stonecut();
            case "trade" -> steps.trade();
            case "rename" -> steps.rename();
            case "enchanting" -> steps.enchant();
            case "empty" -> steps.empty();
            case "fill" -> steps.fill();
            case "sorting" -> steps.sorting();
            case "fishing" -> steps.effect(() -> fishing(player, state));
            case "librarian" -> steps.effect(() -> librarian(player, state));
            default ->
                    CompletableFuture.failedFuture(new IllegalStateException("Unknown Org action " + state.action.kind));
        });
        var merged = TisCommandContinuations.then(action, ignored -> steps.effect(() -> {
            if (--state.mergeTimer <= 0) {
                state.mergeTimer = 40;
                mergeEmptyBoxes(player);
            }
        }));
        var broadcast = TisCommandContinuations.then(merged, ignored -> steps.effect(menu::broadcastChanges));
        var updated = TisCommandContinuations.then(broadcast, ignored -> steps.effect(player::detectEquipmentUpdates));
        var stopped = TisCommandContinuations.then(updated.handle((ignored, failure) -> failure), failure -> failure == null
                ? CompletableFuture.completedFuture(null) : OrgMenuNativeEffects.run(player, () -> {
            STATES.remove(player, state);
            announce(player, Component.literal("Org action stopped: " + failure.getMessage()));
            return null;
        }));
        carpet.script.external.ScarpetNativeWork.record(stopped);
    }

    /**
     * Each physical menu call ends, including its dynamic children, before the next source read.
     */
    private static final class MenuSteps {
        final ServerBot player;
        final State state;
        final AbstractContainerMenu menu;

        MenuSteps(ServerBot player, State state, AbstractContainerMenu menu) {
            this.player = player;
            this.state = state;
            this.menu = menu;
        }

        <T> CompletableFuture<T> read(Supplier<T> operation) {
            var phase = OrgMenuNativeEffects.run(player, () -> {
                if (!menuOwned(menu))
                    throw new IllegalStateException("The Org action menu moved outside its owner's region");
                var value = new CompletableFuture<T>();
                OrgItemShadowGroups.actor(player, () -> OrgItemShadowGroups.inventory(player, menu.slots), () -> {
                    T actual = OrgGameplayHelper.withOrgAction(operation);
                    value.complete(actual);
                    return actual;
                }, null, owner -> {
                    if (owner != player || player.isRemoved() || !menuOwned(menu))
                        throw new IllegalStateException("The accepted Org menu owner retired or moved");
                    return true;
                }, ignored -> {
                });
                return value;
            });
            var actual = phase.thenCompose(Function.identity());
            carpet.script.external.ScarpetNativeWork.record(actual);
            return actual;
        }

        CompletableFuture<Void> effect(Runnable operation) {
            return read(() -> {
                operation.run();
                return null;
            });
        }

        <T, R> CompletableFuture<R> after(CompletableFuture<T> before, Function<T, CompletableFuture<R>> next) {
            return TisCommandContinuations.then(before, next);
        }

        CompletableFuture<Void> click(int slot, int button, ContainerInput type) {
            return effect(() -> menu.clicked(slot, button, type, player));
        }

        CompletableFuture<Void> click(int slot, int button) {
            return click(slot, button, ContainerInput.PICKUP);
        }

        CompletableFuture<Void> cursor() {
            return after(read(() -> {
                ItemStack carried = menu.getCarried();
                if (carried.isEmpty()) return false;
                OrgFakePlayerInventory.insert(player, carried);
                return true;
            }), present -> present ? effect(() -> menu.setCarried(ItemStack.EMPTY)) : CompletableFuture.completedFuture(null));
        }

        CompletableFuture<Void> returnSlot(int slot) {
            return after(read(() -> menu.getSlot(slot).hasItem()), present -> !present ? CompletableFuture.completedFuture(null)
                    : after(click(slot, 0), ignored -> after(cursor(), nothing -> click(slot, 0))));
        }

        CompletableFuture<Void> dropCursor() {
            return after(read(() -> !menu.getCarried().isEmpty()), present -> present ? click(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, 0) : CompletableFuture.completedFuture(null));
        }

        CompletableFuture<Boolean> move(int source, int destination) {
            return after(read(() -> transferable(menu.getSlot(source).getItem()) > 0), available -> {
                if (!available) return CompletableFuture.completedFuture(false);
                var prepared = after(cursor(), ignored -> click(source, 0));
                var kept = after(prepared, ignored -> after(read(() -> GeneralCompatConfig.fakePlayerActionKeepItem && menu.getCarried().getMaxStackSize() > 1), keep -> keep ? click(source, 1) : CompletableFuture.completedFuture(null)));
                var placed = after(kept, ignored -> click(destination, 0));
                var returned = after(placed, ignored -> after(read(() -> !menu.getCarried().isEmpty()), remaining -> remaining ? click(source, 0) : CompletableFuture.completedFuture(null)));
                return after(returned, ignored -> after(cursor(), finished -> CompletableFuture.completedFuture(true)));
            });
        }

        CompletableFuture<Boolean> supply(int destination, int start, Predicate<ItemStack> filter, boolean single) {
            return after(read(() -> {
                for (int index = start; index < menu.slots.size(); index++) {
                    ItemStack stack = menu.getSlot(index).getItem();
                    if (!stack.isEmpty() && filter.test(stack) && transferable(stack) > 0) return index;
                }
                return -1;
            }), index -> {
                if (index < 0) return supplyBox(destination, start, filter, single);
                if (!single) return move(index, destination);
                return after(cursor(), ignored -> after(effect(() -> {
                    ItemStack stack = menu.getSlot(index).getItem();
                    menu.setCarried(stack.split(1));
                    menu.getSlot(index).setChanged();
                }), picked -> after(click(destination, 0), placed -> after(cursor(), finished -> CompletableFuture.completedFuture(true)))));
            });
        }

        private record BoxInput(int slot, ItemStack picked) {
        }

        CompletableFuture<Boolean> supplyBox(int destination, int start, Predicate<ItemStack> filter, boolean single) {
            return after(read(() -> {
                if (!GeneralCompatConfig.fakePlayerShulkerBoxItemHandling) return null;
                for (int pass = 0; pass < 2; pass++)
                    for (int index = start; index < menu.slots.size(); index++) {
                        ItemStack box = menu.getSlot(index).getItem();
                        if (!OrgGameplayHelper.isShulkerBox(box) || box.isEmpty() || (pass == 0) != (box.getCount() == 1))
                            continue;
                        ItemStack picked = extractBox(player, box, filter, single ? 1 : Integer.MAX_VALUE);
                        if (!picked.isEmpty()) return new BoxInput(index, picked);
                    }
                return null;
            }), input -> input == null ? CompletableFuture.completedFuture(false) : after(effect(() -> menu.getSlot(input.slot()).setChanged()), changed ->
                    after(cursor(), ignored -> after(effect(() -> menu.setCarried(input.picked())), picked ->
                            after(click(destination, 0), placed -> after(cursor(), finished -> CompletableFuture.completedFuture(true)))))));
        }

        /**
         * Completion-driven trampoline preserves the original bounded loop without recursive immediate futures.
         */
        CompletableFuture<Void> repeat(int maximum, String exhausted, Function<Integer, CompletableFuture<Boolean>> operation) {
            var result = new CompletableFuture<Void>();
            carpet.script.external.ScarpetNativeWork.record(result);
            class Pump {
                final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
                CompletableFuture<Boolean> current;
                int index;

                void run() {
                    if (running.getAndIncrement() != 0) return;
                    do {
                        try {
                            while (!result.isDone()) {
                                if (current == null) {
                                    if (index >= maximum) {
                                        if (exhausted == null) result.complete(null);
                                        else result.completeExceptionally(new IllegalStateException(exhausted));
                                        break;
                                    }
                                    current = operation.apply(index++);
                                    if (!current.isDone())
                                        current.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((more, failure) -> run()));
                                }
                                if (!current.isDone()) break;
                                boolean more = current.join();
                                current = null;
                                if (!more) result.complete(null);
                            }
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    } while (running.decrementAndGet() != 0);
                }
            }
            new Pump().run();
            return result;
        }

        CompletableFuture<Boolean> collect(int output) {
            return after(cursor(), ignored -> after(read(() -> menu.getSlot(output).getItem().copy()), expected -> {
                if (expected.isEmpty()) return CompletableFuture.completedFuture(false);
                var produced = new java.util.concurrent.atomic.AtomicBoolean();
                var loop = repeat(1200, "Item collection exceeded upstream loop limit", index -> after(read(() -> {
                    Slot slot = menu.getSlot(output);
                    return slot.hasItem() && slot.mayPickup(player) && ItemStack.isSameItemSameComponents(expected, slot.getItem());
                }), ready -> !ready ? CompletableFuture.completedFuture(false) : after(click(output, 0), picked -> after(read(() -> !menu.getCarried().isEmpty()), carried -> {
                    if (!carried) return CompletableFuture.completedFuture(false);
                    produced.set(true);
                    return after(dropCursor(), dropped -> CompletableFuture.completedFuture(true));
                }))));
                return after(loop, finished -> CompletableFuture.completedFuture(produced.get()));
            }));
        }

        CompletableFuture<Void> craft() {
            int size = state.action.filters.size();
            if (size == 9 && !(menu instanceof CraftingMenu) || size == 4 && !(menu instanceof InventoryMenu))
                return CompletableFuture.completedFuture(null);
            var count = new java.util.concurrent.atomic.AtomicInteger();
            return repeat(1200, "Craft action exceeded upstream loop limit", round -> {
                var ready = new java.util.concurrent.atomic.AtomicBoolean(true);
                var filled = repeat(size, null, index -> {
                    int input = index + 1;
                    Filter filter = state.action.filters.get(index);
                    return after(read(() -> !menu.getSlot(input).getItem().isEmpty() && !filter.test(menu.getSlot(input).getItem())), wrong ->
                            after(wrong ? returnSlot(input) : CompletableFuture.completedFuture(null), returned -> after(read(() -> filter.test(menu.getSlot(input).getItem())), matches ->
                                    matches ? CompletableFuture.completedFuture(true) : after(supply(input, size + 1, filter, false), available -> {
                                        if (!available) ready.set(false);
                                        return CompletableFuture.completedFuture(true);
                                    }))));
                });
                return after(filled, ignored -> !ready.get() ? CompletableFuture.completedFuture(false) : after(collect(0), produced -> {
                    if (produced) return read(() -> !completed(count.incrementAndGet()));
                    return after(effect(() -> {
                        STATES.remove(player, state);
                        announce(player, Component.literal("Craft recipe has no output; Org action stopped"));
                    }), stopped -> CompletableFuture.completedFuture(false));
                }));
            });
        }

        CompletableFuture<Void> stonecut() {
            if (!(menu instanceof StonecutterMenu cutter)) return CompletableFuture.completedFuture(null);
            var count = new java.util.concurrent.atomic.AtomicInteger();
            Filter filter = state.action.filters.getFirst();
            return repeat(1000, "Stonecutting action exceeded upstream loop limit", round -> {
                var cleared = after(read(() -> !menu.getSlot(0).getItem().isEmpty() && !filter.test(menu.getSlot(0).getItem())), wrong -> wrong ? returnSlot(0) : CompletableFuture.completedFuture(null));
                var supplied = after(cleared, ignored -> after(read(() -> menu.getSlot(0).getItem().isEmpty()), empty -> empty ? supply(0, 2, filter, false) : CompletableFuture.completedFuture(true)));
                return after(supplied, available -> {
                    if (!available) return CompletableFuture.completedFuture(false);
                    return after(effect(() -> cutter.clickMenuButton(player, state.action.index)), selected -> after(collect(1), produced -> {
                        if (produced) return read(() -> !completed(count.incrementAndGet()));
                        return after(effect(() -> {
                            STATES.remove(player, state);
                            announce(player, Component.literal("Stonecutting recipe has no output; Org action stopped"));
                        }), stopped -> CompletableFuture.completedFuture(false));
                    }));
                });
            });
        }

        CompletableFuture<Boolean> fillTradeSlot(int slot, ItemStack cost) {
            return after(read(cost::isEmpty), empty -> empty ? after(returnSlot(slot), ignored -> CompletableFuture.completedFuture(true)) : fillTradeCost(slot, cost));
        }

        CompletableFuture<Boolean> fillTradeCost(int slot, ItemStack cost) {
            Predicate<ItemStack> match = stack -> !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, cost);
            return after(read(() -> !menu.getSlot(slot).getItem().isEmpty() && !match.test(menu.getSlot(slot).getItem())), wrong -> after(wrong ? returnSlot(slot) : CompletableFuture.completedFuture(null), returned -> {
                var enough = new java.util.concurrent.atomic.AtomicBoolean();
                var filled = repeat(menu.slots.size(), null, index -> after(read(() -> match.test(menu.getSlot(slot).getItem()) && menu.getSlot(slot).getItem().getCount() >= cost.getCount()), ready -> {
                    if (ready) {
                        enough.set(true);
                        return CompletableFuture.completedFuture(false);
                    }
                    return supply(slot, 3, match, false);
                }));
                return after(filled, ignored -> CompletableFuture.completedFuture(enough.get()));
            }));
        }

        CompletableFuture<Void> trade() {
            if (!(menu instanceof MerchantMenu merchantMenu)) return CompletableFuture.completedFuture(null);
            return after(read(() -> {
                var merchant = ((org.bukkit.craftbukkit.inventory.CraftMerchant) merchantMenu.getBukkitView().getMerchant()).getMerchant();
                if (merchant instanceof Entity entity && !entity.isRemoved() && !TickThread.isTickThreadFor(entity))
                    return false;
                if (state.action.voidTrade) {
                    if (merchant instanceof Entity entity && !entity.isRemoved()) {
                        state.timer = 1;
                        return false;
                    }
                    if (state.timer > 0) {
                        state.timer--;
                        return false;
                    }
                    state.timer = 1;
                }
                if (state.action.index < 0 || state.action.index >= merchantMenu.getOffers().size()) {
                    STATES.remove(player, state);
                    throw new IllegalStateException("Trade index is outside this merchant's offers");
                }
                return true;
            }), admitted -> {
                if (!admitted) return CompletableFuture.completedFuture(null);
                var loop = repeat(1001, null, index -> {
                    if (index == 1000) throw new OrgActionInfiniteLoopException();
                    return tradeRound(merchantMenu, index);
                });
                return after(loop, finished -> state.action.voidTrade ? effect(player::closeContainer) : CompletableFuture.completedFuture(null));
            });
        }

        CompletableFuture<Boolean> tradeRound(MerchantMenu merchant, int index) {
            return after(read(() -> merchant.getOffers().get(state.action.index)), offer -> after(read(offer::isOutOfStock), out -> {
                if (out) return CompletableFuture.completedFuture(false);
                var selected = effect(() -> merchant.setSelectionHint(state.action.index));
                var first = after(selected, ignored -> after(read(offer::getCostA), cost -> fillTradeSlot(0, cost)));
                var filled = after(first, available -> available ? after(read(offer::getCostB), cost -> fillTradeSlot(1, cost)) : CompletableFuture.completedFuture(false));
                return after(filled, available -> {
                    if (!available) return CompletableFuture.completedFuture(false);
                    return after(read(offer::getResult), expected -> {
                        var produced = new java.util.concurrent.atomic.AtomicBoolean();
                        var outputs = repeat(1200, null, operation -> after(read(() -> {
                            Slot slot = menu.getSlot(2);
                            return slot.hasItem() && slot.mayPickup(player) && ItemStack.isSameItemSameComponents(expected, slot.getItem());
                        }), ready -> !ready ? CompletableFuture.completedFuture(false) : after(click(2, 0, ContainerInput.THROW), dropped -> {
                            produced.set(true);
                            return CompletableFuture.completedFuture(true);
                        })));
                        return after(outputs, finished -> read(() -> {
                            if (!produced.get())
                                throw new IllegalStateException("Merchant did not provide the selected trade output");
                            boolean infinite = GeneralCompatConfig.villagerInfiniteTrade;
                            return (infinite || state.action.voidTrade) && !(infinite && completed(index + 1));
                        }));
                    });
                });
            }));
        }

        CompletableFuture<Boolean> lockTrades() {
            if (!(menu instanceof MerchantMenu merchant)) return CompletableFuture.completedFuture(false);
            var locked = new java.util.concurrent.atomic.AtomicBoolean();
            return after(read(() -> merchant.getOffers().size()), size -> {
                var tried = repeat(size, null, index -> after(read(() -> merchant.getOffers().get(index)), offer -> {
                    var selected = effect(() -> merchant.setSelectionHint(index));
                    var first = after(selected, ignored -> after(read(offer::isOutOfStock), out -> out ? CompletableFuture.completedFuture(false)
                            : after(read(offer::getCostA), cost -> fillTradeSlot(0, cost))));
                    var supplied = after(first, available -> !available ? CompletableFuture.completedFuture(false) : after(read(offer::getCostB), cost -> fillTradeSlot(1, cost)));
                    var ready = after(supplied, available -> !available ? CompletableFuture.completedFuture(false) : read(() -> menu.getSlot(2).hasItem() && menu.getSlot(2).mayPickup(player)));
                    return after(ready, available -> {
                        if (!available) return CompletableFuture.completedFuture(true);
                        var picked = after(cursor(), ignored -> click(2, 0));
                        return after(picked, ignored -> after(dropCursor(), dropped -> {
                            locked.set(true);
                            return CompletableFuture.completedFuture(false);
                        }));
                    });
                }));
                return after(tried, ignored -> CompletableFuture.completedFuture(locked.get()));
            });
        }

        CompletableFuture<Void> rename() {
            if (!(menu instanceof AnvilMenu anvil)) return effect(() -> openAnvil(player, state));
            var count = new java.util.concurrent.atomic.AtomicInteger();
            Filter filter = state.action.filters.getFirst();
            Predicate<ItemStack> match = stack -> filter.test(stack) && !stack.getHoverName().getString().equals(state.action.name);
            return repeat(1200, "Rename action exceeded upstream loop limit", round -> after(read(() -> menu.getSlot(0).hasItem() && !match.test(menu.getSlot(0).getItem())), wrong -> {
                if (wrong) return after(returnSlot(0), returned -> CompletableFuture.completedFuture(true));
                return after(read(() -> menu.getSlot(0).hasItem() && menu.getSlot(0).getItem().getCount() >= menu.getSlot(0).getItem().getMaxStackSize()), full ->
                        after(full ? CompletableFuture.completedFuture(false) : supply(0, 3, stack -> match.test(stack) && (menu.getSlot(0).getItem().isEmpty() || ItemStack.isSameItemSameComponents(menu.getSlot(0).getItem(), stack)), false), supplied -> {
                            if (supplied) return CompletableFuture.completedFuture(true);
                            if (!full) return CompletableFuture.completedFuture(false);
                            return after(returnSlot(1), returned -> after(effect(() -> anvil.setItemName(state.action.name)), named -> after(read(() -> menu.getSlot(2).hasItem() && menu.getSlot(2).mayPickup(player)), ready -> !ready
                                    ? after(effect(() -> lackExperience(player, state, anvil)), notified -> CompletableFuture.completedFuture(false))
                                    : after(click(2, 0), picked -> after(dropCursor(), dropped -> read(() -> !completed(count.incrementAndGet()) && menuOwned(menu) && menu.stillValid(player)))))));
                        }));
            }));
        }

        CompletableFuture<Void> enchant() {
            if (!(menu instanceof AnvilMenu anvil)) return effect(() -> openAnvil(player, state));
            var count = new java.util.concurrent.atomic.AtomicInteger();
            Predicate<ItemStack> book = stack -> stack.is(Items.ENCHANTED_BOOK) && enchanted(stack, state.action.enchantment);
            return repeat(1200, "Enchanting action exceeded upstream loop limit", round -> {
                var cleared = after(read(() -> !canEnchant(player, state.action, menu.getSlot(0).getItem()) || menu.getSlot(0).getItem().getCount() != 1), wrong -> wrong ? returnSlot(0) : CompletableFuture.completedFuture(null));
                var input = after(cleared, ignored -> after(read(() -> menu.getSlot(0).getItem().isEmpty()), empty -> empty ? supply(0, 3, stack -> canEnchant(player, state.action, stack), true) : CompletableFuture.completedFuture(true)));
                return after(input, available -> {
                    if (!available) return CompletableFuture.completedFuture(false);
                    var bookCleared = after(read(() -> !book.test(menu.getSlot(1).getItem())), wrong -> wrong ? returnSlot(1) : CompletableFuture.completedFuture(null));
                    var supplied = after(bookCleared, ignored -> after(read(() -> menu.getSlot(1).getItem().isEmpty()), empty -> empty ? supply(1, 3, book, true) : CompletableFuture.completedFuture(true)));
                    return after(supplied, hasBook -> {
                        if (!hasBook) return CompletableFuture.completedFuture(false);
                        return after(read(() -> {
                            if (!menu.getSlot(2).hasItem() || !enchanted(menu.getSlot(2).getItem(), state.action.enchantment)) {
                                STATES.remove(player, state);
                                throw new IllegalStateException("Anvil could not add the requested enchantment");
                            }
                            return menu.getSlot(2).mayPickup(player);
                        }), ready -> {
                            if (!ready)
                                return after(effect(() -> lackExperience(player, state, anvil)), notified -> CompletableFuture.completedFuture(false));
                            return after(click(2, 1, ContainerInput.THROW), dropped -> read(() -> !completed(count.incrementAndGet()) && menuOwned(menu) && menu.stillValid(player)));
                        });
                    });
                });
            });
        }

        CompletableFuture<Void> empty() {
            if (menu instanceof InventoryMenu) return CompletableFuture.completedFuture(null);
            var loop = repeat(menu.slots.size(), null, index -> after(read(() -> {
                Slot slot = menu.slots.get(index);
                if (slot.container instanceof Inventory) return -1;
                return !slot.getItem().isEmpty() && state.action.filters.getFirst().test(slot.getItem()) ? 1 : 0;
            }), selected -> selected < 0 ? CompletableFuture.completedFuture(false) : selected == 0 ? CompletableFuture.completedFuture(true) : after(click(index, 1, ContainerInput.THROW), dropped -> CompletableFuture.completedFuture(true))));
            return after(loop, finished -> effect(player::closeContainer));
        }

        CompletableFuture<Void> fill() {
            if (menu instanceof InventoryMenu || !state.action.moreContainer && !(menu instanceof ShulkerBoxMenu)
                    || !(menu instanceof ShulkerBoxMenu || menu instanceof ChestMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu || menu instanceof CrafterMenu))
                return CompletableFuture.completedFuture(null);
            return repeat(menu.slots.size(), null, index -> after(read(() -> {
                Slot slot = menu.slots.get(index);
                if (!(slot.container instanceof Inventory) || slot.getItem().isEmpty()) return 0;
                if (state.action.filters.getFirst().test(slot.getItem()) && (!(menu instanceof ShulkerBoxMenu) || slot.getItem().getItem().canFitInsideContainerItems()))
                    return 1;
                return state.action.dropOther ? 2 : 0;
            }), mode -> mode == 0 ? CompletableFuture.completedFuture(true) : mode == 2 ? after(click(index, 1, ContainerInput.THROW), dropped -> CompletableFuture.completedFuture(true))
                    : after(read(() -> menu.slots.get(index).getItem().copy()), before -> after(click(index, 0, ContainerInput.QUICK_MOVE), moved -> after(read(() -> ItemStack.matches(before, menu.slots.get(index).getItem())), unchanged -> unchanged
                    ? after(effect(player::closeContainer), closed -> CompletableFuture.completedFuture(false)) : CompletableFuture.completedFuture(true))))));
        }

        CompletableFuture<Void> sorting() {
            return after(read(() -> player.getInventory().getContainerSize()), size -> repeat(size, null, index -> after(read(() -> player.getInventory().getItem(index)), stack -> {
                return after(read(() -> !stack.isEmpty()), present -> {
                    if (!present) return CompletableFuture.completedFuture(true);
                    return after(read(() -> state.action.filters.stream().anyMatch(filter -> filter.test(stack))), selected -> {
                        var contents = after(read(() -> !selected && OrgGameplayHelper.isShulkerBox(stack)), box -> !box ? CompletableFuture.completedFuture(null)
                                : repeat(100, null, loop -> after(read(() -> extractBox(player, stack, ANY, Integer.MAX_VALUE)), content -> after(read(() -> !content.isEmpty()), found -> !found ? CompletableFuture.completedFuture(false)
                                : after(effect(() -> {
                            player.lookAt(EntityAnchorArgument.Anchor.EYES, state.action.filters.stream().anyMatch(filter -> filter.test(content)) ? state.action.selected : state.action.other);
                            player.drop(content, false, Prediction.SERVER_ONLY);
                        }), dropped -> CompletableFuture.completedFuture(true))))));
                        var dropped = after(contents, ignored -> effect(() -> {
                            player.lookAt(EntityAnchorArgument.Anchor.EYES, selected ? state.action.selected : state.action.other);
                            player.drop(stack.copyAndClear(), false, Prediction.SERVER_ONLY);
                        }));
                        return after(dropped, ignored -> after(effect(() -> player.getInventory().setChanged()), changed -> CompletableFuture.completedFuture(true)));
                    });
                });
            })));
        }
    }

    private static int limit() {
        return GeneralCompatConfig.fakePlayerMaxItemOperationCount;
    }

    private static boolean completed(int count) {
        return limit() > 0 && count >= limit();
    }

    /**
     * Preserve one stackable item only when supplying action input, matching upstream MenuController.
     */
    static int transferable(ItemStack stack) {
        return Math.max(0, stack.getCount() - (GeneralCompatConfig.fakePlayerActionKeepItem && stack.getMaxStackSize() > 1 ? 1 : 0));
    }

    private static ItemStack extractBox(ServerBot player, ItemStack box, Predicate<ItemStack> filter, int maximum) {
        if (box.getCount() > 1 && player.getInventory().getFreeSlot() < 0) return ItemStack.EMPTY;
        var contents = new ArrayList<>(box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).itemCopies().toList());
        for (ItemStack stack : contents) {
            if (stack.isEmpty() || !filter.test(stack)) continue;
            ItemStack picked = stack.split(Math.min(maximum, stack.getCount()));
            ItemStack edited = box.getCount() == 1 ? box : box.split(1);
            edited.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            if (edited != box) OrgFakePlayerInventory.insert(player, edited);
            return picked;
        }
        return ItemStack.EMPTY;
    }

    private static void area(ServerBot player, State state, net.minecraft.world.phys.AABB box, java.util.function.Consumer<ServerBot> action) {
        if (state.pendingArea) return;
        state.pendingArea = true;
        long epoch = ++state.areaEpoch;
        var world = player.level();
        var id = player.getUUID();
        var bounds = CarpetPlayerTargetArea.Bounds.query(box);
        var nativeArea = CarpetRegionLease.<CompletableFuture<Void>>runValue(world, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), lease -> {
            ServerPlayer actor = world.getServer().getPlayerList().getPlayer(id);
            if (!(actor instanceof ServerBot bot) || !TickThread.isTickThreadFor(actor) || actor.isRemoved() || actor.level() != world || STATES.get(actor) != state || state.areaEpoch != epoch)
                return CompletableFuture.completedFuture(null);
            return carpet.script.external.ScarpetNativeWork.observeNative(actor, () -> {
                try (var admitted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(actor)) {
                    OrgItemShadowGroups.attempt(OrgItemShadowGroups.inventory(actor, actor.containerMenu.slots), () -> {
                        try {
                            OrgGameplayHelper.withOrgAction(() -> action.accept(bot));
                        } catch (RuntimeException failure) {
                            STATES.remove(actor);
                            announce(actor, Component.literal("Org action stopped: " + failure.getMessage()));
                        }
                        return true;
                    });
                    return (Void) null;
                }
            });
        }).thenCompose(Function.identity());
        var committed = nativeArea.handle((ignored, failure) -> failure).thenCompose(failure -> owned(player, () -> {
            try (var admitted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)) {
                // The player may have moved outside the captured area. Retry from its current owner.
                if (STATES.get(player) == state && state.areaEpoch == epoch) state.pendingArea = false;
                if (failure != null) throw new java.util.concurrent.CompletionException(failure);
                return (Void) null;
            }
        }));
        carpet.script.external.ScarpetNativeWork.record(committed);
    }

    private static void openAnvil(ServerBot player, State state) {
        if (player.tickCount % 10 != 0) return;
        Vec3 end = player.getEyePosition().add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
        var box = player.getBoundingBox().expandTowards(end.subtract(player.getEyePosition())).inflate(1);
        area(player, state, box, actor -> {
            try {
                var hit = CarpetPlayerTracer.rayTrace(actor, 1.0F, actor.blockInteractionRange(), false);
                if (hit instanceof net.minecraft.world.phys.BlockHitResult block && actor.level().getBlockState(block.getBlockPos()).is(BlockTags.ANVIL)
                        && actor.level().getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, new net.minecraft.world.phys.AABB(block.getBlockPos()).expandTowards(0, 3, 0)).isEmpty())
                    actor.carpetActionPack.start(CarpetPlayerActionPack.ActionType.USE, CarpetPlayerActionPack.Action.once());
            } catch (CarpetPlayerTargetArea.Pending pending) { /* A moved actor retries with a fresh area. */ }
        });
    }

    private static boolean enchanted(ItemStack stack, Holder<Enchantment> enchantment) {
        return !stack.isEmpty() && EnchantmentHelper.getEnchantmentsForCrafting(stack).getLevel(enchantment) > 0;
    }

    private static boolean canEnchant(ServerBot player, Action action, ItemStack stack) {
        if (!action.filters.getFirst().test(stack) || !EnchantmentHelper.canStoreEnchantments(stack) || !action.enchantment.value().canEnchant(stack) && !player.hasInfiniteMaterials() && !stack.is(Items.ENCHANTED_BOOK))
            return false;
        for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet())
            if (entry.getKey().equals(action.enchantment) || !Enchantment.areCompatible(entry.getKey(), action.enchantment))
                return false;
        return true;
    }

    private static void fishing(ServerBot player, State state) {
        Inventory inventory = player.getInventory();
        if (!player.getMainHandItem().is(Items.FISHING_ROD) && !player.getOffhandItem().is(Items.FISHING_ROD)) {
            for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++)
                if (inventory.getItem(i).is(Items.FISHING_ROD)) {
                    inventory.pickSlot(i, inventory.getSelectedSlot());
                    break;
                }
        }
        if (!player.getMainHandItem().is(Items.FISHING_ROD) && !player.getOffhandItem().is(Items.FISHING_ROD)) return;
        var hook = player.fishing;
        if (hook == null) {
            if (state.timer == 0)
                player.carpetActionPack.start(CarpetPlayerActionPack.ActionType.USE, CarpetPlayerActionPack.Action.once());
            else state.timer--;
            return;
        }
        if (!TickThread.isTickThreadFor(hook)) return;
        Entity hooked = hook.getHookedIn();
        if (hook.onGround() || hooked != null && !(hooked instanceof ItemEntity)) {
            if (player.getOffhandItem().is(Items.FISHING_ROD))
                player.carpetActionPack.start(CarpetPlayerActionPack.ActionType.SWAP_HANDS, CarpetPlayerActionPack.Action.once());
            for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++)
                if (!inventory.getItem(i).is(Items.FISHING_ROD)) {
                    inventory.pickSlot(i, inventory.getSelectedSlot());
                    if (!player.getMainHandItem().is(Items.FISHING_ROD)) break;
                }
        }
        if (hook.nibble > 0) {
            player.carpetActionPack.start(CarpetPlayerActionPack.ActionType.USE, CarpetPlayerActionPack.Action.once());
            state.timer = 10;
        }
    }

    private static void librarian(ServerBot player, State state) {
        Librarian spec = state.action.librarian;
        if (spec == null) throw new IllegalStateException("Missing librarian job site");
        BlockPos pos = spec.jobSite;
        if (!TickThread.isTickThreadFor(player.level(), pos) || !player.isWithinBlockInteractionRange(pos, 0)) return;
        if (state.started == 0) state.started = System.nanoTime();
        var block = player.level().getBlockState(pos);
        if (state.digging) {
            if (!block.is(net.minecraft.world.level.block.Blocks.LECTERN)) {
                player.carpetActionPack.start(CarpetPlayerActionPack.ActionType.ATTACK, null);
                state.digging = false;
            } else {
                Inventory inventory = player.getInventory();
                int best = inventory.getSelectedSlot();
                float speed = inventory.getSelectedItem().getDestroySpeed(block);
                for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) {
                    float candidate = inventory.getItem(i).getDestroySpeed(block);
                    if (candidate > speed) {
                        best = i;
                        speed = candidate;
                    }
                }
                inventory.pickSlot(best, inventory.getSelectedSlot());
                player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
                var current = player.carpetActionPack.getAction(CarpetPlayerActionPack.ActionType.ATTACK);
                if (current == null || !current.isContinuous()) {
                    state.miningAction = CarpetPlayerActionPack.Action.continuous();
                    player.carpetActionPack.start(CarpetPlayerActionPack.ActionType.ATTACK, state.miningAction);
                }
            }
            return;
        }
        if (block.isAir() || block.is(net.minecraft.world.level.block.Blocks.WATER)) {
            if (!player.getMainHandItem().is(Items.LECTERN))
                for (int i = 0; i < player.getInventory().getNonEquipmentItems().size(); i++)
                    if (player.getInventory().getItem(i).is(Items.LECTERN)) {
                        player.getInventory().pickSlot(i, player.getInventory().getSelectedSlot());
                        break;
                    }
            if (!player.getMainHandItem().is(Items.LECTERN) && GeneralCompatConfig.fakePlayerShulkerBoxItemHandling) {
                for (ItemStack box : player.getInventory().getNonEquipmentItems()) {
                    if (!OrgGameplayHelper.isShulkerBox(box)) continue;
                    ItemStack lectern = extractBox(player, box, stack -> stack.is(Items.LECTERN), 1);
                    if (!lectern.isEmpty()) {
                        ItemStack previous = player.getMainHandItem();
                        player.setItemInHand(InteractionHand.MAIN_HAND, lectern);
                        OrgFakePlayerInventory.insert(player, previous);
                        break;
                    }
                }
            }
            if (player.getMainHandItem().is(Items.LECTERN)) {
                state.timer = 0;
                state.notifiedMaterials = false;
                // Look at the support block so native use places the replacement at the job site.
                player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos.below()).add(0, 0.499, 0));
                player.carpetActionPack.start(CarpetPlayerActionPack.ActionType.USE, CarpetPlayerActionPack.Action.once());
                state.refreshCount++;
            } else if (++state.timer >= 100 && !state.notifiedMaterials) {
                state.notifiedMaterials = true;
                announce(player, Component.literal("Librarian action is waiting for a lectern"));
            }
            return;
        }
        if (!block.is(net.minecraft.world.level.block.Blocks.LECTERN)) return;
        var box = new net.minecraft.world.phys.AABB(pos.getX() - 128.0, player.level().getMinY() - 64.0, pos.getZ() - 128.0,
                pos.getX() + 128.0, player.level().getMaxY() + 64.0, pos.getZ() + 128.0);
        area(player, state, box, actor -> {
            if (!actor.level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.LECTERN) || !actor.isWithinBlockInteractionRange(pos, 0))
                return;
            var expected = new net.minecraft.core.GlobalPos(actor.level().dimension(), pos);
            var villagers = actor.level().getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, box,
                    villager -> TickThread.isTickThreadFor(villager) && villager.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE).filter(expected::equals).isPresent());
            if (villagers.isEmpty()) return;
            var villager = villagers.getFirst();
            actor.lookAt(EntityAnchorArgument.Anchor.EYES, villager.getEyePosition());
            for (var offer : villager.getOffers()) {
                var stored = offer.getResult().get(DataComponents.STORED_ENCHANTMENTS);
                int level = stored == null ? 0 : stored.getLevel(state.action.enchantment);
                if (level < spec.minLevel || offer.getBaseCostA().getCount() > spec.maxPrice) continue;
                // Remove name tags/leads/spawn eggs before the normal merchant interaction.
                if (actor.getMainHandItem().is(Items.NAME_TAG) || actor.getMainHandItem().is(Items.LEAD) || actor.getMainHandItem().is(Items.VILLAGER_SPAWN_EGG))
                    for (int i = 0; i < actor.getInventory().getNonEquipmentItems().size(); i++) {
                        ItemStack stack = actor.getInventory().getItem(i);
                        if (!stack.is(Items.NAME_TAG) && !stack.is(Items.LEAD) && !stack.is(Items.VILLAGER_SPAWN_EGG)) {
                            actor.getInventory().pickSlot(i, actor.getInventory().getSelectedSlot());
                            break;
                        }
                    }
                librarianLock(actor, state, villager, offer, level);
                return;
            }
            if (villager.getVillagerXp() == 0) state.digging = true;
            else if (!state.notifiedLocked) {
                state.notifiedLocked = true;
                announce(actor, Component.literal("The librarian already has trade experience; its trades cannot be rerolled"));
            }
        });
    }

    private static void librarianLock(ServerBot actor, State state, net.minecraft.world.entity.npc.villager.Villager villager,
                                      net.minecraft.world.item.trading.MerchantOffer offer, int level) {
        var opened = OrgMenuNativeEffects.run(actor, () -> {
            TickThread.ensureTickThread(villager, "Librarian merchant interaction requires the original shared region");
            villager.mobInteract(actor, InteractionHand.MAIN_HAND);
            return null;
        });
        var selected = TisCommandContinuations.then(opened, ignored -> OrgMenuNativeEffects.run(actor, () -> actor.containerMenu));
        var locked = TisCommandContinuations.then(selected, menu -> new MenuSteps(actor, state, menu).lockTrades());
        var closed = TisCommandContinuations.then(locked, success -> TisCommandContinuations.then(
                OrgMenuNativeEffects.run(actor, () -> {
                    actor.closeContainer();
                    return null;
                }), ignored -> OrgMenuNativeEffects.run(actor, () -> {
                    STATES.remove(actor, state);
                    announce(actor, Component.literal("Librarian found: level " + level + ", price " + offer.getBaseCostA().getCount() + ", refreshes " + state.refreshCount
                            + ", elapsed " + (System.nanoTime() - state.started) / 1_000_000_000L + "s, " + (success ? "trade locked" : "trade unlocked")));
                    return null;
                })));
        carpet.script.external.ScarpetNativeWork.record(closed);
    }

    private static void lackExperience(ServerBot player, State state, AnvilMenu menu) {
        if (!player.hasInfiniteMaterials() && player.experienceLevel < menu.getCost() && !state.notifiedExperience) {
            state.notifiedExperience = true;
            announce(player, Component.literal("Org anvil action is waiting for experience"));
        }
    }

    private static void announce(ServerPlayer actor, Component text) {
        Component message = Component.literal(actor.getScoreboardName() + ": ").append(text);
        var server = actor.level().getServer();
        var sent = OrgCommandNativeEffects.broadcast(server, message);
        var logged = TisCommandContinuations.then(sent, ignored -> OrgCommandNativeEffects.global(server, () -> {
            net.minecraft.server.MinecraftServer.LOGGER.info("{}", message.getString());
            return null;
        }));
        carpet.script.external.ScarpetNativeWork.record(logged);
    }

    private static void mergeEmptyBoxes(ServerBot player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack first = player.getInventory().getItem(i);
            if (first.isEmpty() || !OrgGameplayHelper.isShulkerBox(first) || first.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().findAny().isPresent())
                continue;
            for (int j = i + 1; j < player.getInventory().getContainerSize(); j++) {
                ItemStack second = player.getInventory().getItem(j);
                if (OrgItemShadowGroups.sameAlias(first, second) || second.isEmpty() || !ItemStack.isSameItemSameComponents(first, second))
                    continue;
                int count = Math.min(second.getCount(), Math.max(0, first.getMaxStackSize() - first.getCount()));
                first.grow(count);
                second.shrink(count);
            }
        }
        player.getInventory().setChanged();
    }
}
