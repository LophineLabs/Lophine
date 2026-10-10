package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TisProjectileVisualizerLifecycleTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static Snowball marker() throws Exception {
        return marker(true, mock(MinecraftServer.class));
    }

    private static Snowball marker(boolean admitted, MinecraftServer server) throws Exception {
        var marker = mock(Snowball.class);
        marker.persist = true;
        var world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(server);
        when(marker.level()).thenReturn(world);
        when(marker.entityTags()).thenReturn(Set.of(TisProjectileVisualizer.TAG));
        when(marker.getUUID()).thenReturn(UUID.randomUUID());
        when(marker.getDeltaMovement()).thenReturn(new Vec3(1, 0, 0));
        var bukkit = mock(CraftEntity.class);
        var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
        var field = CraftEntity.class.getField("taskScheduler");
        field.setAccessible(true);
        field.set(bukkit, scheduler);
        when(marker.getBukkitEntity()).thenReturn(bukkit);
        if (admitted) {
            var generationsField = TisProjectileVisualizer.class.getDeclaredField("MARKER_GENERATIONS");
            generationsField.setAccessible(true);
            var epochField = TisProjectileVisualizer.class.getDeclaredField("GENERATION");
            epochField.setAccessible(true);
            @SuppressWarnings("unchecked") var generations = (carpet.script.external.WeakIdentityMap<Entity, Long>) generationsField.get(null);
            generations.put(marker, ((AtomicLong) epochField.get(null)).get());
        }
        return marker;
    }

    private static Map<?, ?> registry() throws Exception {
        var field = TisProjectileVisualizer.class.getDeclaredField("VISUALIZERS");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    @Test
    void clearDuringTheOwnerTickCannotReinsertAnExpiredMarker() throws Exception {
        TisProjectileVisualizer.reset();
        try (var loggers = mockStatic(CarpetLoggerProtocol.class)) {
            loggers.when(() -> CarpetLoggerProtocol.hasSubscribers("projectiles")).thenReturn(true);
            var marker = marker();
            doAnswer(call -> {
                TisProjectileVisualizer.clear();
                return null;
            }).when(marker).setDeltaMovement(Vec3.ZERO);
            assertTrue(TisProjectileVisualizer.tick(marker));
            assertTrue(registry().isEmpty());
            verify(marker).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
        } finally {
            TisProjectileVisualizer.reset();
        }
    }

    @Test
    void aClearedMarkerCannotRejoinOnItsNextTickAndUnsubscribedMarkersReleaseRegistryReferences() throws Exception {
        TisProjectileVisualizer.reset();
        try (var loggers = mockStatic(CarpetLoggerProtocol.class)) {
            loggers.when(() -> CarpetLoggerProtocol.hasSubscribers("projectiles")).thenReturn(true);
            var expired = marker();
            TisProjectileVisualizer.tick(expired);
            assertEquals(1, registry().size());
            TisProjectileVisualizer.clear();
            TisProjectileVisualizer.tick(expired);
            assertTrue(registry().isEmpty());
            verify(expired).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
            var active = marker();
            TisProjectileVisualizer.tick(active);
            assertEquals(1, registry().size());
            loggers.when(() -> CarpetLoggerProtocol.hasSubscribers("projectiles")).thenReturn(false);
            TisProjectileVisualizer.tick(active);
            assertTrue(registry().isEmpty());
            verify(active).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
        } finally {
            TisProjectileVisualizer.reset();
        }
    }

    @Test
    void removalMetadataReleasesOnlyTheActualMarkerObject() throws Exception {
        TisProjectileVisualizer.reset();
        try (var loggers = mockStatic(CarpetLoggerProtocol.class)) {
            loggers.when(() -> CarpetLoggerProtocol.hasSubscribers("projectiles")).thenReturn(true);
            var original = marker();
            var replacement = marker();
            UUID id = original.getUUID();
            when(replacement.getUUID()).thenReturn(id);
            TisProjectileVisualizer.tick(original);
            TisProjectileVisualizer.tick(replacement);
            TisProjectileVisualizer.removed(original);
            assertEquals(1, registry().size());
            TisProjectileVisualizer.removed(replacement);
            assertTrue(registry().isEmpty());
        } finally {
            TisProjectileVisualizer.reset();
        }
    }

    @Test
    void reloadedTaggedMarkersHaveNoGenerationAdmissionAndCannotResurrectAfterClear() throws Exception {
        TisProjectileVisualizer.reset();
        try (var loggers = mockStatic(CarpetLoggerProtocol.class)) {
            loggers.when(() -> CarpetLoggerProtocol.hasSubscribers("projectiles")).thenReturn(true);
            var original = marker();
            TisProjectileVisualizer.tick(original);
            assertFalse(original.persist);
            TisProjectileVisualizer.removed(original); // chunk unload retires this original Java entity
            TisProjectileVisualizer.clear();
            var reloaded = marker(false, mock(MinecraftServer.class));
            UUID id = original.getUUID();
            when(reloaded.getUUID()).thenReturn(id);
            assertTrue(TisProjectileVisualizer.tick(reloaded));
            assertFalse(reloaded.persist);
            assertTrue(registry().isEmpty());
            verify(reloaded).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
        } finally {
            TisProjectileVisualizer.reset();
        }
    }

    @Test
    void shutdownDrainsAcceptedMarkerDiscardChildrenBeforeItsOwnerSchedulersCanHalt() throws Exception {
        TisProjectileVisualizer.reset();
        try (var loggers = mockStatic(CarpetLoggerProtocol.class)) {
            loggers.when(() -> CarpetLoggerProtocol.hasSubscribers("projectiles")).thenReturn(true);
            var server = mock(MinecraftServer.class);
            var marker = marker(true, server);
            var scheduled = new AtomicReference<java.util.function.Consumer<Entity>>();
            when(marker.getBukkitEntity().taskScheduler.schedule(any(), any(), eq(1L))).thenAnswer(call -> {
                scheduled.set(call.getArgument(0));
                return true;
            });
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(child);
                return null;
            }).when(marker).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
            TisProjectileVisualizer.tick(marker);
            TisProjectileVisualizer.clearAtShutdown(server);
            var drain = ScarpetNativeWork.whenIdle(server);
            assertFalse(drain.isDone());
            verify(marker, never()).discard(any());
            scheduled.get().accept(marker);
            assertFalse(drain.isDone());
            child.complete(null);
            drain.join();
            assertTrue(registry().isEmpty());
            TisProjectileVisualizer.tick(marker);
            assertTrue(registry().isEmpty());
        } finally {
            TisProjectileVisualizer.reset();
        }
    }
}
