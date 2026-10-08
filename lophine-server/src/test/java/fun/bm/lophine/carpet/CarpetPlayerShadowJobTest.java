package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CarpetPlayerShadowJobTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void shutdownUnblocksAPollWithoutCompletingItsAlreadyAcceptedSnapshot() {
        var server = mock(MinecraftServer.class);
        var job = new CarpetPlayerShadowJob(server);
        var snapshot = new CompletableFuture<Void>();
        job.finish(snapshot.thenCompose(ignored -> job.disconnected));
        var nativeIdle = ScarpetNativeWork.whenIdle(server);
        var birthIdle = CarpetPlayerBirths.whenIdle(server);
        CarpetPlayerBirths.beginDrain(server);
        assertTrue(job.disconnected.isCompletedExceptionally());
        assertFalse(nativeIdle.isDone());
        assertFalse(birthIdle.isDone());
        assertThrows(IllegalStateException.class, job::beginPlacement);
        snapshot.complete(null);
        assertThrows(CompletionException.class, job.actual::join);
        assertTrue(nativeIdle.isDone());
        assertTrue(birthIdle.isDone());
    }

    @Test
    void shutdownDrainsAnAdmittedPlacementAndKeepsTheUuidFileLeaseUntilItsRealTailEnds() {
        var server = mock(MinecraftServer.class);
        UUID id = UUID.randomUUID();
        var job = new CarpetPlayerShadowJob(server);
        var placement = new CompletableFuture<Void>();
        job.disconnected.complete(null);
        job.finish(OrgPlayerFileLease.withLease(server, id, "shadow regression", lease -> {
            job.beginPlacement();
            return placement;
        }));
        var fileIdle = OrgPlayerFileLease.whenAvailable(server, id);
        var nativeIdle = ScarpetNativeWork.whenIdle(server);
        var birthIdle = CarpetPlayerBirths.whenIdle(server);
        CarpetPlayerBirths.beginDrain(server);
        assertFalse(job.actual.isDone());
        assertFalse(fileIdle.isDone());
        assertFalse(nativeIdle.isDone());
        assertFalse(birthIdle.isDone());
        placement.complete(null);
        job.actual.join();
        assertTrue(fileIdle.isDone());
        assertTrue(nativeIdle.isDone());
        assertTrue(birthIdle.isDone());
    }

    @Test
    void placementFailureReleasesFileAdmissionAndFinishesTheBirthWithItsNativeFailure() {
        var server = mock(MinecraftServer.class);
        UUID id = UUID.randomUUID();
        var job = new CarpetPlayerShadowJob(server);
        var placement = new CompletableFuture<Void>();
        job.disconnected.complete(null);
        job.finish(OrgPlayerFileLease.withLease(server, id, "shadow regression", lease -> {
            job.beginPlacement();
            return placement;
        }));
        var problem = new IllegalStateException("native shadow join failed");
        placement.completeExceptionally(problem);
        assertSame(problem, assertThrows(CompletionException.class, job.actual::join).getCause());
        assertFalse(OrgPlayerFileLease.busy(server, id));
        assertTrue(CarpetPlayerBirths.whenIdle(server).isDone());
        ScarpetNativeWork.whenIdle(server).handle((ignored, failure) -> null).join();
    }
}
