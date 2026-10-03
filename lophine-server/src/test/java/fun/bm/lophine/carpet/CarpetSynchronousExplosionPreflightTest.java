package fun.bm.lophine.carpet;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import carpet.script.CarpetEventServer;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class CarpetSynchronousExplosionPreflightTest{
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 private ServerLevel world(){var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(mock(MinecraftServer.class));when(world.getEntities(nullable(net.minecraft.world.entity.Entity.class),any(),any(java.util.function.Predicate.class))).thenReturn(List.of());when(world.getLocalPlayers()).thenReturn(List.of());return world;}
 @Test void actualUnownedFootprintRejectsBeforeChunkReadsOrEffects(){var world=world();try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){assertThrows(IllegalStateException.class,()->CarpetSynchronousExplosionPreflight.require(world,null,null,null,Vec3.ZERO,4));verify(world,never()).getChunkIfLoaded(anyInt(),anyInt());verify(world,never()).getEntities(nullable(net.minecraft.world.entity.Entity.class),any(),any(java.util.function.Predicate.class));}}
 @Test void actualUnloadedFootprintRejectsBeforeEntityQueryOrEffects(){var world=world();try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),anyInt(),anyInt())).thenReturn(true);assertThrows(IllegalStateException.class,()->CarpetSynchronousExplosionPreflight.require(world,null,null,null,Vec3.ZERO,4));verify(world,never()).getEntities(nullable(net.minecraft.world.entity.Entity.class),any(),any(java.util.function.Predicate.class));}}
 @Test void actualScarpetExplosionCallbackRejectsBeforeAnyNativeFootprintWork()throws Exception{
  var world=world();var field=CarpetEventServer.CallbackList.class.getDeclaredField("callList");field.setAccessible(true);var handler=CarpetEventServer.Event.EXPLOSION.handler;var previous=field.get(handler);field.set(handler,new java.util.concurrent.CopyOnWriteArrayList<>(List.of(mock(CarpetEventServer.Callback.class))));
  try{assertThrows(IllegalStateException.class,()->CarpetSynchronousExplosionPreflight.require(world,null,null,null,Vec3.ZERO,4));verify(world,never()).getChunkIfLoaded(anyInt(),anyInt());}finally{field.set(handler,previous);}
 }
 @Test void actualPausedTargetRejectsBeforeAcceptedDamage(){var world=world();var target=mock(ServerPlayer.class);when(target.level()).thenReturn(world);when(world.getEntities(nullable(net.minecraft.world.entity.Entity.class),any(),any(java.util.function.Predicate.class))).thenReturn(List.of(target));when(world.getChunkIfLoaded(anyInt(),anyInt())).thenReturn(mock(LevelChunk.class));
  try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);var gate=mockStatic(carpet.script.external.ScarpetPlayerInventoryGate.class)){
   ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),anyInt(),anyInt())).thenReturn(true);ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(target)).thenReturn(true);gate.when(()->carpet.script.external.ScarpetPlayerInventoryGate.paused(target)).thenReturn(true);assertThrows(IllegalStateException.class,()->CarpetSynchronousExplosionPreflight.require(world,null,null,null,Vec3.ZERO,4));verify(target,never()).getInventory();
  }
 }
 @Test void actualForeignCauseRejectsBeforeItsMutableMetadataIsRead(){var world=world();var source=mock(net.minecraft.world.entity.Entity.class);when(world.getChunkIfLoaded(anyInt(),anyInt())).thenReturn(mock(LevelChunk.class));
  try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),anyInt(),anyInt())).thenReturn(true);var rejected=assertThrows(CarpetSynchronousExplosionScope.Unavailable.class,()->CarpetSynchronousExplosionPreflight.require(world,source,null,null,Vec3.ZERO,4));assertFalse(rejected.nativeStarted());verify(source,never()).level();}
 }
 @Test void unknownBlockEntityScopeRejectsBeforeNativeBody(){var world=world();var chunk=mock(LevelChunk.class);when(world.getChunkIfLoaded(anyInt(),anyInt())).thenReturn(chunk);when(chunk.getBlockEntities()).thenReturn(Map.of(net.minecraft.core.BlockPos.ZERO,mock(net.minecraft.world.level.block.entity.BlockEntity.class)));
  try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),anyInt(),anyInt())).thenReturn(true);var rejected=assertThrows(CarpetSynchronousExplosionScope.Unavailable.class,()->CarpetSynchronousExplosionPreflight.require(world,null,null,null,Vec3.ZERO,4));assertFalse(rejected.nativeStarted());verify(world,never()).getEntities(nullable(net.minecraft.world.entity.Entity.class),any(),any(java.util.function.Predicate.class));}
 }
}
