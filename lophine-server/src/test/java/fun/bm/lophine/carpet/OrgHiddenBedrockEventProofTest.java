package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OrgHiddenBedrockEventProofTest {
    @Test
    void delayedOriginalEventWaitsForActualTailAndSurvivesSequenceCompletion() {
        var proof = new OrgHiddenBedrockEventProof<Object>();
        var event = new Object();
        var ticket = proof.arm();
        proof.queued(event);
        assertTrue(proof.defer(event));
        assertFalse(proof.ready(event));
        var actual = new CompletableFuture<Boolean>();
        var caller = actual.whenComplete((replaced, failure) -> {
            if (failure == null && replaced) ticket.replaced();
            ticket.close();
        }).thenApply(value -> value);
        caller.cancel(false);
        assertTrue(proof.defer(event));
        assertFalse(proof.ready(event));
        actual.complete(true);
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(proof.defer(event));
            assertTrue(proof.ready(event));
        }
        proof.consumed(event);
        assertFalse(proof.ready(event));
        assertTrue(proof.empty());
    }

    @Test
    void rejectedOrFailedReplacementNeverEnablesAQueuedExploit() {
        for (boolean failure : new boolean[]{false, true}) {
            var proof = new OrgHiddenBedrockEventProof<Object>();
            var event = new Object();
            var ticket = proof.arm();
            proof.queued(event);
            var actual = new CompletableFuture<Boolean>();
            actual.whenComplete((replaced, error) -> {
                if (error == null && replaced) ticket.replaced();
                ticket.close();
            });
            if (failure) actual.completeExceptionally(new IllegalStateException("native cancelled"));
            else actual.complete(false);
            assertFalse(proof.defer(event));
            assertFalse(proof.ready(event));
            proof.consumed(event);
            assertTrue(proof.empty());
        }
    }

    @Test
    void proofUsesNativeEventIdentityEvenForEqualRecordValues() {
        record Event(int value) {
        }
        var proof = new OrgHiddenBedrockEventProof<Event>();
        var original = new Event(7);
        var equal = new Event(7);
        proof.queued(original);
        var ticket = proof.arm();
        ticket.replaced();
        ticket.close();
        assertTrue(proof.ready(original));
        assertFalse(proof.ready(equal));
        proof.consumed(equal);
        assertTrue(proof.ready(original));
        proof.consumed(original);
        assertTrue(proof.empty());
    }

    @Test
    void aSuccessfulSequenceWithoutARealQueuedEventCannotGrantFutureEvents() {
        var proof = new OrgHiddenBedrockEventProof<Object>();
        var ticket = proof.arm();
        ticket.replaced();
        ticket.close();
        assertTrue(proof.empty());
        var unrelated = new Object();
        proof.queued(unrelated);
        assertFalse(proof.ready(unrelated));
        assertFalse(proof.defer(unrelated));
        proof.consumed(unrelated);
        assertTrue(proof.empty());
    }

    @Test
    void overlappingActualSequencesRetainHoldUntilAllAcceptedTailsTerminate() {
        var proof = new OrgHiddenBedrockEventProof<Object>();
        var event = new Object();
        var first = proof.arm();
        var second = proof.arm();
        proof.queued(event);
        first.replaced();
        first.close();
        assertTrue(proof.defer(event));
        assertTrue(proof.ready(event));
        second.close();
        assertFalse(proof.defer(event));
        assertTrue(proof.ready(event));
        proof.consumed(event);
        assertTrue(proof.empty());
    }

    @Test
    void actualTailCompletionAndQueueConsumptionCanRaceWithoutLeakingProof() throws Exception {
        for (int epoch = 0; epoch < 500; epoch++) {
            var proof = new OrgHiddenBedrockEventProof<Object>();
            var event = new Object();
            var ticket = proof.arm();
            proof.queued(event);
            var start = new java.util.concurrent.CountDownLatch(1);
            var finished = new CompletableFuture<Void>();
            var worker = new Thread(() -> {
                try {
                    start.await();
                    ticket.replaced();
                    ticket.close();
                    finished.complete(null);
                } catch (Throwable error) {
                    finished.completeExceptionally(error);
                }
            });
            worker.start();
            start.countDown();
            proof.consumed(event);
            finished.get(5, java.util.concurrent.TimeUnit.SECONDS);
            worker.join();
            assertFalse(proof.ready(event));
            assertTrue(proof.empty());
        }
    }
}

