package carpet.script.external;
import java.util.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class ScarpetExplosionPacketsOwnerTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void actualFinalPacketUsesRealHitMapCountRangeAndOwnerWorld()throws Exception{
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);
  var players=mock(PlayerList.class);when(server.getPlayerList()).thenReturn(players);var list=PlayerList.class.getDeclaredField("realPlayers");list.setAccessible(true);
  var near=mock(ServerPlayer.class);var far=mock(ServerPlayer.class);var foreign=mock(ServerPlayer.class);list.set(players,List.of(near,far,foreign));
  for(var player:List.of(near,far,foreign)){player.connection=mock(ServerGamePacketListenerImpl.class);when(player.getUUID()).thenReturn(UUID.randomUUID());}
  when(near.getId()).thenReturn(123);when(far.getId()).thenReturn(456);when(foreign.getId()).thenReturn(789);
  when(near.level()).thenReturn(world);when(near.distanceToSqr(Vec3.ZERO)).thenReturn(4095D);when(far.level()).thenReturn(world);when(far.distanceToSqr(Vec3.ZERO)).thenReturn(4096D);when(foreign.level()).thenReturn(mock(ServerLevel.class));
  var impulse=new Vec3(1,2,3);var explosion=mock(ServerExplosion.class);when(explosion.level()).thenReturn(world);when(explosion.center()).thenReturn(Vec3.ZERO);when(explosion.radius()).thenReturn(4F);when(explosion.getDamageSource()).thenReturn(mock(DamageSource.class));when(explosion.getHitPlayers()).thenReturn(Map.of(near,impulse));when(explosion.carpetHitPlayerIds()).thenReturn(Map.of(123,impulse));
  var global=mock(io.papermc.paper.threadedregions.RegionizedServer.class);doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(global).addTask(any(Runnable.class));
  double previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange=64;
  try(var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
   globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);
   ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),any(net.minecraft.core.BlockPos.class))).thenReturn(true);
   ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(any(net.minecraft.world.entity.Entity.class))).thenReturn(true);
   var actual=ScarpetExplosionPackets.send(explosion,123,ParticleTypes.EXPLOSION,ParticleTypes.EXPLOSION_EMITTER,net.minecraft.util.random.WeightedList.of(),net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);assertTrue(actual.isDone());assertFalse(actual.isCompletedExceptionally());
   var captured=org.mockito.ArgumentCaptor.forClass(net.minecraft.network.protocol.Packet.class);verify(near.connection).send(captured.capture());var packet=(ClientboundExplodePacket)captured.getValue();
   assertEquals(123,packet.blockCount());assertEquals(Optional.of(impulse),packet.playerKnockback());assertEquals(4F,packet.radius());assertSame(ParticleTypes.EXPLOSION_EMITTER,packet.explosionParticle());assertTrue(packet.playSound());
   verifyNoInteractions(far.connection,foreign.connection);
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange=previous;}
 }
 @Test void realCancelledExplosionNeverSendsAPacketOrCapturesAnAudience(){
  var world=mock(ServerLevel.class);var explosion=mock(ServerExplosion.class);when(explosion.level()).thenReturn(world);when(explosion.center()).thenReturn(Vec3.ZERO);when(explosion.getDamageSource()).thenReturn(mock(DamageSource.class));explosion.wasCanceled=true;
  try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)){
   ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),any(net.minecraft.core.BlockPos.class))).thenReturn(true);
   var actual=ScarpetExplosionPackets.send(explosion,0,ParticleTypes.EXPLOSION,ParticleTypes.EXPLOSION_EMITTER,net.minecraft.util.random.WeightedList.of(),net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
   assertTrue(actual.isDone());assertFalse(actual.isCompletedExceptionally());globals.verifyNoInteractions();
  }
 }
}
