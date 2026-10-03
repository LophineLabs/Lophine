package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetExplosionActors;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.util.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production restore bodies, observer and lease lifecycle; physical actor/chunk envelopes are controlled. */
public class CarpetPlayerSpawnContinuationsTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel world=mock(ServerLevel.class);
        final ServerPlayer player=mock(ServerPlayer.class);
        final Deque<Runnable> actors=new ArrayDeque<>();
        final List<String> order=new ArrayList<>();
        final Map<String,Entity> restored=new HashMap<>();
        final Map<Entity,String> names=new IdentityHashMap<>();
        final List<CarpetRegionLeaseLifecycle> held=new ArrayList<>();
        final AtomicInteger released=new AtomicInteger();
        final AtomicBoolean riding=new AtomicBoolean();
        final MockedStatic<ScarpetExplosionActors> actor=mockStatic(ScarpetExplosionActors.class);
        final MockedStatic<CarpetRegionLease> lease=mockStatic(CarpetRegionLease.class);
        final MockedStatic<EntityType> types=mockStatic(EntityType.class);
        final MockedStatic<TisLifetimeTracker> lifetime=mockStatic(TisLifetimeTracker.class);
        final MockedStatic<ca.spottedleaf.moonrise.common.util.TickThread> ownership=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);
        final MockedStatic<ServerPlayer> pearlTickets=mockStatic(ServerPlayer.class,CALLS_REAL_METHODS);
        CompletableFuture<Void> addTail;
        CompletableFuture<Void> ticketTail;
        CompletableFuture<Void> constructorTail;
        CompletableFuture<Void> mountTail;
        CompletableFuture<Void> removeTail;
        List<CompletableFuture<Void>> addChildren=List.of();
        boolean rejectQueue;
        boolean addThrows;
        final Map<Entity,Boolean> addResults=new IdentityHashMap<>();
        Fixture() {
            when(world.getServer()).thenReturn(server); when(world.dimension()).thenReturn(Level.OVERWORLD);
            when(server.getLevel(any())).thenReturn(world);
            when(player.carpetSpawnServer()).thenReturn(server); when(player.level()).thenReturn(world);
            when(player.position()).thenReturn(new Vec3(100,70,100)); when(player.blockPosition()).thenReturn(new BlockPos(100,70,100));
            when(player.isPassenger()).thenAnswer(invocation->riding.get());
            ownership.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            when(player.startRiding(any(),eq(true),eq(false))).thenAnswer(invocation->{order.add("player-mount");riding.set(true);if(mountTail!=null)ScarpetNativeWork.record(mountTail);return true;});
            actor.when(()->ScarpetExplosionActors.entity(any(),any())).thenAnswer(invocation->queue(invocation.getArgument(1)));
            actor.when(()->ScarpetExplosionActors.world(any(),any(),any())).thenAnswer(invocation->queue(invocation.getArgument(2)));
            lease.when(()->CarpetRegionLease.runValue(any(),anyInt(),anyInt(),anyInt(),anyInt(),any(Function.class))).thenAnswer(this::full);
            lease.when(()->CarpetRegionLease.runLoadedValue(any(),anyInt(),anyInt(),anyInt(),anyInt(),any(Function.class))).thenAnswer(this::full);
            types.when(()->EntityType.loadEntityRecursive(any(ValueInput.class),eq(world),eq(EntitySpawnReason.LOAD),eq(EntityProcessor.NOP))).thenAnswer(invocation->{
                ValueInput input=invocation.getArgument(0);String name=input.getStringOr("test_name", "empty");order.add("load:"+name);
                if(constructorTail!=null){var tail=constructorTail;constructorTail=null;ScarpetNativeWork.record(tail);}
                return restored.get(name);
            });
            when(world.addFreshEntity(any(),eq(org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT))).thenAnswer(invocation->{
                Entity entity=invocation.getArgument(0); assertTrue(CarpetPlayerSpawnContinuations.pending(entity));order.add("add:"+names.get(entity));
                if(addThrows)throw new IllegalStateException("real native add failure");
                addChildren.forEach(ScarpetNativeWork::record);addChildren=List.of();
                if(addTail!=null){var tail=addTail;addTail=null;ScarpetNativeWork.record(tail);}return addResults.getOrDefault(entity,true);
            });
            when(world.addWithUUID(any(),eq(org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.MOUNT))).thenAnswer(invocation->{
                Entity entity=invocation.getArgument(0);assertTrue(CarpetPlayerSpawnContinuations.pending(entity));order.add("mount-add:"+names.get(entity));
                if(addTail!=null){var tail=addTail;addTail=null;ScarpetNativeWork.record(tail);}return addResults.getOrDefault(entity,true);
            });
            when(world.carpetAddFreshEntityNativeAsync(any(),eq(org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT))).thenAnswer(invocation->{
                Entity entity=invocation.getArgument(0);
                return OrgBlockDropRouting.addNative(world,entity,()->world.addFreshEntity(entity,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT));
            });
            when(world.carpetAddWithUUIDNativeAsync(any(),eq(org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.MOUNT))).thenAnswer(invocation->{
                Entity entity=invocation.getArgument(0);
                return OrgBlockDropRouting.addNative(world,entity,()->world.addWithUUID(entity,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.MOUNT));
            });
            pearlTickets.when(()->ServerPlayer.placeEnderPearlTicket(eq(world),any())).thenAnswer(invocation->{order.add("ticket");if(ticketTail!=null){var tail=ticketTail;ticketTail=null;ScarpetNativeWork.record(tail);}return 40L;});
        }
        Object full(org.mockito.invocation.InvocationOnMock invocation) {
            var lifecycle=new CarpetRegionLeaseLifecycle(released::incrementAndGet); held.add(lifecycle);
            var action=ScarpetRuntime.captureNativeContinuation(()->((Function<?,?>)invocation.getArgument(5)).apply(null));
            CompletableFuture<Object> result=new CompletableFuture<>();
            result.whenComplete((v,f)->lifecycle.close());
            actors.add(()->{
                assertTrue(lifecycle.beginActor());
                try { Object value=action.get(); lifecycle.follow(value);result.complete(value); }
                catch(Throwable failure){result.completeExceptionally(failure);}
                finally{lifecycle.actorFinished();lifecycle.acquired();}
            }); return result;
        }
        CompletableFuture<Object> queue(Supplier<?> supplier){
            CompletableFuture<Object> done=new CompletableFuture<>();ScarpetNativeWork.record(done);
            Supplier<?> captured=ScarpetRuntime.captureNativeContinuation(supplier);
            if(rejectQueue){done.completeExceptionally(new RejectedExecutionException("real owner queue rejected"));return done;}
            actors.add(()->{try{done.complete(captured.get());}catch(Throwable failure){done.completeExceptionally(failure);}});return done;
        }
        Entity entity(String name){
            Entity entity=mock(Entity.class);restored.put(name,entity);names.put(entity,name);
            when(entity.level()).thenReturn(world);when(entity.blockPosition()).thenReturn(new BlockPos(100,70,100));
            when(entity.chunkPosition()).thenReturn(new ChunkPos(6,6));when(entity.position()).thenReturn(new Vec3(100,70,100));
            when(entity.getUUID()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            when(entity.getSelfAndPassengers()).thenAnswer(invocation->Stream.of(entity));
            when(entity.getIndirectPassengers()).thenReturn(List.of());
            when(entity.startRiding(any(),eq(true),eq(false))).thenAnswer(invocation->{order.add("mount:"+name);if(mountTail!=null){var tail=mountTail;mountTail=null;ScarpetNativeWork.record(tail);}return true;});
            doAnswer(invocation->{order.add("remove:"+name);if(removeTail!=null){var tail=removeTail;removeTail=null;ScarpetNativeWork.record(tail);}return null;}).when(entity).discard(null);
            return entity;
        }
        CompoundTag tag(String name){CompoundTag tag=new CompoundTag();tag.putString("test_name",name);tag.putString("id","minecraft:ender_pearl");tag.putString("ender_pearl_dimension","minecraft:overworld");tag.store("Pos",Vec3.CODEC,new Vec3(100,70,100));return tag;}
        CompoundTag input(String... names){CompoundTag tag=new CompoundTag();ListTag pearls=new ListTag();for(String name:names)pearls.add(tag(name));tag.put("ender_pearls",pearls);return tag;}
        ValueInput input(CompoundTag tag){return TagValueInput.create(ProblemReporter.DISCARDING,RegistryAccess.EMPTY,tag);}
        void pump(){int count=0;while(!actors.isEmpty()){if(++count>200)throw new AssertionError("actor cycle");actors.remove().run();}}
        @Override public void close(){pearlTickets.close();ownership.close();lifetime.close();types.close();lease.close();actor.close();}
    }

    @Test void actualSequentialPearlsWaitAddAndTicketChildrenBeforeNextPearlAndParent(){try(var f=new Fixture()){
        var first=f.entity("first");f.entity("second");var vehicle=f.entity("vehicle");
        CompoundTag input=f.input("first","second");CompoundTag root=new CompoundTag();root.put("Entity",f.tag("vehicle"));root.store("Attach",UUIDUtil.CODEC,vehicle.getUUID());input.put("RootVehicle",root);
        var add=new CompletableFuture<Void>();var ticket=new CompletableFuture<Void>();f.addTail=add;f.ticketTail=ticket;
        var actual=CarpetPlayerSpawnContinuations.extras(f.player,f.input(input));assertFalse(ScarpetNativeWork.whenIdle(f.server).isDone());f.pump();
        assertEquals(List.of("load:first","add:first"),f.order);assertFalse(actual.isDone());assertEquals(0,f.released.get());assertTrue(CarpetPlayerSpawnContinuations.pending(first));
        add.complete(null);f.pump();assertEquals(List.of("load:first","add:first","ticket"),f.order);assertFalse(actual.isDone());assertEquals(0,f.released.get());
        ticket.complete(null);f.pump();actual.join();assertEquals(List.of("load:first","add:first","ticket","load:second","add:second","ticket","load:vehicle","mount-add:vehicle","player-mount"),f.order);
        assertEquals(f.held.size(),f.released.get());assertFalse(CarpetPlayerSpawnContinuations.pending(first));assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
    }}
    @Test void constructorDynamicChildReallyFinishesBeforeAdd(){try(var f=new Fixture()){
        f.entity("first");var tail=new CompletableFuture<Void>();f.constructorTail=tail;var actual=CarpetPlayerSpawnContinuations.pearls(f.player,f.input(f.input("first")));f.pump();assertEquals(List.of("load:first"),f.order);assertFalse(actual.isDone());tail.complete(null);f.pump();actual.join();assertEquals(List.of("load:first","add:first","ticket"),f.order);
    }}
    @Test void parentRootAddCompletesBeforeChildLoadAndAllMountChildrenBeforePlayerAttach(){try(var f=new Fixture()){
        var root=f.entity("root");f.entity("child");CompoundTag rootTag=f.tag("root");ListTag passengers=new ListTag();passengers.add(f.tag("child"));rootTag.put("Passengers",passengers);
        CompoundTag wrapper=new CompoundTag();wrapper.put("Entity",rootTag);wrapper.store("Attach",UUIDUtil.CODEC,root.getUUID());CompoundTag input=new CompoundTag();input.put("RootVehicle",wrapper);
        var add=new CompletableFuture<Void>();var mount=new CompletableFuture<Void>();f.addTail=add;f.mountTail=mount;
        var actual=CarpetPlayerSpawnContinuations.parent(f.player,f.input(input));f.pump();assertEquals(List.of("load:root","mount-add:root"),f.order);assertFalse(actual.isDone());add.complete(null);f.pump();assertEquals(List.of("load:root","mount-add:root","load:child","mount-add:child","mount:child"),f.order);assertFalse(actual.isDone());mount.complete(null);f.pump();actual.join();assertEquals("player-mount",f.order.getLast());assertEquals(f.held.size(),f.released.get());
    }}
    @Test void failedAttachmentWaitsEveryRealDiscardTail(){try(var f=new Fixture()){
        f.entity("root");CompoundTag wrapper=new CompoundTag();wrapper.put("Entity",f.tag("root"));CompoundTag input=new CompoundTag();input.put("RootVehicle",wrapper);
        var removal=new CompletableFuture<Void>();f.removeTail=removal;var actual=CarpetPlayerSpawnContinuations.parent(f.player,f.input(input));f.pump();assertEquals(List.of("load:root","mount-add:root","remove:root"),f.order);assertFalse(actual.isDone());assertTrue(CarpetPlayerSpawnContinuations.pending(f.restored.get("root")));removal.complete(null);f.pump();actual.join();assertFalse(CarpetPlayerSpawnContinuations.pending(f.restored.get("root")));
    }}
    @Test void actualNativeFailureStopsNextPearlAndPropagates(){try(var f=new Fixture()){
        var first=f.entity("first");f.entity("second");f.addThrows=true;var actual=CarpetPlayerSpawnContinuations.extras(f.player,f.input(f.input("first","second")));f.pump();assertThrows(CompletionException.class,actual::join);assertEquals(List.of("load:first","add:first"),f.order);assertFalse(CarpetPlayerSpawnContinuations.pending(first));assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
    }}
    @Test void ownerQueueRejectionCompletesActualRegisteredJobWithoutTickets(){try(var f=new Fixture()){
        f.rejectQueue=true;var actual=CarpetPlayerSpawnContinuations.extras(f.player,f.input(f.input("first")));assertThrows(CompletionException.class,actual::join);assertTrue(f.held.isEmpty());assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
    }}
    @Test void callerCancellationCannotReleaseAcceptedNativeBodyOrFullTickets(){try(var f=new Fixture()){
        var first=f.entity("first");var tail=new CompletableFuture<Void>();f.addTail=tail;var view=CarpetPlayerSpawnContinuations.pearls(f.player,f.input(f.input("first")));f.pump();assertTrue(view.cancel(false));assertFalse(ScarpetNativeWork.whenIdle(f.server).isDone());assertTrue(CarpetPlayerSpawnContinuations.pending(first));assertEquals(0,f.released.get());tail.complete(null);f.pump();assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());assertFalse(CarpetPlayerSpawnContinuations.pending(first));assertEquals(f.held.size(),f.released.get());
    }}
    @Test void completeInputIsCopiedBeforeOwnerQueueAndCannotChangeDuringSuspension(){try(var f=new Fixture()){
        f.entity("old");var tag=f.input("old");var actual=CarpetPlayerSpawnContinuations.pearls(f.player,f.input(tag));tag.put("ender_pearls",new ListTag());f.pump();actual.join();assertEquals(List.of("load:old","add:old","ticket"),f.order);
    }}
    @Test void missingDimensionRetainsOriginalSkipSemanticsWithoutCreatingTicket(){try(var f=new Fixture()){
        when(f.server.getLevel(any())).thenReturn(null);var actual=CarpetPlayerSpawnContinuations.pearls(f.player,f.input(f.input("missing")));f.pump();actual.join();assertTrue(f.order.isEmpty());assertTrue(f.held.isEmpty());
    }}
    @Test void nonTagValueInputCopiesItsCompleteMapThroughActualCodec(){try(var f=new Fixture()){
        f.entity("generic");ValueInput original=f.input(f.input("generic"));ValueInput generic=(ValueInput)java.lang.reflect.Proxy.newProxyInstance(ValueInput.class.getClassLoader(),new Class[]{ValueInput.class},(proxy,method,args)->method.invoke(original,args));
        var actual=CarpetPlayerSpawnContinuations.pearls(f.player,generic);f.pump();actual.join();assertEquals(List.of("load:generic","add:generic","ticket"),f.order);
    }}
    @Test void actualServerPlayerAsyncEntryDelegatesToCompleteNativeSequence(){try(var f=new Fixture()){
        f.entity("entry");when(f.player.loadAndSpawnEnderPearlsAsync(any())).thenCallRealMethod();var actual=f.player.loadAndSpawnEnderPearlsAsync(f.input(f.input("entry")));f.pump();actual.join();assertEquals(List.of("load:entry","add:entry","ticket"),f.order);
    }}
    static void guest(Throwable failure) throws Exception {var mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);}
    @Test void explicitGuestOnlyFailureStillRunsAcceptedTicketsNextPearlAndParentButReportsFailure() throws Exception {try(var f=new Fixture()){
        f.entity("first");f.entity("second");var vehicle=f.entity("vehicle");CompoundTag input=f.input("first","second");CompoundTag wrapper=new CompoundTag();wrapper.put("Entity",f.tag("vehicle"));wrapper.store("Attach",UUIDUtil.CODEC,vehicle.getUUID());input.put("RootVehicle",wrapper);
        var child=new CompletableFuture<Void>();f.addTail=child;var actual=CarpetPlayerSpawnContinuations.extras(f.player,f.input(input));f.pump();var failure=new IllegalStateException("guest-specific identity, not message matching");guest(failure);child.completeExceptionally(failure);f.pump();
        var result=assertThrows(CompletionException.class,actual::join);assertTrue(ScarpetNativeWork.onlyGuestFailure(result));assertEquals(List.of("load:first","add:first","ticket","load:second","add:second","ticket","load:vehicle","mount-add:vehicle","player-mount"),f.order);assertEquals(f.held.size(),f.released.get());
    }}
    @Test void failedGuestThenFailedNativeSiblingNeverAllowsAcceptedNextPhase() throws Exception {try(var f=new Fixture()){
        f.entity("first");f.entity("second");var guestChild=new CompletableFuture<Void>();var nativeChild=new CompletableFuture<Void>();f.addChildren=List.of(guestChild,nativeChild);var actual=CarpetPlayerSpawnContinuations.extras(f.player,f.input(f.input("first","second")));f.pump();
        var guestFailure=new IllegalStateException("guest failure");guest(guestFailure);guestChild.completeExceptionally(guestFailure);f.pump();assertFalse(actual.isDone());assertEquals(List.of("load:first","add:first"),f.order);
        nativeChild.completeExceptionally(new IllegalStateException("actual native child failure"));f.pump();var failure=assertThrows(CompletionException.class,actual::join);assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));assertEquals(List.of("load:first","add:first"),f.order);
    }}
    @Test void foreignWorldVehicleReturnsTrueNativeFailedMountWithoutForeignMutableAccess(){try(var f=new Fixture()){
        var vehicle=f.entity("vehicle");var foreign=mock(ServerLevel.class);when(vehicle.level()).thenReturn(foreign);CompoundTag input=new CompoundTag();CompoundTag wrapper=new CompoundTag();wrapper.put("Entity",f.tag("vehicle"));wrapper.store("Attach",UUIDUtil.CODEC,vehicle.getUUID());input.put("RootVehicle",wrapper);
        var actual=CarpetPlayerSpawnContinuations.parent(f.player,f.input(input));f.pump();actual.join();verify(f.player,never()).startRiding(any(),eq(true),eq(false));assertTrue(f.order.contains("remove:vehicle"));
    }}
    @Test void restoredPhysicsRemainPausedThroughRealParentNativeInitAndJoinTails(){try(var f=new Fixture()){
        var restored=f.entity("first");var suffix=new CompletableFuture<Void>();var extra=new AtomicReference<CompletableFuture<Void>>();
        var parent=ScarpetNativeWork.observeNative(f.player,()->{extra.set(CarpetPlayerSpawnContinuations.extras(f.player,f.input(f.input("first"))));ScarpetNativeWork.record(suffix);return null;});
        f.pump();assertTrue(extra.get().isDone());assertFalse(parent.isDone());assertTrue(CarpetPlayerSpawnContinuations.pending(restored));suffix.complete(null);parent.join();assertFalse(CarpetPlayerSpawnContinuations.pending(restored));
    }}
    @Test void actualNativePassengerAndNonPassengerTickBodiesHonorRestorationPause() throws Exception {try(var f=new Fixture()){
        var restored=f.entity("first");var tail=new CompletableFuture<Void>();f.addTail=tail;var extra=CarpetPlayerSpawnContinuations.extras(f.player,f.input(f.input("first")));f.pump();assertTrue(CarpetPlayerSpawnContinuations.pending(restored));
        var world=mock(ServerLevel.class,CALLS_REAL_METHODS);world.tickNonPassenger(restored);var method=ServerLevel.class.getDeclaredMethod("tickPassenger",Entity.class,Entity.class,boolean.class);method.setAccessible(true);method.invoke(world,mock(Entity.class),restored,true);verify(restored,never()).tick();verify(restored,never()).commonTick();tail.complete(null);f.pump();extra.join();
    }}
    @Test void anActualFalseParentAddWaitsItsChildrenThenSkipsPassengerLoadAndAttachment(){try(var f=new Fixture()){
        var root=f.entity("root");f.entity("child");f.addResults.put(root,false);
        CompoundTag rootTag=f.tag("root");ListTag passengers=new ListTag();passengers.add(f.tag("child"));rootTag.put("Passengers",passengers);
        CompoundTag wrapper=new CompoundTag();wrapper.put("Entity",rootTag);wrapper.store("Attach",UUIDUtil.CODEC,root.getUUID());CompoundTag input=new CompoundTag();input.put("RootVehicle",wrapper);
        var child=new CompletableFuture<Void>();f.addTail=child;var actual=CarpetPlayerSpawnContinuations.parent(f.player,f.input(input));f.pump();
        assertEquals(List.of("load:root","mount-add:root"),f.order);assertFalse(actual.isDone());assertTrue(CarpetPlayerSpawnContinuations.pending(root));
        child.complete(null);f.pump();actual.join();assertEquals(List.of("load:root","mount-add:root"),f.order);verify(f.player,never()).startRiding(any(),eq(true),eq(false));assertFalse(CarpetPlayerSpawnContinuations.pending(root));assertEquals(f.held.size(),f.released.get());
    }}

}
