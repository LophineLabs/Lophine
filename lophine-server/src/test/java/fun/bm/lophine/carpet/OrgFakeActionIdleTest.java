package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetPlayerInventoryGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.CraftBot;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgFakeActionIdleTest {
    private static CarpetActionCompletion completion(ServerPlayer player) throws Exception {
        var method = OrgFakePlayerActions.class.getDeclaredMethod("completion", ServerPlayer.class);
        method.setAccessible(true);
        return (CarpetActionCompletion) method.invoke(null, player);
    }

    @Test
    void replacedPublicActionAndRealNativeHandTailBothFinishBeforeSnapshot() throws Exception {
        try (var fixture = new Fixture()) {
            var accepted = completion(fixture.player).begin();
            var nativeTail = new CompletableFuture<Void>();
            ScarpetPlayerInventoryGate.trackAccepted(fixture.player, nativeTail);
            OrgFakePlayerActions.set(fixture.player, OrgFakePlayerActions.Action.simple("fishing", List.of()));
            var value = new AtomicInteger();
            var saved = OrgFakePlayerActions.whenIdle(fixture.player, value::get);
            assertFalse(saved.isDone());
            assertDoesNotThrow(() -> OrgFakePlayerActions.tick(fixture.player));
            accepted.finish();
            assertFalse(saved.isDone());
            assertTrue(ScarpetPlayerInventoryGate.paused(fixture.player));
            value.set(7);
            nativeTail.complete(null);
            assertTrue(saved.isDone());
            assertTrue(fixture.tasks.isEmpty());
            assertEquals(7, saved.join());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
        }
    }

    @Test
    void cancellingCallerDoesNotUnpauseWhileActualNestedSnapshotRuns() throws Exception {
        try (var fixture = new Fixture()) {
            var child = new CompletableFuture<Integer>();
            OrgFakePlayerActions.set(fixture.player, OrgFakePlayerActions.Action.simple("fishing", List.of()));
            var saved = OrgFakePlayerActions.whenIdle(fixture.player, () -> child);
            fixture.run();
            assertFalse(saved.isDone());
            saved.cancel(false);
            assertTrue(ScarpetPlayerInventoryGate.paused(fixture.player));
            assertDoesNotThrow(() -> OrgFakePlayerActions.tick(fixture.player));
            child.complete(12);
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
            assertTrue(saved.isCancelled());
        }
    }

    @Test
    void failedAcceptedNativeWorkCannotProduceAPreFailureSnapshot() throws Exception {
        try (var fixture = new Fixture()) {
            var tail = new CompletableFuture<Void>();
            var captures = new AtomicInteger();
            ScarpetPlayerInventoryGate.trackAccepted(fixture.player, tail);
            var saved = OrgFakePlayerActions.whenIdle(fixture.player, captures::incrementAndGet);
            tail.completeExceptionally(new IllegalStateException("actual native failure"));
            fixture.run();
            assertTrue(saved.isCompletedExceptionally());
            assertEquals(0, captures.get());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
        }
    }

    @Test
    void mandatorySnapshotWaitsForBothRealFailedJobTerminationsThenCapturesLatestState() throws Exception {
        try (var fixture = new Fixture()) {
            var publicJob = completion(fixture.player).begin();
            var nativeTail = new CompletableFuture<Void>();
            ScarpetPlayerInventoryGate.trackAccepted(fixture.player, nativeTail);
            var value = new AtomicInteger();
            var saved = OrgFakePlayerActions.whenIdleForRemoval(fixture.player, value::get);
            publicJob.finish(new IllegalStateException("old guest cancelled"));
            fixture.run();
            assertFalse(saved.isDone());
            value.set(7);
            nativeTail.completeExceptionally(new IllegalStateException("actual native old tail terminated"));
            assertTrue(saved.isDone());
            assertTrue(fixture.tasks.isEmpty());
            assertEquals(7, saved.join());
        }
    }

    @Test
    void mandatorySnapshotStillPropagatesItsNewSaveFailure() throws Exception {
        try (var fixture = new Fixture()) {
            var old = completion(fixture.player).begin();
            var captures = new AtomicInteger();
            var saved = OrgFakePlayerActions.whenIdleForRemoval(fixture.player, () -> {
                captures.incrementAndGet();
                throw new IllegalStateException("new save failed");
            });
            old.finish(new IllegalArgumentException("old guest failed"));
            fixture.run();
            assertTrue(saved.isCompletedExceptionally());
            assertEquals(1, captures.get());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
        }
    }

    @Test
    void mandatorySnapshotRetainsPauseThroughARealNewSaveTailAndItsFailure() throws Exception {
        try (var fixture = new Fixture()) {
            var write = new CompletableFuture<Void>();
            var saved = OrgFakePlayerActions.whenIdleForRemoval(fixture.player, () -> write);
            fixture.run();
            assertFalse(saved.isDone());
            assertTrue(ScarpetPlayerInventoryGate.paused(fixture.player));
            write.completeExceptionally(new IllegalStateException("new IO readback unknown"));
            assertTrue(saved.isCompletedExceptionally());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ServerBot player = mock(ServerBot.class);
        final ArrayDeque<Consumer<Entity>> tasks = new ArrayDeque<>();
        final org.mockito.MockedStatic<TickThread> ticks;
        final org.mockito.MockedStatic<OrgHiddenPlayerActions> hidden;

        Fixture() throws Exception {
            var server = mock(net.minecraft.server.MinecraftServer.class);
            var level = mock(net.minecraft.server.level.ServerLevel.class);
            when(level.getServer()).thenReturn(server);
            when(player.level()).thenReturn(level);
            when(player.carpetSpawnServer()).thenReturn(server);
            CraftBot bukkit = mock(CraftBot.class);
            when(player.getBukkitEntity()).thenReturn(bukkit);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
            field.setAccessible(true);
            field.set(bukkit, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                tasks.add(call.getArgument(0));
                return true;
            });
            ticks = mockStatic(TickThread.class);
            org.mockito.MockedStatic<OrgHiddenPlayerActions> created = null;
            try {
                ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
                created = mockStatic(OrgHiddenPlayerActions.class);
                created.when(() -> OrgHiddenPlayerActions.whenIdle(eq(player), any())).thenAnswer(call -> CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(1)).get()));
                created.when(() -> OrgHiddenPlayerActions.whenIdleForRemoval(eq(player), any())).thenAnswer(call -> CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(1)).get()));
                hidden = created;
            } catch (RuntimeException | Error failure) {
                if (created != null) created.close();
                ticks.close();
                throw failure;
            }
        }

        void run() {
            while (!tasks.isEmpty()) tasks.remove().accept(player);
        }

        @Override
        public void close() {
            hidden.close();
            ticks.close();
        }
    }
}
