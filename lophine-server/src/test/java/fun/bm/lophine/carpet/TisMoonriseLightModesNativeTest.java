package fun.bm.lophine.carpet;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.concurrentutil.executor.queue.AreaDependentQueue;
import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class TisMoonriseLightModesNativeTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 private static StarLightInterface.ServerLightQueue queue()throws Exception{
  var world=mock(ServerLevel.class);var scheduler=mock(ChunkTaskScheduler.class);when(world.moonrise$getChunkTaskScheduler()).thenReturn(scheduler);var area=mock(AreaDependentQueue.class);var f=ChunkTaskScheduler.class.getField("radiusAwareScheduler");f.setAccessible(true);f.set(scheduler,area);when(area.createTask(anyInt(),anyInt(),anyInt(),any(Runnable.class),any(Priority.class))).thenAnswer(call->mock(PrioritisedExecutor.PrioritisedTask.class));
  var engine=mock(StarLightInterface.class);when(engine.getWorld()).thenReturn(world);return new StarLightInterface.ServerLightQueue(engine);
 }
 @Test void ignoredModeDropsBlockChangesButAcceptsOriginalImportantLightTask()throws Exception{
  String old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates="ignored";var queue=queue();assertNull(queue.queueBlockChange(BlockPos.ZERO));var body=new AtomicInteger();var task=queue.queueChunkLightTask(new ChunkPos(0,0),()->{body.incrementAndGet();return true;},Priority.NORMAL);assertNotNull(task);var pending=queue.carpetSnapshotPendingTasks();assertFalse(pending.isDone());task.run();pending.get(3,TimeUnit.SECONDS);assertEquals(1,body.get());assertTrue(queue.isEmpty());}
  finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates=old;}
 }
 @Test void offModeRejectsOrdinaryQueueKindsWithoutSchedulerAdmission()throws Exception{
  String old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates="off";var queue=queue();var section=SectionPos.of(0,0,0);var checks=new it.unimi.dsi.fastutil.shorts.ShortArrayList();assertNull(queue.queueBlockChange(BlockPos.ZERO));assertNull(queue.queueSectionChange(section,false));assertNull(queue.queueChunkSkylightEdgeCheck(section,checks));assertNull(queue.queueChunkBlocklightEdgeCheck(section,checks));assertTrue(queue.isEmpty());assertTrue(queue.carpetSnapshotPendingTasks().isDone());}
  finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates=old;}
 }
 @Test void suppressedModeAcceptsButPausesActualWorkerAndItsRealSnapshotUntilOn()throws Exception{pause("suppressed");}
 @Test void offModePausesPreviouslyAcceptedActualWorkerUntilIgnored()throws Exception{pause("off");}
 private void pause(String mode)throws Exception{
  String old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates;CompletableFuture<Void> executed=null;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates="on";var queue=queue();var body=new AtomicInteger();var task=queue.queueChunkLightTask(new ChunkPos(0,0),()->{body.incrementAndGet();return true;},Priority.NORMAL);var pending=queue.carpetSnapshotPendingTasks();fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates=mode;executed=CompletableFuture.runAsync(task);var work=executed;assertThrows(TimeoutException.class,()->work.get(50,TimeUnit.MILLISECONDS));assertFalse(pending.isDone());assertEquals(0,body.get());
   fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates=mode.equals("off")?"ignored":"on";executed.get(3,TimeUnit.SECONDS);pending.get(3,TimeUnit.SECONDS);assertEquals(1,body.get());assertTrue(queue.isEmpty());
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates="on";if(executed!=null)executed.get(3,TimeUnit.SECONDS);fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates=old;}
 }
}
