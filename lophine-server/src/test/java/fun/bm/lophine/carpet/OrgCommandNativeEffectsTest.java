package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgCommandNativeEffectsTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }

    @Test void cancelledViewRetainsActualTeleportAndArrivalNativeChildrenOnTheArrivedOwner() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player();
            var trip = new CompletableFuture<Boolean>();
            var child = new CompletableFuture<Void>();
            var calls = new AtomicInteger();
            when(player.getBukkitEntity().teleportAsync(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.COMMAND)))
                .thenAnswer(call -> { assertSame(player, fixture.owner.get()); return trip; });
            var result = new AtomicReference<CompletableFuture<Boolean>>();
            var parent = ScarpetNativeWork.observeNative(null, () -> {
                result.set(OrgCommandNativeEffects.teleport(player, new Location(null, 17, 73, -23), () -> {
                    assertSame(player, fixture.owner.get());
                    calls.incrementAndGet(); ScarpetNativeWork.record(child);
                }));
                return null;
            });
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertTrue(result.get().cancel(false)); assertFalse(parent.isDone()); assertFalse(idle.isDone());
            fixture.drain(fixture.viewer); assertEquals(0, calls.get());
            trip.complete(true); fixture.drain(fixture.viewer);
            assertEquals(1, calls.get()); assertFalse(parent.isDone()); assertFalse(idle.isDone());
            child.complete(null); parent.join(); idle.join(); assertTrue(result.get().isCancelled());
        }
    }

    @Test void actualTeleportCancellationSkipsArrivalAndReturnsFalseAfterTheRealTrip() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player(); var trip = new CompletableFuture<Boolean>(); var calls = new AtomicInteger();
            when(player.getBukkitEntity().teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(trip);
            var result = OrgCommandNativeEffects.teleport(player, new Location(null, 2, 64, 5), calls::incrementAndGet);
            fixture.drain(fixture.viewer); assertFalse(result.isDone());
            trip.complete(false); assertFalse(result.join()); assertEquals(0, calls.get());
            ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }

    @Test void broadcastWaitsEveryActualRecipientAndItsDynamicallyCreatedNativeChildren() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var child = new CompletableFuture<Void>(); var delivered = new AtomicInteger();
            for (var actor : java.util.List.of(fixture.viewer, fixture.target)) {
                when(actor.player().blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
                doAnswer(call -> {
                    assertSame(actor.player(), fixture.owner.get()); delivered.incrementAndGet();
                    if (actor == fixture.target) ScarpetNativeWork.record(child);
                    return null;
                }).when(actor.player()).sendSystemMessage(any(Component.class));
            }
            var result = OrgCommandNativeEffects.broadcast(fixture.server, Component.literal("immutable position"));
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertTrue(result.cancel(false)); fixture.drain(fixture.viewer); assertEquals(1, delivered.get()); assertFalse(idle.isDone());
            fixture.drain(fixture.target); assertEquals(2, delivered.get()); assertFalse(idle.isDone());
            child.complete(null); idle.join();
        }
    }

    @Test void realFileJobFailureRetainsItsCauseInTheNativeParent() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
            var failure = new IllegalStateException("file receipt rejected");
            var caller = new AtomicReference<CompletableFuture<Integer>>();
            var parent = ScarpetNativeWork.observeNative(null, () -> {
                caller.set(OrgCommandNativeEffects.file(fixture.server, () -> {
                    entered.countDown();
                    try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch (InterruptedException problem) { throw new AssertionError(problem); }
                    throw failure;
                }));
                return null;
            });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(caller.get().cancel(false)); var idle = ScarpetNativeWork.whenIdle(fixture.server); assertFalse(idle.isDone());
            release.countDown();
            assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, parent::join).getCause());
            idle.handle((ignored, problem) -> null).join();
        }
    }
}
