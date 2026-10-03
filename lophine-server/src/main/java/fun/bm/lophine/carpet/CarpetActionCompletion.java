package fun.bm.lophine.carpet;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/** Accepted actions remain visible until their native effects and owner state commit terminate. */
final class CarpetActionCompletion {
    private final Set<CompletableFuture<Void>> accepted = Collections.newSetFromMap(new IdentityHashMap<>());
    private int pauses;

    synchronized boolean paused() { return pauses != 0; }
    synchronized boolean hasPending() { return !accepted.isEmpty(); }

    synchronized Accepted begin() {
        if (pauses != 0) throw new IllegalStateException("New actions cannot start during a player snapshot");
        var result = new Accepted();
        accepted.add(result.terminal);
        return result;
    }

    synchronized CompletableFuture<Void> pendingCompletion() {
        // Each entry already includes every dynamic native child and its owner commit.
        return CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new));
    }

    <T> CompletableFuture<T> whenIdle(Function<Supplier<T>, CompletableFuture<T>> owner, Supplier<T> snapshot) {
        return whenIdle(owner,snapshot,false);
    }

    <T> CompletableFuture<T> whenIdleAfterTermination(Function<Supplier<T>, CompletableFuture<T>> owner, Supplier<T> snapshot) {
        return whenIdle(owner,snapshot,true);
    }

    private <T> CompletableFuture<T> whenIdle(Function<Supplier<T>, CompletableFuture<T>> owner, Supplier<T> snapshot, boolean ignorePreviousFailure) {
        CompletableFuture<Void> pending;
        synchronized (this) {
            var remaining = new java.util.ArrayList<CompletableFuture<Void>>();
            for(var terminal:accepted){
                var dependency=carpet.script.external.ScarpetNativeWork.knownDependencyOf(terminal);
                boolean ownCausalWork=dependency!=null&&carpet.script.external.ScarpetNativeWork.currentDependsOn(dependency);
                if(ownCausalWork){
                    if(!ignorePreviousFailure)return CompletableFuture.failedFuture(new IllegalStateException(
                        "Cannot capture a stable player action snapshot from a callback that depends on its own pending native action"));
                    // A mandatory removal may interrupt its own causal operation. It still waits
                    // every independently admitted job, even another job for the same player.
                    continue;
                }
                remaining.add(terminal);
            }
            pauses++;pending=CompletableFuture.allOf(remaining.toArray(CompletableFuture[]::new));
        }
        // allOf waits every actual terminal future even after an earlier entry fails.
        if(ignorePreviousFailure)pending=pending.handle((ignored,failure)->null);
        var result = new CompletableFuture<T>();
        var lifetime = new Snapshot<>(result, () -> {
            synchronized (CarpetActionCompletion.this) {
                if (--pauses < 0) throw new IllegalStateException("Player snapshot pause completed twice");
            }
        });
        try {
            CompletableFuture<T> operation = pending.thenCompose(ignored -> owner.apply(snapshot));
            lifetime.follow(operation, true);
        } catch (Throwable failure) { lifetime.failed(failure); }
        finally { lifetime.exit(); }
        // Cancelling the caller's view must not release the pause while the actual snapshot runs.
        return result;
    }

    final class Accepted {
        private final CompletableFuture<Void> terminal = new CompletableFuture<>();
        private final AtomicBoolean ended = new AtomicBoolean();
        CompletableFuture<Void> future() { return terminal; }

        void finish() { finish(null); }

        void finish(Throwable failure) {
            if (!ended.compareAndSet(false, true)) return;
            // Complete before removal: a racing snapshot observes either this terminal future or
            // the state after its real owner commit. A failed retired owner cannot be serialized.
            if (failure == null) terminal.complete(null); else terminal.completeExceptionally(failure);
            synchronized (CarpetActionCompletion.this) { accepted.remove(terminal); }
        }
    }

    /** Preserve T while retaining the pause through CompletionStage values recursively returned in T. */
    private static final class Snapshot<T> {
        private final CompletableFuture<T> result;
        private final Runnable release;
        private final Set<CompletionStage<?>> observed = Collections.newSetFromMap(new IdentityHashMap<>());
        private int pending = 1;
        private T value;
        private Throwable failure;
        private boolean ended;

        Snapshot(CompletableFuture<T> result, Runnable release) { this.result = result; this.release = release; }

        @SuppressWarnings("unchecked")
        void follow(CompletionStage<?> stage, boolean outer) {
            synchronized (this) {
                if (!observed.add(stage)) return;
                if (ended) throw new IllegalStateException("Snapshot acquired a tail after completion");
                pending++;
            }
            var once = new AtomicBoolean();
            try {
                stage.whenComplete((nested, thrown) -> {
                    if (!once.compareAndSet(false, true)) return;
                    try {
                        if (thrown != null) failed(thrown);
                        else {
                            if (outer) synchronized (this) { value = (T) nested; }
                            if (nested instanceof CompletionStage<?> child) follow(child, false);
                        }
                    } catch (Throwable failure) { failed(failure); }
                    finally { exit(); }
                });
            } catch (Throwable failure) {
                failed(failure);
                if (once.compareAndSet(false, true)) exit();
            }
        }

        synchronized void failed(Throwable thrown) { if (failure == null) failure = thrown; }

        void exit() {
            T completedValue;
            Throwable completedFailure;
            synchronized (this) {
                if (--pending < 0) throw new IllegalStateException("Snapshot completion was counted twice");
                if (pending != 0) return;
                ended = true; observed.clear(); completedValue = value; completedFailure = failure;
            }
            // Release first so a callback that begins the next action sees the correct gate state.
            try { release.run(); }
            catch (Throwable thrown) { if (completedFailure == null) completedFailure = thrown; }
            if (completedFailure == null) result.complete(completedValue);
            else result.completeExceptionally(completedFailure);
        }
    }
}
