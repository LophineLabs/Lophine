package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.*;
import org.bukkit.craftbukkit.entity.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Gate, NativeWork and native target admission; only scheduler envelopes are mocked. */
public class ScarpetExplosionAdmissionTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);final ServerLevel world=mock(ServerLevel.class);final ServerPlayer target=mock(ServerPlayer.class);
        final ArrayDeque<Consumer<Entity>> tasks=new ArrayDeque<>();
        final MockedStatic<TickThread> ticks=mockStatic(TickThread.class);final MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);final MockedStatic<org.bukkit.Bukkit> bukkit=mockStatic(org.bukkit.Bukkit.class);
        Fixture()throws Exception {
            servers.when(MinecraftServer::getServer).thenReturn(server);ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            when(world.getServer()).thenReturn(server);when(target.level()).thenReturn(world);when(target.blockPosition()).thenReturn(BlockPos.ZERO);when(target.getUUID()).thenReturn(UUID.randomUUID());
            CraftServer craft=mock(CraftServer.class);when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            var f=MinecraftServer.class.getField("server");f.setAccessible(true);f.set(server,craft);
            CraftPlayer player=mock(CraftPlayer.class);when(target.getBukkitEntity()).thenReturn(player);var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var sf=CraftEntity.class.getField("taskScheduler");sf.setAccessible(true);sf.set(player,scheduler);
            when(scheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{tasks.add(call.getArgument(0));return true;});
        }
        @Override public void close(){ScarpetRuntime.beginShutdown(server,()->{});bukkit.close();servers.close();ticks.close();}
    }
    @Test void targetSnapshotWaitsForTheActualWholeNativePhaseAfterItsHurtSubphaseEnds()throws Exception {
        try(Fixture f=new Fixture()) {
            CompletableFuture<Void> laterPhysicalTail=new CompletableFuture<>();AtomicInteger snapshots=new AtomicInteger();
            var whole=ScarpetNativeWork.observeNative(null,()->{
                ScarpetNativeWork.record(laterPhysicalTail);
                return ScarpetExplosionActors.admitTarget(f.target,()->{
                    assertFalse(ScarpetPlayerInventoryGate.paused(f.target));assertTrue(ScarpetPlayerInventoryGate.captureAccepted().contains(f.target));
                    return CompletableFuture.completedFuture(true);
                });
            });
            assertFalse(whole.isDone());
            var snapshot=ScarpetPlayerInventoryGate.whenIdle(f.target,()->{snapshots.incrementAndGet();return 42;});
            assertFalse(snapshot.isDone());assertEquals(0,snapshots.get());
            laterPhysicalTail.complete(null);assertEquals(1,f.tasks.size());f.tasks.removeFirst().accept(f.target);
            assertEquals(42,snapshot.get(3,TimeUnit.SECONDS));assertTrue(whole.get(3,TimeUnit.SECONDS).get());assertEquals(1,snapshots.get());
        }
    }
    @Test void aPausedTargetDoesNotRegisterTheNewWholePhaseUntilItsRealAdmission()throws Exception {
        try(Fixture f=new Fixture()) {
            CompletableFuture<Integer> saving=new CompletableFuture<>();var snapshot=ScarpetPlayerInventoryGate.<Object>whenIdle(f.target,()->saving);
            assertTrue(ScarpetPlayerInventoryGate.paused(f.target));assertEquals(1,f.tasks.size());f.tasks.removeFirst().accept(f.target);
            AtomicInteger entered=new AtomicInteger();CompletableFuture<Boolean> effects=new CompletableFuture<>();
            var whole=ScarpetNativeWork.observeNative(null,()->ScarpetExplosionActors.admitTarget(f.target,()->{
                entered.incrementAndGet();assertFalse(ScarpetPlayerInventoryGate.paused(f.target));assertTrue(ScarpetPlayerInventoryGate.captureAccepted().contains(f.target));return effects;
            }));
            assertFalse(whole.isDone());assertEquals(0,entered.get());
            saving.complete(7);assertSame(saving,snapshot.get(3,TimeUnit.SECONDS));assertEquals(0,entered.get());
            assertEquals(1,f.tasks.size());f.tasks.removeFirst().accept(f.target);
            assertEquals(1,entered.get());assertFalse(whole.isDone());effects.complete(true);
            assertTrue(whole.get(3,TimeUnit.SECONDS).get(3,TimeUnit.SECONDS));
        }
    }
    @Test void aRejectedEntitySchedulerTerminatesTheRecordedNativeObserver()throws Exception {
        try(Fixture f=new Fixture()) {
            f.ticks.when(()->TickThread.isTickThreadFor(f.target)).thenReturn(false);
            var rejection=new java.util.concurrent.RejectedExecutionException("actor stopped");
            var scheduler=f.target.getBukkitEntity().taskScheduler;doThrow(rejection).when(scheduler).schedule(any(),any(),anyLong());
            var whole=ScarpetNativeWork.observeNative(null,()->ScarpetExplosionActors.entity(f.target,()->true));
            var failure=assertThrows(ExecutionException.class,()->whole.get(3,TimeUnit.SECONDS));
            assertSame(rejection,failure.getCause());
        }
    }
}
