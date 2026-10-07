// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.CarpetEventServer.Event;
import carpet.script.value.ScreenValue;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Owner -> real VM callback -> owner native replay, with captured state checks and client resynchronization.
 */
public final class ScarpetNativeContinuations {
    private ScarpetNativeContinuations() {
    }

    private record Operation(Object type, Object target) {
    }

    private static final WeakIdentityMap<ServerPlayerGameMode, Map<Operation, CompletableFuture<Boolean>>> BLOCK_RESULTS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<ServerPlayerGameMode, Map<Operation, CompletableFuture<Boolean>>> PAUSED_BLOCK_RESULTS = new WeakIdentityMap<>();

    private record MenuSnapshot(AbstractContainerMenu menu, int state, List<ItemStack> slots, ItemStack carried) {
        static MenuSnapshot capture(ServerPlayer player) {
            AbstractContainerMenu menu = player.containerMenu;
            return new MenuSnapshot(menu, menu.getStateId(), menu.slots.stream().map(slot -> slot.getItem().copy()).toList(), menu.getCarried().copy());
        }

        boolean valid(ServerPlayer player) {
            if (player.isRemoved() || player.containerMenu != menu || menu.getStateId() != state || !menu.stillValid(player)
                    || menu.slots.size() != slots.size() || !ItemStack.matches(carried, menu.getCarried()))
                return false;
            for (int i = 0; i < slots.size(); ++i)
                if (!ItemStack.matches(slots.get(i), menu.slots.get(i).getItem())) return false;
            return true;
        }
    }

    private static void resync(ServerPlayer player) {
        player.containerMenu.sendAllDataToRemote();
    }

    /**
     * Command events observe the command; their boolean return never cancels its native execution.
     */
    public static boolean playerCommand(ServerGamePacketListenerImpl connection,
                                        com.mojang.brigadier.ParseResults<net.minecraft.commands.CommandSourceStack> parsed, String command) {
        if (!Event.PLAYER_COMMAND.isNeeded()) return false;
        ServerPlayer player = connection.player;
        Object operation = new Object();
        Runnable execute = () -> player.level().getServer().getCommands().performCommand(parsed, command);
        return ScarpetRuntime.deferDecisions(player, operation,
                () -> ScarpetRuntime.captureEvent(() -> Event.PLAYER_COMMAND.onPlayerMessage(player, command)).handle((ignored, failure) -> {
                    if (failure != null)
                        carpet.script.CarpetScriptServer.LOG.error("Scarpet player command observer failed", failure);
                    return false;
                }), () -> !player.hasDisconnected(), execute, () -> {
                });
    }

    public static boolean containerClick(ServerGamePacketListenerImpl connection, ServerboundContainerClickPacket packet) {
        ServerPlayer player = connection.player;
        if (ScarpetRuntime.isReplaying(packet) || player.isSpectator() || player.containerMenu.containerId != packet.containerId() || !player.containerMenu.stillValid(player))
            return false;
        List<ScreenValue.ScarpetScreenHandlerListener> listeners = player.containerMenu.carpetScreenListeners();
        if (listeners.isEmpty()) return false;
        MenuSnapshot state = MenuSnapshot.capture(player);
        List<Supplier<Boolean>> callbacks = listeners.stream().<Supplier<Boolean>>map(listener -> () -> listener.onSlotClick(player, packet.containerInput(), packet.slotNum(), packet.buttonNum())).toList();
        return ScarpetRuntime.deferDecisions(player, packet, () -> ScarpetRuntime.captureDecisions(callbacks), () -> state.valid(player), () -> connection.handleContainerClick(packet), () -> resync(player));
    }

    public static boolean containerButton(ServerGamePacketListenerImpl connection, ServerboundContainerButtonClickPacket packet) {
        ServerPlayer player = connection.player;
        if (ScarpetRuntime.isReplaying(packet) || player.isSpectator() || player.containerMenu.containerId != packet.containerId() || !player.containerMenu.stillValid(player))
            return false;
        List<ScreenValue.ScarpetScreenHandlerListener> listeners = player.containerMenu.carpetScreenListeners();
        if (listeners.isEmpty()) return false;
        MenuSnapshot state = MenuSnapshot.capture(player);
        List<Supplier<Boolean>> callbacks = listeners.stream().<Supplier<Boolean>>map(listener -> () -> listener.onButtonClick(player, packet.buttonId())).toList();
        return ScarpetRuntime.deferDecisions(player, packet, () -> ScarpetRuntime.captureDecisions(callbacks), () -> state.valid(player), () -> connection.handleContainerButtonClick(packet), () -> resync(player));
    }

    public static boolean recipe(ServerGamePacketListenerImpl connection, ServerboundPlaceRecipePacket packet) {
        ServerPlayer player = connection.player;
        if (ScarpetRuntime.isReplaying(packet) || player.isSpectator() || player.containerMenu.containerId != packet.containerId() || !player.containerMenu.stillValid(player))
            return false;
        RecipeManager.ServerDisplayInfo display = player.level().getServer().getRecipeManager().getRecipeFromDisplay(packet.recipe());
        if (display == null || !player.getRecipeBook().contains(display.parent().id())) return false;
        List<Supplier<Boolean>> callbacks = new ArrayList<>();
        if (Event.PLAYER_CHOOSES_RECIPE.isNeeded())
            callbacks.add(() -> Event.PLAYER_CHOOSES_RECIPE.onRecipeSelected(player, display.parent().id().identifier(), packet.useMaxItems()));
        for (ScreenValue.ScarpetScreenHandlerListener listener : player.containerMenu.carpetScreenListeners())
            callbacks.add(() -> listener.onSelectRecipe(player, display.parent(), packet.useMaxItems()));
        if (callbacks.isEmpty()) return false;
        MenuSnapshot state = MenuSnapshot.capture(player);
        return ScarpetRuntime.deferDecisions(player, packet, () -> ScarpetRuntime.captureDecisions(callbacks), () -> state.valid(player), () -> connection.handlePlaceRecipe(packet), () -> resync(player));
    }

    public static boolean useItem(ServerGamePacketListenerImpl connection, ServerboundUseItemPacket packet) {
        ServerPlayer player = connection.player;
        if (!Event.PLAYER_USES_ITEM.isNeeded() || ScarpetRuntime.isReplaying(packet)) return false;
        ServerLevel world = player.level();
        ItemStack stack = player.getItemInHand(packet.hand()).copy();
        return ScarpetRuntime.defer(player, packet, () -> Event.PLAYER_USES_ITEM.onItemAction(player, packet.hand(), stack),
                () -> player.level() == world && ItemStack.matches(stack, player.getItemInHand(packet.hand())), () -> connection.handleUseItem(packet), () -> resync(player));
    }

    public static boolean useItemOn(ServerGamePacketListenerImpl connection, ServerboundUseItemOnPacket packet) {
        ServerPlayer player = connection.player;
        if (!Event.PLAYER_RIGHT_CLICKS_BLOCK.isNeeded() || ScarpetRuntime.isReplaying(packet)) return false;
        ServerLevel world = player.level();
        BlockPos pos = packet.hitResult().getBlockPos().immutable();
        if (!TickThread.isTickThreadFor(world, pos.getX() >> 4, pos.getZ() >> 4, 8) || !player.isWithinBlockInteractionRange(pos, 1.0))
            return false;
        BlockState block = world.getBlockState(pos);
        ItemStack stack = player.getItemInHand(packet.hand()).copy();
        return ScarpetRuntime.defer(player, packet, () -> Event.PLAYER_RIGHT_CLICKS_BLOCK.onBlockHit(player, packet.hand(), packet.hitResult()),
                () -> player.level() == world && TickThread.isTickThreadFor(world, pos) && world.getBlockState(pos) == block && ItemStack.matches(stack, player.getItemInHand(packet.hand())) && player.isWithinBlockInteractionRange(pos, 1.0),
                () -> connection.handleUseItemOn(packet), () -> {
                    resync(player);
                    fun.bm.lophine.carpet.CarpetBlockPredictionFence.resyncBlocks(connection, world,
                            pos, pos.relative(packet.hitResult().getDirection()));
                });
    }

    public static boolean playerAction(ServerGamePacketListenerImpl connection, ServerboundPlayerActionPacket packet) {
        ServerPlayer player = connection.player;
        if (ScarpetRuntime.isReplaying(packet)) return false;
        Event event = switch (packet.getAction()) {
            case DROP_ITEM -> Event.PLAYER_DROPS_ITEM;
            case DROP_ALL_ITEMS -> Event.PLAYER_DROPS_STACK;
            case SWAP_ITEM_WITH_OFFHAND -> Event.PLAYER_SWAPS_HANDS;
            case START_DESTROY_BLOCK -> Event.PLAYER_CLICKS_BLOCK;
            default -> null;
        };
        if (event == null || !event.isNeeded()) return false;
        ServerLevel world = player.level();
        MenuSnapshot state = MenuSnapshot.capture(player);
        BlockPos pos = packet.getPos().immutable();
        if (event == Event.PLAYER_CLICKS_BLOCK && (!TickThread.isTickThreadFor(world, pos) || !player.isWithinBlockInteractionRange(pos, 1.0)))
            return false;
        return ScarpetRuntime.defer(player, packet, () -> event == Event.PLAYER_CLICKS_BLOCK ? event.onBlockAction(player, pos, packet.getDirection()) : event.onPlayerEvent(player),
                () -> player.level() == world && state.valid(player) && (event != Event.PLAYER_CLICKS_BLOCK || TickThread.isTickThreadFor(world, pos) && player.isWithinBlockInteractionRange(pos, 1.0)),
                () -> connection.handlePlayerAction(packet), () -> {
                    resync(player);
                    if (event == Event.PLAYER_CLICKS_BLOCK)
                        fun.bm.lophine.carpet.CarpetBlockPredictionFence.resyncBlocks(connection, world, pos);
                });
    }

    public static boolean breakBlock(ServerPlayerGameMode mode, BlockPos position) {
        ServerPlayer player = mode.carpetGetPlayer();
        BlockPos queuedPos = position.immutable();
        Operation queuedKey = new Operation(Event.PLAYER_BREAK_BLOCK, queuedPos);
        if (ScarpetPlayerInventoryGate.paused(player)) {
            var queued = PAUSED_BLOCK_RESULTS.computeIfAbsent(mode, ignored -> new ConcurrentHashMap<>());
            var existing = queued.get(queuedKey);
            if (existing != null) {
                ScarpetNativeWork.record(existing);
                return true;
            }
            var actual = ScarpetPlayerInventoryGate.enqueuePaused(player, () -> {
                boolean immediate = mode.destroyBlock(queuedPos);
                var active = BLOCK_RESULTS.get(mode);
                var result = active == null ? null : active.get(queuedKey);
                return result == null ? CompletableFuture.completedFuture(immediate) : result;
            });
            queued.put(queuedKey, actual);
            actual.whenComplete((ignored, failure) -> queued.remove(queuedKey, actual));
            return true;
        }
        if (!Event.PLAYER_BREAK_BLOCK.isNeeded()) return false;
        ServerLevel world = mode.carpetGetLevel();
        BlockPos pos = position.immutable();
        Operation key = new Operation(Event.PLAYER_BREAK_BLOCK, pos);
        if (ScarpetRuntime.isReplaying(key) || !TickThread.isTickThreadFor(world, pos)) return false;
        BlockState state = world.getBlockState(pos);
        ItemStack tool = player.getMainHandItem().copy();
        var pending = BLOCK_RESULTS.computeIfAbsent(mode, ignored -> new ConcurrentHashMap<>());
        var outcome = new CompletableFuture<Boolean>();
        var existing = pending.putIfAbsent(key, outcome);
        if (existing != null) {
            ScarpetNativeWork.record(existing);
            return true;
        }
        ScarpetNativeWork.record(outcome);
        outcome.whenComplete((result, failure) -> pending.remove(key, outcome));
        boolean deferred;
        try {
            deferred = ScarpetRuntime.defer(player, key, () -> Event.PLAYER_BREAK_BLOCK.onBlockBroken(player, pos, state),
                    () -> player.level() == world && mode.carpetGetLevel() == world && TickThread.isTickThreadFor(world, pos) && world.getBlockState(pos) == state && ItemStack.matches(tool, player.getMainHandItem()),
                    () -> {
                        boolean destroyed = mode.destroyBlock(pos);
                        fun.bm.lophine.carpet.CarpetBlockPredictionFence.resyncBlocks(player.connection, world, pos);
                        outcome.complete(destroyed);
                    },
                    () -> {
                        resync(player);
                        fun.bm.lophine.carpet.CarpetBlockPredictionFence.resyncBlocks(player.connection, world, pos);
                        outcome.complete(false);
                    });
        } catch (Throwable failure) {
            outcome.completeExceptionally(failure);
            throw failure;
        }
        if (!deferred) outcome.complete(false);
        else {
            var tail = ScarpetRuntime.nativeDecisionFuture(player, key);
            if (tail == null)
                outcome.completeExceptionally(new IllegalStateException("Missing native block-break continuation"));
            else tail.whenComplete((ignored, failure) -> {
                if (failure != null) outcome.completeExceptionally(failure);
                else if (!outcome.isDone())
                    outcome.completeExceptionally(new IllegalStateException("Block-break continuation had no outcome"));
            });
        }
        return deferred;
    }

    public static CompletableFuture<Boolean> destroyBlockAsync(ServerPlayerGameMode mode, BlockPos position) {
        BlockPos pos = position.immutable();
        Operation key = new Operation(Event.PLAYER_BREAK_BLOCK, pos);
        if (ScarpetRuntime.currentNativeDecision(mode.carpetGetPlayer(), key))
            throw new carpet.script.exception.InternalExpressionException("harvest cannot await its own pending block-break callback");
        boolean replaying = ScarpetRuntime.isReplaying(key);
        boolean result = mode.destroyBlock(pos);
        var queued = PAUSED_BLOCK_RESULTS.get(mode);
        var paused = queued == null ? null : queued.get(key);
        if (paused != null) return paused;
        var pending = BLOCK_RESULTS.get(mode);
        var future = replaying || pending == null ? null : pending.get(key);
        return future == null ? CompletableFuture.completedFuture(result) : future;
    }

    /**
     * Observes the already submitted block operation without replaying or initiating a second break.
     */
    public static CompletableFuture<?> pendingBlockResult(ServerPlayerGameMode mode, BlockPos position) {
        Operation key = new Operation(Event.PLAYER_BREAK_BLOCK, position.immutable());
        var queued = PAUSED_BLOCK_RESULTS.get(mode);
        var paused = queued == null ? null : queued.get(key);
        if (paused != null) return paused;
        var actual = ScarpetRuntime.nativeDecisionFuture(mode.carpetGetPlayer(), key);
        if (actual != null) return actual;
        var pending = BLOCK_RESULTS.get(mode);
        return pending == null ? null : pending.get(key);
    }

    public static boolean finishItem(ServerPlayer player) {
        Event event = Event.PLAYER_FINISHED_USING_ITEM;
        if (!event.isNeeded() || !player.isUsingItem() || player.getUseItem().isEmpty() || ScarpetRuntime.isReplaying(event))
            return false;
        InteractionHand hand = player.getUsedItemHand();
        ItemStack item = player.getUseItem().copy();
        return ScarpetRuntime.defer(player, event, () -> event.onItemAction(player, hand, item),
                () -> player.isUsingItem() && player.getUsedItemHand() == hand && ItemStack.matches(item, player.getUseItem()), player::completeUsingItem, () -> resync(player));
    }

    public static boolean attack(ServerPlayer player, Entity target) {
        Event event = Event.PLAYER_ATTACKS_ENTITY;
        if (!event.isNeeded() || ScarpetRuntime.isReplaying(event) || !TickThread.isTickThreadFor(target) || !target.isAttackable())
            return false;
        return ScarpetRuntime.defer(player, event, () -> event.onEntityHandAction(player, target, null),
                () -> TickThread.isTickThreadFor(target) && !target.isRemoved() && target.level() == player.level() && player.isWithinEntityInteractionRange(target, 1.0), () -> player.attack(target), () -> resync(player));
    }
}
