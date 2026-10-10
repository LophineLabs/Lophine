package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class CarpetActionCausalSnapshotTest {
    private static <T> CompletableFuture<T> owner(java.util.function.Supplier<T> work) {
        return CompletableFuture.completedFuture(work.get());
    }

    @Test
    void ordinarySelfSnapshotFailsImmediatelyAndDoesNotPauseItsNativeProducer() {
        var bag = new CarpetActionCompletion();
        var result = new AtomicReference<CompletableFuture<String>>();
        var accepted = new AtomicReference<CarpetActionCompletion.Accepted>();
        var actual = ScarpetNativeWork.observeNative(null, () -> {
            accepted.set(bag.begin());
            ScarpetNativeWork.record(accepted.get().future());
            result.set(bag.whenIdle(CarpetActionCausalSnapshotTest::owner, () -> "invalid early snapshot"));
            return null;
        });
        assertTrue(result.get().isCompletedExceptionally());
        assertFalse(bag.paused());
        assertFalse(actual.isDone());
        assertTrue(assertThrows(java.util.concurrent.CompletionException.class, () -> result.get().join()).getCause().getMessage().contains("own pending native action"));
        accepted.get().finish();
        assertTrue(actual.isDone());
    }

    @Test
    void mandatorySelfRemovalCanCompleteWithoutWaitingOnItsOwnTail() {
        var bag = new CarpetActionCompletion();
        var result = new AtomicReference<CompletableFuture<String>>();
        var accepted = new AtomicReference<CarpetActionCompletion.Accepted>();
        var actual = ScarpetNativeWork.observeNative(null, () -> {
            accepted.set(bag.begin());
            ScarpetNativeWork.record(accepted.get().future());
            result.set(bag.whenIdleAfterTermination(CarpetActionCausalSnapshotTest::owner, () -> "removed"));
            return null;
        });
        assertEquals("removed", result.get().join());
        assertFalse(actual.isDone());
        assertFalse(bag.paused());
        accepted.get().finish();
        assertTrue(actual.isDone());
    }

    @Test
    void aDescendantCallbackExcludesOnlyItsActualAncestorJob() {
        var bag = new CarpetActionCompletion();
        var accepted = new AtomicReference<CarpetActionCompletion.Accepted>();
        var result = new AtomicReference<CompletableFuture<Integer>>();
        var outer = ScarpetNativeWork.observeNative(null, () -> {
            accepted.set(bag.begin());
            ScarpetNativeWork.record(accepted.get().future());
            ScarpetNativeWork.observeNative(null, () -> {
                result.set(bag.whenIdleAfterTermination(CarpetActionCausalSnapshotTest::owner, () -> 7));
                return null;
            });
            return null;
        });
        assertEquals(7, result.get().join());
        assertFalse(outer.isDone());
        accepted.get().finish();
        assertTrue(outer.isDone());
    }

    @Test
    void aDependentSerializedJobIsExcludedOnlyWhenItReallyDependsOnTheCallback() {
        var bag = new CarpetActionCompletion();
        var callbackHold = new CompletableFuture<Void>();
        var dependentHold = new CompletableFuture<Void>();
        var callbackToken = new AtomicReference<ScarpetNativeWork.Token>();
        var callback = ScarpetNativeWork.observeNative(null, () -> {
            callbackToken.set(ScarpetNativeWork.capture());
            ScarpetNativeWork.record(callbackHold);
            return null;
        });
        var accepted = bag.begin();
        var dependent = ScarpetNativeWork.observeNative(null, () -> {
            ScarpetNativeWork.record(dependentHold);
            return null;
        });
        ScarpetNativeWork.linkDependency(dependent, callback);
        ScarpetNativeWork.aliasDependency(accepted.future(), dependent);
        var result = ScarpetNativeWork.with(callbackToken.get(), () -> bag.whenIdleAfterTermination(CarpetActionCausalSnapshotTest::owner, () -> "causal dependent removed"));
        assertEquals("causal dependent removed", result.join());
        assertFalse(callback.isDone());
        assertFalse(dependent.isDone());
        accepted.finish();
        dependentHold.complete(null);
        callbackHold.complete(null);
        assertTrue(callback.isDone());
        assertTrue(dependent.isDone());
    }

    @Test
    void anotherSameOwnerJobMustReachItsRealTerminalBeforeMandatorySnapshot() {
        var bag = new CarpetActionCompletion();
        var independent = bag.begin();
        var independentHold = new CompletableFuture<Void>();
        var independentJob = ScarpetNativeWork.observeNative(null, () -> {
            ScarpetNativeWork.record(independentHold);
            return null;
        });
        ScarpetNativeWork.aliasDependency(independent.future(), independentJob);
        var own = new AtomicReference<CarpetActionCompletion.Accepted>();
        var result = new AtomicReference<CompletableFuture<String>>();
        var ownJob = ScarpetNativeWork.observeNative(null, () -> {
            own.set(bag.begin());
            ScarpetNativeWork.record(own.get().future());
            result.set(bag.whenIdleAfterTermination(CarpetActionCausalSnapshotTest::owner, () -> "stable others"));
            return null;
        });
        assertTrue(bag.paused());
        assertFalse(result.get().isDone());
        independentHold.complete(null);
        assertFalse(result.get().isDone());
        independent.finish(new IllegalStateException("old independent job failed at real terminal"));
        assertEquals("stable others", result.get().join());
        assertFalse(bag.paused());
        assertFalse(ownJob.isDone());
        own.get().finish();
        assertTrue(ownJob.isDone());
    }

    @Test
    void untaggedAcceptedJobCannotBeMistakenForCurrentCallbackByFallback() {
        var bag = new CarpetActionCompletion();
        var untagged = bag.begin();
        var hold = new CompletableFuture<Void>();
        var result = new AtomicReference<CompletableFuture<String>>();
        var callback = ScarpetNativeWork.observeNative(null, () -> {
            ScarpetNativeWork.record(hold);
            result.set(bag.whenIdleAfterTermination(CarpetActionCausalSnapshotTest::owner, () -> "after untagged"));
            return null;
        });
        assertNull(ScarpetNativeWork.knownDependencyOf(untagged.future()));
        assertFalse(result.get().isDone());
        untagged.finish();
        assertEquals("after untagged", result.get().join());
        hold.complete(null);
        assertTrue(callback.isDone());
    }

    @Test
    void trueCycleOfMandatoryJobWaitingForCallbackSnapshotTerminatesWithoutDeadlock() {
        var bag = new CarpetActionCompletion();
        var accepted = new AtomicReference<CarpetActionCompletion.Accepted>();
        var result = new AtomicReference<CompletableFuture<String>>();
        var own = ScarpetNativeWork.observeNative(null, () -> {
            accepted.set(bag.begin());
            ScarpetNativeWork.record(accepted.get().future());
            result.set(bag.whenIdleAfterTermination(CarpetActionCausalSnapshotTest::owner, () -> "snapshot"));
            result.get().whenComplete((value, failure) -> accepted.get().finish(failure));
            return null;
        });
        assertEquals("snapshot", result.get().join());
        assertTrue(own.isDone());
        assertFalse(bag.hasPending());
        assertFalse(bag.paused());
    }

    @Test
    void mandatorySelfExclusionStillPropagatesNewNestedSnapshotFailure() {
        var bag = new CarpetActionCompletion();
        var accepted = new AtomicReference<CarpetActionCompletion.Accepted>();
        var nested = new CompletableFuture<String>();
        var result = new AtomicReference<CompletableFuture<CompletableFuture<String>>>();
        var own = ScarpetNativeWork.observeNative(null, () -> {
            accepted.set(bag.begin());
            ScarpetNativeWork.record(accepted.get().future());
            result.set(bag.whenIdleAfterTermination(CarpetActionCausalSnapshotTest::owner, () -> nested));
            return null;
        });
        assertTrue(bag.paused());
        assertFalse(result.get().isDone());
        nested.completeExceptionally(new IllegalStateException("new snapshot failed"));
        assertTrue(result.get().isCompletedExceptionally());
        assertFalse(bag.paused());
        assertFalse(own.isDone());
        accepted.get().finish();
        assertTrue(own.isDone());
    }
}
