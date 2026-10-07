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
    private static final WeakIdentityMap<ServerPlayer, IntentQueue> INTENTS = new WeakIdentityMap<>();
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
        return deferPausedReceipt(player, nativeOperation) != null;
    }

    /**
     * The private queued native receipt also fences later client prediction acknowledgements.
     */
    public static CompletableFuture<Void> deferPausedReceipt(ServerPlayer player, Runnable nativeOperation) {
        if (!paused(player)) return null;
        TickThread.ensureTickThread(player, "Paused native player intent must be queued by its owner");
        var done = ScarpetPlayerInventoryGate.<Void>intentReceipt(player);
        ScarpetNativeWork.record(done);
        Supplier<CompletableFuture<Void>> captured = ScarpetRuntime.captureOwnerOperation(() ->
                ScarpetNativeWork.observeNative(player, () -> {
                    try (var accepted = acceptedScope(player)) {
                        ScarpetNativeWork.aliasDependency(done, ScarpetNativeWork.completionOf(ScarpetNativeWork.capture()));
                        nativeOperation.run();
                        return null;
                    }
                }));
        queue(player, captured, done);
        done.whenComplete((ignored, failure) -> {
            if (failure != null)
                net.minecraft.server.MinecraftServer.LOGGER.error("Paused player operation failed", failure);
        });
        return done;
    }

    /**
     * A queued typed intent is not part of a snapshot's old accepted work until it starts.
     */
    public static <T> CompletableFuture<T> enqueuePaused(ServerPlayer player, Supplier<CompletableFuture<T>> nativeOperation) {
        TickThread.ensureTickThread(player, "Paused typed intent must be queued by its owner");
        var done = ScarpetPlayerInventoryGate.<T>intentReceipt(player);
        ScarpetNativeWork.record(done);
        Supplier<CompletableFuture<T>> captured = ScarpetRuntime.captureOwnerOperation(() -> {
            var observed = ScarpetNativeWork.observeNative(player, () -> {
                try (var accepted = acceptedScope(player)) {
                    ScarpetNativeWork.aliasDependency(done, ScarpetNativeWork.completionOf(ScarpetNativeWork.capture()));
                    CompletableFuture<T> actual = nativeOperation.get();
                    ScarpetNativeWork.record(actual);
                    return actual;
                }
            });
            var actual = observed.thenCompose(java.util.function.Function.identity());
            ScarpetNativeWork.aliasDependency(actual, observed);
            return actual;
        });
        queue(player, captured, done);
        return done;
    }

    private static <T> CompletableFuture<T> intentReceipt(ServerPlayer player) {
        var done = new CompletableFuture<T>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        ScarpetNativeWork.trackNative(player.carpetSpawnServer(), done);
        return done;
    }

    private static <T> void queue(ServerPlayer player, Supplier<CompletableFuture<T>> operation, CompletableFuture<T> done) {
        INTENTS.computeIfAbsent(player, IntentQueue::new).add(new Intent<>(operation, done));
    }

    /**
     * Only not-yet-admitted intents are abandoned; executing native children keep their true receipt.
     */
    public static void retired(ServerPlayer player) {
        IntentQueue queue = INTENTS.get(player);
        if (queue != null) queue.failUnstarted(new IllegalStateException("Paused player retired"), true);
    }

    private static final class Intent<T> {
        final Supplier<CompletableFuture<T>> operation;
        final CompletableFuture<T> done;
        final java.util.function.BiConsumer<T, Throwable> publish;
        boolean started;

        Intent(Supplier<CompletableFuture<T>> operation, CompletableFuture<T> done) {
            this.operation = operation;
            this.done = done;
            publish = ScarpetRuntime.captureNativeConsumer((T value, Throwable failure) -> {
                if (failure == null) done.complete(value);
                else done.completeExceptionally(failure);
            });
        }

        void finish(T value, Throwable failure) {
            if (done.isDone()) return;
            try {
                publish.accept(value, failure);
            } catch (Throwable problem) {
                done.completeExceptionally(problem);
            }
        }
    }

    /**
     * A single gate callback and active native body preserve packet admission order across CompletableFuture's LIFO listeners.
     */
    private static final class IntentQueue {
        final java.lang.ref.WeakReference<ServerPlayer> player;
        final java.util.ArrayDeque<Intent<?>> waiting = new java.util.ArrayDeque<>();
        final java.util.concurrent.atomic.AtomicInteger pumping = new java.util.concurrent.atomic.AtomicInteger();
        Intent<?> current;
        boolean retired;

        IntentQueue(ServerPlayer player) {
            this.player = new java.lang.ref.WeakReference<>(player);
        }

        void add(Intent<?> intent) {
            boolean rejected;
            synchronized (this) {
                rejected = retired;
                if (!rejected) waiting.addLast(intent);
            }
            if (rejected) intent.finish(null, new IllegalStateException("Paused player retired"));
            else pump();
        }

        void pump() {
            if (pumping.getAndIncrement() != 0) return;
            int missed = 1;
            do {
                for (; ; ) {
                    Intent<?> next;
                    synchronized (this) {
                        if (current != null || retired || waiting.isEmpty()) break;
                        current = next = waiting.removeFirst();
                    }
                    awaitOpen(next);
                    synchronized (this) {
                        if (current != null) break;
                    }
                }
                missed = pumping.addAndGet(-missed);
            } while (missed != 0);
        }

        synchronized boolean isCurrent(Intent<?> intent) {
            return current == intent;
        }

        void awaitOpen(Intent<?> intent) {
            ServerPlayer owner = player.get();
            if (owner == null) {
                failUnstarted(new IllegalStateException("Paused player was collected"), true);
                return;
            }
            try {
                whenOpen(owner).whenComplete((ignored, failure) -> {
                    if (!isCurrent(intent)) return;
                    if (failure != null) failUnstarted(failure, false);
                    else dispatch(owner, intent);
                });
            } catch (Throwable failure) {
                failUnstarted(failure, false);
            }
        }

        void dispatch(ServerPlayer owner, Intent<?> intent) {
            try {
                if (TickThread.isTickThreadFor(owner)) run(owner, intent);
                else if (!owner.getBukkitEntity().taskScheduler.schedule(owned -> {
                    if (owned != owner)
                        failUnstarted(new IllegalStateException("Paused player identity changed"), true);
                    else run(owner, intent);
                }, ignored -> failUnstarted(new IllegalStateException("Paused player scheduler retired"), true), 1L))
                    failUnstarted(new IllegalStateException("Paused player scheduler retired"), true);
            } catch (Throwable failure) {
                failUnstarted(failure, true);
            }
        }

        <T> void run(ServerPlayer owner, Intent<T> intent) {
            if (!isCurrent(intent)) return;
            try {
                TickThread.ensureTickThread(owner, "Paused player intent must run on its owner");
                if (owner.isRemoved() || owner.hasDisconnected()) {
                    failUnstarted(new IllegalStateException("Paused player retired"), true);
                    return;
                }
                if (paused(owner)) {
                    awaitOpen(intent);
                    return;
                }
                synchronized (this) {
                    if (current != intent || retired) return;
                    intent.started = true;
                }
                CompletableFuture<T> actual;
                trackAccepted(owner, intent.done);
                try {
                    actual = intent.operation.get();
                } catch (Throwable failure) {
                    completed(intent, null, failure);
                    return;
                }
                ScarpetNativeWork.aliasDependency(intent.done, actual);
                actual.whenComplete((value, failure) -> completed(intent, value, failure));
            } catch (Throwable failure) {
                completed(intent, null, failure);
            }
        }

        <T> void completed(Intent<T> intent, T value, Throwable failure) {
            intent.finish(value, failure);
            synchronized (this) {
                if (current == intent) current = null;
            }
            pump();
        }

        void failUnstarted(Throwable failure, boolean terminal) {
            java.util.List<Intent<?>> failed;
            synchronized (this) {
                if (terminal) retired = true;
                failed = new java.util.ArrayList<>(waiting);
                waiting.clear();
                if (current != null && !current.started) {
                    failed.add(0, current);
                    current = null;
                }
            }
            for (Intent<?> intent : failed) intent.finish(null, failure);
            pump();
        }
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
            java.util.function.Consumer<net.minecraft.world.entity.Entity> body = owned -> {
                try {
                    TickThread.ensureTickThread(player, "Player inventory snapshot must run on its owner");
                    if (owned != player || player.isRemoved())
                        throw new IllegalStateException("Player retired before inventory snapshot");
                    result.complete(operation.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            };
            // A plain logout has no old asynchronous native work. Keep its original
            // synchronous owner cleanup instead of adding an artificial tick boundary.
            if (TickThread.isTickThreadFor(player)) body.accept(player);
            else try {
                boolean scheduled = player.getBukkitEntity().taskScheduler.schedule(body,
                        retired -> result.completeExceptionally(new IllegalStateException("Player retired before inventory snapshot")), 1L);
                if (!scheduled)
                    result.completeExceptionally(new IllegalStateException("Player scheduler retired before inventory snapshot"));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
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
