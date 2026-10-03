package fun.bm.lophine.carpet;

import carpet.script.external.*;
import fun.bm.lophine.protocol.AmsNetworkProtocol;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import java.lang.reflect.*;import java.nio.file.Path;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
import net.minecraft.commands.*;import net.minecraft.core.RegistryAccess;import net.minecraft.network.*;import net.minecraft.network.chat.Component;import net.minecraft.network.protocol.Packet;import net.minecraft.network.protocol.common.*;import net.minecraft.network.protocol.common.custom.*;import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;

public class AmsNativeNetworkQueryTest {
 @BeforeAll static void boot(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @TempDir Path directory;
 @SuppressWarnings("unchecked")static <K,V>Map<K,V> map(String name)throws Exception{Field f=AmsNetworkProtocol.class.getDeclaredField(name);f.setAccessible(true);return (Map<K,V>)f.get(null);}
 @BeforeEach void reset()throws Exception{map("CLIENTS").clear();map("FPS").clear();map("CLIENT_VERSIONS").clear();GeneralCompatConfig.amsNetworkProtocol=true;}
 @AfterEach void end(){AmsClientQueryCommands.stopAtShutdown();GeneralCompatConfig.amsNetworkProtocol=false;}
 static FriendlyByteBuf packet(String name){return new FriendlyByteBuf(Unpooled.buffer()).writeUtf(name);}
 static void acknowledge(ChannelFutureListener listener,Throwable failure)throws Exception{var channel=mock(ChannelFuture.class);when(channel.isSuccess()).thenReturn(failure==null);when(channel.cause()).thenReturn(failure);listener.operationComplete(channel);}
 static void install(ServerPlayer target)throws Exception{AmsNativeNetworkQueryTest.<UUID,AmsNetworkProtocol.Client>map("CLIENTS").put(target.getUUID(),new AmsNetworkProtocol.Client("target","26.3",Set.of("handshake_s2c","sync_custom_block_hardness","update_player_pose_s2c","lazy_settings_s2c","request_client_mod_version_s2c","client_player_fps_s2c")));}
 @Test void unsupportedFpsKeepsOfficialOneLatestSourceAndNoTimeout()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   var second=mock(CommandSourceStack.class);assertEquals(1,AmsNetworkProtocol.requestFps(f.source,f.target));assertEquals(1,AmsNetworkProtocol.requestFps(second,f.target));f.drain();
   assertSame(second,map("FPS").get(f.target.getUUID()));verify(f.target.connection,never()).send(any(Packet.class),any(ChannelFutureListener.class));
  }
 }
 @Test void actualHandshakeBooleanWaitsEachActualWriteBeforeTheNextNativeSync()throws Exception{handshake(false);}
 @Test void genuineHandshakeWriteFailureStopsRemainingHardnessPoseAndLazySync()throws Exception{handshake(true);}
 void handshake(boolean fail)throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var buffers=mockStatic(ProtocolUtils.class)){
   buffers.when(()->ProtocolUtils.decorate(any(io.netty.buffer.ByteBuf.class))).thenAnswer(call->{assertSame(f.target,f.current);return new RegistryFriendlyByteBuf(call.getArgument(0),RegistryAccess.EMPTY);});
   var writes=new ArrayDeque<ChannelFutureListener>();var names=new ArrayList<String>();
   doAnswer(call->{assertSame(f.target,f.current);var payload=((ClientboundCustomPayloadPacket)call.getArgument(0)).payload();var raw=new FriendlyByteBuf(Unpooled.wrappedBuffer(((DiscardedPayload)payload).data()));try{names.add(raw.readUtf());}finally{raw.release();}writes.add(call.getArgument(1));return null;}).when(f.target.connection).send(any(Packet.class),any(ChannelFutureListener.class));
   var input=packet("handshake_c2s");input.writeUtf("26.3").writeUUID(f.target.getUUID()).writeVarInt(4);for(var name:List.of("handshake_s2c","sync_custom_block_hardness","update_player_pose_s2c","lazy_settings_s2c"))input.writeUtf(name);
   CompletableFuture<Boolean> actual;try{actual=ScarpetNativeWork.observeNative(f.sourcePlayer,()->AmsNetworkProtocol.receive(f.target,input));}finally{input.release();}f.drain();
   assertEquals(List.of("handshake_s2c"),names);assertFalse(actual.isDone());var failure=new IllegalStateException("actual handshake write");acknowledge(writes.remove(),fail?failure:null);f.drain();
   if(fail){assertSame(failure,assertThrows(CompletionException.class,actual::join).getCause());assertEquals(1,names.size());return;}
   for(String next:List.of("sync_custom_block_hardness","update_player_pose_s2c","lazy_settings_s2c")){assertEquals(next,names.getLast());assertFalse(actual.isDone());acknowledge(writes.remove(),null);f.drain();}
   assertTrue(actual.join());assertEquals(4,names.size());
  }
 }
 @Test void fpsReplyOwnsTargetNameThenSourceLocaleAndWaitsTheRealReplyChild()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   install(f.target);AmsNativeNetworkQueryTest.<UUID,CommandSourceStack>map("FPS").put(f.target.getUUID(),f.source);var child=new CompletableFuture<Void>();
   doAnswer(call->{assertSame(f.sourcePlayer,f.current);f.order.add(((Supplier<Component>)call.getArgument(0)).get().getString());ScarpetNativeWork.record(child);return null;}).when(f.source).sendSuccess(any(),eq(false));
   var input=packet("client_player_fps_c2s");input.writeUUID(f.target.getUUID()).writeInt(120);CompletableFuture<Boolean> actual;try{actual=ScarpetNativeWork.observeNative(f.sourcePlayer,()->AmsNetworkProtocol.receive(f.target,input));}finally{input.release();}f.drain();
   assertEquals(List.of("command.getClientPlayerFps.feedback"),f.order);assertTrue(map("FPS").isEmpty());assertFalse(actual.isDone());child.complete(null);f.drain();assertTrue(actual.join());
  }
 }
 @Test void versionKeepsOriginalThreeSecondCheckAndActualOneThroughFinalReply()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var buffers=mockStatic(ProtocolUtils.class);var effects=mockStatic(AmsNativeCommandEffects.class,CALLS_REAL_METHODS);var scope=CarpetAsyncCommandResults.open()){
   install(f.target);var pause=new CompletableFuture<Void>();effects.when(()->AmsNativeCommandEffects.pause(f.server,3000L)).thenReturn(pause);var writes=new ArrayDeque<ChannelFutureListener>();
   buffers.when(()->ProtocolUtils.decorate(any(io.netty.buffer.ByteBuf.class))).thenAnswer(call->new RegistryFriendlyByteBuf(call.getArgument(0),RegistryAccess.EMPTY));doAnswer(call->{writes.add(call.getArgument(1));return null;}).when(f.target.connection).send(any(Packet.class),any(ChannelFutureListener.class));
   var reply=new CompletableFuture<Void>();doAnswer(call->{assertSame(f.sourcePlayer,f.current);String key=((Supplier<Component>)call.getArgument(0)).get().getString();f.order.add(key);if(key.endsWith("success_feedback"))ScarpetNativeWork.record(reply);return null;}).when(f.source).sendSuccess(any(),eq(false));
   AmsNativeCommandEffects.command(f.context(),ctx->AmsNetworkProtocol.requestVersion(f.source,f.target));var actual=scope.resultFuture(f.source);f.drain();assertFalse(actual.isDone());assertTrue(f.order.isEmpty());acknowledge(writes.remove(),null);f.drain();assertEquals(List.of("command.amsp.get_client_version_waiting"),f.order);
   var input=packet("request_client_mod_version_c2s");input.writeUtf("26.3").writeUUID(f.target.getUUID());try{AmsNetworkProtocol.receive(f.target,input);}finally{input.release();}f.drain();assertEquals(1,f.order.size());pause.complete(null);f.drain();assertEquals("command.amsp.client_mod_version_success_feedback",f.order.getLast());assertFalse(actual.isDone());reply.complete(null);f.drain();assertEquals(1,actual.join());verify(f.callback).onResult(true,1);
  }
 }
 @Test void actualZeroQuantityPingRetainsWorkerSourceReplyAndOriginalNegativeSentValue()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
   var child=new CompletableFuture<Void>();doAnswer(call->{assertSame(f.sourcePlayer,f.current);f.order.add(((Supplier<Component>)call.getArgument(0)).get().getString());ScarpetNativeWork.record(child);return null;}).when(f.source).sendSuccess(any(),eq(false));
   AmsNativeCommandEffects.command(f.context(),ctx->{try{Method method=AmsClientQueryCommands.class.getDeclaredMethod("ping",CommandSourceStack.class,String.class,int.class);method.setAccessible(true);return (Integer)method.invoke(null,f.source,"unqueried.invalid",-2);}catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}});var actual=scope.resultFuture(f.source);
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(f.order.isEmpty()&&System.nanoTime()<deadline){f.drain();Thread.sleep(2);}assertEquals(1,f.order.size());assertTrue(f.order.getFirst().contains("Sent = -2"));assertFalse(actual.isDone());child.complete(null);while(!actual.isDone()&&System.nanoTime()<deadline){f.drain();Thread.sleep(2);}assertEquals(1,actual.get(1,TimeUnit.SECONDS));verify(f.callback).onResult(true,1);
  }
 }
 @Test void versionRetainsFiveActualRetryPacketsAndFinalNativeFailureFeedback()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var buffers=mockStatic(ProtocolUtils.class);var effects=mockStatic(AmsNativeCommandEffects.class,CALLS_REAL_METHODS);var scope=CarpetAsyncCommandResults.open()){
   install(f.target);var pauses=new ArrayDeque<CompletableFuture<Void>>();effects.when(()->AmsNativeCommandEffects.pause(f.server,3000L)).thenAnswer(call->{var actual=new CompletableFuture<Void>();pauses.add(actual);return actual;});
   buffers.when(()->ProtocolUtils.decorate(any(io.netty.buffer.ByteBuf.class))).thenAnswer(call->new RegistryFriendlyByteBuf(call.getArgument(0),RegistryAccess.EMPTY));var writes=new ArrayDeque<ChannelFutureListener>();doAnswer(call->{writes.add(call.getArgument(1));return null;}).when(f.target.connection).send(any(Packet.class),any(ChannelFutureListener.class));
   doAnswer(call->{f.order.add(((Supplier<Component>)call.getArgument(0)).get().getString());return null;}).when(f.source).sendSuccess(any(),eq(false));
   AmsNativeCommandEffects.command(f.context(),ctx->AmsNetworkProtocol.requestVersion(f.source,f.target));var actual=scope.resultFuture(f.source);f.drain();acknowledge(writes.remove(),null);f.drain();
   for(int retry=1;retry<=5;++retry){assertFalse(actual.isDone());pauses.remove().complete(null);f.drain();assertEquals("command.amsp.request_client_version",f.order.getLast());assertEquals(1,writes.size());acknowledge(writes.remove(),null);f.drain();}
   assertFalse(actual.isDone());pauses.remove().complete(null);f.drain();assertEquals("command.amsp.client_mod_version_failed_feedback",f.order.getLast());assertTrue(pauses.isEmpty());assertEquals(1,actual.join());verify(f.target.connection,times(6)).send(any(Packet.class),any(ChannelFutureListener.class));
  }
 }
 @Test void genuineInitialVersionWriteFailureBlocksWaitFeedbackAndTimerAndCompletesOriginalZero()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var buffers=mockStatic(ProtocolUtils.class);var effects=mockStatic(AmsNativeCommandEffects.class,CALLS_REAL_METHODS);var scope=CarpetAsyncCommandResults.open()){
   install(f.target);buffers.when(()->ProtocolUtils.decorate(any(io.netty.buffer.ByteBuf.class))).thenAnswer(call->new RegistryFriendlyByteBuf(call.getArgument(0),RegistryAccess.EMPTY));var writes=new ArrayDeque<ChannelFutureListener>();doAnswer(call->{writes.add(call.getArgument(1));return null;}).when(f.target.connection).send(any(Packet.class),any(ChannelFutureListener.class));
   AmsNativeCommandEffects.command(f.context(),ctx->AmsNetworkProtocol.requestVersion(f.source,f.target));var actual=scope.resultFuture(f.source);f.drain();acknowledge(writes.remove(),new IllegalStateException("native initial version write"));f.drain();assertEquals(0,actual.join());f.translations.verify(()->AmsTranslations.message(eq(f.source),eq("command.amsp.get_client_version_waiting"),any(Object[].class)),never());effects.verify(()->AmsNativeCommandEffects.pause(any(),anyLong()),never());verify(f.callback).onResult(false,0);
  }
 }
 @Test void actualRegisteredClientQueryHelpLeafHoldsSourceReplyBeforeItsOriginalOne()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
   var reply=new CompletableFuture<Void>();doAnswer(call->{assertSame(f.sourcePlayer,f.current);ScarpetNativeWork.record(reply);return null;}).when(f.source).sendSuccess(any(),eq(false));
   var dispatcher=new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();AmsClientQueryCommands.register(dispatcher);var node=dispatcher.getRoot().getChild("getClientPlayerFps").getChild("help");assertEquals(1,node.getCommand().run(f.context()));var actual=scope.resultFuture(f.source);f.drain();assertFalse(actual.isDone());reply.complete(null);f.drain();assertEquals(1,actual.join());verify(f.callback).onResult(true,1);
  }
 }
}
