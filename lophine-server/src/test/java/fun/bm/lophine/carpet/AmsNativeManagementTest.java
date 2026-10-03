package fun.bm.lophine.carpet;

import carpet.script.external.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
import net.minecraft.commands.*;import net.minecraft.core.BlockPos;import net.minecraft.server.*;import net.minecraft.server.level.*;import net.minecraft.server.network.*;
import net.minecraft.world.entity.*;import net.minecraft.world.phys.Vec3;import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;

/** Actual AMS helper bodies with distinct source/target/global owners and their true Native children. */
public class AmsNativeManagementTest {
 @BeforeAll static void bootstrap()throws Exception{
  net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  var config=new io.papermc.paper.configuration.GlobalConfiguration();config.misc=config.new Misc();
  try(var configurations=mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)){
   configurations.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);Class.forName("net.minecraft.network.Connection");
  }
 }
 @TempDir Path directory;
 static final class Fixture implements AutoCloseable {
  final MinecraftServer server=mock(MinecraftServer.class);final ServerLevel world=mock(ServerLevel.class);
  final ServerPlayer sourcePlayer=mock(ServerPlayer.class),target=mock(ServerPlayer.class);
  final CommandSourceStack source=mock(CommandSourceStack.class);final CommandResultCallback callback=mock(CommandResultCallback.class);
  final Queue<Runnable> tasks=new ConcurrentLinkedQueue<>();final List<String> order=new ArrayList<>();Entity current=sourcePlayer;boolean global;
  final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
  final org.mockito.MockedStatic<io.papermc.paper.threadedregions.RegionizedServer> globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);
  final org.mockito.MockedStatic<CarpetMessenger> messenger=mockStatic(CarpetMessenger.class);
  final org.mockito.MockedStatic<AmsTranslations> translations=mockStatic(AmsTranslations.class);
  Fixture(Path path)throws Exception{
   when(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).thenReturn(path);when(world.getServer()).thenReturn(server);
   for(ServerPlayer player:List.of(sourcePlayer,target)){
    when(player.level()).thenReturn(world);when(player.carpetSpawnServer()).thenReturn(server);when(player.blockPosition()).thenReturn(BlockPos.ZERO);when(player.getUUID()).thenReturn(UUID.randomUUID());when(player.getScoreboardName()).thenReturn(player==sourcePlayer?"source":"target");
    player.connection=mock(ServerGamePacketListenerImpl.class);
    var nativeConnection=mock(net.minecraft.network.Connection.class);when(nativeConnection.isConnected()).thenReturn(true);
    nativeConnection.channel=mock(io.netty.channel.Channel.class);when(nativeConnection.channel.closeFuture()).thenReturn(mock(io.netty.channel.ChannelFuture.class));
    Field nativeField=ServerCommonPacketListenerImpl.class.getDeclaredField("connection");nativeField.setAccessible(true);nativeField.set(player.connection,nativeConnection);
    var wrapper=mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);when(player.getBukkitEntity()).thenReturn(wrapper);
    var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);Field field=org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");field.setAccessible(true);field.set(wrapper,scheduler);
    when(scheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{Consumer<Entity> action=call.getArgument(0);tasks.add(()->{Entity previous=current;current=player;try{action.accept(player);}finally{current=previous;}});return true;});
   }
   when(source.getServer()).thenReturn(server);when(source.getLevel()).thenReturn(world);when(source.getEntity()).thenReturn(sourcePlayer);when(source.getPosition()).thenReturn(Vec3.ZERO);when(source.getPlayerOrException()).thenReturn(sourcePlayer);when(source.callback()).thenReturn(callback);
   ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call->call.getArgument(0)==current);ticks.when(()->TickThread.isTickThreadFor(eq(world),any(BlockPos.class))).thenReturn(true);
   var executor=mock(io.papermc.paper.threadedregions.RegionizedServer.class);globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(executor);globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenAnswer(call->global);
   doAnswer(call->{Runnable action=call.getArgument(0);tasks.add(()->{boolean previous=global;global=true;try{action.run();}finally{global=previous;}});return null;}).when(executor).addTask(any(Runnable.class));
   translations.when(()->AmsTranslations.message(any(),anyString(),any(Object[].class))).thenAnswer(call->Component.literal((String)call.getArgument(1)));
   translations.when(()->AmsTranslations.translate(any(Component.class),any(ServerPlayer.class))).thenAnswer(call->call.getArgument(0));
   AmsManagementSettings.load(server);
  }
  com.mojang.brigadier.context.CommandContext<CommandSourceStack> context(){var ctx=mock(com.mojang.brigadier.context.CommandContext.class);when(ctx.getSource()).thenReturn(source);return ctx;}
  void drain(){Runnable next;while((next=tasks.poll())!=null)next.run();}
  @Override public void close(){AmsManagementSettings.flushAtShutdown();translations.close();messenger.close();globals.close();ticks.close();}
 }
 @SuppressWarnings("unchecked")static <T>T invoke(String name,Class<?>[] signature,Object...args){try{Method m=AmsManagementCommands.class.getDeclaredMethod(name,signature);m.setAccessible(true);return (T)m.invoke(null,args);}catch(InvocationTargetException failure){throw new RuntimeException(failure.getCause());}catch(Exception failure){throw new RuntimeException(failure);}}
 @Test void actualAtSourceIntWaitsTitleThenBroadcastThenTargetSoundThenFinalBroadcast()throws Exception{
  try(Fixture f=new Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
   var title=new CompletableFuture<Void>();var first=new CompletableFuture<Void>();var sound=new CompletableFuture<Void>();var second=new CompletableFuture<Void>();var callback=new CompletableFuture<Void>();
   doAnswer(call->{assertSame(f.target,f.current);f.order.add("title");ScarpetNativeWork.record(title);var sent=mock(io.netty.channel.ChannelFuture.class);when(sent.isSuccess()).thenReturn(true);((io.netty.channel.ChannelFutureListener)call.getArgument(1)).operationComplete(sent);return null;}).when(f.target.connection).send(any(net.minecraft.network.protocol.Packet.class),any(io.netty.channel.ChannelFutureListener.class));
   f.messenger.when(()->CarpetMessenger.print_server_message(eq(f.server),any(Component.class))).thenAnswer(call->{assertTrue(f.global);String text=((Component)call.getArgument(1)).getString();f.order.add(text.contains(" @ ")?"broadcast2":"broadcast1");ScarpetNativeWork.record(text.contains(" @ ")?second:first);return null;});
   doAnswer(call->{assertSame(f.target,f.current);f.order.add("sound");ScarpetNativeWork.record(sound);return null;}).when(f.world).playSound(isNull(),eq(BlockPos.ZERO),eq(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP),eq(net.minecraft.sounds.SoundSource.PLAYERS),eq(1F),eq(1F));
   doAnswer(call->{assertSame(f.sourcePlayer,f.current);f.order.add("callback");ScarpetNativeWork.record(callback);return null;}).when(f.callback).onResult(true,1);
   int immediate=AmsNativeCommandEffects.command(f.context(),ctx->{CompletableFuture<Void> actual=invoke("at",new Class<?>[]{CommandSourceStack.class,ServerPlayer.class,ServerPlayer.class,String.class},f.source,f.sourcePlayer,f.target,"hello");AmsNativeCommandEffects.receipt(actual);return 1;});
   assertEquals(1,immediate);var actual=scope.resultFuture(f.source);f.drain();assertEquals(List.of("title"),f.order);assertFalse(actual.isDone());
   title.complete(null);f.drain();assertEquals(List.of("title","broadcast1"),f.order);first.complete(null);f.drain();assertEquals(List.of("title","broadcast1","sound"),f.order);sound.complete(null);f.drain();assertEquals(List.of("title","broadcast1","sound","broadcast2"),f.order);second.complete(null);f.drain();assertEquals("callback",f.order.getLast());assertFalse(actual.isDone());callback.complete(null);assertEquals(1,actual.get(3,TimeUnit.SECONDS));
  }
 }
 @Test void actualLeaderEffectAndGlobalAnnouncementFinishBeforeMapAndRealFileSave()throws Exception{
  try(Fixture f=new Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
   var glow=new CompletableFuture<Void>();var message=new CompletableFuture<Void>();
   when(f.target.addEffect(any(net.minecraft.world.effect.MobEffectInstance.class))).thenAnswer(call->{assertSame(f.target,f.current);f.order.add("effect");ScarpetNativeWork.record(glow);return true;});
   f.messenger.when(()->CarpetMessenger.print_server_message(eq(f.server),any(Component.class))).thenAnswer(call->{assertTrue(f.global);f.order.add("message");ScarpetNativeWork.record(message);return null;});
   AmsNativeCommandEffects.command(f.context(),ctx->{CompletableFuture<Void> actual=invoke("leader",new Class<?>[]{CommandSourceStack.class,ServerPlayer.class,boolean.class},f.source,f.target,true);AmsNativeCommandEffects.receipt(actual);return 1;});var actual=scope.resultFuture(f.source);
   f.drain();assertEquals(List.of("effect"),f.order);assertTrue(AmsManagementSettings.LEADERS.isEmpty());glow.complete(null);f.drain();assertEquals(List.of("effect","message"),f.order);assertTrue(AmsManagementSettings.LEADERS.isEmpty());message.complete(null);f.drain();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!actual.isDone()&&System.nanoTime()<deadline){Thread.sleep(5);f.drain();}assertEquals(1,actual.get(1,TimeUnit.SECONDS));
   assertEquals(f.target.getUUID(),AmsManagementSettings.LEADERS.get("target"));assertTrue(Files.exists(directory.resolve("carpetamsaddition/leader.json")));verify(f.callback).onResult(true,1);
  }
 }
 @Test void genuineAtNativeTitleFailureBlocksAllLaterGlobalAndTargetStagesAndReturnsZero()throws Exception{
  try(Fixture f=new Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
   var child=new CompletableFuture<Void>();var failure=new IllegalStateException("native title");
   doAnswer(call->{ScarpetNativeWork.record(child);throw failure;}).when(f.target.connection).send(any(net.minecraft.network.protocol.Packet.class),any(io.netty.channel.ChannelFutureListener.class));
   AmsNativeCommandEffects.command(f.context(),ctx->{CompletableFuture<Void> actual=invoke("at",new Class<?>[]{CommandSourceStack.class,ServerPlayer.class,ServerPlayer.class,String.class},f.source,f.sourcePlayer,f.target,"hello");AmsNativeCommandEffects.receipt(actual);return 1;});var actual=scope.resultFuture(f.source);f.drain();assertFalse(actual.isDone());child.complete(null);f.drain();assertEquals(0,actual.get(3,TimeUnit.SECONDS));verify(f.callback).onResult(false,0);f.messenger.verify(()->CarpetMessenger.print_server_message(any(MinecraftServer.class),any(Component.class)),never());
  }
 }
 @Test void actualStorageReceiptKeepsSnapshotCancellationAndFailureVisibleBeforeSourceReply()throws Exception{
  try(Fixture f=new Fixture(directory)){
   AmsManagementSettings.PERMISSIONS.put("give",2);var saved=AmsManagementSettings.savePermissions(f.server);assertFalse(saved.cancel(false));saved.get(3,TimeUnit.SECONDS);assertEquals(2,com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("carpetamsaddition/custom_command_permission_level.json"))).getAsJsonObject().get("give").getAsInt());
   Files.createFile(directory.resolve("blocked"));MinecraftServer blocked=mock(MinecraftServer.class);when(blocked.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).thenReturn(directory.resolve("blocked"));var failed=AmsManagementSettings.savePermissions(blocked);assertThrows(ExecutionException.class,()->failed.get(3,TimeUnit.SECONDS));
  }
 }
 @Test void sourceSaveReceiptFencesEveryReplyAndItsActualNativeChildren()throws Exception{
  try(Fixture f=new Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
   var save=new CompletableFuture<Void>();var first=new CompletableFuture<Void>();var second=new CompletableFuture<Void>();var messages=new ArrayList<String>();
   AmsNativeCommandEffects.command(f.context(),ctx->{AmsNativeCommandEffects.receipt(save);AmsNativeCommandEffects.reply(f.source,()->{messages.add("first");ScarpetNativeWork.record(first);});AmsNativeCommandEffects.reply(f.source,()->{messages.add("second");ScarpetNativeWork.record(second);});return 7;});var actual=scope.resultFuture(f.source);
   assertTrue(messages.isEmpty());save.complete(null);f.drain();assertEquals(List.of("first"),messages);first.complete(null);f.drain();assertEquals(List.of("first","second"),messages);assertFalse(actual.isDone());second.complete(null);f.drain();assertEquals(7,actual.get(3,TimeUnit.SECONDS));verify(f.callback).onResult(true,7);
  }
 }
 @Test void sourcePoseReceiptWaitsOwnedSyncAndRealDelayedReleaseBeforeCommandCallback()throws Exception{
  try(Fixture f=new Fixture(directory);var protocol=mockStatic(fun.bm.lophine.protocol.AmsNetworkProtocol.class);var scope=CarpetAsyncCommandResults.open()){
   var sync=new CompletableFuture<Void>();doAnswer(call->{assertSame(f.target,f.current);f.order.add("shift:"+call.getArgument(0));return null;}).when(f.target).setShiftKeyDown(anyBoolean());
   protocol.when(()->fun.bm.lophine.protocol.AmsNetworkProtocol.syncPoses(f.target.getUUID())).thenAnswer(call->{assertTrue(f.global);f.order.add("sync");ScarpetNativeWork.record(sync);return null;});
   AmsNativeCommandEffects.command(f.context(),ctx->invoke("pose",new Class<?>[]{CommandSourceStack.class,ServerPlayer.class,String.class},f.source,f.target,"swimming"));var actual=scope.resultFuture(f.source);f.drain();assertEquals(List.of("shift:true","sync"),f.order);assertFalse(actual.isDone());sync.complete(null);f.drain();assertEquals(List.of("shift:true","sync","shift:true"),f.order);assertFalse(actual.isDone());
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!actual.isDone()&&System.nanoTime()<deadline){Thread.sleep(5);f.drain();}assertEquals(1,actual.get(1,TimeUnit.SECONDS));assertEquals("shift:false",f.order.getLast());
  }
 }
 @Test void registeredRealManagementLeafOwnsSourceIntAndActualFailedSaveBeforeSuccessReply()throws Exception{
  try(Fixture f=new Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
   var dispatcher=new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();var access=CommandBuildContext.simple(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY),net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
   AmsManagementCommands.register(dispatcher,access);var command=dispatcher.getRoot().getChild("customAntiFireItems").getChild("removeAll").getCommand();assertNotNull(command);
   Files.createFile(directory.resolve("blocked"));when(f.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).thenReturn(directory.resolve("blocked"));
   assertEquals(1,command.run(f.context()));var result=scope.resultFuture(f.source);assertNotNull(result);
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!result.isDone()&&System.nanoTime()<deadline){Thread.sleep(5);f.drain();}assertEquals(0,result.get(1,TimeUnit.SECONDS));verify(f.callback).onResult(false,0);
   f.messenger.verify(()->CarpetMessenger.send(eq(f.source),any(List.class)),never());
  }
 }
 @Test void actualProtocolPacketOwnedEncodingWaitsTheRealWriteReceipt()throws Exception{
  protocolPacket(false);
 }
 @Test void actualProtocolWriteFailureRetainsGenuineNativeCause()throws Exception{
  protocolPacket(true);
 }
 void protocolPacket(boolean fail)throws Exception{
  try(Fixture f=new Fixture(directory);var utils=mockStatic(org.leavesmc.leaves.protocol.core.ProtocolUtils.class)){
   utils.when(()->org.leavesmc.leaves.protocol.core.ProtocolUtils.decorate(any(io.netty.buffer.ByteBuf.class))).thenAnswer(call->{assertSame(f.target,f.current);return new net.minecraft.network.RegistryFriendlyByteBuf(call.getArgument(0),net.minecraft.core.RegistryAccess.EMPTY);});
   var write=new java.util.concurrent.atomic.AtomicReference<io.netty.channel.ChannelFutureListener>();
   doAnswer(call->{assertSame(f.target,f.current);var payload=((net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket)call.getArgument(0)).payload();assertInstanceOf(net.minecraft.network.protocol.common.custom.DiscardedPayload.class,payload);
    byte[] bytes=((net.minecraft.network.protocol.common.custom.DiscardedPayload)payload).data();var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes));try{assertEquals("actual",buffer.readUtf());assertEquals(17,buffer.readInt());}finally{buffer.release();}write.set(call.getArgument(1));return null;
   }).when(f.target.connection).send(any(net.minecraft.network.protocol.Packet.class),any(io.netty.channel.ChannelFutureListener.class));
   var actual=ScarpetNativeWork.observeNative(f.sourcePlayer,()->{
    try{var method=fun.bm.lophine.protocol.AmsNetworkProtocol.class.getDeclaredMethod("sendPacket",ServerPlayer.class,String.class,boolean.class,Consumer.class);method.setAccessible(true);method.invoke(null,f.target,"actual",true,(Consumer<net.minecraft.network.FriendlyByteBuf>)buffer->buffer.writeInt(17));}catch(Exception error){throw new RuntimeException(error);}return 17;
   });f.drain();assertFalse(actual.isDone());assertNotNull(write.get());var channel=mock(io.netty.channel.ChannelFuture.class);var failure=new IllegalStateException("actual AMS channel");when(channel.isSuccess()).thenReturn(!fail);when(channel.cause()).thenReturn(failure);write.get().operationComplete(channel);f.drain();
   if(fail){var cause=assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS)).getCause();assertSame(failure,cause);assertFalse(ScarpetNativeWork.onlyGuestFailure(cause));}else assertEquals(17,actual.get(3,TimeUnit.SECONDS));
  }
 }
}
