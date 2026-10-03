package fun.bm.lophine.carpet;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CarpetActionTerminationTest {
    @Test void removalWaitsAllActualAcceptedTerminalsAfterAnEarlierFailure(){
        var bag=new CarpetActionCompletion();var failed=bag.begin();var unfinished=bag.begin();var snapshots=new AtomicInteger();
        var result=bag.whenIdleAfterTermination(work->CompletableFuture.completedFuture(work.get()),()->snapshots.incrementAndGet());
        assertTrue(bag.paused());failed.finish(new IllegalStateException("old VM shutdown"));assertFalse(result.isDone());assertEquals(0,snapshots.get());
        assertThrows(IllegalStateException.class,bag::begin);unfinished.finish();assertEquals(1,result.join());assertFalse(bag.paused());
    }
    @Test void ordinarySaveStillFailsClosedForTheSamePendingFailure(){
        var bag=new CarpetActionCompletion();var accepted=bag.begin();var calls=new AtomicInteger();
        var result=bag.whenIdle(work->CompletableFuture.completedFuture(work.get()),()->calls.incrementAndGet());accepted.finish(new IllegalStateException("native failed"));
        assertTrue(result.isCompletedExceptionally());assertEquals(0,calls.get());assertFalse(bag.paused());
    }
    @Test void oldFailureDoesNotHideNewSnapshotOrNestedWriteFailures(){
        var bag=new CarpetActionCompletion();var old=bag.begin();var write=new CompletableFuture<String>();
        var result=bag.whenIdleAfterTermination(work->CompletableFuture.completedFuture(work.get()),()->write);old.finish(new IllegalStateException("old tail"));
        assertFalse(result.isDone());assertTrue(bag.paused());write.completeExceptionally(new IllegalArgumentException("new disk I/O"));
        assertTrue(result.isCompletedExceptionally());assertFalse(bag.paused());assertInstanceOf(IllegalArgumentException.class,assertThrows(java.util.concurrent.CompletionException.class,result::join).getCause());
    }
    @Test void cancellingRemovalViewCannotReleaseActualNestedWritePause(){
        var bag=new CarpetActionCompletion();var inner=new CompletableFuture<String>();var result=bag.whenIdleAfterTermination(work->CompletableFuture.completedFuture(work.get()),()->inner);
        result.cancel(false);assertTrue(bag.paused());inner.complete("written");assertFalse(bag.paused());assertTrue(result.isCancelled());
    }
}
