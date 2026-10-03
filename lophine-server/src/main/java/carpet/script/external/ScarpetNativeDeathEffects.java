// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;

/** Each removed effect finishes its actual native children before the next original effect. */
public final class ScarpetNativeDeathEffects {
    private static final WeakIdentityMap<LivingEntity, Prefix> PREFIXES = new WeakIdentityMap<>();
    private static final ThreadLocal<Set<LivingEntity>> REPLAYING = ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final class Prefix {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        final List<Runnable> tails = new ArrayList<>();
        volatile boolean finished;
    }
    private ScarpetNativeDeathEffects() { }

    public static <T> CompletableFuture<Void> sequence(LivingEntity target, Iterator<T> originalIterator, Consumer<T> effect, Runnable finish) {
        Supplier<CompletableFuture<Void>> step = ScarpetRuntime.captureNativeContinuation(() -> step(target, originalIterator, effect, finish));
        CompletableFuture<Void> actual = step.get();
        ScarpetNativeWork.record(actual);
        return actual;
    }
    private static <T> CompletableFuture<Void> step(LivingEntity target, Iterator<T> iterator, Consumer<T> effect, Runnable finish) {
        return ScarpetExplosionActors.entity(target, () -> {
            if (!iterator.hasNext()) {
                return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(target, () -> { finish.run(); return (Void)null; }));
            }
            T next = iterator.next();
            // Capture before the asynchronous boundary; completion must preserve the accepted native flags.
            Supplier<CompletableFuture<Void>> continueEffects = ScarpetRuntime.captureNativeContinuation(() -> step(target, iterator, effect, finish));
            var current = ScarpetNativeWork.observeNative(target, () -> { effect.accept(next); return (Void)null; });
            return ScarpetNativeWork.recoverGuestValue(current).thenCompose(ignored -> continueEffects.get());
        }).thenCompose(value -> value);
    }

    /** Includes the effect prefix, physical Entity.remove, and subclass enclosing cleanup. */
    public static void remove(LivingEntity target, Supplier<CompletableFuture<Void>> effects, Runnable physical) {
        Prefix previous = PREFIXES.get(target);
        if (previous != null && !previous.result.isDone()) return;
        Prefix prefix = new Prefix(); PREFIXES.put(target, prefix);
        var observed = ScarpetNativeWork.observeNative(target, () -> {
            Supplier<CompletableFuture<Void>> physicalStep = ScarpetRuntime.captureNativeContinuation(() -> ScarpetExplosionActors.entity(target, () -> {
                boolean added = REPLAYING.get().add(target);
                try { return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(target, () -> { physical.run(); return (Void)null; })); }
                finally { if (added) REPLAYING.get().remove(target); }
            }).thenCompose(value -> value));
            Supplier<CompletableFuture<Void>> tails = ScarpetRuntime.captureNativeContinuation(() -> ScarpetExplosionActors.entity(target, () -> {
                List<Runnable> pending;
                synchronized (prefix.tails) { prefix.finished = true; pending = List.copyOf(prefix.tails); }
                return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(target, () -> { for (Runnable tail : pending) tail.run(); return (Void)null; }));
            }).thenCompose(value -> value));
            var body = effects.get().thenCompose(ignored -> physicalStep.get()).thenCompose(ignored -> tails.get());
            ScarpetNativeWork.record(body); return body;
        });
        var actual = ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value -> value);
        ScarpetNativeWork.aliasDependency(actual, observed); ScarpetNativeWork.aliasDependency(prefix.result, observed);
        actual.whenComplete((ignored, failure) -> {
            PREFIXES.remove(target, prefix);
            if (failure == null) prefix.result.complete(null); else prefix.result.completeExceptionally(failure);
        });
        ScarpetNativeWork.record(prefix.result);
        ScarpetNativeWork.trackNative(target.level().getServer(), prefix.result);
    }
    public static boolean isPending(Entity entity) { return entity instanceof LivingEntity living && PREFIXES.get(living) != null; }
    public static CompletableFuture<Void> completion(Entity entity) {
        if (!(entity instanceof LivingEntity living) || REPLAYING.get().contains(living)) return null;
        Prefix prefix = PREFIXES.get(living); if (prefix == null) return null;
        var copy = prefix.result.copy(); ScarpetNativeWork.aliasDependency(copy, prefix.result); return copy;
    }
    public static boolean thenOwner(Entity entity, Runnable tail) {
        if (!(entity instanceof LivingEntity living) || REPLAYING.get().contains(living)) return false;
        Prefix prefix = PREFIXES.get(living); if (prefix == null) return false;
        synchronized (prefix.tails) { if (prefix.finished) return false; prefix.tails.add(tail); return true; }
    }
}
