package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class CarpetActionSequenceTest {
    @Test
    void deferredPassAndNativeOwnerTailMustBothFinishBeforeTheNextPassAndFinalCompletion() {
        var first = new CompletableFuture<Boolean>();
        var firstTail = new CompletableFuture<Boolean>();
        var second = new CompletableFuture<Boolean>();
        var executed = new ArrayList<Integer>();
        var finished = new ArrayList<Integer>();
        var result = CarpetActionSequence.run(0, 2, false, index -> {
            executed.add(index);
            return index == 0 ? first : second;
        }, (index, value) -> {
            finished.add(index);
            return index == 0 ? firstTail : CompletableFuture.completedFuture(value);
        });
        assertEquals(List.of(0), executed);
        assertFalse(result.isDone());
        first.complete(false);
        assertEquals(List.of(0), executed);
        assertEquals(List.of(0), finished);
        assertFalse(result.isDone());
        firstTail.complete(false);
        assertEquals(List.of(0, 1), executed);
        assertFalse(result.isDone());
        second.complete(true);
        assertTrue(result.isDone());
        assertTrue(result.getNow(false));
    }

    @Test
    void restoredProgressDoesNotRepeatPreviouslyCompletedPassesAndFailuresDoNotAdvanceTheTail() {
        var executed = new ArrayList<Integer>();
        var finished = new ArrayList<Integer>();
        var pending = new CompletableFuture<Boolean>();
        var result = CarpetActionSequence.run(2, 4, false, index -> {
            executed.add(index);
            return pending;
        }, (index, value) -> {
            finished.add(index);
            return CompletableFuture.completedFuture(value);
        });
        assertEquals(List.of(2), executed);
        pending.completeExceptionally(new IllegalStateException("native interaction failed"));
        assertTrue(result.isCompletedExceptionally());
        assertEquals(List.of(2), executed);
        assertTrue(finished.isEmpty());
    }
}
