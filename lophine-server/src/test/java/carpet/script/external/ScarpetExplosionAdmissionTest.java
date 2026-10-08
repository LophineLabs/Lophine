package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Actual Gate, NativeWork and native target admission; only scheduler envelopes are mocked.
 */
public class ScarpetExplosionAdmissionTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final ServerPlayer target = mock(ServerPlayer.class);
        final AtomicBoolean owned = new AtomicBoolean(true);
        final ArrayDeque<Consumer<Entity>> tasks = new ArrayDeque<>();
        final MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
        final MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class);

        Fixture() throws Exception {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> owned.get());
            when(world.getServer()).thenReturn(server);
            when(target.level()).thenReturn(world);
            when(target.blockPosition()).thenReturn(BlockPos.ZERO);
            when(target.getUUID()).thenReturn(UUID.randomUUID());
            when(target.carpetSpawnServer()).thenReturn(server);
            CraftServer craft = mock(CraftServer.class);
            when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            var f = MinecraftServer.class.getField("server");
            f.setAccessible(true);
            f.set(server, craft);
            CraftPlayer player = mock(CraftPlayer.class);
            when(target.getBukkitEntity()).thenReturn(player);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var sf = CraftEntity.class.getField("taskScheduler");
            sf.setAccessible(true);
            sf.set(player, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                tasks.add(call.getArgument(0));
                return true;
            });
        }

        void tick() {
            var task = tasks.removeFirst();
            boolean previous = owned.getAndSet(true);
            try {
                task.accept(target);
            } finally {
                owned.set(previous);
            }
        }

        @Override
        public void close() {
            ScarpetRuntime.beginShutdown(server, () -> {
            });
            bukkit.close();
            servers.close();
            ticks.close();
        }
    }

    @Test
    void targetSnapshotWaitsForTheActualWholeNativePhaseAfterItsHurtSubphaseEnds() throws Exception {
        try (Fixture f = new Fixture()) {
            CompletableFuture<Void> laterPhysicalTail = new CompletableFuture<>();
            AtomicInteger snapshots = new AtomicInteger();
            var whole = ScarpetNativeWork.observeNative(null, () -> {
                ScarpetNativeWork.record(laterPhysicalTail);
                return ScarpetExplosionActors.admitTarget(f.target, () -> {
                    assertFalse(ScarpetPlayerInventoryGate.paused(f.target));
                    assertTrue(ScarpetPlayerInventoryGate.captureAccepted().contains(f.target));
                    return CompletableFuture.completedFuture(true);
                });
            });
            assertFalse(whole.isDone());
            var snapshot = ScarpetPlayerInventoryGate.whenIdle(f.target, () -> {
                assertTrue(f.owned.get());
                snapshots.incrementAndGet();
                return 42;
            });
            assertFalse(snapshot.isDone());
            assertEquals(0, snapshots.get());
            laterPhysicalTail.complete(null);
            assertTrue(f.tasks.isEmpty(), "Owned snapshot must not add an artificial actor tick");
            assertEquals(1, snapshots.get());
            assertEquals(42, snapshot.get(3, TimeUnit.SECONDS));
            assertTrue(whole.get(3, TimeUnit.SECONDS).get());
            assertEquals(1, snapshots.get());
        }
    }

    @Test
    void aPausedTargetDoesNotRegisterTheNewWholePhaseUntilItsRealAdmission() throws Exception {
        try (Fixture f = new Fixture()) {
            CompletableFuture<Integer> saving = new CompletableFuture<>();
            AtomicInteger snapshots = new AtomicInteger();
            var snapshot = ScarpetPlayerInventoryGate.<Object>whenIdle(f.target, () -> {
                assertTrue(f.owned.get());
                snapshots.incrementAndGet();
                return saving;
            });
            assertTrue(ScarpetPlayerInventoryGate.paused(f.target));
            assertEquals(1, snapshots.get());
            assertTrue(f.tasks.isEmpty());
            assertFalse(snapshot.isDone());
            AtomicInteger entered = new AtomicInteger();
            CompletableFuture<Boolean> effects = new CompletableFuture<>();
            var whole = ScarpetNativeWork.observeNative(null, () -> ScarpetExplosionActors.admitTarget(f.target, () -> {
                assertTrue(f.owned.get());
                entered.incrementAndGet();
                assertFalse(ScarpetPlayerInventoryGate.paused(f.target));
                assertTrue(ScarpetPlayerInventoryGate.captureAccepted().contains(f.target));
                return effects;
            }));
            if (whole.isCompletedExceptionally()) whole.get(3, TimeUnit.SECONDS);
            assertFalse(whole.isDone());
            assertEquals(0, entered.get());
            f.owned.set(false);
            saving.complete(7);
            assertSame(saving, snapshot.get(3, TimeUnit.SECONDS));
            assertEquals(0, entered.get());
            assertEquals(1, f.tasks.size());
            f.tick();
            assertEquals(1, entered.get());
            assertFalse(whole.isDone());
            effects.complete(true);
            assertTrue(whole.get(3, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void aRejectedEntitySchedulerTerminatesTheRecordedNativeObserver() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ticks.when(() -> TickThread.isTickThreadFor(f.target)).thenReturn(false);
            var rejection = new java.util.concurrent.RejectedExecutionException("actor stopped");
            var scheduler = f.target.getBukkitEntity().taskScheduler;
            doThrow(rejection).when(scheduler).schedule(any(), any(), anyLong());
            var whole = ScarpetNativeWork.observeNative(null, () -> ScarpetExplosionActors.entity(f.target, () -> true));
            var failure = assertThrows(ExecutionException.class, () -> whole.get(3, TimeUnit.SECONDS));
            assertSame(rejection, failure.getCause());
        }
    }
}
