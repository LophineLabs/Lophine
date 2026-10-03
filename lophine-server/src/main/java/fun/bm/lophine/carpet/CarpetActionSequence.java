package fun.bm.lophine.carpet;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * Per-tick passes advance only after each actual native result and its owner-side tail.
 */
final class CarpetActionSequence {
    private CarpetActionSequence() {
    }

    static <T> CompletableFuture<T> run(int first, int count, T initial,
                                        IntFunction<CompletableFuture<T>> execute, BiFunction<Integer, T, CompletableFuture<T>> tail) {
        CompletableFuture<T> result = CompletableFuture.completedFuture(initial);
        for (int pass = first; pass < count; pass++) {
            final int index = pass;
            result = result.thenCompose(previous -> execute.apply(index)).thenCompose(value -> tail.apply(index, value));
        }
        return result;
    }
}
