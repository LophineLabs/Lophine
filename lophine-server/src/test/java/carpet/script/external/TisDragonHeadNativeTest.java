package carpet.script.external;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.*;
import net.minecraft.world.entity.boss.enderdragon.phases.*;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.damagesource.DamageSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class TisDragonHeadNativeTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private boolean previous;
    private final MinecraftServer server=mock(MinecraftServer.class);
    private final ServerLevel world=mock(ServerLevel.class);
    private final Creeper creeper=mock(Creeper.class,CALLS_REAL_METHODS);
    private final DamageSource source=mock(DamageSource.class);
    private final DragonPhaseInstance phase=mock(DragonPhaseInstance.class);
    private final EnderDragonPhaseManager manager=mock(EnderDragonPhaseManager.class);
    private final AtomicReference<EnderDragonPhase<?>> current=new AtomicReference<>(EnderDragonPhase.HOVERING);
    private final List<String> order=new ArrayList<>();
    private CompletableFuture<Void> damageChild,reserveChild;
    private final EnderDragon dragon=mock(EnderDragon.class,call->{
        if(call.getMethod().getName().equals("reallyHurt")){
            assertSame(world,call.getArgument(0));assertSame(source,call.getArgument(1));order.add("damage");
            if(damageChild!=null)ScarpetNativeWork.record(damageChild);else current.set(EnderDragonPhase.DYING);return null;
        }
        if(Set.of("hurt","carpetHurtPrefix","carpetHeadReallyHurt","carpetHeadCandidate","carpetHeadAfterDamage").contains(call.getMethod().getName()))return CALLS_REAL_METHODS.answer(call);
        return RETURNS_DEFAULTS.answer(call);
    });
    @BeforeEach void setup()throws Exception{
        previous=GeneralCompatConfig.renewableDragonHead;GeneralCompatConfig.renewableDragonHead=true;when(world.getServer()).thenReturn(server);
        when(dragon.level()).thenReturn(world);when(dragon.blockPosition()).thenReturn(BlockPos.ZERO);when(dragon.getHealth()).thenReturn(100F);
        doReturn(world).when(creeper).level();doReturn(new BlockPos(64,0,0)).when(creeper).blockPosition();
        Field f=EnderDragon.class.getDeclaredField("phaseManager");f.setAccessible(true);f.set(dragon,manager);
        when(manager.getCurrentPhase()).thenReturn(phase);when(phase.getPhase()).thenAnswer(call->current.get());when(phase.onHurt(any(),anyFloat())).thenAnswer(call->call.getArgument(1));
        when(phase.isSitting()).thenAnswer(call->{order.add("sitting");return false;});when(source.getEntity()).thenReturn(creeper);when(source.is(DamageTypeTags.ALWAYS_HURTS_ENDER_DRAGONS)).thenReturn(true);
        doAnswer(call->{order.add("reserve");boolean value=(boolean)call.callRealMethod();if(reserveChild!=null)ScarpetNativeWork.record(reserveChild);return value;}).when(creeper).carpetReserveDragonHeadDrop();
    }
    @AfterEach void restore(){GeneralCompatConfig.renewableDragonHead=previous;}
    private org.mockito.MockedStatic<TickThread> owner(){var ticks=mockStatic(TickThread.class);ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);return ticks;}
    private boolean flagged()throws Exception{Field f=EnderDragon.class.getDeclaredField("carpetDropDragonHead");f.setAccessible(true);return f.getBoolean(dragon);}
    private CompletableFuture<Void> hit(){return ScarpetNativeWork.observeNative(dragon,()->{dragon.hurt(world,mock(EnderDragonPart.class),source,4F);return null;});}
    @Test void realHurtWaitsActualDamageBeforeReadingDyingStageAndHeadCandidate()throws Exception{
        damageChild=new CompletableFuture<>();try(var ticks=owner()){
            var raw=hit();assertEquals(List.of("damage"),order);assertFalse(flagged());assertFalse(raw.isDone());current.set(EnderDragonPhase.DYING);damageChild.complete(null);raw.join();assertTrue(flagged());assertEquals(List.of("damage","reserve","sitting"),order);
        }
    }
    @Test void actualCreeperReserveChildrenFinishBeforeFlagAndOriginalSittingTail()throws Exception{
        reserveChild=new CompletableFuture<>();try(var ticks=owner()){
            var raw=hit();assertEquals(List.of("damage","reserve"),order);assertFalse(flagged());reserveChild.complete(null);raw.join();assertTrue(flagged());assertEquals("sitting",order.getLast());
        }
    }
    @Test void originalAlreadyReservedCreeperDoesNotProduceAnotherDragonHead()throws Exception{
        assertTrue(creeper.carpetReserveDragonHeadDrop());order.clear();try(var ticks=owner()){hit().join();assertFalse(flagged());assertEquals(List.of("damage","reserve","sitting"),order);}
    }
    @Test void guestOnlyReserveFailureKeepsRawParentAndOriginalTrueNativeReservation()throws Exception{
        reserveChild=new CompletableFuture<>();try(var ticks=owner()){
            var raw=hit();Throwable failure=new IllegalStateException("guest");Method mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);reserveChild.completeExceptionally(failure);
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,raw::join)));assertTrue(flagged());assertEquals("sitting",order.getLast());
        }
    }
    @Test void nativeDamageFailureBlocksCreeperAndNativeReserveFailureBlocksDragonTail()throws Exception{
        damageChild=new CompletableFuture<>();try(var ticks=owner()){
            var raw=hit();damageChild.completeExceptionally(new IllegalStateException("damage native"));assertThrows(CompletionException.class,raw::join);assertFalse(flagged());verify(creeper,never()).carpetReserveDragonHeadDrop();
        }
        damageChild=null;reserveChild=new CompletableFuture<>();order.clear();current.set(EnderDragonPhase.HOVERING);try(var ticks=owner()){
            var raw=hit();reserveChild.completeExceptionally(new IllegalStateException("reserve native"));assertThrows(CompletionException.class,raw::join);assertFalse(flagged());assertEquals(List.of("damage","reserve"),order);
        }
    }
    @Test void realForeignCreeperAndDragonSchedulersRunTheirOriginalMutationsOnTheirOwners()throws Exception{
        var queue=new ArrayDeque<Runnable>();var running=new AtomicReference<Entity>(dragon);
        for(Entity actor:List.of(dragon,creeper)){
            org.bukkit.craftbukkit.entity.CraftEntity craft=actor==dragon?mock(org.bukkit.craftbukkit.entity.CraftEnderDragon.class):mock(org.bukkit.craftbukkit.entity.CraftCreeper.class);
            doReturn(craft).when(actor).getBukkitEntity();var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);Field f=org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");f.setAccessible(true);f.set(craft,scheduler);
            when(scheduler.schedule(any(),any(),eq(1L))).thenAnswer(call->{Consumer<Entity> task=call.getArgument(0);queue.add(()->{running.set(actor);task.accept(actor);running.set(null);});return true;});
        }
        doAnswer(call->{assertSame(creeper,running.get());order.add("reserve");return call.callRealMethod();}).when(creeper).carpetReserveDragonHeadDrop();
        try(var ticks=mockStatic(TickThread.class)){
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call->running.get()==call.getArgument(0));
            var raw=hit();running.set(null);assertFalse(flagged());assertEquals(List.of("damage"),order);while(!queue.isEmpty())queue.remove().run();raw.join();assertTrue(flagged());assertEquals(List.of("damage","reserve","sitting"),order);
        }
    }
    @Test void completePrefixWaitsChildrenAndOuterTrueOverridesInnerLivingFalseOutcome()throws Exception{
        var prefix=new CompletableFuture<Void>();damageChild=new CompletableFuture<>();var outer=new AtomicReference<CompletableFuture<Boolean>>();
        when(phase.onHurt(any(),anyFloat())).thenAnswer(call->{ScarpetNativeWork.record(prefix);return call.getArgument(1);});
        try(var ticks=owner()) {
            var raw=ScarpetNativeWork.observeNative(dragon,()->{
                assertFalse(dragon.hurt(world,mock(EnderDragonPart.class),source,4F));outer.set(ScarpetDamageContinuations.pendingResult(dragon));return null;
            });
            assertTrue(order.isEmpty());assertFalse(outer.get().isDone());prefix.complete(null);assertEquals(List.of("damage"),order);
            var inner=new CompletableFuture<Boolean>();ScarpetDamageContinuations.publishBodyResult(dragon,inner);assertSame(outer.get(),ScarpetDamageContinuations.pendingResult(dragon));inner.complete(false);
            current.set(EnderDragonPhase.DYING);damageChild.complete(null);assertTrue(outer.get().join());raw.join();assertTrue(flagged());
        }
    }
    @Test void sourceInvalidPrefixFalseAndValidUnqualifiedTrueNeverInvokeDamage()throws Exception{
        try(var ticks=owner()) {
            current.set(EnderDragonPhase.DYING);assertFalse(dragon.hurt(world,mock(EnderDragonPart.class),source,4F));verify(phase,never()).onHurt(any(),anyFloat());
            current.set(EnderDragonPhase.HOVERING);when(phase.onHurt(any(),anyFloat())).thenReturn(0F);assertFalse(dragon.hurt(world,mock(EnderDragonPart.class),source,4F));assertTrue(order.isEmpty());
            when(phase.onHurt(any(),anyFloat())).thenReturn(4F);when(source.is(DamageTypeTags.ALWAYS_HURTS_ENDER_DRAGONS)).thenReturn(false);assertTrue(dragon.hurt(world,mock(EnderDragonPart.class),source,4F));assertTrue(order.isEmpty());
        }
    }
    @Test void sourceRuleIsReadAfterActualDamageEvenWhenInitiallyDisabled()throws Exception{
        GeneralCompatConfig.renewableDragonHead=false;damageChild=new CompletableFuture<>();try(var ticks=owner()) {
            var raw=hit();assertEquals(List.of("damage"),order);GeneralCompatConfig.renewableDragonHead=true;current.set(EnderDragonPhase.DYING);damageChild.complete(null);raw.join();assertTrue(flagged());assertEquals(List.of("damage","reserve","sitting"),order);
        }
    }
}

