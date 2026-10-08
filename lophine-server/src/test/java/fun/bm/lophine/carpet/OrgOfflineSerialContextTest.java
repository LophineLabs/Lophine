package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrgOfflineSerialContextTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletableFuture<T> serial(Object session, Supplier<CompletableFuture<T>> action) {
        try {
            Method method = session.getClass().getDeclaredMethod("serial", Supplier.class);
            method.setAccessible(true);
            return (CompletableFuture<T>) method.invoke(session, action);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private Object session(OrgInventoryPersistenceTest.Fixture fixture) throws Exception {
        when(fixture.server.getWorldPath(LevelResource.PLAYER_DATA_DIR)).thenReturn(directory.resolve("playerdata"));
        var snapshot = mock(OrgOfflinePlayerSnapshots.Snapshot.class);
        when(snapshot.inventory()).thenReturn(List.of());
        when(snapshot.enderItems()).thenReturn(List.of());
        when(snapshot.recoveredCursor()).thenReturn(List.of());
        when(snapshot.nativeData()).thenReturn(new CompoundTag());
        when(snapshot.fileData()).thenReturn(new CompoundTag());
        Class<?> type = Class.forName(OrgOfflineInventorySessions.class.getName() + "$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(MinecraftServer.class, UUID.class, OrgOfflinePlayerSnapshots.Snapshot.class);
        constructor.setAccessible(true);
        return constructor.newInstance(fixture.server, UUID.randomUUID(), snapshot);
    }

    @Test
    void aQueuedSecondViewerDoesNotInheritThePreviousViewersNativeCauseOrFlags() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            Object session = session(fixture);
            var firstWork = new CompletableFuture<Void>();
            var secondChild = new CompletableFuture<Void>();
            var token = new AtomicReference<ScarpetNativeWork.Token>();
            var first = ScarpetNativeWork.observeNative(fixture.viewer.player(), () -> {
                ScarpetNativeWork.record(serial(session, () -> firstWork));
                return 1;
            });
            boolean fill = ScarpetRuntime.FILL_SKIP_UPDATES.get(), events = ScarpetRuntime.EVENT_DISABLED.get();
            CompletableFuture<Integer> second;
            try {
                ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
                ScarpetRuntime.EVENT_DISABLED.set(true);
                second = ScarpetNativeWork.observeNative(fixture.target.player(), () -> {
                    token.set(ScarpetNativeWork.capture());
                    ScarpetNativeWork.record(serial(session, () -> {
                        assertSame(token.get(), ScarpetNativeWork.capture());
                        assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());
                        assertTrue(ScarpetRuntime.EVENT_DISABLED.get());
                        return OrgFakePlayerActions.owned(fixture.target.player(), () -> {
                            assertSame(fixture.target.player(), fixture.owner.get());
                            assertSame(token.get(), ScarpetNativeWork.capture());
                            assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());
                            assertTrue(ScarpetRuntime.EVENT_DISABLED.get());
                            ScarpetNativeWork.record(secondChild);
                            return 7;
                        });
                    }));
                    return 2;
                });
            } finally {
                ScarpetRuntime.FILL_SKIP_UPDATES.set(fill);
                ScarpetRuntime.EVENT_DISABLED.set(events);
            }
            fixture.owner.set(null);
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            firstWork.complete(null);
            fixture.drain(fixture.target);
            assertEquals(1, first.join());
            assertFalse(second.isDone());
            secondChild.complete(null);
            assertEquals(2, second.join());
            assertEquals(fill, ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertEquals(events, ScarpetRuntime.EVENT_DISABLED.get());
        }
    }
}
