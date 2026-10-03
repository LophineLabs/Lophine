// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

/** Native explosion phases remain actor-owned while their real guest decisions are suspended. */
public final class ScarpetExplosionActors {
    private ScarpetExplosionActors() { }
    public static <T> CompletableFuture<T> world(ServerLevel world, BlockPos position, Supplier<T> operation) {
        CompletableFuture<T> completed = new CompletableFuture<>();
        Supplier<T> captured = ScarpetRuntime.captureNativeContinuation(operation);
        ScarpetNativeWork.record(completed);
        Runnable action = () -> {
            if (completed.isDone()) return;
            try { completed.complete(captured.get()); }
            catch (Throwable failure) { completed.completeExceptionally(failure); }
        };
        try {
            if (TickThread.isTickThreadFor(world, position)) action.run();
            else world.getServer().server.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE, world.getWorld(), position.getX() >> 4, position.getZ() >> 4, action);
        } catch (Throwable failure) { completed.completeExceptionally(failure); }
        return completed;
    }
    public static <T> CompletableFuture<T> entity(Entity entity, Supplier<T> operation) {
        Supplier<T> captured = ScarpetRuntime.captureNativeContinuation(operation);
        CompletableFuture<T> result = new CompletableFuture<>();
        ScarpetNativeWork.record(result);
        if (TickThread.isTickThreadFor(entity) || TickThread.isShutdownThread()) {
            runEntity(entity, captured, result); return result;
        }
        try {
            boolean scheduled = entity.getBukkitEntity().taskScheduler.schedule(ignored -> runEntity(entity, captured, result),
                ignored -> retired(entity, captured, result, 0), 1L);
            if (!scheduled) retired(entity, captured, result, 0);
        } catch (Throwable failure) { result.completeExceptionally(failure); }
        return result;
    }
    public static <T> CompletableFuture<T> area(Explosion explosion, ScarpetAttribution.Token attribution, Supplier<T> operation) {
        BlockPos center=BlockPos.containing(explosion.center()); int radius=(int)Math.ceil(explosion.radius()*2D)+2;
        int minX=(center.getX()-radius)>>4, maxX=(center.getX()+radius)>>4, minZ=(center.getZ()-radius)>>4, maxZ=(center.getZ()+radius)>>4;
        for (var data : attribution.values()) { if (data.world()==explosion.level()) { minX=Math.min(minX,data.position().getX()>>4); maxX=Math.max(maxX,data.position().getX()>>4); minZ=Math.min(minZ,data.position().getZ()>>4); maxZ=Math.max(maxZ,data.position().getZ()>>4); } }
        Supplier<T> captured=ScarpetRuntime.captureNativeContinuation(() -> ScarpetAttribution.with(attribution,operation));
        CompletableFuture<T> done=fun.bm.lophine.carpet.CarpetRegionLease.runValue((ServerLevel)explosion.level(),minX,minZ,maxX,maxZ,lease -> captured.get());
        ScarpetNativeWork.record(done); return done;
    }
    private static <T> void runEntity(Entity entity, Supplier<T> operation, CompletableFuture<T> result) {
        if (result.isDone()) return;
        try { result.complete(operation.get()); }
        catch (Throwable failure) { result.completeExceptionally(failure); }
        finally { ScarpetRetiredActors.capture(entity); }
    }
    private static <T> void retired(Entity entity, Supplier<T> operation, CompletableFuture<T> result, int attempts) {
        if (result.isDone()) return;
        var location = ScarpetRetiredActors.lastOwner(entity);
        if (location == null || attempts == 8) {
            result.completeExceptionally(new IllegalStateException("Native explosion entity lost its final owner")); return;
        }
        try {
            location.world().getServer().server.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE, location.world().getWorld(),
                location.position().getX() >> 4, location.position().getZ() >> 4, () -> {
                    if (!ScarpetRetiredActors.matchesLastOwner(entity, location)) { retired(entity, operation, result, attempts + 1); return; }
                    runEntity(entity, operation, result);
                });
        } catch (Throwable failure) { result.completeExceptionally(failure); }
    }
    public static <T> CompletableFuture<T> blocks(net.minecraft.world.level.ServerExplosion explosion, java.util.Collection<BlockPos> positions, Supplier<T> operation) {
        BlockPos center = BlockPos.containing(explosion.center());
        int minX=center.getX() >> 4, maxX=minX, minZ=center.getZ() >> 4, maxZ=minZ;
        for (BlockPos position : positions) {
            minX=Math.min(minX,position.getX() >> 4); maxX=Math.max(maxX,position.getX() >> 4);
            minZ=Math.min(minZ,position.getZ() >> 4); maxZ=Math.max(maxZ,position.getZ() >> 4);
        }
        Supplier<T> captured = ScarpetRuntime.captureNativeContinuation(operation);
        CompletableFuture<T> completed = fun.bm.lophine.carpet.CarpetRegionLease.runValue(explosion.level(), minX, minZ, maxX, maxZ, lease -> captured.get());
        ScarpetNativeWork.record(completed); return completed;
    }
    /** Admits one actual target phase, including all its post-hit effects, before a snapshot can pause that target. */
    public static <T> CompletableFuture<T> admitTarget(Entity target, Supplier<CompletableFuture<T>> acceptedNativePhase) {
        var wholePhase = ScarpetNativeWork.capture();
        // Create the observer INSIDE the captured supplier: restoring the initiating flags must not replace its new token or target privilege.
        Supplier<CompletableFuture<T>> admitted = ScarpetRuntime.captureNativeContinuation(() -> targetPhase(target,wholePhase,acceptedNativePhase));
        CompletableFuture<T> completed = entity(target, () -> {
            if (target instanceof net.minecraft.server.level.ServerPlayer player && !player.isRemoved() && ScarpetPlayerInventoryGate.paused(player))
                return ScarpetPlayerInventoryGate.enqueuePaused(player, admitted);
            return admitted.get();
        }).thenCompose(result -> result);
        ScarpetNativeWork.record(completed); return completed;
    }
    private static <T> CompletableFuture<T> targetPhase(Entity target, ScarpetNativeWork.Token wholePhase, Supplier<CompletableFuture<T>> phase) {
        var observed = ScarpetNativeWork.observeNative(target, () -> {
            if (target instanceof net.minecraft.server.level.ServerPlayer player) {
                try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                    // This future is metadata for Gate only. Recording it into the same root would await itself.
                    if (wholePhase != null && !player.isRemoved()) ScarpetPlayerInventoryGate.trackAccepted(player, ScarpetNativeWork.completionOf(wholePhase));
                    CompletableFuture<T> actual=phase.get(); ScarpetNativeWork.record(actual); return actual;
                }
            }
            CompletableFuture<T> actual=phase.get(); ScarpetNativeWork.record(actual); return actual;
        });
        var actual = ScarpetNativeWork.recoverGuestValue(observed).thenCompose(result -> result);
        ScarpetNativeWork.aliasDependency(actual, observed);
        if (target instanceof net.minecraft.server.level.ServerPlayer player && !player.isRemoved()) ScarpetPlayerInventoryGate.trackAccepted(player, actual);
        return actual;
    }
    /** Returns the real native damage result after all dynamically created damage/death work, never the provisional false. */
    public static CompletableFuture<Boolean> hurt(Entity target, ServerLevel world, net.minecraft.world.damagesource.DamageSource source, float amount) {
        return admitTarget(target, () -> hurtAccepted(target,world,source,amount));
    }
    private static CompletableFuture<Boolean> hurtAccepted(Entity target, ServerLevel world, net.minecraft.world.damagesource.DamageSource source, float amount) {
        java.util.List<Entity> related = new java.util.ArrayList<>();
        if (source.getEntity() != null) related.add(source.getEntity());
        if (source.getDirectEntity() != null) related.add(source.getDirectEntity());
        CompletableFuture<Boolean> completed = ScarpetAttribution.snapshot(related).thenCompose(attribution ->
            entity(target, () -> ScarpetAttribution.with(attribution, () -> {
                var before = ScarpetDamageContinuations.pendingResult(target);
                var deferred = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>>();
                var actual = ScarpetNativeWork.observeNative(target, () -> {
                    boolean immediate = target.hurtServer(world, source, amount);
                    var after = ScarpetDamageContinuations.pendingResult(target);
                    if (after != null && after != before) deferred.set(after);
                    return immediate;
                });
                return ScarpetNativeWork.recoverGuestValue(actual).thenCompose(immediate -> deferred.get() == null ? CompletableFuture.completedFuture(immediate) : deferred.get());
            }))).thenCompose(result -> result);
        ScarpetNativeWork.record(completed); return completed;
    }
    public static CompletableFuture<ScarpetAttribution.Token> currentAttribution(Explosion explosion) {
        CompletableFuture<ScarpetAttribution.Token> current = ScarpetAttribution.refreshExplosion(explosion);
        ScarpetNativeWork.record(current); return current;
    }
}
