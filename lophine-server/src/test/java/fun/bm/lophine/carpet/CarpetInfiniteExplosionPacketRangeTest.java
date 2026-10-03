package fun.bm.lophine.carpet;
import carpet.script.external.*;
import java.util.*;
import net.minecraft.core.particles.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.server.players.*;
import net.minecraft.server.network.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.level.*;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CarpetInfiniteExplosionPacketRangeTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
 @Test void acceptedPositiveInfinityActuallySendsToFarOwnedRecipientsInSameWorld() throws Exception{
  double previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange;
  try(var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
   fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange=Double.POSITIVE_INFINITY;
   var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);var list=mock(PlayerList.class);when(server.getPlayerList()).thenReturn(list);
   var far=mock(ServerPlayer.class);var foreign=mock(ServerPlayer.class);var field=PlayerList.class.getField("realPlayers");field.setAccessible(true);field.set(list,List.of(far,foreign));far.connection=mock(ServerGamePacketListenerImpl.class);foreign.connection=mock(ServerGamePacketListenerImpl.class);
   when(far.level()).thenReturn(world);when(far.distanceToSqr(Vec3.ZERO)).thenReturn(1.0E18);when(foreign.level()).thenReturn(mock(ServerLevel.class));
   var explosion=mock(ServerExplosion.class);when(explosion.level()).thenReturn(world);when(explosion.center()).thenReturn(Vec3.ZERO);when(explosion.radius()).thenReturn(4F);when(explosion.getDamageSource()).thenReturn(mock(DamageSource.class));when(explosion.carpetHitPlayerIds()).thenReturn(Map.of());
   var global=mock(io.papermc.paper.threadedregions.RegionizedServer.class);doAnswer(invocation->{((Runnable)invocation.getArgument(0)).run();return null;}).when(global).addTask(any());globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);
   ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),any(net.minecraft.core.BlockPos.class))).thenReturn(true);ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(any(net.minecraft.world.entity.Entity.class))).thenReturn(true);
   var completed=ScarpetExplosionPackets.send(explosion,3,ParticleTypes.EXPLOSION,ParticleTypes.EXPLOSION_EMITTER,net.minecraft.util.random.WeightedList.of(),net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);completed.join();verify(far.connection).send(any(ClientboundExplodePacket.class));verifyNoInteractions(foreign.connection);
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange=previous;}
 }
 @Test void infiniteSynchronousFootprintRefusesBeforeEffectsAndKeepsConfiguredValue(){
  double previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange;
  try{
   fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange=Double.POSITIVE_INFINITY;assertEquals(Double.POSITIVE_INFINITY,CarpetSynchronousExplosionPreflight.packetRange());
   var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(mock(MinecraftServer.class));var refused=assertThrows(CarpetSynchronousExplosionScope.Unavailable.class,()->CarpetSynchronousExplosionPreflight.require(world,null,null,null,Vec3.ZERO,4F));assertFalse(refused.nativeStarted());assertTrue(refused.getMessage().contains("createExplosionAsync"));verify(world,never()).getChunkIfLoaded(anyInt(),anyInt());verify(world,never()).getEntities(nullable(net.minecraft.world.entity.Entity.class),any(),any(java.util.function.Predicate.class));
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionPacketRange=previous;}
 }
 @Test void actualOriginalNonnegativeParserContinuesAcceptingPositiveInfinity(){assertEquals(Double.POSITIVE_INFINITY,((Number)CarpetRuleRegistry.get("explosionPacketRange").parse("Infinity")).doubleValue());}
}
