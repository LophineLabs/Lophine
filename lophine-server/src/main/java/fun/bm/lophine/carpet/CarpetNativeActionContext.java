package fun.bm.lophine.carpet;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Causal metadata only; the actual owner dispatcher separately enters the Native inventory gate.
 */
final class CarpetNativeActionContext {
    private static final Object NONE = new Object();
    private static final ScopedValue<Object> CURRENT = ScopedValue.newInstance();

    private CarpetNativeActionContext() {
    }

    static Object current() {
        Object value = CURRENT.orElse(NONE);
        return value == NONE ? null : value;
    }

    static <T> T with(Object owner, Supplier<T> work) {
        return ScopedValue.where(CURRENT, owner == null ? NONE : owner).call(work::get);
    }

    static <T> CompletableFuture<T> relay(Object owner, CompletionStage<T> actual) {
        return relay(owner, null, actual);
    }

    static <T> CompletableFuture<T> relay(Object owner, carpet.script.external.ScarpetNativeWork.Token token, CompletionStage<T> actual) {
        var future = new CompletableFuture<T>();
        if (actual instanceof CompletableFuture<?> nativeFuture)
            carpet.script.external.ScarpetNativeWork.aliasDependency(future, nativeFuture);
        actual.whenComplete((value, failure) -> inNative(token, () -> with(owner, () -> {
            if (failure == null) future.complete(value);
            else future.completeExceptionally(failure);
            return null;
        })));
        return future;
    }

    static <T> T inNative(carpet.script.external.ScarpetNativeWork.Token token, Supplier<T> work) {
        return token == null ? carpet.script.external.ScarpetNativeWork.without(work) : carpet.script.external.ScarpetNativeWork.with(token, work);
    }
}
