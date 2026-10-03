// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1 BlockExcavator.
package fun.bm.lophine.carpet;

import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.GameMasterBlock;

final class OrgHiddenExcavator {
    final ServerPlayer player;
    private int cooldown;
    private BlockPos position;
    private float progress;
    OrgHiddenExcavator(ServerPlayer player) { this.player = player; }
    void tick() { if (cooldown > 0) cooldown--; }
    int remaining(BlockPos block) {
        if (player.isCreative()) return 1;
        float delta = player.level().getBlockState(block).getDestroyProgress(player, player.level(), block);
        return Math.max((int)Math.ceil((1 - progress) / delta), 1);
    }
    boolean canBreak(BlockPos block) {
        var world = player.level(); var state = world.getBlockState(block);
        if (state.getBlock() instanceof GameMasterBlock && !player.canUseGameMasterBlocks()) return false;
        if (player.blockActionRestricted(world, block, player.gameMode.getGameModeForPlayer())) return false;
        var held = player.getMainHandItem();
        if (held.getItem().canDestroyBlock(held, state, world, block, player) && world.mayInteract(player, block))
            return player.isCreative() || state.isAir() || state.getDestroySpeed(world, block) != -1;
        return true; // Preserve the source BlockExcavator's final fallback.
    }
    CompletableFuture<Boolean> mine(BlockPos block, Direction face, boolean useCooldown) {
        if (useCooldown && cooldown > 0) return CompletableFuture.completedFuture(false);
        var world = player.level();
        if (player.blockActionRestricted(world, block, player.gameMode.getGameModeForPlayer()) || !world.mayInteract(player, block))
            return CompletableFuture.completedFuture(false);
        if (position != null && world.getBlockState(position).isAir()) { position = null; return CompletableFuture.completedFuture(true); }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, net.minecraft.world.phys.Vec3.atCenterOf(block));
        float delta = world.getBlockState(block).getDestroyProgress(player, world, block);
        if (player.isCreative()) return action(Action.START_DESTROY_BLOCK, block, face).thenCompose(ignored -> OrgHiddenNative.owner(player, () -> {
            cooldown = 5; finish(); return true;
        }));
        if (position == null) return start(block, face, delta);
        if (!position.equals(block)) return action(Action.ABORT_DESTROY_BLOCK, position, face).thenCompose(ignored -> OrgHiddenNative.ownerFuture(player, () -> start(block, face, delta)));
        progress += delta;
        if (progress < 1) { finish(); return CompletableFuture.completedFuture(false); }
        return action(Action.STOP_DESTROY_BLOCK, block, face).thenCompose(ignored -> OrgHiddenNative.owner(player, () -> {
            position = null; progress = 0; cooldown = 5; finish(); return true;
        }));
    }
    private CompletableFuture<Boolean> start(BlockPos block, Direction face, float delta) {
        return action(Action.START_DESTROY_BLOCK, block, face).thenCompose(ignored -> OrgHiddenNative.owner(player, () -> {
            if (delta < 1) { position = block.immutable(); progress = 2 * delta; }
            finish(); return delta >= 1;
        }));
    }
    private CompletableFuture<Void> action(Action action, BlockPos block, Direction face) {
        return OrgHiddenNative.observed(player, () -> {
            player.gameMode.handleBlockBreakAction(block, action, face, player.level().getMaxY(), -1); return null;
        });
    }
    private void finish() { player.resetLastActionTime(); OrgHiddenNative.swing(player, InteractionHand.MAIN_HAND); }
}
