package carpet.script.external;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Completion represents the real global audience capture and every accepted owner packet tail.
 */
final class ScarpetExplosionPacketBarrier {
    private ScarpetExplosionPacketBarrier() {
    }

    static <A> CompletableFuture<Void> fanOut(CompletableFuture<List<A>> audience, Function<A, CompletableFuture<?>> send) {
        return audience.thenCompose(players -> {
            var actual = new java.util.ArrayList<CompletableFuture<?>>();
            for (A player : players)
                try {
                    actual.add(send.apply(player));
                } catch (Throwable failure) {
                    actual.add(CompletableFuture.failedFuture(failure));
                }
            return CompletableFuture.allOf(actual.toArray(CompletableFuture[]::new));
        });
    }
}
