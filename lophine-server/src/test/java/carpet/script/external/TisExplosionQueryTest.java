package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TisExplosionQueryTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private boolean previous;
    private final ServerLevel world = mock(ServerLevel.class);
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final Entity target = mock(Entity.class);
    private final ServerExplosion explosion = mock(ServerExplosion.class, CALLS_REAL_METHODS);

    @BeforeEach
    void setup() throws Exception {
        previous = GeneralCompatConfig.explosionNoEntityInfluence;
        GeneralCompatConfig.explosionNoEntityInfluence = true;
        when(world.getServer()).thenReturn(server);
        field("level", world);
        field("center", Vec3.ZERO);
        field("radius", 2F);
        when(world.getEntities(isNull(Entity.class), any(AABB.class), any())).thenReturn(List.of(target));
    }

    @AfterEach
    void restore() {
        GeneralCompatConfig.explosionNoEntityInfluence = previous;
    }

    private void field(String name, Object value) throws Exception {
        Field f = ServerExplosion.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(explosion, value);
    }

    private org.mockito.MockedStatic<TickThread> owned() {
        var ticks = mockStatic(TickThread.class);
        ticks.when(() -> TickThread.isTickThreadFor(world, BlockPos.ZERO)).thenReturn(true);
        return ticks;
    }

    private Object invoke(String name) throws Exception {
        Method m = ServerExplosion.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(explosion);
    }

    @Test
    void synchronousActualBodyQueriesBeforeApplyingEmptyResult() throws Exception {
        invoke("hurtEntities");
        verify(world).getEntities(isNull(Entity.class), any(AABB.class), any());
        verifyNoInteractions(target);
    }

    @Test
    void asynchronousActualQueryWaitsItsNativeChildrenEvenWhenResultsAreSuppressed() throws Exception {
        var child = new CompletableFuture<Void>();
        when(world.getEntities(isNull(Entity.class), any(AABB.class), any())).thenAnswer(call -> {
            ScarpetNativeWork.record(child);
            return List.of(target);
        });
        try (var ticks = owned()) {
            @SuppressWarnings("unchecked") var actual = (CompletableFuture<Void>) invoke("carpetHurtEntitiesAsync");
            assertFalse(actual.isDone());
            verifyNoInteractions(target);
            child.complete(null);
            actual.join();
            verifyNoInteractions(target);
        }
    }

    @Test
    void guestOnlyQueryFailureRetainsRawParentAndOriginalEmptyNativeResult() throws Exception {
        var guest = new CompletableFuture<Void>();
        var child = new CompletableFuture<Void>();
        when(world.getEntities(isNull(Entity.class), any(AABB.class), any())).thenAnswer(call -> {
            ScarpetNativeWork.record(guest);
            ScarpetNativeWork.record(child);
            return List.of(target);
        });
        try (var ticks = owned()) {
            var actual = new AtomicReference<CompletableFuture<Void>>();
            var raw = ScarpetNativeWork.observeNative(null, () -> {
                try {
                    actual.set((CompletableFuture<Void>) invoke("carpetHurtEntitiesAsync"));
                    ScarpetNativeWork.record(actual.get());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            Throwable failure = new IllegalStateException("guest");
            Method mark = ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure", Throwable.class);
            mark.setAccessible(true);
            mark.invoke(null, failure);
            guest.completeExceptionally(failure);
            assertFalse(actual.get().isDone());
            child.complete(null);
            actual.get().join();
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, raw::join)));
            verifyNoInteractions(target);
        }
    }

    @Test
    void realQueryNativeFailureIsNotHiddenByEmptyResult() throws Exception {
        var child = new CompletableFuture<Void>();
        when(world.getEntities(isNull(Entity.class), any(AABB.class), any())).thenAnswer(call -> {
            ScarpetNativeWork.record(child);
            return List.of(target);
        });
        try (var ticks = owned()) {
            @SuppressWarnings("unchecked") var actual = (CompletableFuture<Void>) invoke("carpetHurtEntitiesAsync");
            child.completeExceptionally(new IllegalStateException("native"));
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, actual::join)));
            verifyNoInteractions(target);
        }
    }
}

