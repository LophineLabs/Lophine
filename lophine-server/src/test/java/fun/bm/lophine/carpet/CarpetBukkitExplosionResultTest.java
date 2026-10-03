package fun.bm.lophine.carpet;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.damagesource.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class CarpetBukkitExplosionResultTest{
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 private ServerLevel nativeWorld(MinecraftServer server)throws Exception{
  var world=mock(ServerLevel.class,CALLS_REAL_METHODS);var field=ServerLevel.class.getDeclaredField("server");field.setAccessible(true);field.set(world,server);doReturn(mock(GameRules.class)).when(world).getGameRules();doReturn(mock(LevelChunk.class)).when(world).getChunkIfLoaded(anyInt(),anyInt());doReturn(List.of()).when(world).getLocalPlayers();doReturn(List.of()).when(world).getEntities(nullable(net.minecraft.world.entity.Entity.class),any(),any(java.util.function.Predicate.class));
  var chunk=mock(LevelChunk.class);when(chunk.moonrise$getBlock(anyInt(),anyInt(),anyInt())).thenReturn(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());doReturn(chunk).when(world).getChunk(anyInt(),anyInt());
  var random=Level.class.getDeclaredField("random");random.setAccessible(true);random.set(world,mock(net.minecraft.util.RandomSource.class));return world;
 }
 @Test void actualBukkitSynchronousBooleanFollowsActualNativeBlockCancel()throws Exception{actualBukkit(true);}
 @Test void actualBukkitSynchronousBooleanFollowsCompletedNativeSuccess()throws Exception{actualBukkit(false);}
 private void actualBukkit(boolean cancel)throws Exception{
  var server=mock(MinecraftServer.class);var world=nativeWorld(server);var craft=mock(CraftWorld.class,CALLS_REAL_METHODS);var field=CraftWorld.class.getDeclaredField("world");field.setAccessible(true);field.set(craft,world);doReturn(craft).when(world).getWorld();
  var sources=mock(DamageSources.class);var damage=mock(DamageSource.class);doReturn(sources).when(world).damageSources();when(sources.explosion(nullable(net.minecraft.world.entity.Entity.class),nullable(net.minecraft.world.entity.Entity.class))).thenReturn(damage);
  var block=mock(org.bukkit.block.Block.class);doReturn(block).when(craft).getBlockAt(anyInt(),anyInt(),anyInt());when(block.getState()).thenReturn(mock(org.bukkit.block.BlockState.class));var cancelled=mock(org.bukkit.event.block.BlockExplodeEvent.class);when(cancelled.isCancelled()).thenReturn(cancel);when(cancelled.blockList()).thenReturn(List.of());
  doNothing().when(world).gameEvent(nullable(net.minecraft.world.entity.Entity.class),eq(net.minecraft.world.level.gameevent.GameEvent.EXPLODE),any(net.minecraft.world.phys.Vec3.class));
  try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);var events=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)){
   ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),anyInt(),anyInt())).thenReturn(true);
   events.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.callBlockExplodeEvent(eq(block),any(),anyList(),anyFloat(),eq(Explosion.BlockInteraction.DESTROY))).thenReturn(cancelled);
   assertEquals(!cancel,craft.createExplosion(0,0,0,0F,false,true,null));events.verify(()->org.bukkit.craftbukkit.event.CraftEventFactory.callBlockExplodeEvent(eq(block),any(),anyList(),anyFloat(),eq(Explosion.BlockInteraction.DESTROY)));assertTrue(carpet.script.external.ScarpetNativeWork.whenIdle(server).isDone());
  }
 }
 @Test void actualLegacyFactoryPreflightRejectsBeforeConstructingAnExplosion()throws Exception{
  var server=mock(MinecraftServer.class);var world=nativeWorld(server);try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);var constructions=mockConstruction(ServerExplosion.class)){
   assertThrows(IllegalStateException.class,()->world.explode0(null,null,null,0,0,0,4F,false,Level.ExplosionInteraction.NONE,net.minecraft.core.particles.ParticleTypes.EXPLOSION,net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER,Level.DEFAULT_EXPLOSION_BLOCK_PARTICLES,net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE));assertTrue(constructions.constructed().isEmpty());
  }
 }
 @Test void publicAsyncBooleanWaitsActualTypedResultAndKeepsRealJobAfterCallerCancel(){
  var craft=mock(CraftWorld.class);var world=mock(ServerLevel.class);when(craft.getHandle()).thenReturn(world);var server=mock(MinecraftServer.class);when(world.getServer()).thenReturn(server);var receipts=new CompletableFuture<Void>();var explosion=mock(ServerExplosion.class);
  var actual=carpet.script.external.ScarpetNativeWork.observeNative(null,()->{carpet.script.external.ScarpetNativeWork.record(receipts);return explosion;});var nativeView=carpet.script.external.ScarpetNativeWork.trackNative(server,actual);
  when(world.explode0Async(isNull(),isNull(),isNull(),eq(1D),eq(2D),eq(3D),eq(4F),eq(false),eq(Level.ExplosionInteraction.STANDARD),any(),any(),any(),any(),isNull())).thenReturn(nativeView);
  var visible=CarpetBukkitExplosions.createExplosionAsync(craft,1,2,3,4F,false,true,null);assertFalse(visible.isDone());visible.cancel(false);assertFalse(carpet.script.external.ScarpetNativeWork.whenIdle(server).isDone());receipts.complete(null);assertTrue(carpet.script.external.ScarpetNativeWork.whenIdle(server).isDone());assertTrue(visible.isCancelled());assertFalse(nativeView.isCancelled());
 }
 @Test void publicAsyncBooleanReportsActualCancellation(){var craft=mock(CraftWorld.class);var world=mock(ServerLevel.class);when(craft.getHandle()).thenReturn(world);var actual=new CompletableFuture<ServerExplosion>();when(world.explode0Async(isNull(),isNull(),isNull(),anyDouble(),anyDouble(),anyDouble(),anyFloat(),anyBoolean(),any(),any(),any(),any(),any(),isNull())).thenReturn(actual);
  var visible=CarpetBukkitExplosions.createExplosionAsync(craft,0,0,0,4F,false,false,null);assertFalse(visible.isDone());var explosion=mock(ServerExplosion.class);explosion.wasCanceled=true;actual.complete(explosion);assertFalse(visible.join());
 }
}
