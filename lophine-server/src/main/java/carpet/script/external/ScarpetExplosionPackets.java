// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Committed native explosion packets complete on their actual recipients, including foreign regions.
 */
public final class ScarpetExplosionPackets {
    private ScarpetExplosionPackets() {
    }

    private record Packet(ServerLevel world, Vec3 center, float radius, int count, double rangeSquared, boolean audible,
                          ParticleOptions particle, Holder<SoundEvent> sound,
                          WeightedList<ExplosionParticleInfo> blockParticles, Map<Integer, Vec3> impulses) {
    }

    public static CompletableFuture<Void> send(ServerExplosion explosion, int count, ParticleOptions small, ParticleOptions large,
                                               WeightedList<ExplosionParticleInfo> blocks, Holder<SoundEvent> sound) {
        if (explosion.wasCanceled) return CompletableFuture.completedFuture(null);
        var world = explosion.level();
        var sent = ScarpetAttribution.refreshExplosion(explosion).thenCompose(attribution -> ScarpetExplosionActors.world(world, BlockPos.containing(explosion.center()), () -> {
            if (explosion.wasCanceled) return null;
            double range = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange;
            if (!(range >= 0)) range = 64;
            boolean audible = true;
            var source = explosion.getDirectSourceEntity();
            if (source != null) {
                var state = attribution.get(source);
                if (state == null)
                    throw new IllegalStateException("No current owner sound metadata for explosion source");
                audible = !state.silent();
            }
            var impulses = explosion.carpetHitPlayerIds();
            return new Packet(world, explosion.center(), explosion.radius(), count, range * range, audible, explosion.isSmall() ? small : large, sound, blocks, Map.copyOf(impulses));
        })).thenCompose(packet -> {
            if (packet == null) return CompletableFuture.completedFuture(null);
            var audience = new CompletableFuture<List<ServerPlayer>>();
            ScarpetNativeWork.record(audience);
            var capture = ScarpetRuntime.captureNativeContinuation(() -> {
                audience.complete(List.copyOf(world.getServer().getPlayerList().realPlayers));
                return null;
            });
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
                try {
                    capture.get();
                } catch (Throwable failure) {
                    audience.completeExceptionally(failure);
                }
            });
            return ScarpetExplosionPacketBarrier.fanOut(audience, recipient -> send(packet, recipient));
        });
        ScarpetNativeWork.record(sent);
        return sent;
    }

    private static CompletableFuture<Void> send(Packet packet, ServerPlayer recipient) {
        var done = new CompletableFuture<Void>();
        ScarpetNativeWork.record(done);
        var actual = ScarpetRuntime.captureNativeContinuation(() -> ScarpetNativeWork.<Void>observeNative(recipient, () -> {
            if (!recipient.isRemoved() && recipient.level() == packet.world && recipient.distanceToSqr(packet.center) < packet.rangeSquared)
                recipient.connection.send(new ClientboundExplodePacket(packet.center, packet.radius, packet.count, Optional.ofNullable(packet.impulses.get(recipient.getId())),
                        packet.particle, packet.sound, packet.blockParticles, packet.audible));
            return null;
        }));
        Runnable body = () -> {
            try {
                ScarpetNativeWork.recoverGuestValue(actual.get()).whenComplete((ignored, failure) -> {
                    if (failure == null) done.complete(null);
                    else done.completeExceptionally(failure);
                });
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        };
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(recipient)) body.run();
        else if (!recipient.getBukkitEntity().taskScheduler.schedule(owned -> {
            if (owned == recipient) body.run();
            else done.complete(null);
        }, retired -> done.complete(null), 1L)) done.complete(null);
        return done;
    }
}
