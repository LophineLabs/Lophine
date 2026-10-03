package fun.bm.lophine.carpet;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class TisLightSynchronizationSourceTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void nativeLightingWaitRetainsSourceProfilerAndRealPendingSnapshotOrder(){check(false,false);}
 @Test void failureFromActualLightSnapshotClosesBothNativeProfilerScopes(){check(true,false);}
 @Test void disabledRuleDoesNotTouchQueueOrEitherProfiler(){check(false,true);}
 void check(boolean failed,boolean disabled){
  boolean old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.synchronizedLightThread;String oldMode=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.synchronizedLightThread=!disabled;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates="on";
   var engine=mock(ThreadedLevelLightEngine.class,CALLS_REAL_METHODS);var star=mock(StarLightInterface.class);doReturn(star).when(engine).starlight$getLightEngine();var queue=mock(StarLightInterface.ServerLightQueue.class);when(star.getServerLightQueue()).thenReturn(queue);var profiler=mock(net.minecraft.util.profiling.ProfilerFiller.class);var order=new ArrayList<String>();
   doAnswer(call->{order.add("push:"+call.getArgument(0));return null;}).when(profiler).push(anyString());doAnswer(call->{order.add("pop");return null;}).when(profiler).pop();var failure=new IllegalStateException("actual lighting snapshot failed");when(queue.carpetSnapshotPendingTasks()).thenAnswer(call->{order.add("snapshot");if(failed)throw failure;return CompletableFuture.completedFuture(null);});
   try(var profiles=mockStatic(net.minecraft.util.profiling.Profiler.class);var observer=mockStatic(CarpetProfileObserver.class)){
    profiles.when(net.minecraft.util.profiling.Profiler::get).thenReturn(profiler);observer.when(()->CarpetProfileObserver.startDynamic(any())).thenAnswer(call->{order.add("start:"+((java.util.function.Supplier<?>)call.getArgument(0)).get());return 7;});observer.when(()->CarpetProfileObserver.stopTimer(7)).thenAnswer(call->{order.add("stop");return null;});
    if(failed&&!disabled)assertSame(failure,assertThrows(IllegalStateException.class,engine::carpetWaitForPendingTasks));else engine.carpetWaitForPendingTasks();
    if(disabled){assertTrue(order.isEmpty());verifyNoInteractions(star,queue,profiler);observer.verifyNoInteractions();profiles.verifyNoInteractions();}
    else assertEquals(List.of("push:Lighting synchronization","start:Lighting synchronization","snapshot","pop","stop"),order);
   }
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.synchronizedLightThread=old;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates=oldMode;}
 }
}