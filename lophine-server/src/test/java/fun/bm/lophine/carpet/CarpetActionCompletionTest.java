package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CarpetActionCompletionTest {
    @Test
    void snapshotWaitsForNativeTailAndOwnerCommitEvenAfterActionWasReplaced() {
        var tracker = new CarpetActionCompletion();
        var oldAction = tracker.begin();
        var effects = new CompletableFuture<Void>();
        var ownerCommit = new CompletableFuture<Void>();
        var count = new AtomicInteger();
        effects.thenCompose(ignored -> ownerCommit).whenComplete((ignored, failure) -> {
            count.incrementAndGet();
            oldAction.finish(failure);
        });
        var saved = tracker.whenIdle(work -> CompletableFuture.completedFuture(work.get()), count::get);
        assertTrue(tracker.paused());
        assertFalse(saved.isDone());
        effects.complete(null);
        assertFalse(saved.isDone());
        assertEquals(0, count.get());
        ownerCommit.complete(null);
        assertEquals(1, saved.join());
        assertFalse(tracker.paused());
        assertFalse(tracker.hasPending());
    }

    @Test
    void newWorkIsPausedButAcceptedEffectsMayFinishWithoutWaitingForSnapshot() {
        var tracker = new CarpetActionCompletion();
        var accepted = tracker.begin();
        var saved = tracker.whenIdle(work -> CompletableFuture.completedFuture(work.get()), () -> 42);
        assertThrows(IllegalStateException.class, tracker::begin);
        accepted.finish();
        accepted.finish();
        assertEquals(42, saved.join());
        assertTrue(tracker.begin() != null);
    }

    @Test
    void nestedSnapshotStagesRetainPauseAndPreserveOriginalValue() {
        var tracker = new CarpetActionCompletion();
        var outer = new CompletableFuture<CompletableFuture<Integer>>();
        var inner = new CompletableFuture<Integer>();
        var result = tracker.whenIdle(work -> CompletableFuture.completedFuture(work.get()), () -> outer);
        assertTrue(tracker.paused());
        assertFalse(result.isDone());
        outer.complete(inner);
        assertFalse(result.isDone());
        assertTrue(tracker.paused());
        inner.complete(7);
        assertSame(outer, result.join());
        assertEquals(7, result.join().join().join());
        assertFalse(tracker.paused());
    }

    @Test
    void failingSnapshotTailResumesActionsAndPropagatesActualFailure() {
        var tracker = new CarpetActionCompletion();
        var child = new CompletableFuture<Integer>();
        var result = tracker.whenIdle(work -> CompletableFuture.completedFuture(work.get()), () -> child);
        child.completeExceptionally(new IllegalArgumentException("actual snapshot failure"));
        assertTrue(result.isCompletedExceptionally());
        assertFalse(tracker.paused());
        var failed = tracker.whenIdle(work -> {
            throw new IllegalStateException("owner retired");
        }, () -> 0);
        assertTrue(failed.isCompletedExceptionally());
        assertFalse(tracker.paused());
    }

    @Test
    void callerCancellationCannotReleasePauseBeforeActualSnapshotTerminates() {
        var tracker = new CarpetActionCompletion();
        var accepted = tracker.begin();
        var tail = new CompletableFuture<Integer>();
        var snapshots = new AtomicInteger();
        var result = tracker.whenIdle(work -> CompletableFuture.completedFuture(work.get()), () -> {
            snapshots.incrementAndGet();
            return tail;
        });
        result.cancel(false);
        assertTrue(tracker.paused());
        assertEquals(0, snapshots.get());
        accepted.finish();
        assertTrue(tracker.paused());
        assertEquals(1, snapshots.get());
        tail.complete(1);
        assertFalse(tracker.paused());
        assertTrue(result.isCancelled());
    }

    @Test
    void overlappingSnapshotsHaveIndependentPauseLifetimes() {
        var tracker = new CarpetActionCompletion();
        var a = new CompletableFuture<Integer>();
        var b = new CompletableFuture<Integer>();
        var first = tracker.whenIdle(work -> CompletableFuture.completedFuture(work.get()), () -> a);
        var second = tracker.whenIdle(work -> CompletableFuture.completedFuture(work.get()), () -> b);
        a.complete(1);
        assertTrue(first.isDone());
        assertTrue(tracker.paused());
        b.complete(2);
        assertTrue(second.isDone());
        assertFalse(tracker.paused());
    }

    @Test
    void concurrentRealCommitAndSnapshotRegistrationNeverCapturePrecommitState() throws Exception {
        for (int i = 0; i < 500; i++) {
            var tracker = new CarpetActionCompletion();
            var accepted = tracker.begin();
            var commit = new AtomicBoolean();
            var start = new CountDownLatch(1);
            var finished = new CompletableFuture<Void>();
            var actor = Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    commit.set(true);
                    accepted.finish();
                    finished.complete(null);
                } catch (Throwable failure) {
                    finished.completeExceptionally(failure);
                }
            });
            start.countDown();
            var snapshot = tracker.whenIdle(work -> CompletableFuture.completedFuture(work.get()), commit::get);
            assertTrue(snapshot.get(5, TimeUnit.SECONDS));
            finished.get(5, TimeUnit.SECONDS);
            actor.join(5_000);
            assertFalse(tracker.paused());
            assertFalse(tracker.hasPending());
        }
    }
}
