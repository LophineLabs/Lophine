package fun.bm.lophine.carpet.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Queued operations always settle on shutdown, even if the global task queue stops draining.
 */
final class CarpetApiTasks {
    private final Executor executor;
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private boolean closed;

    CarpetApiTasks(Executor executor) {
        this.executor = executor;
    }

    <T> CompletableFuture<T> submit(Supplier<T> body) {
        return submitAsync(() -> CompletableFuture.completedFuture(body.get()));
    }

    <T> CompletableFuture<T> submitAsync(Supplier<CompletableFuture<T>> body) {
        var actual = new CompletableFuture<T>();
        var operation = new AtomicReference<>(body);
        synchronized (this) {
            if (this.closed) return CompletableFuture.failedFuture(new IllegalStateException("Carpet API is closing"));
            this.pending.add(actual);
        }
        actual.whenComplete((value, failure) -> {
            operation.set(null); // A stopped global queue must not retain plugin callbacks.
            synchronized (this) {
                this.pending.remove(actual);
            }
        });
        try {
            this.executor.execute(() -> {
                synchronized (this) {
                    if (this.closed) return;
                }
                var action = operation.getAndSet(null);
                if (action == null) return;
                try {
                    action.get().whenComplete((value, failure) -> {
                        if (failure == null) actual.complete(value);
                        else actual.completeExceptionally(failure);
                    });
                } catch (Throwable failure) {
                    actual.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        // A caller's cancellation must not cancel an accepted configuration write.
        return actual.copy();
    }

    void close() {
        List<CompletableFuture<?>> rejected;
        synchronized (this) {
            this.closed = true;
            rejected = List.copyOf(this.pending);
            this.pending.clear();
        }
        rejected.forEach(future -> future.completeExceptionally(new IllegalStateException("Carpet API is closing")));
    }
}
