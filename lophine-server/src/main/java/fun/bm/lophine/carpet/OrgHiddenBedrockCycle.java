package fun.bm.lophine.carpet;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Real power-off, surrounding lever tails, completed excavation and replacement order. */
final class OrgHiddenBedrockCycle {
    private OrgHiddenBedrockCycle() {}
    static <T> CompletableFuture<T> unpowerAndReplace(Supplier<? extends CompletableFuture<?>> primary,
            Supplier<? extends CompletableFuture<?>> nearby,Supplier<? extends CompletableFuture<?>> mining,Supplier<CompletableFuture<T>> replacement){
        return primary.get().thenCompose(ignored->nearby.get().thenApply(value->(Object)value))
            .thenCompose(ignored->mining.get().thenApply(value->(Object)value)).thenCompose(ignored->replacement.get());
    }
}
