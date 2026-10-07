package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.*;
import org.bukkit.craftbukkit.entity.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Native Entity endpoints and original ServerPlayer virtual removeVehicle tails on distinct actor fixtures. */
public class ScarpetNativeRelationshipsTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    private record Task(Entity owner,Consumer<Entity> callback){}
    private static int cooldown(Entity entity)throws Exception{Field f=Entity.class.getDeclaredField("boardingCooldown");f.setAccessible(true);return f.getInt(entity);}
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel world=mock(ServerLevel.class),foreignWorld=mock(ServerLevel.class);
        final Entity rider,vehicle;
        final Queue<Task> tasks=new ArrayDeque<>();final List<String> order=new ArrayList<>();
        final MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);
        final MockedStatic<org.bukkit.Bukkit> bukkit=mockStatic(org.bukkit.Bukkit.class);
        final MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        final ScarpetRuntime runtime;
        Entity owner;boolean shared;final Map<ServerLevel,Integer> footprints=new IdentityHashMap<>();
        Fixture(boolean player,boolean crossWorld) throws Exception {this(player,crossWorld,Entity.class);}
        Fixture(boolean player,boolean crossWorld,Class<? extends Entity> vehicleType) throws Exception {
            vehicle=mock(vehicleType,CALLS_REAL_METHODS);
            rider=player?mock(ServerPlayer.class,CALLS_REAL_METHODS):mock(Entity.class,CALLS_REAL_METHODS);
            servers.when(MinecraftServer::getServer).thenReturn(server);when(world.getServer()).thenReturn(server);when(foreignWorld.getServer()).thenReturn(server);
            CraftServer craft=mock(CraftServer.class);when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            for(Entity entity:List.of(rider,vehicle)) {
                doReturn(entity==vehicle&&crossWorld?foreignWorld:world).when(entity).level();doReturn(BlockPos.ZERO).when(entity).blockPosition();
                doReturn(Vec3.ZERO).when(entity).position();doReturn(false).when(entity).isRemoved();doReturn(entity==rider?7:9).when(entity).getId();
                entity.levelCallback=EntityInLevelCallback.NULL;entity.passengers=com.google.common.collect.ImmutableList.of();entity.valid=false;
                CraftEntity wrapper=entity instanceof ServerPlayer?mock(CraftPlayer.class):entity instanceof net.minecraft.world.entity.LivingEntity?mock(CraftLivingEntity.class):mock(CraftEntity.class);
                doReturn(wrapper).when(entity).getBukkitEntity();
                var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);Field field=CraftEntity.class.getField("taskScheduler");field.setAccessible(true);field.set(wrapper,scheduler);
                when(scheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{tasks.add(new Task(entity,call.getArgument(0)));return true;});
            }
            if(player)((ServerPlayer)rider).connection=mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            Field v=Entity.class.getDeclaredField("vehicle");v.setAccessible(true);v.set(rider,vehicle);vehicle.passengers=com.google.common.collect.ImmutableList.of(rider);
            Field cooldown=Entity.class.getDeclaredField("boardingCooldown");cooldown.setAccessible(true);cooldown.setInt(rider,17);
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call->{Entity entity=call.getArgument(0);return owner==entity||footprints.containsKey(entity.level());});
            leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(any(ServerLevel.class),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                ServerLevel level=call.getArgument(0);footprints.merge(level,1,Integer::sum);shared=true;
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);Entity oldOwner=owner;
                owner=level==world?rider:vehicle;Object value;
                try{value=action.apply(null);}finally{owner=oldOwner;}
                Runnable release=()->{int count=footprints.get(level)-1;if(count==0)footprints.remove(level);else footprints.put(level,count);shared=!footprints.isEmpty();};
                if(value instanceof CompletableFuture<?> future)return future.handle((ignored,failure)->{release.run();if(failure!=null)throw new CompletionException(failure);return value;});
                release.run();return CompletableFuture.completedFuture(value);
            });
            runtime=ScarpetRuntime.of(server);
        }
        CompletableFuture<Void> begin(){owner=rider;try{return ScarpetNativeWork.observeNative(rider,()->ScarpetNativeRelationships.stopRiding(rider,false)).thenCompose(value->value);}finally{owner=null;}}
        void drain(){Task task;while((task=tasks.poll())!=null){owner=task.owner();try{task.callback().accept(owner);}finally{owner=null;}}}
        @Override public void close(){ScarpetRuntime.beginShutdown(server,()->{});leases.close();bukkit.close();servers.close();ticks.close();}
    }
    private static void riderInputs(Fixture f)throws Exception {
        var rider=(net.minecraft.world.entity.LivingEntity)f.rider;
        doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));return .6F;}).when(rider).getBbWidth();
        doReturn(1.5F).when(rider).getBbHeight();
        doReturn(false).when(rider).isDescending();
        doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));return 0F;}).when(rider).getYRot();
        doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));return net.minecraft.world.entity.HumanoidArm.RIGHT;}).when(rider).getMainArm();
        doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));f.order.add("rider inputs");return com.google.common.collect.ImmutableList.of(net.minecraft.world.entity.Pose.CROUCHING);}).when(rider).getDismountPoses();
        doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));return net.minecraft.world.entity.EntityDimensions.scalable(.6F,1.5F);}).when(rider).getDimensions(any());
        doReturn(net.minecraft.world.item.ItemStack.EMPTY).when(rider).getMainHandItem();doReturn(net.minecraft.world.item.ItemStack.EMPTY).when(rider).getItemBySlot(any());
        doReturn(false).when(rider).is(any(net.minecraft.tags.TagKey.class));doReturn(net.minecraft.world.phys.shapes.Shapes.empty()).when(rider).getLiquidCollisionShape();
        doReturn(0D).when(rider).getX();doReturn(0D).when(rider).getY();doReturn(0D).when(rider).getZ();
        doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));assertEquals(net.minecraft.world.entity.Pose.CROUCHING,call.getArgument(0));f.order.add("rider pose");return null;}).when(rider).setPose(any());
        when(f.world.getBlockState(any())).thenReturn(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }
    @Test void theRealCrossWorldBoatVirtualUsesFreshOwnedRiderInputsAndPreservesPacketPoseAndPositionOrder()throws Exception {
        try(Fixture f=new Fixture(true,true,net.minecraft.world.entity.vehicle.boat.AbstractBoat.class)) {
            riderInputs(f);var rider=(ServerPlayer)f.rider;var boat=(net.minecraft.world.entity.vehicle.boat.AbstractBoat)f.vehicle;
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));return call.callRealMethod();}).when(rider).getYRot();
            doReturn(2F).when(boat).getBbWidth();doReturn(50D).when(boat).getX();doReturn(5D).when(boat).getY();doReturn(3D).when(boat).getZ();
            doReturn(new BlockPos(50,5,3)).when(boat).blockPosition();doReturn(new Vec3(50,5,3)).when(boat).position();
            doReturn(new net.minecraft.world.phys.AABB(49,5,2,51,6,4)).when(boat).getBoundingBox();
            when(f.foreignWorld.getBlockFloorHeight(any(BlockPos.class))).thenReturn(0D);
            var border=mock(net.minecraft.world.level.border.WorldBorder.class);when(border.isWithinBounds(any(net.minecraft.world.phys.AABB.class))).thenReturn(true);when(f.foreignWorld.getWorldBorder()).thenReturn(border);
            when(f.foreignWorld.getBlockCollisionsFromContext(any(),any())).thenAnswer(call->{
                assertTrue(TickThread.isTickThreadFor(boat));assertFalse(TickThread.isTickThreadFor(rider));
                var context=(net.minecraft.world.phys.shapes.CollisionContext)call.getArgument(0);assertInstanceOf(ScarpetDismountView.View.class,context);
                assertTrue(context.getCollisionShape(net.minecraft.world.level.block.Blocks.POWDER_SNOW.defaultBlockState(),f.foreignWorld,BlockPos.ZERO).isEmpty());return List.of();
            });
            doAnswer(call->{call.callRealMethod();boat.valid=true;return null;}).when(boat).carpetRemovePassengerPhysical(rider);
            doAnswer(call->{assertSame(rider,call.getArgument(0));assertTrue(TickThread.isTickThreadFor(boat));assertFalse(TickThread.isTickThreadFor(rider));f.order.add("original boat virtual");return call.callRealMethod();}).when(boat).getDismountLocationForPassenger(rider);
            var packetDone=new CompletableFuture<Void>();
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));f.order.add("passenger packet");ScarpetNativeWork.record(packetDone);return null;}).when(rider.connection).send(any(net.minecraft.network.protocol.game.ClientboundSetPassengersPacket.class));
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));assertTrue((double)call.getArgument(0)<49);assertEquals(6D,(double)call.getArgument(1));assertEquals(3D,(double)call.getArgument(2),1.0E-5);f.order.add("rider dismount");return null;}).when(rider).dismountTo(anyDouble(),anyDouble(),anyDouble());
            var actual=f.begin();f.drain();assertEquals(List.of("passenger packet"),f.order);assertEquals(0,cooldown(rider));assertFalse(actual.isDone());
            f.owner=rider;rider.setYRot(90);f.owner=null;
            packetDone.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);
            assertEquals(List.of("passenger packet","rider inputs","original boat virtual","rider pose","rider dismount"),f.order);
        }
    }
    @Test void crossWorldPortalFallbackReadsRiderWorldAtVehicleCoordinatesAndKeepsOriginalOwnXZ()throws Exception {
        try(Fixture f=new Fixture(true,true)) {
            riderInputs(f);var rider=(ServerPlayer)f.rider;
            doReturn(new BlockPos(50,5,3)).when(f.vehicle).blockPosition();doReturn(new Vec3(50,5,3)).when(f.vehicle).position();
            var portal=mock(net.minecraft.world.level.block.state.BlockState.class);when(portal.is(net.minecraft.tags.BlockTags.PORTALS)).thenReturn(true);
            when(f.world.getBlockState(new BlockPos(50,5,3))).thenReturn(portal);
            when(f.world.findFreePosition(eq(rider),any(),any(),anyDouble(),anyDouble(),anyDouble())).thenAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));f.order.add("rider world free position");return Optional.empty();});
            doAnswer(call->{call.callRealMethod();f.vehicle.valid=true;return null;}).when(f.vehicle).carpetRemovePassengerPhysical(rider);
            doAnswer(call->{assertEquals(0D,(double)call.getArgument(0));assertEquals(5D,(double)call.getArgument(1));assertEquals(0D,(double)call.getArgument(2));f.order.add("fallback dismount");return null;}).when(rider).dismountTo(anyDouble(),anyDouble(),anyDouble());
            var actual=f.begin();f.drain();actual.get(3,TimeUnit.SECONDS);assertEquals(List.of("rider world free position","fallback dismount"),f.order);
            verify(f.vehicle,never()).getDismountLocationForPassenger(any());verify(f.foreignWorld,never()).getBlockState(any());
        }
    }
    @Test void cancelledCrossWorldDismountStillRunsOriginalPlayerCooldownAndCurrentPassengerPacket()throws Exception {
        try(Fixture f=new Fixture(true,true)) {
            var rider=(ServerPlayer)f.rider;f.vehicle.valid=true;
            doReturn(false).when(rider).carpetDismountEvents(eq(f.vehicle),any(),eq(true),eq(false));
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));return 7;}).when(rider).getId();
            doAnswer(call->{var packet=(net.minecraft.network.protocol.game.ClientboundSetPassengersPacket)call.getArgument(0);assertEquals(9,packet.getVehicle());assertArrayEquals(new int[]{7},packet.getPassengers());f.order.add("cancelled passenger packet");return null;}).when(rider.connection).send(any(net.minecraft.network.protocol.game.ClientboundSetPassengersPacket.class));
            var actual=f.begin();f.drain();actual.get(3,TimeUnit.SECONDS);
            assertSame(f.vehicle,rider.getVehicle());assertEquals(0,cooldown(rider));assertEquals(List.of("cancelled passenger packet"),f.order);verify(rider,never()).dismountTo(anyDouble(),anyDouble(),anyDouble());
        }
    }
    @Test void actualCurrentVehicleEffectsFinishBeforeThePassengerPacketAndFreshRiderInputs()throws Exception {
        try(Fixture f=new Fixture(true,true,net.minecraft.world.entity.LivingEntity.class)) {
            var rider=(ServerPlayer)f.rider;var vehicle=(net.minecraft.world.entity.LivingEntity)f.vehicle;
            var effect=new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SPEED,100);
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(vehicle));f.order.add("current vehicle effects");return List.of(effect);}).when(vehicle).getActiveEffects();
            var effectDone=new CompletableFuture<Void>();
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));f.order.add("remove effect packet");ScarpetNativeWork.record(effectDone);return null;}).when(rider.connection).send(any(net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket.class));
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(rider));f.order.add("passenger packet");return null;}).when(rider.connection).send(any(net.minecraft.network.protocol.game.ClientboundSetPassengersPacket.class));
            var actual=f.begin();f.drain();assertEquals(List.of("current vehicle effects","remove effect packet"),f.order);assertFalse(actual.isDone());
            effectDone.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);assertEquals(List.of("current vehicle effects","remove effect packet","passenger packet"),f.order);
        }
    }
    @Test void sameWorldUsesTheOriginalPlayerCooldownAndRealPassengerPacketUntilItsNativeChildCompletes() throws Exception {
        try(Fixture f=new Fixture(true,false)) {
            var packetDone=new CompletableFuture<Void>();var player=(ServerPlayer)f.rider;
            doAnswer(call->{assertTrue(f.shared);f.order.add("passenger packet");ScarpetNativeWork.record(packetDone);return null;}).when(player.connection).send(any(net.minecraft.network.protocol.game.ClientboundSetPassengersPacket.class));
            var actual=f.begin();assertFalse(actual.isDone());f.drain();
            assertNull(player.getVehicle());assertTrue(f.vehicle.getPassengers().isEmpty());assertEquals(0,cooldown(player));
            assertEquals(List.of("passenger packet"),f.order);assertFalse(actual.isDone());assertTrue(f.shared);
            packetDone.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);assertFalse(f.shared);
        }
    }
    @Test void crossWorldNativeEntityMutatorsRunOnTheOriginalTwoOwnersAndUseTheFreshVehiclePosition() throws Exception {
        try(Fixture f=new Fixture(false,true)) {
            var completed=new CompletableFuture<Void>();
            doAnswer(call->{assertSame(f.rider,f.owner);f.order.add("rider detach");return call.callRealMethod();}).when(f.rider).carpetBeginVehicleRemoval();
            doAnswer(call->{assertSame(f.vehicle,f.owner);f.order.add("vehicle remove");call.callRealMethod();doReturn(new Vec3(50,2,3)).when(f.vehicle).position();return null;}).when(f.vehicle).carpetRemovePassengerPhysical(f.rider);
            doAnswer(call->{assertSame(f.rider,f.owner);assertEquals(new Vec3(50,2,3),call.getArgument(2));f.order.add("rider completion");call.callRealMethod();ScarpetNativeWork.record(completed);return null;}).when(f.rider).carpetCompleteVehicleRemoval(eq(f.vehicle),eq(true),any(Vec3.class));
            var actual=f.begin();f.drain();assertEquals(List.of("rider detach","vehicle remove","rider completion"),f.order);
            assertNull(f.rider.getVehicle());assertTrue(f.vehicle.getPassengers().isEmpty());assertEquals(60,cooldown(f.rider));assertFalse(actual.isDone());
            completed.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);
        }
    }
}
