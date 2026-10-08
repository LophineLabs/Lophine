package fun.bm.lophine.carpet;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OrgPlayerFileLeaseTest {
    @Test
    void realLoginLoadJoinCompletionSerializesOfflineEditingAndLaterLoginInOrder() {
        var server = mock(MinecraftServer.class);
        UUID player = UUID.randomUUID();
        var events = new ArrayList<String>();
        var join = new CompletableFuture<Boolean>();
        var edit = new CompletableFuture<Boolean>();
        var login = OrgPlayerFileLease.withLease(server, player, "login", lease -> {
            assertTrue(lease.active());
            events.add("load");
            return join;
        });
        var inventory = OrgPlayerFileLease.withLease(server, player, "offline inventory", lease -> {
            events.add("edit");
            return edit;
        });
        var next = OrgPlayerFileLease.withLease(server, player, "next login", lease -> {
            events.add("next load");
            return CompletableFuture.completedFuture(true);
        });
        assertEquals(List.of("load"), events);
        assertFalse(inventory.isDone());
        join.complete(true);
        assertTrue(login.join());
        assertEquals(List.of("load", "edit"), events);
        assertFalse(next.isDone());
        edit.complete(true);
        assertTrue(inventory.join());
        assertTrue(next.join());
        assertEquals(List.of("load", "edit", "next load"), events);
        assertFalse(OrgPlayerFileLease.busy(server, player));
    }

    @Test
    void cancellationCannotReleaseAnUnfinishedActualFileOperation() {
        var server = mock(MinecraftServer.class);
        UUID player = UUID.randomUUID();
        var actual = new CompletableFuture<Boolean>();
        var first = OrgPlayerFileLease.withLease(server, player, "write/readback", lease -> actual);
        var available = OrgPlayerFileLease.whenAvailable(server, player);
        first.cancel(false);
        assertFalse(available.isDone());
        assertTrue(OrgPlayerFileLease.busy(server, player));
        actual.completeExceptionally(new IllegalStateException("operation aborted before any unverified write"));
        assertTrue(available.isDone());
        assertFalse(OrgPlayerFileLease.busy(server, player));
        assertTrue(first.isCancelled());
    }

    @Test
    void callbackFailureReleasesAdmissionWithoutHoldingTheMetadataMonitor() {
        var server = mock(MinecraftServer.class);
        UUID player = UUID.randomUUID();
        var failed = OrgPlayerFileLease.withLease(server, player, "failed login", lease -> {
            throw new IllegalArgumentException("wrong UUID");
        });
        assertTrue(failed.isCompletedExceptionally());
        assertTrue(OrgPlayerFileLease.withLease(server, player, "retry", lease -> CompletableFuture.completedFuture(true)).join());
        assertFalse(OrgPlayerFileLease.busy(server, player));
    }
}
