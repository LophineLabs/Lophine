// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from fabric-carpet f358000b175ddbcf1dd0bc59641c715fb0545664.
package fun.bm.lophine.carpet;

import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public final class CarpetPathfindingLogger {
    private static final Scope INACTIVE = new Scope(null, null, List.of(), 0);
    private CarpetPathfindingLogger() { }
    public static void registerLogger() { CarpetLoggerProtocol.registerLogger("pathfinding", "20", List.of("2", "5", "10"), false); }
    public static Scope start(Mob mob, Set<BlockPos> targets) {
        if (!CarpetLoggerProtocol.hasSubscribers("pathfinding") || !(mob.level() instanceof ServerLevel world)) return INACTIVE;
        return new Scope(world, mob.position(), targets.stream().map(Vec3::atBottomCenterOf).toList(), System.nanoTime());
    }
    public static final class Scope implements AutoCloseable {
        final ServerLevel world;
        final Vec3 origin;
        final List<Vec3> targets;
        final long start;
        boolean successful;
        boolean completed;
        boolean closed;
        Scope(ServerLevel world, Vec3 origin, List<Vec3> targets, long start) { this.world = world; this.origin = origin; this.targets = targets; this.start = start; }
        public Path result(Path result) { if (this != INACTIVE) { successful = result != null; completed = true; } return result; }
        @Override public void close() {
            if (this == INACTIVE || closed) return;
            closed = true;
            if (!completed) return;
            float duration = ((System.nanoTime() - start) / 1000L) / 1000.0F;
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
                for (ServerPlayer player : world.getServer().getPlayerList().getPlayers()) {
                    String option = CarpetLoggerProtocol.subscriptions(player.getScoreboardName()).get("pathfinding");
                    if (option == null) continue;
                    final int threshold;
                    try { threshold = Integer.parseInt(option); } catch (NumberFormatException invalid) { continue; }
                    if (duration < threshold) continue;
                    player.getBukkitEntity().taskScheduler.schedule(current -> {
                        ServerPlayer owner = (ServerPlayer) current;
                        if (owner.level() != world) return;
                        for (Vec3 target : targets) if (owner.position().distanceToSqr(origin) <= 1000.0 || owner.position().distanceToSqr(target) <= 1000.0) {
                            draw(owner, origin, target, duration, threshold, successful);
                        }
                    }, null, 1L);
                }
            });
        }
    }

    private static void draw(ServerPlayer player, Vec3 from, Vec3 target, float milliseconds, int threshold, boolean successful) {
        float ratio = milliseconds / Math.max(1, threshold);
        ParticleOptions color = new DustParticleOptions(ratio < 2.0 ? 0xFFFF00 : ratio < 4.0 ? 0xFF7700 : 0xFF0000, 1.0F);
        player.connection.send(new ClientboundLevelParticlesPacket(successful ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER,
            true, true, target.x, target.y, target.z, 0.5F, 0.5F, 0.5F, 0.0F, 5));
        double distanceSquared = from.distanceToSqr(target);
        if (distanceSquared == 0.0) return;
        Vec3 increment = target.subtract(from).normalize();
        for (Vec3 delta = Vec3.ZERO; delta.lengthSqr() < distanceSquared; delta = delta.add(increment.scale(ThreadLocalRandom.current().nextFloat()))) {
            Vec3 point = from.add(delta);
            player.connection.send(new ClientboundLevelParticlesPacket(color, true, true, point.x, point.y, point.z, 0.0F, 0.0F, 0.0F, 0.0F, 1));
        }
    }
}
