package fun.bm.lophine.carpet;

import carpet.script.external.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.server.players.*;
import net.minecraft.server.network.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.material.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TisCommandActualCompletionTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
 private final MinecraftServer server=mock(MinecraftServer.class);
 private final ServerLevel world=mock(ServerLevel.class);
 private final ServerPlayer player=mock(ServerPlayer.class);
 private final CommandSourceStack source=mock(CommandSourceStack.class);
 private final CommandResultCallback callback=mock(CommandResultCallback.class);
 private io.netty.channel.embedded.EmbeddedChannel channel;
 @BeforeEach void setup() throws Exception {
  when(world.getServer()).thenReturn(server);when(player.level()).thenReturn(world);when(player.blockPosition()).thenReturn(BlockPos.ZERO);
  when(player.chunkPosition()).thenReturn(new ChunkPos(0,0));when(player.getUUID()).thenReturn(UUID.randomUUID());
  player.connection=mock(ServerGamePacketListenerImpl.class);
  var connection=mock(net.minecraft.network.Connection.class);channel=new io.netty.channel.embedded.EmbeddedChannel();connection.channel=channel;when(connection.isConnected()).thenReturn(true);
  Field network=ServerCommonPacketListenerImpl.class.getDeclaredField("connection");network.setAccessible(true);network.set(player.connection,connection);when(player.carpetSpawnServer()).thenReturn(server);
  when(source.getServer()).thenReturn(server);when(source.getLevel()).thenReturn(world);when(source.getEntity()).thenReturn(player);
  when(source.getPlayerOrException()).thenReturn(player);when(source.callback()).thenReturn(callback);
  when(source.getPosition()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
 }
 @AfterEach void closeChannel(){channel.finishAndReleaseAll();}
 private org.mockito.MockedStatic<TickThread> owner(){var ticks=mockStatic(TickThread.class);ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);ticks.when(()->TickThread.isTickThreadFor(eq(world),any(BlockPos.class))).thenReturn(true);ticks.when(TickThread::isTickThread).thenReturn(true);return ticks;}
 private static Object invoke(Class<?> type,String name,Class<?>[] signature,Object...args)throws Exception{Method m=type.getDeclaredMethod(name,signature);m.setAccessible(true);try{return m.invoke(null,args);}catch(InvocationTargetException failure){throw (Exception)failure.getCause();}}
 @Test void realCommandResultWaitsActionAndOwnerCallbackChildren() throws Exception {
  var action=new CompletableFuture<Integer>();var child=new CompletableFuture<Void>();
  doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(callback).onResult(true,7);
  try(var ticks=owner();var scope=CarpetAsyncCommandResults.open()){
   assertEquals(99,TisCommandContinuations.complete(source,99,()->action,null,()->{}));var actual=scope.resultFuture(source);
   verifyNoInteractions(callback);assertFalse(actual.isDone());action.complete(7);verify(callback).onResult(true,7);assertFalse(actual.isDone());
   child.complete(null);assertEquals(7,actual.join());
  }
 }
 @Test void cancelPublicViewCannotCancelActualCommandOrCallbackLifetime() throws Exception {
  var action=new CompletableFuture<Integer>();var child=new CompletableFuture<Void>();
  doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(callback).onResult(true,3);
  try(var ticks=owner();var scope=CarpetAsyncCommandResults.open()){
   TisCommandContinuations.complete(source,1,()->action,null,()->{});var view=scope.resultFuture(source);view.cancel(false);
   action.complete(3);assertFalse(scope.completionFuture().isDone());assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
   child.complete(null);scope.completionFuture().join();ScarpetNativeWork.whenIdle(server).join();verify(callback).onResult(true,3);
  }
 }
 @Test void entityJobDoesNotReturnBeforeItsActualNativeChildren() {
  var child=new CompletableFuture<Void>();try(var ticks=owner()){
   var actual=TisCommandContinuations.entity(source,player,()->TisCommandContinuations.phase(player,()->{ScarpetNativeWork.record(child);return 2;}));
   assertFalse(actual.isDone());assertFalse(ScarpetNativeWork.whenIdle(server).isDone());child.complete(null);assertEquals(2,actual.join());ScarpetNativeWork.whenIdle(server).join();
  }
 }
 @Test void guestOnlyFailureAllowsRealNextPhaseButFailedReceiptRemainsInParent() throws Exception {
  var guest=new CompletableFuture<Void>();var nativeChild=new CompletableFuture<Void>();var next=new AtomicBoolean();var ready=new AtomicReference<CompletableFuture<Integer>>();
  var original=ScarpetNativeWork.observeNative(null,()->{
   var prefix=TisCommandContinuations.phase(null,()->{ScarpetNativeWork.record(guest);ScarpetNativeWork.record(nativeChild);return 4;});
   ready.set(TisCommandContinuations.then(prefix,count->{next.set(true);return CompletableFuture.completedFuture(count+1);}));return null;
  });var failure=new IllegalStateException("real command guest");var mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);guest.completeExceptionally(failure);assertFalse(next.get());nativeChild.complete(null);assertEquals(5,ready.get().join());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,original::join)));
 }
 @Test void blockTickWaitsDynamicBlockTailBeforeFluidTick() throws Exception {
  var state=mock(BlockState.class);var fluid=mock(FluidState.class);when(world.getBlockState(BlockPos.ZERO)).thenReturn(state);when(world.getFluidState(BlockPos.ZERO)).thenReturn(fluid);
  var blockChild=new CompletableFuture<Void>();var fluidChild=new CompletableFuture<Void>();var order=new ArrayList<String>();
  doAnswer(call->{order.add("block");ScarpetNativeWork.record(blockChild);return null;}).when(state).tick(eq(world),eq(BlockPos.ZERO),any());
  doAnswer(call->{order.add("fluid");ScarpetNativeWork.record(fluidChild);return null;}).when(fluid).tick(world,BlockPos.ZERO,state);
  try(var ticks=owner()){
   var actual=ScarpetNativeWork.observeNative(null,()->{try{invoke(TisManipulateBlocks.class,"apply",new Class[]{ServerLevel.class,BlockPos.class,String.class,int.class,int.class},world,BlockPos.ZERO,"tile_tick",0,0);}catch(Exception failure){throw new RuntimeException(failure);}return null;});
   assertEquals(List.of("block"),order);blockChild.complete(null);assertEquals(List.of("block","fluid"),order);assertFalse(actual.isDone());fluidChild.complete(null);actual.join();
  }
 }
 @Test void shapeUpdateWaitsActualDirectTailBeforeIndirectUpdate() throws Exception {
  var state=mock(BlockState.class);when(world.getBlockState(BlockPos.ZERO)).thenReturn(state);var child=new CompletableFuture<Void>();
  doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(state).updateNeighbourShapes(world,BlockPos.ZERO,2,512);
  try(var ticks=owner()){
   var actual=ScarpetNativeWork.observeNative(null,()->{try{invoke(TisManipulateBlocks.class,"apply",new Class[]{ServerLevel.class,BlockPos.class,String.class,int.class,int.class},world,BlockPos.ZERO,"state_update",0,0);}catch(Exception failure){throw new RuntimeException(failure);}return null;});
   verify(state,never()).updateIndirectNeighbourShapes(any(),any(),anyInt(),anyInt());child.complete(null);actual.join();verify(state).updateIndirectNeighbourShapes(world,BlockPos.ZERO,2,512);
  }
 }
 @Test void inventoryRefreshWaitsActualPlayerInfoBeforeMessageAndResult() throws Exception {
  var list=mock(PlayerList.class);when(server.getPlayerList()).thenReturn(list);var child=new CompletableFuture<Void>();
  doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(list).sendAllPlayerInfo(player);
  try(var ticks=owner();var scope=CarpetAsyncCommandResults.open()){
   invoke(TisRefreshCommand.class,"refreshInventories",new Class[]{CommandSourceStack.class,Collection.class},source,List.of(player));
   verify(player,never()).sendSystemMessage(any());verifyNoInteractions(callback);child.complete(null);scope.completionFuture().join();verify(player).sendSystemMessage(any());verify(callback).onResult(true,1);
  }
 }
 @Test void completionReportWaitsActualNetworkReceipt() throws Exception {
  var receipt=new AtomicReference<io.netty.channel.ChannelFutureListener>();
  doAnswer(call->{receipt.set(call.getArgument(1));return null;}).when(player.connection).send(any(net.minecraft.network.protocol.Packet.class),any(io.netty.channel.ChannelFutureListener.class));
  try(var ticks=owner()){
   @SuppressWarnings("unchecked") var actual=(CompletableFuture<Integer>)invoke(TisRefreshCommand.class,"report",new Class[]{ServerPlayer.class,int.class},player,6);
   assertFalse(actual.isDone());var network=mock(io.netty.channel.ChannelFuture.class);when(network.isSuccess()).thenReturn(true);receipt.get().operationComplete(network);assertEquals(6,actual.join());
  }
 }
 @Test void networkReportFailureIsTrueNativeFailure() throws Exception {
  var receipt=new AtomicReference<io.netty.channel.ChannelFutureListener>();
  doAnswer(call->{receipt.set(call.getArgument(1));return null;}).when(player.connection).send(any(net.minecraft.network.protocol.Packet.class),any(io.netty.channel.ChannelFutureListener.class));
  try(var ticks=owner()){
   @SuppressWarnings("unchecked") var actual=(CompletableFuture<Integer>)invoke(TisRefreshCommand.class,"report",new Class[]{ServerPlayer.class,int.class},player,6);
   var network=mock(io.netty.channel.ChannelFuture.class);when(network.cause()).thenReturn(new IllegalStateException("real network failure"));receipt.get().operationComplete(network);assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,actual::join)));
  }
 }
 @Test void closingAConnectionThatDropsItsWriteCallbackCompletesTheActualRefreshReport() throws Exception {
  try(var ticks=owner()){
   @SuppressWarnings("unchecked") var actual=(CompletableFuture<Integer>)invoke(TisRefreshCommand.class,"report",new Class[]{ServerPlayer.class,int.class},player,6);
   assertFalse(actual.isDone());channel.close();
   assertInstanceOf(java.nio.channels.ClosedChannelException.class,assertThrows(CompletionException.class,actual::join).getCause());
   assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
  }
 }
 @Test void refreshRangeUsesActualWatchedSectionInsteadOfCurrentChunk() throws Exception {
  var loader=mock(ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.PlayerChunkLoaderData.class);when(player.moonrise$getChunkLoader()).thenReturn(loader);
  when(loader.getSentChunksRaw()).thenReturn(new it.unimi.dsi.fastutil.longs.LongOpenHashSet(new long[]{ChunkPos.pack(0,0),ChunkPos.pack(10,0)}));
  when(player.getLastSectionPos()).thenReturn(SectionPos.of(0,0,0));when(player.chunkPosition()).thenReturn(new ChunkPos(10,0));
  Field controller=Level.class.getField("chunkPacketBlockController");controller.setAccessible(true);controller.set(world,io.papermc.paper.antixray.ChunkPacketBlockController.NO_OPERATION_INSTANCE);
  Class<?> selection=Class.forName("fun.bm.lophine.carpet.TisRefreshCommand$Selection");var ctor=selection.getDeclaredConstructor(boolean.class,ChunkPos.class,Integer.class);ctor.setAccessible(true);
  Object view=invoke(TisRefreshCommand.class,"capture",new Class[]{CommandSourceStack.class,ServerPlayer.class,selection,UUID.class},source,player,ctor.newInstance(false,null,1),player.getUUID());
  Method chunks=view.getClass().getDeclaredMethod("chunks");chunks.setAccessible(true);assertEquals(List.of(new ChunkPos(0,0)),chunks.invoke(view));
 }
 @Test void mountIncludesOldVehicleAncestorAndPassengersThenWaitsActualMountChildren() throws Exception {
  var rider=mock(Entity.class);var target=mock(Entity.class);var old=mock(Entity.class);var ancestor=mock(Entity.class);var nested=mock(Entity.class);
  for(Entity actor:List.of(rider,target,old,ancestor,nested)){
   when(actor.level()).thenReturn(world);when(actor.blockPosition()).thenReturn(BlockPos.ZERO);when(actor.position()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);when(actor.getPassengers()).thenReturn(List.of());
  }
  when(rider.getVehicle()).thenReturn(old);when(old.getPassengers()).thenReturn(List.of(rider));when(old.position()).thenReturn(new net.minecraft.world.phys.Vec3(-64,0,0));
  when(target.getVehicle()).thenReturn(ancestor);when(ancestor.getPassengers()).thenReturn(List.of(target));when(ancestor.position()).thenReturn(new net.minecraft.world.phys.Vec3(480,0,0));
  when(target.getPassengers()).thenReturn(List.of(nested));when(nested.getVehicle()).thenReturn(target);
  var child=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.record(child);return true;}).when(rider).startRiding(target,true,true);
  var rectangle=new AtomicReference<List<Integer>>();
  try(var ticks=owner();var lease=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()){
   lease.when(()->CarpetRegionLease.runLoadedValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any(java.util.function.Function.class)))
    .thenAnswer(call->{rectangle.set(List.of(call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4)));return CompletableFuture.completedFuture(((java.util.function.Function)call.getArgument(5)).apply(null));});
   @SuppressWarnings("unchecked") var actual=(CompletableFuture<Integer>)invoke(TisManipulateCommand.class,"mount",new Class[]{CommandSourceStack.class,Entity.class,Entity.class,int.class},source,rider,target,8);
   assertEquals(List.of(-4,0,30,0),rectangle.get());assertFalse(actual.isDone());verify(rider).startRiding(target,true,true);child.complete(null);assertEquals(1,actual.join());
  }
 }
 @Test void rejectedNativeEntityQueueDoesNotPublishSuccessfulCommandValue() throws Exception {
  var target=mock(Entity.class);var bukkit=mock(org.bukkit.craftbukkit.entity.CraftEntity.class);when(target.getBukkitEntity()).thenReturn(bukkit);
  var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);var schedulerField=org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");schedulerField.setAccessible(true);schedulerField.set(bukkit,scheduler);
  when(scheduler.schedule(any(),any(),anyLong())).thenReturn(false);
  var actual=TisCommandContinuations.entity(source,target,()->CompletableFuture.completedFuture(1));
  assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,actual::join)));verify(scheduler).schedule(any(),any(),eq(1L));
 }

 @Test void dismountCountsOnlyOriginalPassengersAndWaitsRealTypedRemovalTail() throws Exception {
  var rider=mock(Entity.class);var idle=mock(Entity.class);for(Entity actor:List.of(rider,idle)){when(actor.level()).thenReturn(world);when(actor.blockPosition()).thenReturn(BlockPos.ZERO);}
  when(rider.isPassenger()).thenReturn(true);var removed=new CompletableFuture<Void>();
  try(var ticks=owner();var relations=mockStatic(ScarpetNativeRelationships.class);var scope=CarpetAsyncCommandResults.open()){
   relations.when(()->ScarpetNativeRelationships.stopRiding(rider,false)).thenReturn(removed);
   invoke(TisManipulateCommand.class,"dismount",new Class[]{CommandSourceStack.class,Collection.class},source,List.of(rider,idle));
   assertFalse(scope.completionFuture().isDone());verifyNoInteractions(callback);relations.verify(()->ScarpetNativeRelationships.stopRiding(idle,false),never());
   removed.complete(null);scope.completionFuture().join();verify(callback).onResult(true,1);
  }
 }
 @Test void chunkRefreshCountWaitsActualPacketChildrenThenReportReceipt() throws Exception {
  var loader=mock(ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.PlayerChunkLoaderData.class);when(player.moonrise$getChunkLoader()).thenReturn(loader);
  when(loader.getSentChunksRaw()).thenReturn(new it.unimi.dsi.fastutil.longs.LongOpenHashSet(new long[]{ChunkPos.pack(0,0)}));
  Field controller=Level.class.getField("chunkPacketBlockController");controller.setAccessible(true);controller.set(world,io.papermc.paper.antixray.ChunkPacketBlockController.NO_OPERATION_INSTANCE);
  when(world.getChunkIfLoaded(0,0)).thenReturn(mock(net.minecraft.world.level.chunk.LevelChunk.class));var packetChild=new CompletableFuture<Void>();var receipt=new AtomicReference<io.netty.channel.ChannelFutureListener>();
  doAnswer(call->{
   io.netty.channel.ChannelFutureListener listener=call.getArgument(1);
   if(call.getArgument(0) instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket){ScarpetNativeWork.record(packetChild);listener.operationComplete(channel.newSucceededFuture());}
   else receipt.set(listener);
   return null;
  }).when(player.connection).send(any(net.minecraft.network.protocol.Packet.class),any(io.netty.channel.ChannelFutureListener.class));
  Class<?> selection=Class.forName("fun.bm.lophine.carpet.TisRefreshCommand$Selection");var ctor=selection.getDeclaredConstructor(boolean.class,ChunkPos.class,Integer.class);ctor.setAccessible(true);
  try(var ticks=owner();var lease=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();var event=mockStatic(io.papermc.paper.event.packet.PlayerChunkLoadEvent.class);
      var packet=mockConstruction(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.class);var scope=CarpetAsyncCommandResults.open()){
   ticks.when(()->TickThread.isTickThreadFor(world,0,0)).thenReturn(true);
   event.when(io.papermc.paper.event.packet.PlayerChunkLoadEvent::getHandlerList).thenReturn(new org.bukkit.event.HandlerList());
   lease.when(()->CarpetRegionLease.runLoadedValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any(java.util.function.Function.class)))
    .thenAnswer(call->CompletableFuture.completedFuture(((java.util.function.Function)call.getArgument(5)).apply(null)));
   invoke(TisRefreshCommand.class,"refresh",new Class[]{CommandSourceStack.class,selection},source,ctor.newInstance(true,null,null));
   assertNull(receipt.get());assertFalse(scope.completionFuture().isDone());verifyNoInteractions(callback);packetChild.complete(null);
   assertNotNull(receipt.get());assertFalse(scope.completionFuture().isDone());var network=mock(io.netty.channel.ChannelFuture.class);when(network.isSuccess()).thenReturn(true);receipt.get().operationComplete(network);
   scope.completionFuture().join();verify(callback).onResult(true,1);
  }
 }

}
