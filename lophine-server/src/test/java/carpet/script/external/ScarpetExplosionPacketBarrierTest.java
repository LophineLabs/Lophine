package carpet.script.external;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class ScarpetExplosionPacketBarrierTest {
    @Test
    void actualAudienceAndEveryOwnerReceiptMustFinish() {
        var audience = new CompletableFuture<List<Integer>>();
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        var actual = ScarpetExplosionPacketBarrier.fanOut(audience, player -> player == 1 ? first : second);
        assertFalse(actual.isDone());
        audience.complete(List.of(1, 2));
        assertFalse(actual.isDone());
        second.complete(null);
        assertFalse(actual.isDone());
        first.complete(null);
        assertTrue(actual.isDone());
    }

    @Test
    void anOwnerFailureDoesNotAllowCompletionBeforeOtherRealOwnerTails() {
        var failed = new CompletableFuture<Void>();
        var unfinished = new CompletableFuture<Void>();
        var actual = ScarpetExplosionPacketBarrier.fanOut(CompletableFuture.completedFuture(List.of(1, 2)), player -> player == 1 ? failed : unfinished);
        failed.completeExceptionally(new IllegalStateException("send failed"));
        assertFalse(actual.isDone());
        unfinished.complete(null);
        assertTrue(actual.isCompletedExceptionally());
    }

    @Test
    void aSynchronousSendFailureStillInvokesAndAwaitsOtherRecipients() {
        var receipt = new CompletableFuture<Void>();
        var invoked = new java.util.concurrent.atomic.AtomicInteger();
        var actual = ScarpetExplosionPacketBarrier.fanOut(CompletableFuture.completedFuture(List.of(1, 2)), player -> {
            if (player == 1) throw new IllegalStateException("packet construction");
            invoked.incrementAndGet();
            return receipt;
        });
        assertEquals(1, invoked.get());
        assertFalse(actual.isDone());
        receipt.complete(null);
        assertTrue(actual.isCompletedExceptionally());
    }

    @Test
    void emptyAudienceCompletesAndGlobalFailureCannotFakeAnEmptyAudience() {
        assertTrue(ScarpetExplosionPacketBarrier.fanOut(CompletableFuture.completedFuture(List.of()), ignored -> CompletableFuture.completedFuture(null)).isDone());
        assertTrue(ScarpetExplosionPacketBarrier.fanOut(CompletableFuture.<List<Integer>>failedFuture(new IllegalStateException("global capture failed")), ignored -> CompletableFuture.completedFuture(null)).isCompletedExceptionally());
    }
}
