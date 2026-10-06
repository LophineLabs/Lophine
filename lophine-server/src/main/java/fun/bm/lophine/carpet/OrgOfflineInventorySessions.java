// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * One real native file actor shared by its GUI viewers; no registered fake player.
 */
final class OrgOfflineInventorySessions {
    static final String CUSTODY = "_lophine_offline_inventory_custody";
    private static final Map<MinecraftServer, Map<UUID, Session>> ACTIVE = Collections.synchronizedMap(new WeakHashMap<>());

    private OrgOfflineInventorySessions() {
    }

    private static Map<UUID, Session> sessions(MinecraftServer server) {
        synchronized (ACTIVE) {
            return ACTIVE.computeIfAbsent(server, ignored -> new ConcurrentHashMap<>());
        }
    }

    static CompletableFuture<Boolean> open(ServerPlayer viewer, UUID target, boolean ender) {
        MinecraftServer server = viewer.level().getServer();
        Map<UUID, Session> active;
        Session existing;
        var opened = new CompletableFuture<Boolean>();
        synchronized (ACTIVE) {
            if (carpet.script.external.ScarpetNativeWork.isDraining(server))
                return CompletableFuture.completedFuture(false);
            active = sessions(server);
            existing = active.get(target);
            // Only preparation/opening is a native job during normal play. The same
            // metadata lock fences admission against beginDrain's barrier snapshot.
            carpet.script.external.ScarpetNativeWork.trackNative(server, opened);
        }
        if (existing != null && !existing.closing.get()) {
            existing.open(viewer, ender, -1).whenComplete((value, failure) -> finish(opened, value, failure));
            return opened.copy();
        }
        CompletableFuture.allOf(OrgInventoryTransfers.whenAvailable(server, target), OrgExperienceTransfers.whenAvailable(server, target))
                .thenCompose(ignored -> OrgPlayerFileLease.withLease(server, target, "offline inventory GUI", lease ->
                        recoverBeforeRead(server, target).thenCompose(done -> load(server, target)).thenCompose(raw -> OrgOfflinePlayerSnapshots.parse(server, target, raw))
                                .thenCompose(snapshot -> {
                                    Session session = new Session(server, target, snapshot);
                                    Session previous = active.putIfAbsent(target, session);
                                    if (previous != null) {
                                        previous.open(viewer, ender, -1).whenComplete((value, failure) -> finish(opened, value, failure));
                                        return CompletableFuture.completedFuture(null);
                                    }
                                    var initializing = session.serial(() -> OrgInventoryTransfers.updateVaultMetadata(server, target, session.relative, true, tag -> {
                                                tag.putBoolean(CUSTODY, true);
                                                return tag;
                                            })
                                            .thenCompose(ignored2 -> load(server, target)).thenCompose(raw -> OrgOfflinePlayerSnapshots.parse(server, target, raw))
                                            .thenCompose(updated -> {
                                                session.state = State.from(server, updated);
                                                List<ItemStack> pinned = new ArrayList<>();
                                                pinned.addAll(session.state.inventory);
                                                pinned.addAll(session.state.ender);
                                                pinned.addAll(session.state.cursor);
                                                OrgItemShadowGroups.pin(server, session.relative, pinned);
                                                return session.open(viewer, ender, -1);
                                            }));
                                    if (carpet.script.external.ScarpetNativeWork.isDraining(server))
                                        session.closeIfUnused();
                                    return initializing
                                            .handle((value, failure) -> {
                                                if (failure != null || !Boolean.TRUE.equals(value))
                                                    session.closeIfUnused();
                                                finish(opened, value, failure);
                                                return null;
                                            })
                                            .thenCompose(ignored2 -> session.lifetime);
                                })))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) opened.completeExceptionally(failure);
                });
        return opened.copy();
    }

    private static <T> void finish(CompletableFuture<T> result, T value, Throwable failure) {
        if (failure == null) result.complete(value);
        else result.completeExceptionally(failure);
    }

    private record State(CompoundTag file, List<ItemStack> inventory, List<ItemStack> ender, List<ItemStack> cursor) {
        static State from(MinecraftServer server, OrgOfflinePlayerSnapshots.Snapshot snapshot) {
            var inventory = new ArrayList<>(snapshot.inventory());
            var ender = new ArrayList<>(snapshot.enderItems());
            var cursor = new ArrayList<>(snapshot.recoveredCursor());
            var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            for (Tag value : snapshot.nativeData().getListOrEmpty("CarpetOrgEscrowShadows")) {
                var descriptor = (CompoundTag) value;
                ItemStack restored = OrgItemShadowGroups.restoreCustody(server, OrgShadowInventoryCodec.descriptor(descriptor, ops));
                if (restored == null) continue;
                List<ItemStack> target = switch (descriptor.getIntOr("kind", -1)) {
                    case 0 -> inventory;
                    case 1 -> ender;
                    case 2 -> cursor;
                    default -> throw new IllegalArgumentException("Offline custody kind");
                };
                target.set(descriptor.getIntOr("slot", -1), restored);
            }
            return new State(snapshot.fileData(), inventory, ender, cursor);
        }
    }

    private record Plan(List<ItemStack> before, List<ItemStack> after, List<ItemStack> viewerBefore,
                        List<ItemStack> viewerAfter, List<ItemStack> drops, List<OrgItemShadowGroups.Change> shadows,
                        CompoundTag fileBefore, CompoundTag fileAfter, List<ItemStack> inventoryAfter,
                        List<ItemStack> enderAfter, List<ItemStack> cursorAfter) {
    }

    private static final class Session {
        final MinecraftServer server;
        final UUID target;
        final String relative;
        final CompletableFuture<Void> lifetime = new CompletableFuture<>();
        final AtomicBoolean closing = new AtomicBoolean();
        final AtomicInteger views = new AtomicInteger();
        final java.util.Set<Menu> menus = ConcurrentHashMap.newKeySet();
        volatile State state;
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

        Session(MinecraftServer server, UUID target, OrgOfflinePlayerSnapshots.Snapshot snapshot) {
            this.server = server;
            this.target = target;
            state = State.from(server, snapshot);
            relative = relative(server, target);
        }

        <T> CompletableFuture<T> serial(Supplier<CompletableFuture<T>> action) {
            var captured = carpet.script.external.ScarpetRuntime.captureNativeContinuation(action);
            var result = new CompletableFuture<T>();
            var next = new CompletableFuture<Void>();
            CompletableFuture<Void> previous;
            synchronized (this) {
                previous = tail;
                tail = next;
            }
            previous.whenComplete((ignored, oldFailure) -> {
                CompletableFuture<T> actual;
                try {
                    actual = captured.get();
                } catch (Throwable failure) {
                    actual = CompletableFuture.failedFuture(failure);
                }
                actual.whenComplete((value, failure) -> {
                    finish(result, value, failure);
                    if (failure == null) next.complete(null);
                    else next.completeExceptionally(failure);
                });
            });
            return result;
        }

        CompletableFuture<Boolean> open(ServerPlayer viewer, boolean ender, int boxSlot) {
            if (closing.get()) return CompletableFuture.completedFuture(false);
            views.incrementAndGet();
            var displayed = new AtomicBoolean();
            var released = new AtomicBoolean();
            return OrgMenuNativeEffects.run(viewer, () -> {
                if (closing.get() || carpet.script.external.ScarpetNativeWork.isDraining(server) || !OrgPlayerInventoryMenus.allowedOffline(viewer, target)) {
                    released.set(true);
                    releaseView();
                    return false;
                }
                var services = server.services();
                String displayName = services == null ? target.toString() : services.nameToIdCache().get(target).map(net.minecraft.server.players.NameAndId::name).orElse(target.toString());
                var shown = viewer.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> {
                    Menu menu = new Menu(id, inventory, this, ender, boxSlot);
                    menus.add(menu);
                    return menu;
                }, OrgPlayerInventoryMenus.offlineTitle(target, displayName)));
                if (shown.isEmpty()) {
                    menus.removeIf(menu -> menu.viewer == viewer && viewer.containerMenu != menu);
                    released.set(true);
                    releaseView();
                } else displayed.set(true);
                return shown.isPresent();
            }).exceptionallyCompose(failure -> {
                if (!displayed.get() && released.compareAndSet(false, true)) releaseView();
                return CompletableFuture.failedFuture(failure);
            });
        }

        void releaseView() {
            if (views.decrementAndGet() == 0) closeIfUnused();
        }

        void closeIfUnused() {
            if (views.get() != 0 || !closing.compareAndSet(false, true)) return;
            var actual = serial(() -> holdFinalize(server, target));
            carpet.script.external.ScarpetNativeWork.trackNative(server, actual);
            actual.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    sessions(server).remove(target, this);
                    lifetime.complete(null);
                } else lifetime.completeExceptionally(failure);
            });
        }

        CompletableFuture<Boolean> click(Menu menu, int index, int button, ContainerInput input) {
            var actual = serial(() -> OrgFakePlayerActions.owned(menu.viewer, () -> {
                if (!menu.stillValid(menu.viewer)) return CompletableFuture.<Plan>completedFuture(null);
                return carpet.script.external.ScarpetPlayerInventoryGate.whenIdle(menu.viewer, () -> preview(menu, index, button, input));
            }).thenCompose(value -> value).thenCompose(plan -> {
                if (plan == null) return CompletableFuture.completedFuture(false);
                return OrgFakePlayerActions.owned(menu.viewer, () -> {
                    if (!menu.stillValid(menu.viewer)) return CompletableFuture.completedFuture(false);
                    // Explicit file custody keeps full pointer moves aliased until the last
                    // GUI closes. Normal copy/split still detaches in the native preview.
                    OrgItemShadowGroups.pin(server, relative, plan.after);
                    var settled = new CompletableFuture<Boolean>();
                    boolean accepted = OrgInventoryTransfers.submitVault(menu.viewer,
                            new OrgInventoryTransfers.Vault(target, relative, true, plan.fileBefore, plan.fileAfter), plan.before, plan.after, plan.viewerBefore, plan.viewerAfter, plan.drops, plan.shadows,
                            (java.util.function.Consumer<Boolean>) success -> {
                                if (success)
                                    state = new State(plan.fileAfter, live(plan.inventoryAfter), live(plan.enderAfter), live(plan.cursorAfter));
                                refresh();
                                settled.complete(success);
                            });
                    if (!accepted) settled.complete(false);
                    return settled;
                }).thenCompose(value -> value);
            }));
            return carpet.script.external.ScarpetNativeWork.trackNative(server, actual);
        }

        Plan preview(Menu menu, int index, int button, ContainerInput input) {
            if (!menu.stillValid(menu.viewer)) return null;
            State original = state;
            List<ItemStack> current = menu.ender ? original.ender : original.inventory;
            var preview = new OrgItemShadowGroups.Preview();
            List<ItemStack> topBefore = OrgInventoryTransfers.copies(current);
            List<ItemStack> sourceBefore = OrgInventoryTransfers.viewerState(menu.viewer);
            var inventoryPreview = preview.copies(original.inventory);
            var enderPreview = preview.copies(original.ender);
            var cursorPreview = preview.copies(original.cursor);
            List<ItemStack> references = OrgInventoryTransfers.installPreview(menu.viewer, preview);
            menu.bridge.install(menu.ender ? enderPreview : inventoryPreview);
            List<ItemStack> after, sourceAfter, drops;
            try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(menu.viewer)) {
                drops = OrgInventoryTransfers.preview(() -> menu.nativeClick(index, button, input));
                after = menu.bridge.result();
                sourceAfter = OrgInventoryTransfers.viewerState(menu.viewer);
            } finally {
                OrgInventoryTransfers.restoreReferences(menu.viewer, references);
                menu.bridge.update(current);
            }
            if (OrgInventoryTransfers.same(topBefore, after) && OrgInventoryTransfers.same(sourceBefore, sourceAfter) && drops.isEmpty())
                return null;
            var inventoryAfter = OrgInventoryTransfers.copies(inventoryPreview);
            var enderAfter = OrgInventoryTransfers.copies(enderPreview);
            var cursorAfter = OrgInventoryTransfers.copies(cursorPreview);
            CompoundTag changed = encode(server, original.file, inventoryAfter, enderAfter, cursorAfter, true);
            return new Plan(topBefore, after, sourceBefore, sourceAfter, drops, preview.changes(), original.file, changed, inventoryAfter, enderAfter, cursorAfter);
        }

        void refresh() {
            for (Menu menu : menus)
                OrgFakePlayerActions.owned(menu.viewer, () -> {
                    if (menu.viewer.containerMenu == menu) {
                        menu.bridge.update(menu.ender ? state.ender : state.inventory);
                        menu.broadcastFullState();
                    }
                    return null;
                });
        }
    }

    private static final class Bridge extends SimpleContainer {
        final boolean ender;
        final int boxSlot;
        List<ItemStack> raw, box;

        Bridge(List<ItemStack> items, boolean ender, int boxSlot) {
            super(boxSlot >= 0 ? boxSize(items.get(boxSlot)) : ender ? items.size() : 54);
            this.ender = ender;
            this.boxSlot = boxSlot;
            update(items);
        }

        static int boxSize(ItemStack stack) {
            return fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.largeShulkerBox || stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).size() > 27 ? 54 : 27;
        }

        void update(List<ItemStack> items) {
            raw = OrgInventoryTransfers.copies(items);
            if (boxSlot >= 0) {
                box = new ArrayList<>(raw.get(boxSlot).getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).itemCopies().toList());
                while (box.size() < getContainerSize()) box.add(ItemStack.EMPTY);
            }
        }

        void install(List<ItemStack> items) {
            raw = items;
            if (boxSlot >= 0) {
                box = new ArrayList<>(raw.get(boxSlot).getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).itemCopies().toList());
                while (box.size() < getContainerSize()) box.add(ItemStack.EMPTY);
            }
        }

        int nativeSlot(int slot) {
            return slot < 0 || slot >= getContainerSize() ? -1 : boxSlot >= 0 || ender ? slot : slot < 36 ? slot : slot < 40 ? 75 - slot : slot < 43 ? slot : -1;
        }

        @Override
        public ItemStack getItem(int slot) {
            int nativeSlot = nativeSlot(slot);
            return nativeSlot < 0 ? ItemStack.EMPTY : (boxSlot >= 0 ? box : raw).get(nativeSlot);
        }

        @Override
        public void setItem(int slot, ItemStack item) {
            int nativeSlot = nativeSlot(slot);
            if (nativeSlot >= 0) (boxSlot >= 0 ? box : raw).set(nativeSlot, item);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return nativeSlot(slot) < 0 ? ItemStack.EMPTY : getItem(slot).split(count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack item = getItem(slot);
            setItem(slot, ItemStack.EMPTY);
            return item;
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        List<ItemStack> result() {
            if (boxSlot >= 0)
                raw.get(boxSlot).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(OrgInventoryTransfers.copies(box)));
            return OrgInventoryTransfers.copies(raw);
        }
    }

    private static final class Menu extends ChestMenu {
        final ServerPlayer viewer;
        final Session session;
        final boolean ender;
        final Bridge bridge;
        boolean removed, pending;

        Menu(int id, Inventory inventory, Session session, boolean ender, int boxSlot) {
            this(id, inventory, session, ender, new Bridge(ender ? session.state.ender : session.state.inventory, ender, boxSlot));
        }

        Menu(int id, Inventory inventory, Session session, boolean ender, Bridge bridge) {
            super(bridge.boxSlot >= 0 && bridge.getContainerSize() == 27 ? MenuType.SHULKER_BOX : menuType(bridge.getContainerSize() / 9), id, inventory, bridge, bridge.getContainerSize() / 9);
            this.viewer = (ServerPlayer) inventory.player;
            this.session = session;
            this.ender = ender;
            this.bridge = bridge;
            for (int index = 0; index < bridge.getContainerSize(); index++) {
                Slot old = slots.get(index);
                Slot replacement = null;
                if (bridge.boxSlot >= 0)
                    replacement = new net.minecraft.world.inventory.ShulkerBoxSlot(bridge, index, old.x, old.y);
                else if (bridge.nativeSlot(index) < 0) replacement = new Slot(bridge, index, old.x, old.y) {
                    @Override
                    public boolean mayPlace(ItemStack item) {
                        return false;
                    }

                    @Override
                    public boolean mayPickup(Player player) {
                        return false;
                    }
                };
                if (replacement != null) {
                    replacement.index = index;
                    slots.set(index, replacement);
                }
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return player == viewer && !removed && !session.closing.get() && !carpet.script.external.ScarpetNativeWork.isDraining(session.server) && !viewer.isRemoved() && !viewer.isDeadOrDying() && viewer.containerMenu == this && OrgPlayerInventoryMenus.allowedOffline(viewer, session.target);
        }

        @Override
        public void clicked(int index, int button, ContainerInput input, Player player) {
            if (!stillValid(player) || pending) {
                broadcastFullState();
                return;
            }
            if (fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.quickShulker && input == ContainerInput.PICKUP && button == 1 && getCarried().isEmpty() && index >= 0 && index < slots.size() && OrgQuickShulker.operable(slots.get(index).getItem())) {
                Slot slot = slots.get(index);
                if (slot.container != bridge) {
                    OrgQuickShulker.open(viewer, slot.getItem(), slot);
                    return;
                }
                if (bridge.boxSlot < 0) {
                    session.open(viewer, ender, bridge.nativeSlot(index));
                    return;
                }
            }
            pending = true;
            session.click(this, index, button, input).whenComplete((success, failure) -> OrgFakePlayerActions.owned(viewer, () -> {
                pending = false;
                if (failure != null)
                    viewer.sendSystemMessage(Component.literal("Offline inventory transaction remains unavailable: " + failure.getMessage()));
                if (viewer.containerMenu == this) broadcastFullState();
                return null;
            }));
        }

        void nativeClick(int index, int button, ContainerInput input) {
            super.clicked(index, button, input, viewer);
        }

        @Override
        public boolean canDragTo(Slot slot) {
            return slot.container != bridge || bridge.nativeSlot(slot.getContainerSlot()) >= 0;
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack item, Slot slot) {
            return canDragTo(slot);
        }

        @Override
        public void broadcastChanges() {
            if (!pending && !removed && viewer != null && viewer.containerMenu == this)
                bridge.update(ender ? session.state.ender : session.state.inventory);
            super.broadcastChanges();
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            removed = true;
            if (session.menus.remove(this)) session.releaseView();
        }
    }

    /**
     * Root calls this while holding its actual first-read UUID lease, before native NBT load.
     */
    static CompletableFuture<Void> recoverBeforeRead(MinecraftServer server, UUID player) {
        return CompletableFuture.supplyAsync(() -> Files.exists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat"))).thenCompose(exists -> !exists ? CompletableFuture.completedFuture(null) : load(server, player).thenCompose(raw -> raw.getBooleanOr(CUSTODY, false) ? holdFinalize(server, player) : CompletableFuture.completedFuture(null)));
    }

    private static CompletableFuture<Void> finalizeFile(MinecraftServer server, UUID player) {
        return OrgInventoryTransfers.whenAvailable(server, player).thenCompose(ignored -> load(server, player)).thenCompose(raw -> {
            if (!raw.getBooleanOr(CUSTODY, false))
                return CompletableFuture.runAsync(() -> OrgItemShadowGroups.unpin(server, relative(server, player)));
            return OrgOfflinePlayerSnapshots.parse(server, player, raw).thenCompose(snapshot -> CompletableFuture.supplyAsync(() -> {
                List<ItemStack> inventory = new ArrayList<>(snapshot.inventory()), ender = new ArrayList<>(snapshot.enderItems()), cursor = new ArrayList<>(snapshot.recoveredCursor());
                var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
                for (Tag value : raw.getListOrEmpty("CarpetOrgEscrowShadows")) {
                    CompoundTag descriptor = (CompoundTag) value;
                    ItemStack restored = OrgItemShadowGroups.restoreCustody(server, OrgShadowInventoryCodec.descriptor(descriptor, ops));
                    if (restored == null) continue;
                    List<ItemStack> list = switch (descriptor.getIntOr("kind", -1)) {
                        case 0 -> inventory;
                        case 1 -> ender;
                        case 2 -> cursor;
                        default -> throw new IllegalArgumentException("Offline custody kind");
                    };
                    list.set(descriptor.getIntOr("slot", -1), restored);
                }
                List<ItemStack> before = new ArrayList<>();
                before.addAll(inventory);
                before.addAll(ender);
                before.addAll(cursor);
                var preview = new OrgItemShadowGroups.Preview();
                preview.copies(before);
                List<ItemStack> after = before.stream().map(ItemStack::copy).toList();
                CompoundTag desired = encode(server, snapshot.nativeData(), inventory.stream().map(ItemStack::copy).toList(), ender.stream().map(ItemStack::copy).toList(), cursor.stream().map(ItemStack::copy).toList(), false);
                return OrgInventoryTransfers.transformVault(server, new OrgInventoryTransfers.Vault(player, relative(server, player), true, raw, desired), OrgInventoryTransfers.copies(before), after, preview.changes());
            }).thenCompose(value -> value).thenCompose(success -> {
                if (!success) return retryFinalize(server, player);
                return CompletableFuture.runAsync(() -> OrgItemShadowGroups.unpin(server, relative(server, player)));
            }));
        });
    }

    private static CompletableFuture<Void> retryFinalize(MinecraftServer server, UUID player) {
        return CompletableFuture.runAsync(() -> {
        }, CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS)).thenCompose(ignored -> finalizeFile(server, player));
    }

    private static CompletableFuture<Void> holdFinalize(MinecraftServer server, UUID player) {
        return finalizeFile(server, player).exceptionallyCompose(failure -> {
            MinecraftServer.LOGGER.error("Offline inventory {} retains its UUID/canonical custody until actual readback is available", player, failure);
            return CompletableFuture.runAsync(() -> {
            }, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)).thenCompose(ignored -> holdFinalize(server, player));
        });
    }

    static void retired(ServerPlayer viewer) {
        List<Menu> retired = new ArrayList<>();
        synchronized (ACTIVE) {
            for (var table : ACTIVE.values())
                for (Session session : table.values())
                    for (Menu menu : List.copyOf(session.menus)) if (menu.viewer == viewer) retired.add(menu);
        }
        for (Menu menu : retired) {
            menu.removed = true;
            if (menu.session.menus.remove(menu)) menu.session.releaseView();
        }
    }

    static void beginDrain(MinecraftServer server) {
        List<Session> draining;
        synchronized (ACTIVE) {
            draining = List.copyOf(sessions(server).values());
            // The owner close is still queued. Its exact full session lifetime must
            // already be in the barrier before any scheduler or file actor can yield.
            for (Session session : draining)
                carpet.script.external.ScarpetNativeWork.trackNative(server, session.lifetime);
        }
        for (Session session : draining) {
            session.closeIfUnused();
            for (Menu menu : List.copyOf(session.menus)) {
                boolean queued = menu.viewer.getBukkitEntity().taskScheduler.schedule(owner -> {
                    if (((ServerPlayer) owner).containerMenu == menu) ((ServerPlayer) owner).closeContainer();
                    else {
                        menu.removed = true;
                        if (session.menus.remove(menu)) session.releaseView();
                    }
                }, retired -> retired(menu.viewer), 1L);
                if (!queued) retired(menu.viewer);
            }
        }
    }

    private static MenuType<ChestMenu> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Offline inventory rows outside native 1..6");
        };
    }

    private static List<ItemStack> live(List<ItemStack> items) {
        return items.stream().map(OrgItemShadowGroups::materialize).toList();
    }

    private static CompletableFuture<CompoundTag> load(MinecraftServer server, UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat");
                if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent()))
                    throw new IOException("Offline native file is a symbolic link");
                return NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024 * 1024));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private static String relative(MinecraftServer server, UUID player) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(), file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize().resolve(player + ".dat");
        if (!file.startsWith(root)) throw new IllegalArgumentException("Native offline file leaves the world");
        return root.relativize(file).toString();
    }

    private static CompoundTag encode(MinecraftServer server, CompoundTag original, List<ItemStack> inventory, List<ItemStack> ender, List<ItemStack> cursor, boolean custody) {
        var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        CompoundTag after = NbtUtils.addCurrentDataVersion(original.copy());
        List<ItemStackWithSlot> main = new ArrayList<>(), end = new ArrayList<>();
        var equipment = new EntityEquipment();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++)
            if (!inventory.get(slot).isEmpty()) main.add(new ItemStackWithSlot(slot, inventory.get(slot)));
        for (var entry : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet())
            equipment.set(entry.getValue(), inventory.get(entry.getIntKey()));
        for (int slot = 0; slot < ender.size(); slot++)
            if (!ender.get(slot).isEmpty()) end.add(new ItemStackWithSlot(slot, ender.get(slot)));
        after.put("Inventory", ItemStackWithSlot.CODEC.listOf().encodeStart(ops, main).getOrThrow());
        after.put("equipment", EntityEquipment.CODEC.encodeStart(ops, equipment).getOrThrow());
        after.put("EnderItems", ItemStackWithSlot.CODEC.listOf().encodeStart(ops, end).getOrThrow());
        if (!cursor.isEmpty())
            after.put("CarpetOrgEscrowCursor", ItemStack.OPTIONAL_CODEC.listOf().encodeStart(ops, cursor).getOrThrow());
        ListTag shadows = new ListTag();
        List<List<ItemStack>> lists = List.of(inventory, ender, cursor);
        if (custody) for (int kind = 0; kind < lists.size(); kind++)
            for (int slot = 0; slot < lists.get(kind).size(); slot++) {
                ItemStack item = lists.get(kind).get(slot);
                if (item.carpetOrgShadowId != null) {
                    var identity = OrgShadowInventoryCodec.descriptor(item, ops, false);
                    identity.putInt("kind", kind);
                    identity.putInt("slot", slot);
                    shadows.add(identity);
                }
            }
        if (shadows.isEmpty()) after.remove("CarpetOrgEscrowShadows");
        else after.put("CarpetOrgEscrowShadows", shadows);
        if (custody) after.putBoolean(CUSTODY, true);
        else after.remove(CUSTODY);
        return after;
    }
}
