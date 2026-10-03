package carpet.script.external;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ScarpetOwnerWorkGateTest {
    @Test void mandatorySelfRemovalAlsoSkipsAQueuedPhaseThatDependsOnItsCurrentCallback() {
        var gate = new ScarpetOwnerWorkGate();
        var currentBody = new CompletableFuture<Void>();
        var currentToken = new java.util.concurrent.atomic.AtomicReference<ScarpetNativeWork.Token>();
        var current = ScarpetNativeWork.observeNative(null, () -> {
            currentToken.set(ScarpetNativeWork.capture()); ScarpetNativeWork.record(currentBody); return true;
        });
        var queuedBody = new CompletableFuture<Void>();
        var queued = ScarpetNativeWork.observeNative(null, () -> {
            ScarpetNativeWork.record(queuedBody); ScarpetNativeWork.linkDependency(queuedBody, currentBody); return true;
        });
        gate.track(current); gate.track(queued);
        var cleanup = ScarpetNativeWork.with(currentToken.get(), () -> gate.whenIdle(
            task -> CompletableFuture.completedFuture(task.get()), () -> 42, true, true));
        assertEquals(42, cleanup.getNow(-1));
        assertFalse(current.isDone()); assertFalse(queued.isDone());
        currentBody.complete(null); queuedBody.complete(null);
        assertTrue(current.getNow(false)); assertTrue(queued.getNow(false));
    }

    @Test void mandatorySelfRemovalExcludesItsExactParentAndStillWaitsIndependentNativeWork() {
        var gate = new ScarpetOwnerWorkGate();
        var parentBody = new CompletableFuture<Void>();
        var parentToken = new java.util.concurrent.atomic.AtomicReference<ScarpetNativeWork.Token>();
        var parent = ScarpetNativeWork.observeNative(null, () -> {
            parentToken.set(ScarpetNativeWork.capture());
            ScarpetNativeWork.record(parentBody);
            return 7;
        });
        var independent = new CompletableFuture<Void>();
        gate.track(parent); gate.track(independent);
        var cleanups = new AtomicInteger();
        var cleanup = ScarpetNativeWork.with(parentToken.get(), () ->
            gate.whenIdle(task -> CompletableFuture.completedFuture(task.get()), () -> { cleanups.incrementAndGet(); return 42; }, true, true));
        assertFalse(cleanup.isDone());
        assertEquals(0, cleanups.get());
        independent.completeExceptionally(new IllegalStateException("old guest cancelled at shutdown"));
        assertEquals(42, cleanup.getNow(-1));
        assertEquals(1, cleanups.get());
        assertFalse(parent.isDone());
        parentBody.complete(null);
        assertEquals(7, parent.getNow(-1));
        assertFalse(gate.paused());
    }

    @Test void ordinarySnapshotCannotUseRemovalAuthorizationToSkipItsOwnUncommittedWork() {
        var gate = new ScarpetOwnerWorkGate();
        var commit = new CompletableFuture<Void>();
        var token = new java.util.concurrent.atomic.AtomicReference<ScarpetNativeWork.Token>();
        var actual = ScarpetNativeWork.observeNative(null, () -> {
            token.set(ScarpetNativeWork.capture()); ScarpetNativeWork.record(commit); return true;
        });
        gate.track(actual);
        var calls = new AtomicInteger();
        var snapshot = ScarpetNativeWork.with(token.get(), () -> gate.whenIdle(
            task -> CompletableFuture.completedFuture(task.get()), () -> { calls.incrementAndGet(); return 3; }, false, false));
        assertFalse(snapshot.isDone());
        assertEquals(0, calls.get());
        commit.complete(null);
        assertEquals(3, snapshot.getNow(-1));
        assertEquals(1, calls.get());
    }

    @Test void snapshotWaitsActualCommitAndRetainsPauseThroughNestedReservation() {
        for (int epoch = 0; epoch < 500; epoch++) {
            var gate = new ScarpetOwnerWorkGate();
            var accepted = new CompletableFuture<Void>();
            gate.track(accepted);
            var ownerTask = new CompletableFuture<CompletableFuture<Void>>();
            var reservation = new CompletableFuture<Void>();
            var calls = new AtomicInteger();
            var snapshot = gate.whenIdle(action -> {
                calls.incrementAndGet();
                assertSame(reservation, action.get());
                return ownerTask;
            }, () -> reservation);
            var opens = gate.whenOpen();
            assertTrue(gate.paused());
            assertEquals(0, calls.get());
            accepted.complete(null);
            assertEquals(1, calls.get());
            ownerTask.complete(reservation);
            assertFalse(snapshot.isDone());
            assertFalse(opens.isDone());
            assertTrue(snapshot.cancel(false));
            assertTrue(gate.paused());
            reservation.complete(null);
            assertFalse(gate.paused());
            assertTrue(opens.isDone());
        }
    }

    @Test void overlappingSnapshotsDoNotAdmitNewWorkUntilBothRealTailsEnd() {
        var gate = new ScarpetOwnerWorkGate();
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        var a = gate.whenIdle(task -> CompletableFuture.completedFuture(task.get()), () -> first);
        var b = gate.whenIdle(task -> CompletableFuture.completedFuture(task.get()), () -> second);
        var waiting = gate.whenOpen();
        first.complete(null);
        assertTrue(a.isDone());
        assertFalse(b.isDone());
        assertFalse(waiting.isDone());
        second.complete(null);
        assertTrue(b.isDone());
        assertTrue(waiting.isDone());
        assertFalse(gate.paused());
    }

    @Test void failedAcceptedNativeWorkCannotProduceAnInventorySnapshot() {
        var gate = new ScarpetOwnerWorkGate();
        var accepted = new CompletableFuture<Void>();
        gate.track(accepted);
        var calls = new AtomicInteger();
        var result = gate.whenIdle(task -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(task.get()); }, () -> 7);
        accepted.completeExceptionally(new IllegalStateException("retired before commit"));
        assertTrue(result.isCompletedExceptionally());
        assertEquals(0, calls.get());
        assertFalse(gate.paused());
    }
    @Test void aReservedBirthThatGetsItsActualTokenAtPlacementDoesNotAwaitItsOwnRemoval() {
        var gate=new ScarpetOwnerWorkGate();var privateBirth=new CompletableFuture<Void>();var unrelated=new CompletableFuture<Void>();
        gate.track(privateBirth);gate.track(unrelated);
        var cleanup=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Integer>>();
        var actual=ScarpetNativeWork.observeNative(null,()->{
            ScarpetNativeWork.aliasDependency(privateBirth,ScarpetNativeWork.completionOf(ScarpetNativeWork.capture()));
            cleanup.set(gate.whenIdle(task->CompletableFuture.completedFuture(task.get()),()->17,true,true));
            ScarpetNativeWork.record(cleanup.get());return true;
        });
        actual.whenComplete((value,failure)->{if(failure==null)privateBirth.complete(null);else privateBirth.completeExceptionally(failure);});
        assertFalse(cleanup.get().isDone());assertFalse(actual.isDone());
        unrelated.complete(null);assertEquals(17,cleanup.get().join());assertTrue(actual.join());assertTrue(privateBirth.isDone());
    }
}
