package carpet.script.external;

import carpet.script.EntityEventsGroup;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real ServerExplosion phase bodies and real typed DamageContinuations; only actor scheduling and collision envelope are fixtures. */
public class ScarpetExplosionContinuationsTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel world=mock(ServerLevel.class);
        final LivingEntity entity=mock(LivingEntity.class);
        final EntityEventsGroup events=mock(EntityEventsGroup.class);
        final DamageSource source=mock(DamageSource.class);
        final ExplosionDamageCalculator calculator=mock(ExplosionDamageCalculator.class);
        final CompletableFuture<Void> decision=new CompletableFuture<>(), lateNative=new CompletableFuture<>();
        final List<String> order=new ArrayList<>();
        final Queue<Runnable> ownerTasks=new ConcurrentLinkedQueue<>();
        final MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final MockedStatic<org.bukkit.Bukkit> bukkit=mockStatic(org.bukkit.Bukkit.class);
        final MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);
        final MockedStatic<org.bukkit.craftbukkit.event.CraftEventFactory> craftEvents=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class);
        final MockedStatic<ScarpetExplosionDensity> density=mockStatic(ScarpetExplosionDensity.class);
        final MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        final ServerExplosion explosion;
        final ScarpetRuntime runtime;
        Fixture() throws Exception {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            when(world.getServer()).thenReturn(server);
            var craft=mock(org.bukkit.craftbukkit.CraftServer.class);when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            var scheduler=mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);when(craft.getRegionScheduler()).thenReturn(scheduler);
            java.lang.reflect.Field sf=MinecraftServer.class.getField("server");sf.set(server,craft);when(world.getWorld()).thenReturn(mock(org.bukkit.craftbukkit.CraftWorld.class));
            doAnswer(call->{ownerTasks.add(call.getArgument(4));return null;}).when(scheduler).execute(any(),any(),anyInt(),anyInt(),any());
            Object internal=org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE;
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            when(entity.level()).thenReturn(world); when(entity.blockPosition()).thenReturn(BlockPos.ZERO);
            when(entity.position()).thenReturn(new Vec3(1,0,0)); when(entity.getEyePosition()).thenReturn(new Vec3(1,0,0));
            when(entity.distanceToSqr(Vec3.ZERO)).thenReturn(1D); when(entity.isAlive()).thenReturn(true);
            when(entity.carpetPeekEventContainer()).thenReturn(events); when(events.hasEvent(EntityEventsGroup.Event.ON_DAMAGE)).thenReturn(true);
            when(events.onEventFuture(eq(EntityEventsGroup.Event.ON_DAMAGE), any(), any())).thenReturn(decision);
            when(calculator.shouldDamageEntity(any(),eq(entity))).thenReturn(true); when(calculator.getKnockbackMultiplier(entity)).thenReturn(1F);
            when(calculator.getEntityDamageAmount(any(),eq(entity),anyFloat())).thenReturn(5F);
            when(world.getEntities(nullable(Entity.class), any(AABB.class), any(java.util.function.Predicate.class))).thenReturn(List.of(entity));
            density.when(() -> ScarpetExplosionDensity.compute(any(),eq(entity))).thenReturn(CompletableFuture.completedFuture(1F));
            doAnswer(call -> {
                assertTrue(ScarpetDamageContinuations.defer(entity,world,source,5F,0F,20,true,cancelled -> {
                    order.add("actual damage"); return !cancelled;
                }));
                return false;
            }).when(entity).hurtServer(world,source,5F);
            doAnswer(call -> { order.add("push"); return null; }).when(entity).pushFromExplosion(any());
            doAnswer(call -> { order.add("onHit"); ScarpetNativeWork.record(lateNative); return null; }).when(entity).onExplosionHit(isNull());
            leases.when(() -> fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call -> {
                java.util.function.Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);
                return CompletableFuture.completedFuture(action.apply(null));
            });
            var knockback = mock(io.papermc.paper.event.entity.EntityKnockbackEvent.class);
            when(knockback.getKnockback()).thenReturn(new org.bukkit.util.Vector(1,0,0));
            craftEvents.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callEntityKnockbackEvent(
                nullable(org.bukkit.craftbukkit.entity.CraftLivingEntity.class), nullable(Entity.class), nullable(Entity.class),
                eq(io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION), anyDouble(), any(Vec3.class))).thenReturn(knockback);
            runtime=ScarpetRuntime.of(server);
            explosion=new ServerExplosion(world,null,source,calculator,Vec3.ZERO,4F,false,Explosion.BlockInteraction.KEEP);
        }
        @SuppressWarnings("unchecked") CompletableFuture<Integer> phases() throws Exception {
            Method run=ServerExplosion.class.getDeclaredMethod("carpetRunExplosionPhases",List.class); run.setAccessible(true);
            return (CompletableFuture<Integer>)run.invoke(explosion,new ArrayList<>(List.of(BlockPos.ZERO)));
        }
        void drainUntil(java.util.function.BooleanSupplier ready)throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!ready.getAsBoolean()) {Runnable next=ownerTasks.poll();if(next!=null)next.run();else Thread.sleep(1);if(System.nanoTime()>deadline)fail("Native explosion owner phase did not finish");}
            Runnable next;while((next=ownerTasks.poll())!=null)next.run();
        }
        @Override public void close() { ScarpetRuntime.beginShutdown(server,()->{}); leases.close(); density.close(); craftEvents.close(); bukkit.close(); servers.close(); ticks.close(); }
    }
    @Test void theActualTypedExplosionKeepsItsCountAfterAnOnHitGuestFailureAndWaitsItsNativeChild()throws Exception {
        boolean previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage;
        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage=true;
        try(Fixture f=new Fixture();var starts=mockStatic(ScarpetWorldExplosions.class,CALLS_REAL_METHODS)) {
            starts.when(()->ScarpetWorldExplosions.decision(f.explosion)).thenReturn(CompletableFuture.completedFuture(false));
            var guest=new AtomicReference<CompletableFuture<Void>>();var problem=new IllegalStateException("onHit script failed");
            doAnswer(call->{f.order.add("onHit");guest.set(f.runtime.<Void>submit(()->{throw problem;}));ScarpetNativeWork.record(f.lateNative);return null;}).when(f.entity).onExplosionHit(isNull());
            var actual=f.explosion.carpetExplodeAsync();f.decision.complete(null);
            assertSame(problem,assertThrows(ExecutionException.class,()->guest.get().get(3,TimeUnit.SECONDS)).getCause());
            assertEquals(List.of("actual damage","push","onHit"),f.order);assertFalse(actual.isDone());
            f.lateNative.complete(null);f.drainUntil(actual::isDone);assertEquals(0,actual.get(3,TimeUnit.SECONDS));
        } finally {fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage=previous;}
    }
    @Test void anActualNativeOnHitFailureStillStopsTheRemainingExplosionPhases()throws Exception {
        try(Fixture f=new Fixture()) {
            var problem=new IllegalStateException("true native onHit failure");
            doThrow(problem).when(f.entity).onExplosionHit(isNull());
            var actual=f.phases();f.decision.complete(null);
            assertSame(problem,assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS)).getCause());
            assertFalse(ScarpetNativeWork.onlyGuestFailure(problem));assertEquals(List.of("actual damage","push"),f.order);
        }
    }
    @Test void theActualCallbackObserverReturnsFalseOnlyForGuestFailureAndRetainsNativeErrors()throws Exception {
        try(Fixture f=new Fixture()) {
            var guest=new AtomicReference<CompletableFuture<Boolean>>();var problem=new IllegalStateException("real explosion callback failed");
            var actual=ScarpetWorldExplosions.observeDecision(null,()->{var result=f.runtime.<Boolean>submit(()->{throw problem;});guest.set(result);return result;});
            assertSame(problem,assertThrows(ExecutionException.class,()->guest.get().get(3,TimeUnit.SECONDS)).getCause());
            assertFalse(actual.get(3,TimeUnit.SECONDS));
            var nativeFailure=new IllegalStateException("native callback producer failed");
            var failed=ScarpetWorldExplosions.observeDecision(null,()->CompletableFuture.failedFuture(nativeFailure));
            assertSame(nativeFailure,assertThrows(ExecutionException.class,()->failed.get(3,TimeUnit.SECONDS)).getCause());
        }
    }
    @Test void actualExplosionOutcomeGuestFailureStillRunsFinalBlockCountAndCacheCleanup()throws Exception {
        boolean previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage;
        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage=true;
        try(Fixture f=new Fixture();var starts=mockStatic(ScarpetWorldExplosions.class,CALLS_REAL_METHODS)) {
            starts.when(()->ScarpetWorldExplosions.decision(f.explosion)).thenReturn(CompletableFuture.completedFuture(false));
            var handler=mock(carpet.script.CarpetEventServer.CallbackList.class);
            java.lang.reflect.Field calls=carpet.script.CarpetEventServer.CallbackList.class.getDeclaredField("callList");calls.setAccessible(true);calls.set(handler,List.of(mock(carpet.script.CarpetEventServer.Callback.class)));
            java.lang.reflect.Field hf=carpet.script.CarpetEventServer.Event.class.getField("handler");hf.setAccessible(true);Object original=hf.get(carpet.script.CarpetEventServer.Event.EXPLOSION_OUTCOME);
            java.lang.reflect.Field capture=ScarpetRuntime.class.getDeclaredField("EVENT_CAPTURE");capture.setAccessible(true);
            var guest=new AtomicReference<CompletableFuture<Boolean>>();var problem=new IllegalStateException("actual outcome script failed");
            doAnswer(call->{
                f.order.add("outcome");var slot=(ThreadLocal<CompletableFuture<Boolean>>)capture.get(null);var accepted=slot.get();slot.remove();
                var work=f.runtime.<Boolean>submit(()->{throw problem;});guest.set(work);
                work.whenComplete((value,failure)->{if(failure==null)accepted.complete(value);else accepted.completeExceptionally(failure);});return false;
            }).when(handler).call(any(),any());hf.set(carpet.script.CarpetEventServer.Event.EXPLOSION_OUTCOME,handler);
            try {
                var actual=f.explosion.carpetExplodeAsync();f.decision.complete(null);assertFalse(actual.isDone());f.lateNative.complete(null);
                assertSame(problem,assertThrows(ExecutionException.class,()->guest.get().get(3,TimeUnit.SECONDS)).getCause());
                f.drainUntil(actual::isDone);assertEquals(0,actual.get(3,TimeUnit.SECONDS));assertEquals(List.of("actual damage","push","onHit","outcome"),f.order);
                java.lang.reflect.Field cache=ServerExplosion.class.getDeclaredField("blockCache");cache.setAccessible(true);assertNull(cache.get(f.explosion));
            } finally {hf.set(carpet.script.CarpetEventServer.Event.EXPLOSION_OUTCOME,original);}
        } finally {fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage=previous;}
    }
    @Test void damageAndDynamicOnHitChildrenFinishBeforeTheBlockPhaseReturns() throws Exception {
        try(Fixture f=new Fixture()) {
            CompletableFuture<Integer> actual=f.phases();
            assertFalse(actual.isDone()); assertTrue(f.order.isEmpty());
            f.decision.complete(null);
            assertEquals(List.of("actual damage","push","onHit"),f.order);
            assertFalse(actual.isDone(),"Native onHit child must finish before outcome/blocks/fire");
            f.lateNative.complete(null);
            assertEquals(1,actual.get(3,TimeUnit.SECONDS));
            assertNull(ScarpetDamageContinuations.pendingResult(f.entity));
        }
    }
    @Test void theActualCancelledDamageSkipsKnockbackAndOnHit() throws Exception {
        try(Fixture f=new Fixture()) {
            doAnswer(call -> {
                assertTrue(ScarpetDamageContinuations.defer(f.entity,f.world,f.source,5F,0F,20,true,cancelled -> {
                    f.entity.lastDamageCancelled=true; f.order.add("actual cancelled"); return false;
                })); return false;
            }).when(f.entity).hurtServer(f.world,f.source,5F);
            CompletableFuture<Integer> actual=f.phases(); assertFalse(actual.isDone());
            f.decision.complete(null); assertEquals(1,actual.get(3,TimeUnit.SECONDS));
            assertEquals(List.of("actual cancelled"),f.order);
            verify(f.entity,never()).pushFromExplosion(any()); verify(f.entity,never()).onExplosionHit(any());
        }
    }
    @Test void theActualStartDecisionCancelsBeforeRayDamageAndGameEvent() throws Exception {
        try(Fixture f=new Fixture();var starts=mockStatic(ScarpetWorldExplosions.class)) {
            CompletableFuture<Boolean> decision=new CompletableFuture<>();
            starts.when(()->ScarpetWorldExplosions.decision(f.explosion)).thenReturn(decision);
            CompletableFuture<Integer> actual=f.explosion.carpetExplodeAsync();
            assertSame(actual,f.explosion.carpetExplodeAsync());assertFalse(actual.isDone());assertFalse(f.explosion.wasCanceled);
            decision.complete(true);
            assertEquals(0,actual.get(3,TimeUnit.SECONDS));assertTrue(f.explosion.wasCanceled);assertTrue(f.order.isEmpty());
            verify(f.entity,never()).hurtServer(any(),any(),anyFloat());
            verify(f.world,never()).gameEvent(nullable(Entity.class),any(net.minecraft.core.Holder.class),any(Vec3.class));
        }
    }
    @Test void directProjectileDamageReceivesTheRealTypedResult() throws Exception {
        try(Fixture f=new Fixture()) {
            CompletableFuture<Boolean> actual=ScarpetExplosionActors.hurt(f.entity,f.world,f.source,5F);
            assertFalse(actual.isDone());assertTrue(f.order.isEmpty());f.decision.complete(null);
            assertTrue(actual.get(3,TimeUnit.SECONDS));assertEquals(List.of("actual damage"),f.order);
        }
    }
    @Test void cancellingTheCallerViewDoesNotReleaseTheRegisteredActualExplosion() throws Exception {
        try(Fixture f=new Fixture();var starts=mockStatic(ScarpetWorldExplosions.class)) {
            CompletableFuture<Boolean> decision=new CompletableFuture<>();
            starts.when(()->ScarpetWorldExplosions.decision(f.explosion)).thenReturn(decision);
            CompletableFuture<Integer> caller=f.explosion.carpetExplodeAsync();
            assertTrue(caller.cancel(false));CompletableFuture<Void> nativeIdle=ScarpetNativeWork.whenIdle(f.server);
            assertFalse(nativeIdle.isDone());decision.complete(true);nativeIdle.get(3,TimeUnit.SECONDS);
            assertTrue(f.explosion.wasCanceled);assertTrue(caller.isCancelled());
        }
    }
    @Test void aFactoryStartDecisionIsMemoizedAndCancellationNeverAcquiresTheCoreFootprint()throws Exception {
        try(Fixture f=new Fixture();var starts=mockStatic(ScarpetWorldExplosions.class)) {
            CompletableFuture<Boolean> decision=new CompletableFuture<>();starts.when(()->ScarpetWorldExplosions.decision(f.explosion)).thenReturn(decision);
            var first=f.explosion.carpetStartDecisionAsync();var repeated=f.explosion.carpetStartDecisionAsync();assertFalse(first.isDone());
            decision.complete(true);assertTrue(first.get());assertTrue(repeated.get());assertEquals(0,f.explosion.carpetExplodeAsync().get());
            starts.verify(()->ScarpetWorldExplosions.decision(f.explosion),times(1));
            f.leases.verify(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(f.world),anyInt(),anyInt(),anyInt(),anyInt(),any()),never());
        }
    }
}
