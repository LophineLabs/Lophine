// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetExplosionActors;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Per-invocation command results follow their actual native actor bodies and dynamic children.
 */
public final class TisCommandContinuations {
    private TisCommandContinuations() {
    }

    static <T> CompletableFuture<T> entity(CommandSourceStack source, Entity target, Supplier<CompletableFuture<T>> body) {
        return job(source, target, () -> ScarpetExplosionActors.admitTarget(target, body));
    }

    static <T> CompletableFuture<T> world(CommandSourceStack source, ServerLevel world, BlockPos position, Supplier<T> body) {
        return area(source, world, position.getX() >> 4, position.getZ() >> 4, position.getX() >> 4, position.getZ() >> 4,
                () -> phase(null, body));
    }

    static <T> CompletableFuture<T> area(CommandSourceStack source, ServerLevel world, int minX, int minZ, int maxX, int maxZ,
                                         Supplier<CompletableFuture<T>> body) {
        return job(source, null, () -> {
            var captured = ScarpetRuntime.captureNativeContinuation(body);
            var held = CarpetRegionLease.<CompletableFuture<T>>runValue(world, minX, minZ, maxX, maxZ, lease -> captured.get());
            var actual = held.thenCompose(value -> value);
            ScarpetNativeWork.record(actual);
            return actual;
        });
    }

    static <T> CompletableFuture<T> loadedArea(CommandSourceStack source, ServerLevel world, int minX, int minZ, int maxX, int maxZ,
                                               Supplier<CompletableFuture<T>> body) {
        return job(source, null, () -> {
            var captured = ScarpetRuntime.captureNativeContinuation(body);
            var held = CarpetRegionLease.<CompletableFuture<T>>runLoadedValue(world, minX, minZ, maxX, maxZ, lease -> captured.get());
            var actual = held.thenCompose(value -> value);
            ScarpetNativeWork.record(actual);
            return actual;
        });
    }

    static <T> CompletableFuture<T> owned(ServerLevel world, BlockPos position, Supplier<T> body) {
        var actor = ScarpetExplosionActors.world(world, position, () -> phase(null, body));
        var actual = actor.thenCompose(value -> value);
        ScarpetNativeWork.record(actual);
        return actual;
    }

    static <T> CompletableFuture<T> owned(Entity owner, Supplier<T> body) {
        var actor = ScarpetExplosionActors.entity(owner, () -> phase(owner, body));
        var actual = actor.thenCompose(value -> value);
        ScarpetNativeWork.record(actual);
        return actual;
    }

    /**
     * Call within the real actor supplier, before the next native phase is prepared.
     */
    static <T> CompletableFuture<T> phase(Entity owner, Supplier<T> body) {
        return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(owner, body));
    }

    static <T, R> CompletableFuture<R> then(CompletableFuture<T> before, java.util.function.Function<T, CompletableFuture<R>> next) {
        var actual = before.thenCompose(ScarpetRuntime.captureNativeFunction(next));
        ScarpetNativeWork.record(actual);
        return actual;
    }

    /**
     * The private real result is enrolled before an actor or lease can enqueue its first task.
     */
    private static <T> CompletableFuture<T> job(CommandSourceStack source, Entity owner, Supplier<CompletableFuture<T>> body) {
        var actual = new CompletableFuture<T>();
        ScarpetNativeWork.record(actual);
        var caller = ScarpetNativeWork.trackNative(source.getServer(), actual);
        var observed = ScarpetNativeWork.observeNative(owner, () -> {
            var nativeBody = body.get();
            ScarpetNativeWork.record(nativeBody);
            return nativeBody;
        });
        ScarpetNativeWork.trackNative(source.getServer(), observed);
        ScarpetNativeWork.aliasDependency(actual, observed);
        ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value -> value).whenComplete((value, failure) -> {
            if (failure == null) actual.complete(value);
            else actual.completeExceptionally(failure);
        });
        return caller;
    }

    static CompletableFuture<Integer> total(List<? extends CompletableFuture<Integer>> actuals) {
        return CompletableFuture.allOf(actuals.toArray(CompletableFuture[]::new)).thenApply(ignored -> actuals.stream().mapToInt(value -> value.getNow(0)).sum());
    }

    static int complete(CommandSourceStack source, int immediate, Supplier<CompletableFuture<Integer>> action, String message, Runnable after) {
        var completion = CarpetAsyncCommandResults.defer(source);
        var observed = ScarpetNativeWork.observeNative(source.getEntity(), () -> {
            ScarpetNativeWork.record(completion.future());
            CompletableFuture<Integer> actual;
            try {
                actual = action.get();
            } catch (Throwable failure) {
                actual = CompletableFuture.failedFuture(failure);
            }
            ScarpetNativeWork.record(actual);
            var outcome = actual.handle((count, failure) -> new Outcome(count == null ? 0 : count, failure));
            var delivered = then(outcome, value -> feedback(source, () -> {
                if (value.failure() == null) {
                    if (message != null) TisManipulateCommand.feedback(source, message + ": " + value.count());
                    after.run();
                } else TisManipulateCommand.feedback(source, "Operation failed: " + value.failure().getMessage());
                return value;
            }));
            var committed = delivered.whenComplete(ScarpetRuntime.captureNativeConsumer((value, failure) -> {
                if (failure != null) completion.complete(false, 0);
                else completion.complete(value.failure() == null, value.failure() == null ? value.count() : 0);
            }));
            ScarpetNativeWork.record(committed);
            return null;
        });
        ScarpetNativeWork.trackNative(source.getServer(), observed);
        return immediate;
    }

    private record Outcome(int count, Throwable failure) {
    }

    static <T> CompletableFuture<T> feedback(CommandSourceStack source, Supplier<T> body) {
        if (source.getEntity() != null) return owned(source.getEntity(), body);
        return owned(source.getLevel(), BlockPos.containing(source.getPosition()), body);
    }

}
