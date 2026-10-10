package fun.bm.lophine.carpet;

import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Nonblocking safe points covering every real region tick and between-tick execution.
 */
public final class CarpetResourceReloadCoordinator {
    private static final Object LIFECYCLE = new Object();
    private static final Gate GATE = new Gate();
    private static final ArrayDeque<Request> QUEUE = new ArrayDeque<>();
    private static final Set<CompletableFuture<?>> FUTURES = new HashSet<>();
    private static final Set<AutoCloseable> PREPARED = new HashSet<>();
    private static Request running;
    private static Barrier<?> barrier;
    private static volatile boolean closed;
    public static final Executor GLOBAL = runnable -> RegionizedServer.getInstance().addTask(runnable);

    private CarpetResourceReloadCoordinator() {
    }

    static final class Gate {
        private final AtomicInteger readers = new AtomicInteger();
        private volatile boolean paused;
        private volatile CompletableFuture<Void> acknowledgement;

        boolean enter() {
            if (paused) return false;
            readers.incrementAndGet();
            // A pause that raced the first check must count this entrant until it
            // withdraws, without allowing resource use after the safe point.
            if (paused) {
                exit();
                return false;
            }
            return true;
        }

        void exit() {
            int remaining = readers.decrementAndGet();
            if (remaining < 0) throw new IllegalStateException("Unbalanced resource reload region gate");
            // Capture the barrier generation before checking the live count. A new
            // reader can enter between decrement and pause, or a cancelled pause
            // can already have been replaced by the next generation.
            CompletableFuture<Void> ready = acknowledgement;
            // Only schedules global continuation; never performs the resource apply on this owner.
            if (remaining == 0 && ready != null && paused && readers.get() == 0) ready.complete(null);
        }

        synchronized CompletableFuture<Void> pause() {
            if (paused) throw new IllegalStateException("Resource reload gate already paused");
            acknowledgement = new CompletableFuture<>();
            paused = true;
            if (readers.get() == 0) acknowledgement.complete(null);
            return acknowledgement;
        }

        synchronized void resume() {
            paused = false;
            acknowledgement = null;
        }

        boolean paused() {
            return paused;
        }
    }

    public static boolean tryEnterRegion() {
        return GATE.enter();
    }

    public static void exitRegion() {
        GATE.exit();
    }

    public static boolean regionsPaused() {
        return GATE.paused();
    }

    private record Request(MinecraftServer server, Supplier<CompletableFuture<Void>> work,
                           CompletableFuture<Void> result) {
    }

    public static CompletableFuture<Void> serial(MinecraftServer server, Supplier<CompletableFuture<Void>> work) {
        CompletableFuture<Void> result = track();
        if (result.isDone()) return result;
        GLOBAL.execute(() -> {
            synchronized (LIFECYCLE) {
                if (closed) {
                    result.completeExceptionally(stopped());
                    return;
                }
                QUEUE.addLast(new Request(server, work, result));
                startNext();
            }
        });
        return result;
    }

    /**
     * Caller holds lifecycle; work starts on the global actor without waiting for it.
     */
    private static void startNext() {
        if (running != null || QUEUE.isEmpty() || closed) return;
        Request request = running = QUEUE.removeFirst();
        CompletableFuture<Void> work;
        try {
            work = request.work().get();
        } catch (Throwable failure) {
            work = CompletableFuture.failedFuture(failure);
        }
        work.whenComplete((unused, failure) -> GLOBAL.execute(() -> {
            synchronized (LIFECYCLE) {
                if (failure == null) request.result().complete(null);
                else request.result().completeExceptionally(failure);
                if (running == request) running = null;
                startNext();
            }
        }));
    }

    public static <T> CompletableFuture<T> atGlobal(Supplier<T> action) {
        CompletableFuture<T> result = track();
        if (result.isDone()) return result;
        GLOBAL.execute(() -> {
            if (result.isDone() || closed) return;
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static <T> CompletableFuture<T> track() {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (LIFECYCLE) {
            if (closed) result.completeExceptionally(stopped());
            else FUTURES.add(result);
        }
        result.whenComplete((value, failure) -> {
            synchronized (LIFECYCLE) {
                FUTURES.remove(result);
            }
        });
        return result;
    }

    private static IllegalStateException stopped() {
        return new IllegalStateException("Server stopped during resource reload");
    }

    public static void prepared(AutoCloseable resource) {
        synchronized (LIFECYCLE) {
            if (closed) {
                close(resource);
                throw stopped();
            }
            PREPARED.add(resource);
        }
    }

    public static void discard(AutoCloseable resource) {
        synchronized (LIFECYCLE) {
            PREPARED.remove(resource);
        }
        close(resource);
    }

    private static void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception failure) {
            MinecraftServer.LOGGER.warn("Could not close prepared data pack resources", failure);
        }
    }

    private static final class Barrier<T> {
        final MinecraftServer server;
        final Supplier<T> action;
        final AutoCloseable retain;
        final CompletableFuture<T> result;
        boolean started;

        Barrier(MinecraftServer server, Supplier<T> action, AutoCloseable retain, CompletableFuture<T> result) {
            this.server = server;
            this.action = action;
            this.retain = retain;
            this.result = result;
        }
    }

    public static <T> CompletableFuture<T> exclusive(MinecraftServer server, Supplier<T> action) {
        return exclusive(server, action, null);
    }

    /**
     * Retain transfers ownership from pending preparation to MinecraftServer under the same lifecycle lock.
     */
    public static <T> CompletableFuture<T> exclusive(MinecraftServer server, Supplier<T> action, AutoCloseable retain) {
        CompletableFuture<T> result = track();
        if (result.isDone()) return result;
        Barrier<T> request = new Barrier<>(server, action, retain, result);
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) GLOBAL.execute(() -> abandon(request));
        });
        GLOBAL.execute(() -> {
            CompletableFuture<Void> ready;
            synchronized (LIFECYCLE) {
                if (closed || result.isDone()) return;
                if (barrier != null) {
                    result.completeExceptionally(new IllegalStateException("Overlapping resource apply barriers"));
                    return;
                }
                barrier = request;
                ready = GATE.pause();
            }
            ready.thenRunAsync(() -> apply(request), GLOBAL);
            CompletableFuture<Void> timeout = CompletableFuture.runAsync(() -> GLOBAL.execute(() -> {
                synchronized (LIFECYCLE) {
                    if (barrier == request && !request.started) {
                        barrier = null;
                        GATE.resume();
                        try {
                            notifyRegions(server);
                        } finally {
                            result.completeExceptionally(new TimeoutException("Regions did not reach the resource reload safe point within 60 seconds"));
                        }
                    }
                }
            }), CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS));
            result.whenComplete((value, failure) -> timeout.cancel(false));
        });
        return result;
    }

    private static <T> void apply(Barrier<T> request) {
        boolean abandoned;
        synchronized (LIFECYCLE) {
            if (barrier != request || closed) return;
            abandoned = request.result.isDone();
            if (!abandoned) {
                request.started = true;
                if (request.retain != null) PREPARED.remove(request.retain);
            }
        }
        if (abandoned) {
            abandon(request);
            return;
        }
        T value = null;
        Throwable failure = null;
        try {
            value = request.action.get();
        } catch (Throwable problem) {
            failure = problem;
        } finally {
            synchronized (LIFECYCLE) {
                if (barrier == request) barrier = null;
                GATE.resume();
            }
            if (!closed) {
                try {
                    notifyRegions(request.server);
                } catch (Throwable problem) {
                    if (failure == null) failure = problem;
                    else failure.addSuppressed(problem);
                }
            }
        }
        if (failure == null) request.result.complete(value);
        else request.result.completeExceptionally(failure);
    }

    private static void abandon(Barrier<?> request) {
        synchronized (LIFECYCLE) {
            // Cancellation of a view cannot release the gate beneath an apply that
            // already started. Its native action retains the original finally tail.
            if (barrier != request || request.started) return;
            barrier = null;
            GATE.resume();
        }
        if (!closed) notifyRegions(request.server);
    }

    private static void notifyRegions(MinecraftServer server) {
        for (var level : server.getAllLevels())
            level.regioniser.computeForAllRegions(region -> region.getData().setHasTasks());
    }

    /**
     * Cancel pending work before the native schedulers halt, and release every waiting gate.
     */
    public static void shutdown() {
        synchronized (LIFECYCLE) {
            closed = true;
            if (barrier == null || !barrier.started) {
                barrier = null;
                GATE.resume();
            }
            QUEUE.clear();
            for (var future : Set.copyOf(FUTURES)) future.completeExceptionally(stopped());
            for (var resource : Set.copyOf(PREPARED)) close(resource);
            PREPARED.clear();
        }
    }
}
