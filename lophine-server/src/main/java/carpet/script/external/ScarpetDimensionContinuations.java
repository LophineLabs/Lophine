// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Native world handoff begins only after every original removal callback and transform completes.
 */
public final class ScarpetDimensionContinuations {
    private ScarpetDimensionContinuations() {
    }

    /**
     * Committed native callbacks retain causal work without re-admitting a cancelled guest frame.
     */
    public static <T> Consumer<T> captureConsumer(Consumer<T> action) {
        return ScarpetRuntime.captureNativeConsumer(action);
    }

    public static <T, U> java.util.function.BiConsumer<T, U> captureConsumer(java.util.function.BiConsumer<T, U> action) {
        return ScarpetRuntime.captureNativeConsumer(action);
    }

    public static <T> CompletableFuture<T> atOrigin(ServerLevel world, BlockPos position, Supplier<T> operation) {
        Supplier<T> owned = ScarpetRuntime.captureNativeContinuation(operation);
        if (TickThread.isTickThreadFor(world, position)) {
            try {
                return CompletableFuture.completedFuture(owned.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        var actual = new CompletableFuture<T>();
        try {
            world.getServer().server.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE, world.getWorld(),
                position.getX() >> 4, position.getZ() >> 4, () -> {
                    try {
                        actual.complete(owned.get());
                    } catch (Throwable failure) {
                        actual.completeExceptionally(failure);
                    }
                });
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }

    public static CompletableFuture<Void> transformTree(Entity originalRoot, ServerLevel origin, BlockPos originPosition,
                                                        List<Entity.EntityTreeNode> nodes, Function<Entity, CompletableFuture<Entity>> transform,
                                                        Consumer<Consumer<Throwable>> place, Runnable failed) {
        // Capture actual original owners while the initial native passenger tree is still owned.
        for (var node : nodes) ScarpetRetiredActors.capture(node.root);
        var done = new CompletableFuture<Void>();
        var observed = ScarpetNativeWork.observeNative(originalRoot, () -> {
            var token = ScarpetNativeWork.capture();
            var accepted = ScarpetPlayerInventoryGate.captureAccepted();
            ScarpetNativeWork.record(done);
            var terminal = new java.util.concurrent.atomic.AtomicBoolean();
            Consumer<Throwable> finished = problem -> {
                if (!terminal.compareAndSet(false, true)) return;
                if (problem == null) done.complete(null);
                else done.completeExceptionally(problem);
            };
            Consumer<Throwable> cleanup = ScarpetRuntime.captureNativeConsumer(problem -> {
                try {
                    failed.run();
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != problem) problem.addSuppressed(cleanupFailure);
                } finally {
                    done.completeExceptionally(problem);
                }
            });
            Consumer<Throwable> abort = problem -> {
                // Claim termination before re-entering the captured token. An already
                // completed placement has released its lease and must not clean up twice.
                if (terminal.compareAndSet(false, true)) cleanup.accept(problem);
            };
            CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
            for (var node : nodes) {
                Entity original = node.root;
                Supplier<CompletableFuture<Entity>> owned = ScarpetRuntime.captureNativeContinuation(() -> transform.apply(original));
                sequence = sequence.thenCompose(ignored -> {
                    if (TickThread.isTickThreadFor(original)) return owned.get();
                    return ScarpetNativeRemovals.onOwnerFuture(original, owned).thenCompose(Function.identity());
                }).thenAccept(copy -> node.root = copy);
            }
            sequence.whenComplete(ScarpetRuntime.captureNativeConsumer((ignored, failure) -> {
                Supplier<Void> finish = () -> {
                    try (var scope = ScarpetPlayerInventoryGate.inheritAccepted(accepted)) {
                        return ScarpetNativeWork.with(token, () -> {
                            if (failure == null) place.accept(finished);
                            else abort.accept(failure);
                            return null;
                        });
                    }
                };
                atOrigin(origin, originPosition, finish).whenComplete((started, problem) -> {
                    if (problem != null) abort.accept(problem);
                });
            }));
            return done;
        });
        var actual = observed.thenCompose(Function.identity());
        ScarpetNativeWork.aliasDependency(actual, observed);
        ScarpetNativeRemovals.trackCaller(origin.getServer(), actual);
        var caller = actual.copy();
        ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }
}
