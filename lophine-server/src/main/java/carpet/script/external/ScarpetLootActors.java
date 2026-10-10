// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ScarpetLootActors {
    private ScarpetLootActors() {
    }

    public static <T> CompletableFuture<T> original(LootContext context, Supplier<T> operation) {
        Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
        return original(context.getLevel(), origin == null ? Vec3.ZERO : origin, operation);
    }

    public static <T> CompletableFuture<T> original(ServerLevel world, Vec3 origin, Supplier<T> operation) {
        var done = ScarpetExplosionActors.world(world, BlockPos.containing(origin), () -> {
                    var observed = ScarpetNativeWork.observeNative(null, operation);
                    ScarpetNativeWork.trackNative(world.getServer(), observed);
                    return ScarpetNativeWork.recoverGuestValue(observed);
                })
                .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        ScarpetNativeWork.record(done);
        return done;
    }

    public static <T> CompletableFuture<T> recipient(LootContext context, Supplier<T> operation) {
        Entity target = context.getOptional(LootContextParams.THIS_ENTITY);
        return target == null ? original(context, operation) : ScarpetLootConditions.actor(target, operation);
    }

    public static <T> CompletableFuture<T> reference(LootContext context, Object actual, Supplier<T> operation) {
        if (actual instanceof Entity entity) return ScarpetLootConditions.actor(entity, operation);
        if (actual instanceof net.minecraft.world.level.block.entity.BlockEntity block && block.getLevel() instanceof ServerLevel world)
            return original(world, Vec3.atCenterOf(block.getBlockPos()), operation);
        return original(context, operation);
    }

    public static <T> CompletableFuture<T> job(ServerLevel world, Supplier<CompletableFuture<T>> operation) {
        var actual = jobNative(world, operation);
        var caller = actual.copy();
        ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }

    public static <T> CompletableFuture<T> jobNative(ServerLevel world, Supplier<CompletableFuture<T>> operation) {
        var actual = new CompletableFuture<T>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(world.getServer(), actual);
        var observed = ScarpetNativeWork.observeNative(null, () -> {
            var work = operation.get();
            ScarpetNativeWork.record(work);
            return work;
        });
        ScarpetNativeWork.trackNative(world.getServer(), observed);
        ScarpetNativeWork.aliasDependency(actual, observed);
        ScarpetNativeWork.recoverGuestValue(observed).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value)).whenComplete((value, failure) -> {
            if (failure == null) actual.complete(value);
            else actual.completeExceptionally(failure);
        });
        return actual;
    }
}
