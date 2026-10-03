// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.CarpetEventServer.Event;
import carpet.script.value.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * World explode() is void upstream: its full native invocation can resume after the real VM decision.
 */
public final class ScarpetWorldExplosions {
    private record Key(ServerLevel world, Entity source, Vec3 center, float power, boolean fire,
                       Explosion.BlockInteraction mode) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.world == world && key.source == source && key.center.equals(center) && Float.compare(key.power, power) == 0 && key.fire == fire && key.mode == mode;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(System.identityHashCode(world), System.identityHashCode(source), center, power, fire, mode);
        }
    }

    private record Source(Value direct, Value causing, LivingEntity indirect) {
    }

    private ScarpetWorldExplosions() {
    }

    public static boolean defer(ServerLevel world, Entity source, Vec3 center, float power, boolean fire, Explosion.BlockInteraction mode, Runnable nativeExplosion) {
        Key key = new Key(world, source, center, power, fire, mode);
        if (ScarpetRuntime.isReplaying(key)) return false;
        boolean eventNeeded = Event.EXPLOSION.isNeeded() && !ScarpetRuntime.EVENT_DISABLED.get();
        boolean nativeAttribution = source != null && (fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tooledTNT
                || fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.wetExplosionReintroduced);
        if (!eventNeeded && !nativeAttribution) return false;
        var nativeToken = ScarpetNativeWork.capture();
        CompletableFuture<Void> tail = new CompletableFuture<>();
        ScarpetNativeWork.record(tail);
        CompletableFuture<Source> origin;
        if (source == null) origin = CompletableFuture.completedFuture(new Source(Value.NULL, Value.NULL, null));
        else origin = ScarpetRetiredActors.accessFuture(source, () -> capture(source));
        origin.thenCompose(captured -> {
            CompletableFuture<Boolean> decision = eventNeeded ? ScarpetRuntime.worldEventDecision(Event.EXPLOSION.handler,
                    List.of(ValueConversions.of(center), NumericValue.of(power), captured.direct(), captured.causing(),
                            StringValue.of(mode.name().toLowerCase(java.util.Locale.ROOT)), BooleanValue.of(fire)), world) : CompletableFuture.completedFuture(false);
            return decision.thenCompose(cancelled -> {
                if (Boolean.TRUE.equals(cancelled)) return CompletableFuture.<Void>completedFuture(null);
                int radius = (int) Math.ceil(Math.max(0, power) * 2.0D) + 2;
                BlockPos pos = BlockPos.containing(center);
                return fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Void>>runValue(world, (pos.getX() - radius) >> 4, (pos.getZ() - radius) >> 4,
                        (pos.getX() + radius) >> 4, (pos.getZ() + radius) >> 4, lease -> {
                            // Loading can take time. Capture current attribution only once the full operation area is ready.
                            // The shared lease follows this returned future and retains every ticket until Native work finishes.
                            CompletableFuture<Source> current = source == null ? CompletableFuture.completedFuture(captured) : ScarpetRetiredActors.accessFuture(source, () -> capture(source));
                            return current.thenCompose(now -> {
                                List<Entity> attributed = new java.util.ArrayList<>();
                                if (source != null) attributed.add(source);
                                if (now.indirect() != null) attributed.add(now.indirect());
                                return ScarpetAttribution.snapshot(attributed).thenCompose(attribution ->
                                        ScarpetRuntime.<CompletableFuture<Void>>atBlockFuture(world, pos, () -> ScarpetNativeWork.with(nativeToken, () ->
                                                ScarpetAttribution.with(attribution, () -> ScarpetNativeWork.<Void>observeNative(source, () -> {
                                                    if (!lease.ownsAll())
                                                        throw new IllegalStateException("Explosion lease lost region ownership before its Native replay");
                                                    ScarpetRuntime.runReplaying(key, nativeExplosion);
                                                    return null;
                                                })))).thenCompose(completed -> completed));
                            });
                        }).thenCompose(completed -> completed);
            });
        }).whenComplete((ignored, failure) -> {
            if (failure == null) tail.complete(null);
            else {
                tail.completeExceptionally(failure);
                carpet.script.CarpetScriptServer.LOG.error("Scarpet explosion continuation failed", failure);
            }
        });
        return true;
    }

    /**
     * The typed native explosion owns the cancellable start event, including direct script explosions.
     */
    public static CompletableFuture<Boolean> decision(net.minecraft.world.level.ServerExplosion explosion) {
        ServerLevel world = explosion.level();
        Entity source = explosion.getDirectSourceEntity();
        if (!Event.EXPLOSION.isNeeded() || ScarpetRuntime.EVENT_DISABLED.get() || !ScarpetRuntime.nativeEventsAllowed(world.getServer()))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Source> origin = source == null ? CompletableFuture.completedFuture(new Source(Value.NULL, Value.NULL, null))
                : ScarpetExplosionActors.entity(source, () -> capture(source));
        var callback = ScarpetRuntime.<Source, CompletableFuture<Boolean>>captureNativeFunction(captured -> observeDecision(source, () -> ScarpetRuntime.worldEventDecision(Event.EXPLOSION.handler,
                List.of(ValueConversions.of(explosion.center()), NumericValue.of(explosion.radius()), captured.direct(), captured.causing(),
                        StringValue.of(explosion.getBlockInteraction().name().toLowerCase(java.util.Locale.ROOT)), BooleanValue.of(explosion.carpetFire())), world)));
        return origin.thenCompose(callback);
    }

    /**
     * The actual callback keeps its failed receipt, while an admitted Native start/outcome still has its default false result.
     */
    public static CompletableFuture<Boolean> observeDecision(Entity owner, java.util.function.Supplier<CompletableFuture<Boolean>> callback) {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var observed = ScarpetNativeWork.observeNative(owner, () -> {
            CompletableFuture<Boolean> decision = callback.get();
            ScarpetNativeWork.record(decision);
            var completed = decision.handle((value, failure) -> {
                if (failure == null) cancelled.set(Boolean.TRUE.equals(value));
                else
                    carpet.script.CarpetScriptServer.LOG.error("Scarpet explosion callback failed; completing the admitted native explosion", failure);
                return (Void) null;
            });
            ScarpetNativeWork.record(completed);
            return completed;
        });
        return ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value -> value).thenApply(ignored -> cancelled.get());
    }

    private static Source capture(Entity source) {
        LivingEntity causing = Explosion.getIndirectSourceEntity(source);
        Value direct = EntityValue.snapshotForRetiredEvent(source); // explosive sources can already be retired, or retire when their native caller returns
        Value indirect = causing == source ? direct : EntityValue.of(causing);
        return new Source(direct, indirect, causing);
    }
}
