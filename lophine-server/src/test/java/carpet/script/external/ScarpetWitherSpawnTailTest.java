package carpet.script.external;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class ScarpetWitherSpawnTailTest {
 @BeforeAll static void bootstrap()throws Exception{net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  var config=new io.papermc.paper.configuration.GlobalConfiguration();config.misc=config.new Misc();
  try(var configs=mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)) {configs.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);Class.forName("net.minecraft.network.Connection");}
 }
 @Test void realLocalSoundUsesActualAudienceOwnerAndOriginalProjectionAndRange(){sound(false);}
 @Test void realGlobalSoundIncludesForeignDimensionAndIgnoresLocalRadius(){sound(true);}
 private void sound(boolean everywhere){
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);var players=mock(PlayerList.class);when(server.getPlayerList()).thenReturn(players);
  var craft=mock(org.bukkit.craftbukkit.CraftServer.class);when(world.getCraftServer()).thenReturn(craft);when(craft.getViewDistance()).thenReturn(4);
  var gamerules=mock(GameRules.class);when(world.getGameRules()).thenReturn(gamerules);when(gamerules.get(GameRules.GLOBAL_SOUND_EVENTS)).thenReturn(everywhere);when(world.getGlobalSoundRangeSquared(any())).thenReturn(40000D);
  var boss=mock(WitherBoss.class);when(boss.position()).thenReturn(new Vec3(100,20,0));when(boss.blockPosition()).thenReturn(new BlockPos(100,20,0));
  var near=mock(ServerPlayer.class);var far=mock(ServerPlayer.class);var foreign=mock(ServerPlayer.class);when(players.getPlayers()).thenReturn(List.of(near,far,foreign));
  connect(near,server);connect(far,server);connect(foreign,server);
  when(near.level()).thenReturn(world);when(far.level()).thenReturn(world);when(foreign.level()).thenReturn(mock(ServerLevel.class));when(far.getX()).thenReturn(1000D);
  var global=mock(io.papermc.paper.threadedregions.RegionizedServer.class);var queued=new ArrayList<Runnable>();doAnswer(call->{queued.add(call.getArgument(0));return null;}).when(global).addTask(any(Runnable.class));
  try(var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
   globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(any(net.minecraft.world.entity.Entity.class))).thenReturn(true);
   var actual=ScarpetNativeWork.observeNative(boss,()->ScarpetWitherSpawnSound.send(boss,world));if(actual.isCompletedExceptionally())actual.join();assertFalse(actual.isDone());verifyNoInteractions(near.connection,far.connection,foreign.connection);assertEquals(1,queued.size());queued.getFirst().run();assertTrue(actual.isDone());if(actual.isCompletedExceptionally())actual.join();assertFalse(actual.isCompletedExceptionally());
   var capture=org.mockito.ArgumentCaptor.forClass(net.minecraft.network.protocol.Packet.class);verify(near.connection).send(capture.capture(),any(io.netty.channel.ChannelFutureListener.class));var packet=(ClientboundLevelEventPacket)capture.getValue();assertEquals(new BlockPos(64,20,0),packet.getPos());assertTrue(packet.isGlobalEvent());
   if(everywhere){verify(far.connection).send(any(),any(io.netty.channel.ChannelFutureListener.class));verify(foreign.connection).send(any(),any(io.netty.channel.ChannelFutureListener.class));}else verifyNoInteractions(far.connection,foreign.connection);
  }
 }
 private static void connect(ServerPlayer player,MinecraftServer server){
  when(player.carpetSpawnServer()).thenReturn(server);when(player.blockPosition()).thenReturn(BlockPos.ZERO);when(player.level()).thenReturn(mock(ServerLevel.class));player.connection=mock(ServerGamePacketListenerImpl.class);
  var wire=mock(net.minecraft.network.Connection.class);wire.channel=mock(io.netty.channel.Channel.class);
  var closing=mock(io.netty.channel.ChannelFuture.class);when(wire.channel.closeFuture()).thenReturn(closing);when(wire.isConnected()).thenReturn(true);
  try{var connectionField=net.minecraft.server.network.ServerCommonPacketListenerImpl.class.getField("connection");connectionField.setAccessible(true);connectionField.set(player.connection,wire);}catch(Exception e){throw new AssertionError(e);}
  doAnswer(call->{var sent=mock(io.netty.channel.ChannelFuture.class);when(sent.isSuccess()).thenReturn(true);((io.netty.channel.ChannelFutureListener)call.getArgument(1)).operationComplete(sent);return null;}).when(player.connection).send(any(net.minecraft.network.protocol.Packet.class),any(io.netty.channel.ChannelFutureListener.class));
 }
 @Test void disabledRuleStillEvaluatesOriginalBlockPositionArgument(){
  boolean before=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.witherSpawnedSoundDisabled;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.witherSpawnedSoundDisabled=true;
   var boss=mock(WitherBoss.class);when(boss.blockPosition()).thenReturn(new BlockPos(7,20,-2));
   assertTrue(ScarpetWitherSpawnSound.send(boss,mock(ServerLevel.class)).isDone());verify(boss).blockPosition();verify(boss,never()).position();
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.witherSpawnedSoundDisabled=before;}
 }
 @Test void physicalWriteAndLateChildBothFenceActualSound()throws Exception{physical(false);}
 @Test void physicalWriteFailureIsNotReplacedByQueueAcceptance()throws Exception{physical(true);}
 private void physical(boolean fail)throws Exception{
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);var list=mock(PlayerList.class);when(server.getPlayerList()).thenReturn(list);
  var target=mock(ServerPlayer.class);connect(target,server);when(list.getPlayers()).thenReturn(List.of(target));when(target.level()).thenReturn(world);
  var boss=mock(WitherBoss.class);when(boss.blockPosition()).thenReturn(BlockPos.ZERO);when(boss.position()).thenReturn(Vec3.ZERO);
  var rules=mock(GameRules.class);when(world.getGameRules()).thenReturn(rules);when(rules.get(GameRules.GLOBAL_SOUND_EVENTS)).thenReturn(true);
  var craft=mock(org.bukkit.craftbukkit.CraftServer.class);when(world.getCraftServer()).thenReturn(craft);
  var write=new java.util.concurrent.atomic.AtomicReference<io.netty.channel.ChannelFutureListener>();var child=new CompletableFuture<Void>();
  doAnswer(call->{write.set(call.getArgument(1));ScarpetNativeWork.record(child);return null;}).when(target.connection).send(any(net.minecraft.network.protocol.Packet.class),any(io.netty.channel.ChannelFutureListener.class));
  try(var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
   globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(target)).thenReturn(true);
   var actual=ScarpetWitherSpawnSound.send(boss,world);if(actual.isCompletedExceptionally())actual.join();assertFalse(actual.isDone());assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
   var sent=mock(io.netty.channel.ChannelFuture.class);when(sent.isSuccess()).thenReturn(!fail);when(sent.cause()).thenReturn(new IllegalStateException("actual sound failed"));
   write.get().operationComplete(sent);if(actual.isCompletedExceptionally())actual.join();assertFalse(actual.isDone());child.complete(null);
   if(fail)assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS));else actual.get(3,TimeUnit.SECONDS);
  }
 }
 @Test void realSilentBirthDoesNotCaptureOrSendAnAudience(){
  var boss=mock(WitherBoss.class);when(boss.isSilent()).thenReturn(true);try(var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)){
   assertTrue(ScarpetWitherSpawnSound.send(boss,mock(ServerLevel.class)).isDone());globals.verifyNoInteractions();
  }
 }
 @Test void actualBirthBodyWaitsExplosionThenSoundAndCommitsOriginalHealDecision()throws Exception{
  var boss=mock(WitherBoss.class,CALLS_REAL_METHODS);var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);doReturn(world).when(boss).level();when(world.getServer()).thenReturn(server);doReturn(1).when(boss).getInvulnerableTicks();doReturn(0D).when(boss).getX();doReturn(0D).when(boss).getEyeY();doReturn(0D).when(boss).getZ();
  var field=WitherBoss.class.getDeclaredField("bossEvent");field.setAccessible(true);field.set(boss,mock(net.minecraft.server.level.ServerBossEvent.class));boss.tickCount=10;
  var bukkit=mock(org.bukkit.craftbukkit.entity.CraftWither.class);doReturn(bukkit).when(boss).getBukkitEntity();var craft=mock(org.bukkit.craftbukkit.CraftServer.class);var plugins=mock(org.bukkit.plugin.PluginManager.class);when(world.getCraftServer()).thenReturn(craft);when(craft.getPluginManager()).thenReturn(plugins);
  var explosion=new CompletableFuture<Void>();var sounds=new CompletableFuture<Void>();var order=new ArrayList<String>();
  doAnswer(call->{order.add("actual explosion");ScarpetNativeWork.record(explosion);return null;}).when(world).explode(eq(boss),anyDouble(),anyDouble(),anyDouble(),eq(7F),eq(false),eq(net.minecraft.world.level.Level.ExplosionInteraction.MOB));
  doAnswer(call->{order.add("actual invulnerability commit");return null;}).when(boss).setInvulnerableTicks(0);doAnswer(call->{order.add("actual heal");return null;}).when(boss).heal(10F,org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.WITHER_SPAWN);
  try(var sound=mockStatic(ScarpetWitherSpawnSound.class);var actors=mockStatic(ScarpetExplosionActors.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
   sound.when(()->ScarpetWitherSpawnSound.send(boss,world)).thenAnswer(call->{order.add("actual sound receipts");return sounds;});actors.when(()->ScarpetExplosionActors.entity(eq(boss),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   var method=WitherBoss.class.getDeclaredMethod("customServerAiStep",ServerLevel.class);method.setAccessible(true);method.invoke(boss,world);assertEquals(List.of("actual explosion"),order);assertTrue(ScarpetExplosionContinuations.pending(boss));boss.tickCount=11;method.invoke(boss,world);verify(plugins,times(1)).callEvent(any());
   explosion.complete(null);assertEquals(List.of("actual explosion","actual sound receipts"),order);assertTrue(ScarpetExplosionContinuations.pending(boss));sounds.complete(null);assertEquals(List.of("actual explosion","actual sound receipts","actual invulnerability commit","actual heal"),order);assertFalse(ScarpetExplosionContinuations.pending(boss));assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
  }
 }
}
