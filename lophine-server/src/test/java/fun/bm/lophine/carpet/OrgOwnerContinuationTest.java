package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OrgOwnerContinuationTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    @Test
    void queuedOwnerAndImmediateResultContinuationRetainTheAdmittingNativeCauseAndFlags() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var target = fixture.target.player();
            fixture.owner.set(null);
            var child = new CompletableFuture<Void>();
            var token = new AtomicReference<ScarpetNativeWork.Token>();
            boolean fill = ScarpetRuntime.FILL_SKIP_UPDATES.get(), events = ScarpetRuntime.EVENT_DISABLED.get();
            CompletableFuture<Integer> parent;
            try {
                ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
                ScarpetRuntime.EVENT_DISABLED.set(true);
                parent = ScarpetNativeWork.observeNative(fixture.viewer.player(), () -> {
                    token.set(ScarpetNativeWork.capture());
                    var actor = OrgFakePlayerActions.owned(target, () -> {
                        assertSame(target, fixture.owner.get());
                        assertSame(token.get(), ScarpetNativeWork.capture());
                        assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());
                        assertTrue(ScarpetRuntime.EVENT_DISABLED.get());
                        return 9;
                    });
                    var continuation = actor.thenApply(value -> {
                        assertSame(token.get(), ScarpetNativeWork.capture());
                        assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());
                        assertTrue(ScarpetRuntime.EVENT_DISABLED.get());
                        ScarpetNativeWork.record(child);
                        return value;
                    });
                    ScarpetNativeWork.record(continuation);
                    return 4;
                });
            } finally {
                ScarpetRuntime.FILL_SKIP_UPDATES.set(fill);
                ScarpetRuntime.EVENT_DISABLED.set(events);
            }
            assertFalse(parent.isDone());
            fixture.drain(fixture.target);
            assertFalse(parent.isDone());
            child.complete(null);
            assertEquals(4, parent.join());
            assertEquals(fill, ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertEquals(events, ScarpetRuntime.EVENT_DISABLED.get());
        }
    }

    @Test
    void retirementFailureContinuationRetainsItsAdmittingFlagsAndSettlesTheOwnerFuture() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            fixture.owner.set(null);
            boolean events = ScarpetRuntime.EVENT_DISABLED.get();
            CompletableFuture<Integer> actor;
            try {
                ScarpetRuntime.EVENT_DISABLED.set(true);
                actor = OrgFakePlayerActions.owned(fixture.target.player(), () -> {
                    fail("retired owner must not run");
                    return 3;
                });
            } finally {
                ScarpetRuntime.EVENT_DISABLED.set(events);
            }
            var handled = actor.handle((value, failure) -> {
                assertTrue(ScarpetRuntime.EVENT_DISABLED.get());
                assertNotNull(failure);
                return 0;
            });
            var scheduled = fixture.scheduled.get(fixture.target.id()).poll();
            scheduled.retired().accept(fixture.target.player());
            assertEquals(0, handled.join());
            assertTrue(actor.isCompletedExceptionally());
            assertEquals(events, ScarpetRuntime.EVENT_DISABLED.get());
        }
    }
}
