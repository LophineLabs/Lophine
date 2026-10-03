package fun.bm.lophine.carpet;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CarpetRegionLeaseLifecycleTest {
    @Test
    void cancellationCannotReleaseATicketBetweenMetadataRegistrationAndNativeAddFinally() throws Exception {
        var nativeAddEntered = new CountDownLatch(1);
        var allowNativeAddExit = new CountDownLatch(1);
        var nativeAddExited = new AtomicBoolean();
        var releases = new AtomicInteger();
        var lifecycle = new CarpetRegionLeaseLifecycle(() -> {
            assertTrue(nativeAddExited.get()); releases.incrementAndGet();
        });
        Thread loading = Thread.ofVirtual().start(() -> {
            assertTrue(lifecycle.appendTicket(() -> {}));
            nativeAddEntered.countDown();
            try { await(allowNativeAddExit); }
            finally { nativeAddExited.set(true); lifecycle.acquired(); }
        });
        try {
            assertTrue(nativeAddEntered.await(5, TimeUnit.SECONDS));
            assertTrue(lifecycle.cancelWaiting());
            assertEquals(0, releases.get());
            assertFalse(lifecycle.beginActor());
        } finally { allowNativeAddExit.countDown(); }
        loading.join(5_000L);
        assertEquals(1, releases.get());
    }

    @Test
    void returnedNativeStageRetainsTicketsAfterOuterFutureAndActorFinallyComplete() {
        var releases = new AtomicInteger();
        var lifecycle = new CarpetRegionLeaseLifecycle(releases::incrementAndGet);
        var nativeTail = new CompletableFuture<Boolean>();
        var outer = new CompletableFuture<CompletableFuture<Boolean>>();
        outer.whenComplete((value, failure) -> lifecycle.close());
        lifecycle.acquired(); assertTrue(lifecycle.beginActor());
        lifecycle.follow(nativeTail); outer.complete(nativeTail); lifecycle.actorFinished();
        assertTrue(outer.isDone()); assertSame(nativeTail, outer.getNow(null));
        assertEquals(0, releases.get());
        nativeTail.complete(true);
        assertEquals(1, releases.get());
    }

    @Test
    void stageCompletionStillCannotReleaseInsideTheRealNativeActorBeforeItsFinally() {
        var releases = new AtomicInteger();
        var lifecycle = new CarpetRegionLeaseLifecycle(releases::incrementAndGet);
        var tail = new CompletableFuture<Void>();
        lifecycle.acquired(); assertTrue(lifecycle.beginActor());
        lifecycle.follow(tail); lifecycle.close(); tail.complete(null);
        assertEquals(0, releases.get());
        lifecycle.actorFinished(); assertEquals(1, releases.get());
    }

    @Test
    void nestedStagesAndFailedShutdownTailReleaseExactlyOnceAfterTheActualTerminalState() {
        var releases = new AtomicInteger();
        var lifecycle = new CarpetRegionLeaseLifecycle(releases::incrementAndGet);
        var parent = new CompletableFuture<CompletableFuture<Boolean>>();
        var child = new CompletableFuture<Boolean>();
        lifecycle.acquired(); assertTrue(lifecycle.beginActor()); lifecycle.follow(parent); lifecycle.actorFinished();
        assertFalse(lifecycle.cancelWaiting());
        parent.complete(child); assertEquals(0, releases.get());
        lifecycle.close(); assertEquals(0, releases.get());
        child.completeExceptionally(new IllegalStateException("Actual native owner tail retired during shutdown"));
        assertEquals(1, releases.get());
        lifecycle.close(); lifecycle.acquired(); lifecycle.actorFinished();
        assertEquals(1, releases.get());
    }

    @Test
    void concurrentActorExitAcquisitionExitAndNativeTailCompletionCannotReleaseEarlyOrTwice() throws Exception {
        for (int epoch = 0; epoch < 500; epoch++) {
            var releases = new AtomicInteger();
            var reference = new java.util.concurrent.atomic.AtomicReference<CarpetRegionLeaseLifecycle>();
            var lifecycle = new CarpetRegionLeaseLifecycle(() -> {
                assertFalse(Thread.holdsLock(reference.get()));
                releases.incrementAndGet();
            });
            reference.set(lifecycle);
            var tail = new CompletableFuture<Void>();
            assertTrue(lifecycle.beginActor()); lifecycle.follow(tail); lifecycle.close();
            var start = new CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread acquired = Thread.ofVirtual().start(() -> run(start, lifecycle::acquired, failure));
            Thread actor = Thread.ofVirtual().start(() -> run(start, lifecycle::actorFinished, failure));
            Thread nativeTail = Thread.ofVirtual().start(() -> run(start, () -> tail.complete(null), failure));
            start.countDown(); acquired.join(5_000L); actor.join(5_000L); nativeTail.join(5_000L);
            assertNull(failure.get()); assertEquals(1, releases.get());
        }
    }

    @Test
    void sharedOrSelfReturningStageIsObservedOnceWithoutARecursiveLeak() {
        var releases = new AtomicInteger();
        var lifecycle = new CarpetRegionLeaseLifecycle(releases::incrementAndGet);
        var tail = new CompletableFuture<Object>();
        lifecycle.acquired(); assertTrue(lifecycle.beginActor());
        lifecycle.follow(tail); lifecycle.follow(tail); lifecycle.actorFinished();
        tail.complete(tail);
        assertEquals(1, releases.get());
    }

    private static void run(CountDownLatch start, Runnable action, java.util.concurrent.atomic.AtomicReference<Throwable> failure) {
        try { await(start); action.run(); }
        catch (Throwable problem) { failure.compareAndSet(null, problem); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lease lifecycle proof timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
