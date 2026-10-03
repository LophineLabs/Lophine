package fun.bm.lophine.carpet;
import java.util.concurrent.atomic.*;
import net.minecraft.core.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.ticks.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real ServerLevel limit arguments feed actual LevelTicks queues; world/owner envelopes are controlled. */
public class CarpetTileTickLimitTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
 static final class Finished extends RuntimeException{}
 static void verifyLimit(int configured,int pending,int expected) throws Exception {
  int previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tileTickLimit;
  try(var scheduler=mockStatic(io.papermc.paper.threadedregions.TickRegionScheduler.class)){
   fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tileTickLimit=configured;
   var world=mock(ServerLevel.class,invocation->invocation.getMethod().getName().equals("tickTime")?null:CALLS_REAL_METHODS.answer(invocation));var server=mock(MinecraftServer.class);var serverField=ServerLevel.class.getDeclaredField("server");serverField.setAccessible(true);serverField.set(world,server);
   var data=mock(io.papermc.paper.threadedregions.RegionizedWorldData.class);doReturn(data).when(world).getCurrentWorldData();when(data.getLocalPlayers()).thenReturn(java.util.List.of());
   var cache=mock(ServerChunkCache.class);doReturn(cache).when(world).getChunkSource();when(cache.getLightEngine()).thenReturn(mock(ThreadedLevelLightEngine.class));
   var attributes=mock(net.minecraft.world.attribute.EnvironmentAttributeSystem.class);doReturn(attributes).when(world).environmentAttributes();
   doNothing().when(world).resetEmptyTime();doReturn(false).when(world).isDebug();doReturn(0L).when(world).getRedstoneGameTime();
   var rate=mock(net.minecraft.server.ServerTickRateManager.class);when(rate.runsNormally()).thenReturn(true);doReturn(rate).when(world).tickRateManager();
   scheduler.when(io.papermc.paper.threadedregions.TickRegionScheduler::getProfiler).thenReturn(mock(ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle.class));
   var paper=mock(io.papermc.paper.configuration.WorldConfiguration.class);var envField=io.papermc.paper.configuration.WorldConfiguration.class.getField("environment");
   Object environment=mock(envField.getType());envField.set(paper,environment);envField.getType().getField("maxBlockTicks").setInt(environment,1);envField.getType().getField("maxFluidTicks").setInt(environment,1);doReturn(paper).when(world).paperConfig();
   var blocks=new LevelTicks<Block>(ignored->true,world,true);var fluids=new LevelTicks<Fluid>(ignored->true,world,false);
   var blockContainer=new LevelChunkTicks<Block>();var fluidContainer=new LevelChunkTicks<Fluid>();
   for(int i=0;i<pending;i++){blockContainer.schedule(new ScheduledTick<>(Blocks.STONE,new BlockPos(0,i,0),0L,i));fluidContainer.schedule(new ScheduledTick<>(Fluids.WATER,new BlockPos(0,i,0),0L,i));}
   blocks.addContainer(new ChunkPos(0,0),blockContainer);fluids.addContainer(new ChunkPos(0,0),fluidContainer);
   var blockRun=new AtomicInteger();var fluidRun=new AtomicInteger();var blockLimit=new AtomicInteger();var fluidLimit=new AtomicInteger();
   LevelTicks<Block> blockEntry=mock(LevelTicks.class);LevelTicks<Fluid> fluidEntry=mock(LevelTicks.class);when(data.getBlockLevelTicks()).thenReturn(blockEntry);when(data.getFluidLevelTicks()).thenReturn(fluidEntry);
   doAnswer(invocation->{int limit=invocation.getArgument(1);blockLimit.set(limit);blocks.tick(invocation.getArgument(0),limit,(pos,type)->blockRun.incrementAndGet());return null;}).when(blockEntry).tick(anyLong(),anyInt(),any());
   doAnswer(invocation->{int limit=invocation.getArgument(1);fluidLimit.set(limit);fluids.tick(invocation.getArgument(0),limit,(pos,type)->fluidRun.incrementAndGet());throw new Finished();}).when(fluidEntry).tick(anyLong(),anyInt(),any());
   assertThrows(Finished.class,()->world.tick(()->true,null));assertEquals(configured,blockLimit.get());assertEquals(configured,fluidLimit.get());assertEquals(expected,blockRun.get());assertEquals(expected,fluidRun.get());assertEquals(pending-expected,blockContainer.count());assertEquals(pending-expected,fluidContainer.count());
  } finally {fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tileTickLimit=previous;}
 }
 @Test void explicitVanillaValueReallyProcessesRuleLimitDespiteSmallerPaperLimits() throws Exception{verifyLimit(65536,3,3);}
 @Test void configuredLowerLimitActuallyLeavesBothNativeQueuesPending() throws Exception{verifyLimit(2,4,2);}
}
