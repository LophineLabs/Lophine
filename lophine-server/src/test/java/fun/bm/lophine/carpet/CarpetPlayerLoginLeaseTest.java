package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarpetPlayerLoginLeaseTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test void regionConnectionCleanupWaitsForAsyncDisconnectTicket() {
        assertFalse(CarpetAsyncDisconnect.cleanupReady(false, null));
        assertTrue(CarpetAsyncDisconnect.cleanupReady(false, new ChunkPos(0, 0)));
        assertTrue(CarpetAsyncDisconnect.cleanupReady(true, null));
    }


    @Test void theFirstReadAndAllJoinChildrenHoldTheUuidAgainstOfflineEditingAndNextLogin() {
        var server = mock(MinecraftServer.class); var player = mock(ServerPlayer.class);
        var world=mock(net.minecraft.server.level.ServerLevel.class);when(player.level()).thenReturn(world);when(world.getServer()).thenReturn(server);
        UUID uuid = UUID.randomUUID(); var events = new ArrayList<String>();
        var read = new CompletableFuture<Void>(); var joinChild = new CompletableFuture<Void>();
        var login = new CarpetPlayerLoginLease(server, uuid);
        login.start(() -> { events.add("first native read"); return read; });
        var offline = OrgPlayerFileLease.withLease(server, uuid, "offline inventory", lease -> {
            events.add("offline read"); return CompletableFuture.completedFuture(true);
        });
        assertEquals(List.of("first native read"), events); read.complete(null);
        try (var tick = mockStatic(TickThread.class)) {
            assertSame(player, login.spawn(player, () -> {
                assertFalse(carpet.script.external.ScarpetPlayerInventoryGate.paused(player));
                events.add("actual placement"); ScarpetNativeWork.record(joinChild); return player;
            }));
            tick.verify(() -> TickThread.ensureTickThread(player, "Native player work must be admitted by its owner"), never());
        }
        var caller = login.completion(); var idle = ScarpetNativeWork.whenIdle(server); var births = CarpetPlayerBirths.whenIdle(server);
        assertTrue(CarpetPlayerBirths.playerPending(player));
        assertTrue(carpet.script.external.ScarpetNativeRemovals.tickPending(player));
        assertFalse(carpet.script.external.ScarpetNativeRemovals.isPending(player));
        assertTrue(carpet.script.external.ScarpetPlayerInventoryGate.paused(player));
        var reopened=carpet.script.external.ScarpetPlayerInventoryGate.whenOpen(player);
        caller.cancel(false); login.close();
        assertFalse(idle.isDone()); assertFalse(births.isDone()); assertFalse(offline.isDone());
        assertEquals(List.of("first native read", "actual placement"), events);
        joinChild.complete(null);
        assertTrue(offline.join()); assertTrue(idle.isDone()); assertTrue(births.isDone()); assertTrue(caller.isCancelled());
        assertTrue(reopened.isDone());assertFalse(CarpetPlayerBirths.playerPending(player));
        assertEquals(List.of("first native read", "actual placement", "offline read"), events);
    }

    @Test void disconnectDuringTheFirstReadCannotReleaseTheActualFilePhase() {
        var server = mock(MinecraftServer.class); UUID uuid = UUID.randomUUID();
        var read = new CompletableFuture<Void>(); var login = new CarpetPlayerLoginLease(server, uuid);
        login.start(() -> read); login.close();
        assertTrue(OrgPlayerFileLease.busy(server, uuid)); assertFalse(login.completion().isDone());
        read.complete(null); assertTrue(login.completion().isDone()); assertFalse(OrgPlayerFileLease.busy(server, uuid));
    }

    @Test void aDisconnectedQueuedLoginNeverPerformsItsFirstRead() {
        var server = mock(MinecraftServer.class); UUID uuid = UUID.randomUUID();
        var oldWrite = new CompletableFuture<Void>();
        OrgPlayerFileLease.withLease(server, uuid, "unknown file write/readback", lease -> oldWrite);
        var login = new CarpetPlayerLoginLease(server, uuid); var events = new ArrayList<String>();
        login.start(() -> { events.add("read"); return CompletableFuture.completedFuture(null); }); login.close();
        assertFalse(login.completion().isDone()); oldWrite.complete(null);
        assertTrue(login.completion().isDone()); assertTrue(events.isEmpty()); assertFalse(OrgPlayerFileLease.busy(server, uuid));
    }

    @Test void shutdownAbandonsPassiveConfigurationButWaitsForAnAlreadyRunningRead() {
        var server = mock(MinecraftServer.class); var read = new CompletableFuture<Void>();
        var reading = new CarpetPlayerLoginLease(server, UUID.randomUUID()); reading.start(() -> read);
        var passive = new CarpetPlayerLoginLease(server, UUID.randomUUID()); passive.start(() -> CompletableFuture.completedFuture(null));
        var births = CarpetPlayerBirths.whenIdle(server); CarpetPlayerBirths.beginDrain(server);
        assertTrue(passive.closed()); assertTrue(passive.completion().isDone()); assertFalse(births.isDone());
        read.complete(null); assertTrue(reading.closed()); assertTrue(births.isDone());
    }
}
