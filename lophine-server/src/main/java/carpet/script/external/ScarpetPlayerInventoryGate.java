// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Inventory snapshots wait for accepted native tails and suspend new native item operations.
 */
public final class ScarpetPlayerInventoryGate {
    private static final WeakIdentityMap<ServerPlayer, ScarpetOwnerWorkGate> GATES = new WeakIdentityMap<>();
    private static final ThreadLocal<java.util.Set<ServerPlayer>> ACCEPTED = ThreadLocal.withInitial(java.util.Set::of);

    private ScarpetPlayerInventoryGate() {
    }

    private static ScarpetOwnerWorkGate of(ServerPlayer player) {
        return GATES.computeIfAbsent(player, ignored -> new ScarpetOwnerWorkGate());
    }

    public static boolean paused(ServerPlayer player) {
        return !ACCEPTED.get().contains(player) && (of(player).paused() || fun.bm.lophine.carpet.CarpetPlayerBirths.playerPending(player) || fun.bm.lophine.carpet.OrgInventoryTransfers.participantBlocked(player));
    }

    public static CompletableFuture<Void> whenOpen(ServerPlayer player) {
        return CompletableFuture.allOf(of(player).whenOpen(), fun.bm.lophine.carpet.OrgInventoryTransfers.participantCompletion(player), fun.bm.lophine.carpet.CarpetPlayerBirths.playerCompletion(player));
    }

    /**
     * Only the UUID reservation's actual birth job uses this pre-placement metadata entry.
     */
    public static void admitBirth(ServerPlayer player, CompletableFuture<?> actual) {
        of(player).track(actual);
    }

    public static void trackAccepted(ServerPlayer player, CompletableFuture<?> actual) {
        TickThread.ensureTickThread(player, "Native player work must be admitted by its owner");
        of(player).track(actual);
    }

    /**
     * Returns the native immediate value while tracking every dynamically appended real tail.
     */
    public static <T> T observeAccepted(ServerPlayer player, Supplier<T> operation) {
        var value = new java.util.concurrent.atomic.AtomicReference<T>();
        var synchronousFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        CompletableFuture<T> actual = ScarpetNativeWork.observeNative(player, () -> {
            try (var admitted = acceptedScope(player)) {
                T result = operation.get();
                value.set(result);
                return result;
            } catch (RuntimeException | Error failure) {
                synchronousFailure.set(failure);
                throw failure;
            }
        });
        trackAccepted(player, actual);
        if (synchronousFailure.get() instanceof RuntimeException failure) throw failure;
        if (synchronousFailure.get() instanceof Error failure) throw failure;
        return value.get();
    }

    public static boolean deferPaused(ServerPlayer player, Runnable nativeOperation) {
        if (!paused(player)) return false;
        TickThread.ensureTickThread(player, "Paused native player intent must be queued by its owner");
        Supplier<CompletableFuture<Void>> captured = ScarpetRuntime.captureOwnerOperation(() ->
                ScarpetNativeWork.observeNative(player, () -> {
                    try (var accepted = acceptedScope(player)) {
                        nativeOperation.run();
                        return null;
                    }
                }));
        var done = new CompletableFuture<Void>();
        ScarpetNativeWork.record(done);
        queue(player, captured, done);
        done.whenComplete((ignored, failure) -> {
            if (failure != null)
                net.minecraft.server.MinecraftServer.LOGGER.error("Paused player operation failed", failure);
        });
        return true;
    }

    /**
     * A queued typed intent is not part of a snapshot's old accepted work until it starts.
     */
    public static <T> CompletableFuture<T> enqueuePaused(ServerPlayer player, Supplier<CompletableFuture<T>> nativeOperation) {
        TickThread.ensureTickThread(player, "Paused typed intent must be queued by its owner");
        Supplier<CompletableFuture<T>> captured = ScarpetRuntime.captureOwnerOperation(() -> {
            var observed = ScarpetNativeWork.observeNative(player, () -> {
                try (var accepted = acceptedScope(player)) {
                    CompletableFuture<T> actual = nativeOperation.get();
                    ScarpetNativeWork.record(actual);
                    return actual;
                }
            });
            var actual = observed.thenCompose(java.util.function.Function.identity());
            ScarpetNativeWork.aliasDependency(actual, observed);
            return actual;
        });
        var done = new CompletableFuture<T>();
        ScarpetNativeWork.record(done);
        queue(player, captured, done);
        return done;
    }

    private static <T> void queue(ServerPlayer player, Supplier<CompletableFuture<T>> operation, CompletableFuture<T> done) {
        whenOpen(player).whenComplete((ignored, failure) -> {
            if (failure != null) {
                done.completeExceptionally(failure);
                return;
            }
            boolean scheduled = player.getBukkitEntity().taskScheduler.schedule(owned -> {
                if (owned != player || player.isRemoved()) {
                    done.completeExceptionally(new IllegalStateException("Paused player retired"));
                    return;
                }
                if (paused(player)) {
                    queue(player, operation, done);
                    return;
                }
                CompletableFuture<T> actual;
                try {
                    actual = operation.get();
                } catch (Throwable problem) {
                    done.completeExceptionally(problem);
                    return;
                }
                trackAccepted(player, actual);
                actual.whenComplete((value, problem) -> {
                    if (problem == null) done.complete(value);
                    else done.completeExceptionally(problem);
                });
            }, retired -> done.completeExceptionally(new IllegalStateException("Paused player retired")), 1L);
            if (!scheduled) done.completeExceptionally(new IllegalStateException("Paused player scheduler retired"));
        });
    }

    public static <T> CompletableFuture<T> whenIdle(ServerPlayer player, Supplier<T> snapshot) {
        return snapshot(player, snapshot, false);
    }

    /**
     * Mandatory removal runs after old operations terminate, including cancelled shutdown guests.
     */
    public static <T> CompletableFuture<T> whenIdleForRemoval(ServerPlayer player, Supplier<T> cleanup) {
        return snapshot(player, cleanup, true);
    }

    private static <T> CompletableFuture<T> snapshot(ServerPlayer player, Supplier<T> snapshot, boolean mandatoryCleanup) {
        TickThread.ensureTickThread(player, "Player inventory snapshot must be requested by its owner");
        return of(player).whenIdle(operation -> {
            var result = new CompletableFuture<T>();
            boolean scheduled = player.getBukkitEntity().taskScheduler.schedule(owned -> {
                try {
                    TickThread.ensureTickThread(player, "Player inventory snapshot must run on its owner");
                    if (owned != player || player.isRemoved())
                        throw new IllegalStateException("Player retired before inventory snapshot");
                    result.complete(operation.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            }, retired -> result.completeExceptionally(new IllegalStateException("Player retired before inventory snapshot")), 1L);
            if (!scheduled)
                result.completeExceptionally(new IllegalStateException("Player scheduler retired before inventory snapshot"));
            return result;
        }, snapshot, mandatoryCleanup, mandatoryCleanup && ACCEPTED.get().contains(player));
    }

    public static AcceptedScope acceptedScope(ServerPlayer player) {
        return new AcceptedScope(player);
    }

    public static java.util.Set<ServerPlayer> captureAccepted() {
        return ACCEPTED.get();
    }

    public static AcceptedScope inheritAccepted(java.util.Set<ServerPlayer> owners) {
        return new AcceptedScope(owners);
    }

    public static final class AcceptedScope implements AutoCloseable {
        private final java.util.Set<ServerPlayer> previous;
        private boolean closed;

        private AcceptedScope(ServerPlayer player) {
            previous = ACCEPTED.get();
            var owners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ServerPlayer, Boolean>());
            owners.addAll(previous);
            owners.add(player);
            ACCEPTED.set(java.util.Collections.unmodifiableSet(owners));
        }

        private AcceptedScope(java.util.Set<ServerPlayer> owners) {
            previous = ACCEPTED.get();
            ACCEPTED.set(owners);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous.isEmpty()) ACCEPTED.remove();
            else ACCEPTED.set(previous);
        }
    }
}
