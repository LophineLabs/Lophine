// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.world.entity.Entity;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Observes complete native operations and every dynamically appended replay, without waiting.
 */
public final class ScarpetNativeWork {
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private static final WeakIdentityMap<CompletableFuture<?>, Token> DEPENDENCIES = new WeakIdentityMap<>();
    private static final WeakIdentityMap<net.minecraft.server.MinecraftServer, Boolean> DRAINING = new WeakIdentityMap<>();

    private enum FailureOrigin {GUEST, NATIVE}

    private static final WeakIdentityMap<Throwable, FailureOrigin> FAILURE_ORIGINS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<CompletableFuture<?>, Boolean> GUEST_FUTURES = new WeakIdentityMap<>();

    private ScarpetNativeWork() {
    }

    /**
     * Interpreter failures retain their public Throwable; identity metadata is set before publication.
     */
    static void markGuestFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (!(cause instanceof GuestContinuationFailure))
            FAILURE_ORIGINS.computeIfAbsent(cause, ignored -> FailureOrigin.GUEST);
    }

    /**
     * Only a complete observer with no failed native child carries this marker.
     */
    public static boolean onlyGuestFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof GuestContinuationFailure && FAILURE_ORIGINS.get(cause) != FailureOrigin.NATIVE;
    }

    private static final class GuestContinuationFailure extends RuntimeException {
        final java.lang.ref.WeakReference<CompletableFuture<?>> observation;
        final Object value;

        GuestContinuationFailure(Throwable cause) {
            this(cause, null, null);
        }

        GuestContinuationFailure(Throwable cause, CompletableFuture<?> observation, Object value) {
            super("Guest callbacks failed after the native body was admitted", cause);
            this.observation = new java.lang.ref.WeakReference<>(observation);
            this.value = value;
        }
    }

    /**
     * Recovers only this direct observer's real body value. Its original receipt remains failed and enrolled in its parent.
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> recoverGuestValue(CompletableFuture<T> observed) {
        var recovered = observed.handle((value, failure) -> {
            if (failure == null) return value;
            Throwable cause = unwrap(failure);
            if (onlyGuestFailure(cause) && cause instanceof GuestContinuationFailure marker && marker.observation.get() == observed)
                return (T) marker.value;
            throw new java.util.concurrent.CompletionException(cause);
        });
        aliasDependency(recovered, observed);
        return recovered;
    }

    public static final class Token {
        private final Entity owner;
        private final Token parent;
        private final java.util.Set<Token> prerequisites = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicInteger pending = new AtomicInteger(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Throwable> guestFailure = new AtomicReference<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();

        private Token(Entity owner, Token parent) {
            this.owner = owner;
            this.parent = parent;
        }

        public Entity owner() {
            return owner;
        }

        private void retain() {
            for (; ; ) {
                int count = pending.get();
                if (count == 0)
                    throw new IllegalStateException("Native work was appended after its owner scope finished");
                if (count == Integer.MAX_VALUE) throw new IllegalStateException("Too many pending native operations");
                if (pending.compareAndSet(count, count + 1)) return;
            }
        }

        private void release(Throwable problem) {
            if (problem != null) {
                Throwable cause = unwrap(problem);
                if (cause instanceof GuestContinuationFailure marker && FAILURE_ORIGINS.get(cause) != FailureOrigin.NATIVE)
                    guestFailure.compareAndSet(null, marker.getCause());
                else if (FAILURE_ORIGINS.computeIfAbsent(cause, ignored -> FailureOrigin.NATIVE) == FailureOrigin.GUEST)
                    guestFailure.compareAndSet(null, cause);
                else failure.compareAndSet(null, cause);
            }
            int count = pending.decrementAndGet();
            if (count < 0) throw new IllegalStateException("Native work scope closed twice");
            if (count == 0) {
                Throwable error = failure.get();
                if (error == null && guestFailure.get() != null)
                    error = new GuestContinuationFailure(guestFailure.get());
                if (error == null) finished.complete(null);
                else finished.completeExceptionally(error);
            }
        }
    }

    /**
     * The supplier is invoked by its native owner; this observer does not inspect entity state.
     */
    public static <T> CompletableFuture<T> observeNative(Entity owner, Supplier<T> operation) {
        Token previous = CURRENT.get();
        Token token = new Token(owner, previous);
        var value = new AtomicReference<T>();
        var result = new CompletableFuture<T>();
        DEPENDENCIES.put(result, token);
        token.finished.whenComplete((ignored, failure) -> {
            if (failure == null) result.complete(value.get());
            else if (onlyGuestFailure(failure))
                result.completeExceptionally(new GuestContinuationFailure(unwrap(failure).getCause(), result, value.get()));
            else result.completeExceptionally(failure);
        });
        // A nested native operation belongs to its still-running outer owner operation too.
        record(result);
        CURRENT.set(token);
        Throwable problem = null;
        try {
            value.set(operation.get());
        } catch (Throwable failure) {
            problem = failure;
            FAILURE_ORIGINS.put(unwrap(failure), FailureOrigin.NATIVE);
        } finally {
            restore(previous);
            token.release(problem);
        }
        return result;
    }

    public static Token capture() {
        return CURRENT.get();
    }

    /**
     * Metadata-only lifetime view for an inventory gate; never record this view into its own token.
     */
    public static CompletableFuture<Void> completionOf(Token token) {
        if (token == null) return CompletableFuture.completedFuture(null);
        var view = token.finished.copy();
        DEPENDENCIES.put(view, token);
        return view;
    }

    /**
     * Pause ordinary world/input producers while actual actor continuations are still drained.
     */
    public static void beginDrain(net.minecraft.server.MinecraftServer server) {
        DRAINING.put(server, Boolean.TRUE);
        fun.bm.lophine.carpet.OrgPlayerInventoryMenus.beginDrain(server);
        fun.bm.lophine.carpet.CarpetPlayerBirths.beginDrain(server);
    }

    public static boolean isDraining(net.minecraft.server.MinecraftServer server) {
        return DRAINING.get(server) != null;
    }

    /**
     * Register the complete actual native job before exposing a cancellation-safe caller view.
     */
    public static <T> CompletableFuture<T> trackNative(net.minecraft.server.MinecraftServer server, CompletableFuture<T> actual) {
        ScarpetNativeRemovals.trackCaller(server, actual);
        var caller = actual.copy();
        aliasDependency(caller, actual);
        return caller;
    }

    /**
     * The shutdown coordinator keeps region actors alive through all registered native tails.
     */
    public static CompletableFuture<Void> whenIdle(net.minecraft.server.MinecraftServer server) {
        return ScarpetNativeRemovals.whenIdle(server);
    }

    public static <T> T without(Supplier<T> operation) {
        Token previous = CURRENT.get();
        CURRENT.remove();
        try {
            return operation.get();
        } finally {
            restore(previous);
        }
    }

    public static void without(Runnable operation) {
        without(() -> {
            operation.run();
            return null;
        });
    }

    public static Token dependencyOf(CompletableFuture<?> future) {
        Token observed = DEPENDENCIES.get(future);
        return observed == null ? CURRENT.get() : observed;
    }

    public static Token knownDependencyOf(CompletableFuture<?> future) {
        return DEPENDENCIES.get(future);
    }

    public static void aliasDependency(CompletableFuture<?> alias, CompletableFuture<?> original) {
        Token token = DEPENDENCIES.get(original);
        if (token != null) DEPENDENCIES.put(alias, token);
    }

    public static boolean currentDependsOn(Token ancestor) {
        if (ancestor == null) return false;
        var currentAncestors = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Token, Boolean>());
        for (Token current = CURRENT.get(); current != null; current = current.parent) currentAncestors.add(current);
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Token, Boolean>());
        var pending = new java.util.ArrayDeque<Token>();
        pending.add(ancestor);
        while (!pending.isEmpty()) {
            Token candidate = pending.removeFirst();
            if (!seen.add(candidate)) continue;
            if (currentAncestors.contains(candidate)) return true;
            pending.addAll(candidate.prerequisites);
        }
        return false;
    }

    /**
     * Metadata for an already serialized phase; it does not change failure or retention semantics.
     */
    public static void linkDependency(CompletableFuture<?> dependent, CompletableFuture<?> prerequisite) {
        Token waiter = dependencyOf(dependent), before = dependencyOf(prerequisite);
        if (waiter != null && before != null && waiter != before) waiter.prerequisites.add(before);
    }

    public static <T> T with(Token token, Supplier<T> operation) {
        if (token == null) return operation.get();
        token.retain();
        Token previous = CURRENT.get();
        CURRENT.set(token);
        Throwable problem = null;
        try {
            return operation.get();
        } catch (Throwable failure) {
            problem = failure;
            throw failure;
        } finally {
            restore(previous);
            token.release(problem);
        }
    }

    public static void with(Token token, Runnable operation) {
        with(token, () -> {
            operation.run();
            return null;
        });
    }

    public static void record(CompletableFuture<?> future) {
        Token token = CURRENT.get();
        if (token == null) return;
        Token prerequisite = DEPENDENCIES.computeIfAbsent(future, ignored -> token);
        if (prerequisite != token) token.prerequisites.add(prerequisite);
        token.retain();
        boolean guest = GUEST_FUTURES.get(future) != null;
        try {
            future.whenComplete((value, failure) -> {
                if (guest && failure != null) markGuestFailure(failure);
                token.release(failure);
            });
        } catch (Throwable failure) {
            token.release(failure);
            throw failure;
        }
    }

    /**
     * Also classifies cancellation of this actual interpreter receipt before observer callbacks run.
     */
    static void recordGuest(CompletableFuture<?> future) {
        GUEST_FUTURES.put(future, Boolean.TRUE);
        record(future);
    }

    private static void restore(Token previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof java.util.concurrent.CompletionException || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
