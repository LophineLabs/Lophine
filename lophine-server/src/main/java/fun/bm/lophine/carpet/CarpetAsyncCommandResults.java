// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetExplosionActors;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-invocation result ownership for commands completed by Folia actors.
 */
public final class CarpetAsyncCommandResults {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private CarpetAsyncCommandResults() {
    }

    public static Scope open() {
        Scope scope = new Scope(CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /**
     * The command's new observer is not an earlier native operation waiting for this invocation.
     */
    public static boolean hasNativeCause() {
        Scope scope = CURRENT.get();
        return scope == null ? ScarpetNativeWork.capture() != null : scope.nativeCause != null;
    }

    public static void trackContext(CompletableFuture<Integer> future) {
        Scope scope = CURRENT.get();
        if (scope != null) scope.children.add(future);
    }

    public static Completion defer(CommandSourceStack source) {
        Scope scope = CURRENT.get();
        if (scope == null) return new Completion(source);
        return scope.deferred.computeIfAbsent(source, Completion::new);
    }

    /**
     * True means the async completion owns this callback, including already finished work.
     */
    public static boolean consumeImmediate(CommandSourceStack source, boolean success, int result) {
        Scope scope = CURRENT.get();
        Completion deferred = scope == null ? null : scope.deferred.get(source);
        if (deferred == null) return false;
        if (!success) deferred.complete(false, 0);
        return true;
    }

    public static final class Scope implements AutoCloseable {
        private final Scope parent;
        private final ScarpetNativeWork.Token nativeCause = ScarpetNativeWork.capture();
        private final IdentityHashMap<CommandSourceStack, Completion> deferred = new IdentityHashMap<>();
        private final java.util.List<CompletableFuture<Integer>> children = new java.util.ArrayList<>();
        private boolean closed;

        private Scope(Scope parent) {
            this.parent = parent;
        }

        private java.util.List<CompletableFuture<Integer>> actualFutures() {
            var futures = new java.util.ArrayList<>(children);
            for (Completion completion : deferred.values()) futures.add(completion.result);
            return java.util.List.copyOf(futures);
        }

        public java.util.List<CompletableFuture<Integer>> pendingFutures() {
            return actualFutures().stream().map(CompletableFuture::copy).toList();
        }

        public CompletableFuture<Void> completionFuture() {
            return CompletableFuture.allOf(actualFutures().toArray(CompletableFuture[]::new));
        }

        public CompletableFuture<Integer> resultFuture(Object source) {
            Completion completion = deferred.get(source);
            return completion == null ? null : completion.future();
        }

        public CompletableFuture<Integer> traceFuture(Object source, boolean forked) {
            Completion completion = deferred.get(source);
            return completion == null ? null : completion.future().thenApply(value -> forked ? completion.success ? 1 : 0 : value);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (CURRENT.get() != this) throw new IllegalStateException("Command scopes closed out of order");
            if (parent != null) parent.children.addAll(actualFutures());
            if (parent == null) CURRENT.remove();
            else CURRENT.set(parent);
        }
    }

    public static final class Completion {
        private final CommandSourceStack source;
        private final CommandResultCallback callback;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final CompletableFuture<Integer> result = new CompletableFuture<>();
        private volatile boolean success;
        private volatile int value;
        private final java.util.function.Supplier<CompletableFuture<Integer>> delivery;

        private Completion(CommandSourceStack source) {
            this.source = source;
            this.callback = source.callback();
            // Capture the accepted command's flags and causal parent before a guest host can close.
            this.delivery = ScarpetRuntime.captureNativeContinuation(() -> {
                Entity owner = source.getEntity();
                java.util.function.Supplier<CompletableFuture<Integer>> body = () -> ScarpetNativeWork.observeNative(owner, () -> {
                    callback.onResult(success, value);
                    return value;
                });
                CompletableFuture<CompletableFuture<Integer>> queued;
                if (owner != null) queued = ScarpetExplosionActors.entity(owner, body);
                else {
                    ServerLevel world = source.getLevel();
                    if (world == null) return body.get();
                    queued = ScarpetExplosionActors.world(world, net.minecraft.core.BlockPos.containing(source.getPosition()), body);
                }
                var actual = queued.thenCompose(next -> next);
                ScarpetNativeWork.record(actual);
                return actual;
            });
            ScarpetNativeWork.record(result);
            var server = source.getServer();
            if (server != null) ScarpetNativeWork.trackNative(server, result);
        }

        /**
         * Caller cancellation cannot finish the actual callback, its children, or the enclosing command scope.
         */
        public CompletableFuture<Integer> future() {
            var caller = result.copy();
            ScarpetNativeWork.aliasDependency(caller, result);
            return caller;
        }

        public void complete(boolean success, int value) {
            if (!finished.compareAndSet(false, true)) return;
            this.success = success;
            this.value = value;
            try {
                var actual = delivery.get();
                ScarpetNativeWork.aliasDependency(result, actual);
                actual.whenComplete((count, failure) -> {
                    if (failure == null) result.complete(count);
                    else result.completeExceptionally(failure);
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }
    }
}
