package fun.bm.lophine.carpet;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import carpet.script.external.ScarpetNativeWork;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class CarpetSynchronousExplosionScopeTest{
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void productionScopeReturnsOnlyActualSynchronousValue(){var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(mock(MinecraftServer.class));assertEquals(7,CarpetSynchronousExplosionScope.run(world,null,()->{assertTrue(CarpetSynchronousExplosionScope.active(world));return 7;}));assertFalse(CarpetSynchronousExplosionScope.active(world));}
 @Test void productionScopeRejectsProvisionalValueAndDrainsAlreadyAcceptedDynamicChild(){var world=mock(ServerLevel.class);var server=mock(MinecraftServer.class);when(world.getServer()).thenReturn(server);var child=new CompletableFuture<Void>();var effects=new java.util.concurrent.atomic.AtomicInteger();var rejected=assertThrows(CarpetSynchronousExplosionScope.Unavailable.class,()->CarpetSynchronousExplosionScope.run(world,null,()->{effects.incrementAndGet();ScarpetNativeWork.record(child);return true;}));assertTrue(rejected.nativeStarted());assertTrue(rejected.getMessage().contains("createExplosionAsync"));assertEquals(1,effects.get());assertFalse(ScarpetNativeWork.whenIdle(server).isDone());assertFalse(CarpetSynchronousExplosionScope.active(world));child.complete(null);assertTrue(ScarpetNativeWork.whenIdle(server).isDone());}
 @Test void actualNativeFailurePropagatesAndScopeAlwaysRestores(){var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(mock(MinecraftServer.class));assertThrows(CompletionException.class,()->CarpetSynchronousExplosionScope.run(world,null,()->{throw new IllegalStateException("actual native failure");}));assertFalse(CarpetSynchronousExplosionScope.active(world));}
}
