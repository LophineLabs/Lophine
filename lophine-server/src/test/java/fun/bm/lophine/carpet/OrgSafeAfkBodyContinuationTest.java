package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetNativeWork;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.damagesource.DamageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgSafeAfkBodyContinuationTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    @Test void nestedOuterScopeChecksOnlyAfterTheRealDeathBodyAndOwnerContinuationComplete()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var damage=mockStatic(ScarpetDamageContinuations.class);var afk=mockStatic(OrgSafeAfk.class,CALLS_REAL_METHODS)){
            var player=fixture.target.player();fixture.owner.set(player);DamageSource source=mock(DamageSource.class);var pending=new AtomicReference<CompletableFuture<Boolean>>();var actualBody=new CompletableFuture<Boolean>();var calls=new AtomicInteger();
            damage.when(()->ScarpetDamageContinuations.pendingBodyResult(player)).thenAnswer(call->pending.get());afk.when(()->OrgSafeAfk.afterDamage(player,source,8F,false)).thenAnswer(call->{calls.incrementAndGet();return null;});
            var actual=ScarpetNativeWork.observeNative(player,()->OrgSafeAfk.withDamageOuter(player,source,8F,()->OrgSafeAfk.withDamage(player,source,8F,()->{pending.set(actualBody);ScarpetNativeWork.record(actualBody);return false;})));
            assertEquals(0,calls.get());assertFalse(actual.isDone());fixture.owner.set(null);actualBody.complete(true);assertEquals(0,calls.get());assertFalse(actual.isDone());fixture.drain(fixture.target);assertEquals(1,calls.get());assertFalse(actual.join());
        }
    }
    @Test void theTotemFlagSurvivesTheDeferredBodyScopeAndTheActualCheckTailIsObserved()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var damage=mockStatic(ScarpetDamageContinuations.class);var afk=mockStatic(OrgSafeAfk.class,CALLS_REAL_METHODS)){
            var player=fixture.target.player();fixture.owner.set(player);DamageSource source=mock(DamageSource.class);var pending=new AtomicReference<CompletableFuture<Boolean>>();var body=new CompletableFuture<Boolean>();var removal=new CompletableFuture<Void>();var checked=new AtomicBoolean();
            damage.when(()->ScarpetDamageContinuations.pendingBodyResult(player)).thenAnswer(call->pending.get());afk.when(()->OrgSafeAfk.afterDamage(player,source,8F,true)).thenAnswer(call->{checked.set(true);ScarpetNativeWork.record(removal);return null;});
            var actual=ScarpetNativeWork.observeNative(player,()->OrgSafeAfk.withDamage(player,source,8F,()->{OrgSafeAfk.markTotemUsed(player);pending.set(body);ScarpetNativeWork.record(body);return false;}));fixture.owner.set(null);body.complete(true);fixture.drain(fixture.target);assertTrue(checked.get());assertFalse(actual.isDone());removal.complete(null);assertFalse(actual.join());
        }
    }
    @Test void aPendingGuestDecisionDoesNotTriggerAnOuterPrefixCheck()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var damage=mockStatic(ScarpetDamageContinuations.class);var afk=mockStatic(OrgSafeAfk.class,CALLS_REAL_METHODS)){
            var player=fixture.target.player();fixture.owner.set(player);DamageSource source=mock(DamageSource.class);damage.when(()->ScarpetDamageContinuations.pendingCompletion(player)).thenReturn(new CompletableFuture<>());var calls=new AtomicInteger();afk.when(()->OrgSafeAfk.afterDamage(player,source,1F,false)).thenAnswer(call->{calls.incrementAndGet();return null;});assertFalse(OrgSafeAfk.withDamageOuter(player,source,1F,()->false));assertEquals(0,calls.get());
        }
    }
}
