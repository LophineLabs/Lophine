// SPDX-License-Identifier: MIT
package carpet.script.external;

import fun.bm.lophine.carpet.AmsNativeCommandEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Actual wither birth sound follows its committed explosion and every physical recipient write.
 */
public final class ScarpetWitherSpawnSound {
    private ScarpetWitherSpawnSound() {
    }

    public static CompletableFuture<Void> send(WitherBoss boss, ServerLevel world) {
        if (boss.isSilent()) return CompletableFuture.completedFuture(null);
        // WrapWithCondition evaluates the original call arguments before checking the rule.
        BlockPos position = boss.blockPosition().immutable();
        if (fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.witherSpawnedSoundDisabled)
            return CompletableFuture.completedFuture(null);
        Vec3 source = boss.position();
        boolean global = world.getGameRules().get(GameRules.GLOBAL_SOUND_EVENTS);
        double radius = world.getGlobalSoundRangeSquared(config -> config.witherSpawnSoundRadius);
        int distance = world.getCraftServer().getViewDistance() * 16;
        var audience = AmsNativeCommandEffects.global(world.getServer(), () -> List.copyOf(world.getServer().getPlayerList().getPlayers()));
        var actual = ScarpetExplosionPacketBarrier.fanOut(audience, target ->
                AmsNativeCommandEffects.owned(target, () -> {
                    if (target.isRemoved() || !global && target.level() != world)
                        return CompletableFuture.<Void>completedFuture(null);
                    double dx = source.x - target.getX(), dz = source.z - target.getZ(), squared = dx * dx + dz * dz;
                    if (!global && squared > radius) return CompletableFuture.<Void>completedFuture(null);
                    BlockPos sound = position;
                    if (squared > (double) distance * distance) {
                        double length = Math.sqrt(squared);
                        sound = new BlockPos((int) (target.getX() + dx / length * distance), (int) source.y, (int) (target.getZ() + dz / length * distance));
                    }
                    return AmsNativeCommandEffects.packet(target, new ClientboundLevelEventPacket(LevelEvent.SOUND_WITHER_BOSS_SPAWN, sound, 0, true));
                }).thenCompose(value -> value));
        ScarpetNativeWork.record(actual);
        return actual;
    }
}
