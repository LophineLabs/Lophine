// SPDX-License-Identifier: LGPL-3.0-or-later
package carpet.script.external;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Actual query data and serialization resources survive until their original sender call and its native receipt. */
public final class ScarpetTagQueries {
    private ScarpetTagQueries() { }
    public static final class Result implements AutoCloseable {
        private final CompoundTag tag;
        private final AutoCloseable resource;
        private final AtomicBoolean closed = new AtomicBoolean();
        public Result(CompoundTag tag, AutoCloseable resource) { this.tag = tag; this.resource = resource; }
        public CompoundTag tag() { return tag; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true) || resource == null) return;
            try { resource.close(); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }
    }
    public static CompletableFuture<Void> entity(ServerPlayer recipient, Entity target, Supplier<Result> read, Consumer<Result> send) {
        return job((ServerLevel)recipient.level(), recipient, read, operation -> ScarpetLootConditions.actor(target, operation),
            cleanup -> ScarpetLootConditions.actor(target, cleanup), send);
    }
    public static CompletableFuture<Void> block(ServerPlayer recipient, ServerLevel original, BlockPos position, Supplier<Result> read, Consumer<Result> send) {
        Vec3 location = Vec3.atCenterOf(position);
        return job((ServerLevel)recipient.level(), recipient, read, operation -> ScarpetLootActors.original(original, location, operation),
            cleanup -> ScarpetLootActors.original(original, location, cleanup), send);
    }
    private static CompletableFuture<Void> job(ServerLevel admitted, ServerPlayer recipient, Supplier<Result> read,
        java.util.function.Function<Supplier<Result>, CompletableFuture<Result>> dispatch,
        java.util.function.Function<Supplier<Void>, CompletableFuture<Void>> cleanup, Consumer<Result> send) {
        return ScarpetLootActors.jobNative(admitted, () -> {
            var acquired = new AtomicReference<Result>();
            // Store the original body value before its observer waits children, so exceptional cleanup can still close it.
            var ready = dispatch.apply(() -> { Result result = read.get(); acquired.set(result); return result; });
            var actual = ready.thenCompose(ScarpetRuntime.captureNativeFunction(result -> {
                if (result == null) return CompletableFuture.<Void>completedFuture(null);
                return ScarpetLootConditions.actor(recipient, () -> {
                    try { send.accept(result); return (Void)null; }
                    finally { result.close(); }
                });
            }));
            // Original reporter cleanup remains necessary when a true native failure blocks the sender.
            var outcome = actual.handle((value, failure) -> failure);
            var finished = outcome.thenCompose(ScarpetRuntime.captureNativeFunction(failure -> {
                Result result = acquired.get();
                var closed = result == null || result.closed.get() ? CompletableFuture.<Void>completedFuture(null) : cleanup.apply(() -> { result.close(); return null; });
                return closed.handle((value, closeFailure) -> {
                    if (failure != null) {
                        if (closeFailure != null) failure.addSuppressed(closeFailure);
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                    if (closeFailure != null) throw new java.util.concurrent.CompletionException(closeFailure);
                    return (Void)null;
                });
            }));
            ScarpetNativeWork.record(finished); return finished;
        });
    }
}
