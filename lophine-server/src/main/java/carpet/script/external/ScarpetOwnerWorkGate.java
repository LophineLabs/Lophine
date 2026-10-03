// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Only admission metadata is synchronized; native work and snapshots run on their owner.
 */
final class ScarpetOwnerWorkGate {
    private final java.util.Map<CompletableFuture<?>, ScarpetNativeWork.Token> accepted = new IdentityHashMap<>();
    private int pauses;
    private CompletableFuture<Void> opened = CompletableFuture.completedFuture(null);

    synchronized boolean paused() {
        return pauses != 0;
    }

    synchronized CompletableFuture<Void> whenOpen() {
        return opened;
    }

    void track(CompletableFuture<?> actual) {
        synchronized (this) {
            if (accepted.containsKey(actual)) return;
            accepted.put(actual, ScarpetNativeWork.dependencyOf(actual));
        }
        actual.whenComplete((ignored, failure) -> {
            synchronized (ScarpetOwnerWorkGate.this) {
                accepted.remove(actual);
            }
        });
    }

    <T> CompletableFuture<T> whenIdle(Function<Supplier<T>, CompletableFuture<T>> owner, Supplier<T> snapshot) {
        return whenIdle(owner, snapshot, false);
    }

    <T> CompletableFuture<T> whenIdle(Function<Supplier<T>, CompletableFuture<T>> owner, Supplier<T> snapshot, boolean mandatoryCleanup) {
        return whenIdle(owner, snapshot, mandatoryCleanup, false);
    }

    <T> CompletableFuture<T> whenIdle(Function<Supplier<T>, CompletableFuture<T>> owner, Supplier<T> snapshot, boolean mandatoryCleanup, boolean authorizedChild) {
        CompletableFuture<Void> pending;
        synchronized (this) {
            if (pauses++ == 0) opened = new CompletableFuture<>();
            pending = CompletableFuture.allOf(accepted.entrySet().stream()
                    .filter(entry -> {
                        var known = ScarpetNativeWork.knownDependencyOf(entry.getKey());
                        return !authorizedChild || !ScarpetNativeWork.currentDependsOn(known == null ? entry.getValue() : known);
                    })
                    .map(entry -> mandatoryCleanup ? entry.getKey().handle((ignored, failure) -> null) : entry.getKey())
                    .toArray(CompletableFuture[]::new));
        }
        var result = new CompletableFuture<T>();
        var lifetime = new Snapshot<T>(result, this::release);
        try {
            lifetime.follow(pending.thenCompose(ignored -> owner.apply(snapshot)), true);
        } catch (Throwable failure) {
            lifetime.fail(failure);
        } finally {
            lifetime.exit();
        }
        return result;
    }

    private void release() {
        CompletableFuture<Void> resume = null;
        synchronized (this) {
            if (--pauses < 0) throw new IllegalStateException("Player snapshot gate closed twice");
            if (pauses == 0) resume = opened;
        }
        if (resume != null) resume.complete(null);
    }

    /**
     * A cancelled caller does not release a reservation still executing its real nested tail.
     */
    private static final class Snapshot<T> {
        private final CompletableFuture<T> result;
        private final Runnable release;
        private final Set<CompletionStage<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private int pending = 1;
        private T value;
        private Throwable failure;

        Snapshot(CompletableFuture<T> result, Runnable release) {
            this.result = result;
            this.release = release;
        }

        @SuppressWarnings("unchecked")
        void follow(CompletionStage<?> stage, boolean outer) {
            synchronized (this) {
                if (!seen.add(stage)) return;
                pending++;
            }
            var once = new AtomicBoolean();
            try {
                stage.whenComplete((nested, thrown) -> {
                    if (!once.compareAndSet(false, true)) return;
                    try {
                        if (thrown != null) fail(thrown);
                        else {
                            if (outer) synchronized (this) {
                                value = (T) nested;
                            }
                            if (nested instanceof CompletionStage<?> child) follow(child, false);
                        }
                    } catch (Throwable problem) {
                        fail(problem);
                    } finally {
                        exit();
                    }
                });
            } catch (Throwable problem) {
                fail(problem);
                if (once.compareAndSet(false, true)) exit();
            }
        }

        synchronized void fail(Throwable problem) {
            if (failure == null) failure = problem;
        }

        void exit() {
            T completed;
            Throwable problem;
            synchronized (this) {
                if (--pending < 0) throw new IllegalStateException("Player snapshot tail closed twice");
                if (pending != 0) return;
                seen.clear();
                completed = value;
                problem = failure;
            }
            try {
                release.run();
            } catch (Throwable thrown) {
                if (problem == null) problem = thrown;
            }
            if (problem == null) result.complete(completed);
            else result.completeExceptionally(problem);
        }
    }
}
