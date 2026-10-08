package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetNativeWork;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgSuicideCommandCompletionTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private static DamageSource generic() {
        return new DamageSource(Holder.direct(new DamageType("genericKill", DamageScaling.NEVER, 0F)));
    }

    @Test
    void theExactPlayerAndDamageSourceRetainSuicideIdentityUntilTheActualDeferredDeathTail() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var damage = mockStatic(ScarpetDamageContinuations.class)) {
            var player = fixture.target.player();
            var world = player.level();
            assertNotNull(world);
            fixture.owner.set(player);
            var original = generic();
            var exact = new AtomicReference<DamageSource>();
            var published = new AtomicReference<CompletableFuture<Boolean>>();
            var body = new CompletableFuture<Boolean>();
            damage.when(() -> ScarpetDamageContinuations.pendingResult(player)).thenAnswer(call -> published.get());
            doAnswer(call -> {
                exact.set(OrgSuicideCommand.source(player, original));
                assertTrue(OrgSuicideCommand.synchronous());
                published.set(body);
                ScarpetNativeWork.record(body);
                return null;
            }).when(player).kill(world);
            var actual = OrgSuicideCommand.kill(player);
            assertFalse(actual.isDone());
            assertNotSame(original, exact.get());
            assertSame(original.typeHolder(), exact.get().typeHolder());
            assertFalse(OrgSuicideCommand.synchronous());
            assertTrue(OrgSuicideCommand.committing(player, exact.get()));
            assertFalse(OrgSuicideCommand.committing(fixture.viewer.player(), exact.get()));
            assertFalse(OrgSuicideCommand.committing(player, original));
            assertSame(original, OrgSuicideCommand.source(fixture.viewer.player(), original));
            var cancelledCaller = actual.copy();
            assertTrue(cancelledCaller.cancel(false));
            assertTrue(OrgSuicideCommand.committing(player, exact.get()));
            body.complete(true);
            actual.join();
            assertFalse(OrgSuicideCommand.committing(player, exact.get()));
        }
    }

    @Test
    void allActualNativeChildrenMustEndAndARealNativeFailureKeepsItsFailureClassification() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var damage = mockStatic(ScarpetDamageContinuations.class)) {
            var player = fixture.target.player();
            var world = player.level();
            assertNotNull(world);
            fixture.owner.set(player);
            var original = generic();
            var exact = new AtomicReference<DamageSource>();
            var published = new AtomicReference<CompletableFuture<Boolean>>();
            var body = new CompletableFuture<Boolean>();
            var nativeCleanup = new CompletableFuture<Void>();
            damage.when(() -> ScarpetDamageContinuations.pendingResult(player)).thenAnswer(call -> published.get());
            doAnswer(call -> {
                exact.set(OrgSuicideCommand.source(player, original));
                published.set(body);
                ScarpetNativeWork.record(body);
                ScarpetNativeWork.record(nativeCleanup);
                return null;
            }).when(player).kill(world);
            var actual = OrgSuicideCommand.kill(player);
            body.complete(true);
            assertFalse(actual.isDone());
            assertTrue(OrgSuicideCommand.committing(player, exact.get()));
            nativeCleanup.completeExceptionally(new java.io.IOException("Actual death native cleanup failed"));
            var failure = assertThrows(java.util.concurrent.CompletionException.class, actual::join);
            assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
            assertFalse(OrgSuicideCommand.committing(player, exact.get()));
        }
    }

    @Test
    void aQueuedCommandRegistersItsActualBarrierAndReportsItsResultOnlyAfterThePhysicalTail() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var damage = mockStatic(ScarpetDamageContinuations.class); var commands = CarpetAsyncCommandResults.open()) {
            var player = fixture.target.player();
            var world = player.level();
            assertNotNull(world);
            fixture.owner.set(null);
            var source = mock(CommandSourceStack.class);
            when(source.getServer()).thenReturn(fixture.server);
            when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
            var body = new CompletableFuture<Boolean>();
            var pending = new AtomicReference<CompletableFuture<Boolean>>();
            damage.when(() -> ScarpetDamageContinuations.pendingResult(player)).thenAnswer(call -> pending.get());
            doAnswer(call -> {
                OrgSuicideCommand.source(player, generic());
                pending.set(body);
                ScarpetNativeWork.record(body);
                return null;
            }).when(player).kill(world);
            assertEquals(1, OrgSuicideCommand.execute(source, player));
            var result = commands.resultFuture(source);
            assertNotNull(result);
            assertFalse(result.isDone());
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            fixture.drain(fixture.target);
            assertFalse(result.isDone());
            assertFalse(idle.isDone());
            body.complete(true);
            assertEquals(1, result.join());
            idle.join();
        }
    }
}
