package fun.bm.lophine.carpet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OrgHiddenBedrockCycleTest {
    @Test void noStageOvertakesAnUnresolvedNativeDecisionAndRealResultIsPreserved(){
        var order=new ArrayList<String>();var power=new CompletableFuture<Void>();var nearby=new CompletableFuture<Void>();
        var mine=new CompletableFuture<Boolean>();var replacement=new CompletableFuture<Object>();var actual=new Object();
        var result=OrgHiddenBedrockCycle.unpowerAndReplace(()->{order.add("power");return power;},()->{order.add("nearby");return nearby;},
            ()->{order.add("mine");return mine;},()->{order.add("replacement");return replacement;});
        assertEquals(List.of("power"),order);power.complete(null);assertEquals(List.of("power","nearby"),order);
        nearby.complete(null);assertEquals(List.of("power","nearby","mine"),order);assertFalse(result.isDone());
        // Source always attempts replacement after its break call, even when that call returns false.
        mine.complete(false);assertEquals(List.of("power","nearby","mine","replacement"),order);assertFalse(result.isDone());
        replacement.complete(actual);assertSame(actual,result.join());
    }
    @Test void actualCancellationStopsLaterWorldEffects(){
        var power=new CompletableFuture<Void>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var result=OrgHiddenBedrockCycle.unpowerAndReplace(()->power,()->{calls.incrementAndGet();return CompletableFuture.completedFuture(null);},
            ()->{calls.incrementAndGet();return CompletableFuture.completedFuture(null);},()->{calls.incrementAndGet();return CompletableFuture.completedFuture(true);});
        power.completeExceptionally(new java.util.concurrent.CancellationException("owner retired"));assertTrue(result.isCompletedExceptionally());assertEquals(0,calls.get());
    }
    @Test void synchronousLaterNativeFailureBecomesTheActualFutureFailure(){
        var result=OrgHiddenBedrockCycle.unpowerAndReplace(()->CompletableFuture.completedFuture(null),()->CompletableFuture.completedFuture(null),
            ()->CompletableFuture.completedFuture(true),()->{throw new IllegalStateException("replacement failed");});
        assertTrue(result.isCompletedExceptionally());assertInstanceOf(IllegalStateException.class,assertThrows(java.util.concurrent.CompletionException.class,result::join).getCause());
    }
}
