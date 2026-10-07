package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.CarpetRegionLease;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetExplosionFootprintFastPathTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void distantAttributionRemainsMetadataAndDoesNotLoadTheRectangleBetweenTheShooterAndBlast() {
        var world = mock(ServerLevel.class);
        var remote = mock(Entity.class);
        var explosion = mock(ServerExplosion.class);
        when(remote.level()).thenReturn(world);
        when(remote.blockPosition()).thenReturn(new BlockPos(16_000, 64, -16_000));
        when(remote.position()).thenReturn(new Vec3(16_000, 64, -16_000));
        when(remote.getDisplayName()).thenReturn(Component.literal("distant shooter"));
        when(remote.getWeaponItem()).thenReturn(ItemStack.EMPTY);
        when(explosion.level()).thenReturn(world);
        when(explosion.center()).thenReturn(Vec3.ZERO);
        when(explosion.radius()).thenReturn(4F);
        var footprint = new AtomicReference<List<Integer>>();
        try (var ticks = mockStatic(TickThread.class); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            ticks.when(() -> TickThread.isTickThreadFor(remote)).thenReturn(true);
            leases.when(() -> CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                footprint.set(List.of(call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
                Function<CarpetRegionLease.Lease<Integer>, Integer> action = call.getArgument(5);
                return CompletableFuture.completedFuture(action.apply(null));
            });
            var attribution = ScarpetAttribution.snapshot(List.of(remote)).join();
            assertEquals(5, ScarpetExplosionActors.area(explosion, attribution, () -> {
                assertSame(attribution, ScarpetAttribution.capture());
                assertEquals(16_000, ScarpetAttribution.data(remote).position().getX());
                return 5;
            }).join());
            assertEquals(List.of(-1, -1, 0, 0), footprint.get());
            leases.verify(() -> CarpetRegionLease.runValue(eq(world), eq(-1), eq(-1), eq(0), eq(0), any()));
        }
    }

    @Test void currentOwnerPacketDeliveryDoesNotWaitForTheGlobalSchedulerAndStillKeepsItsNativeChild() throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var players = mock(PlayerList.class);
        var recipient = mock(ServerPlayer.class);
        when(world.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        var field = PlayerList.class.getField("realPlayers");
        field.setAccessible(true);
        field.set(players, new java.util.concurrent.CopyOnWriteArrayList<>(List.of(recipient)));
        when(recipient.level()).thenReturn(world);
        when(recipient.distanceToSqr(Vec3.ZERO)).thenReturn(1D);
        recipient.connection = mock(ServerGamePacketListenerImpl.class);
        var child = new CompletableFuture<Void>();
        doAnswer(call -> { ScarpetNativeWork.record(child); return null; }).when(recipient.connection).send(any(net.minecraft.network.protocol.Packet.class));
        var explosion = mock(ServerExplosion.class);
        when(explosion.level()).thenReturn(world);
        when(explosion.center()).thenReturn(Vec3.ZERO);
        when(explosion.radius()).thenReturn(4F);
        var damage = mock(DamageSource.class);
        when(explosion.getDamageSource()).thenReturn(damage);
        when(explosion.carpetHitPlayerIds()).thenReturn(java.util.Map.of());
        try (var ticks = mockStatic(TickThread.class);
             var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(recipient)).thenReturn(true);
            var result = new AtomicReference<CompletableFuture<Void>>();
            var parent = ScarpetNativeWork.observeNative(null, () -> {
                result.set(ScarpetExplosionPackets.send(explosion, 7, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER,
                        net.minecraft.util.random.WeightedList.of(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE));
                return null;
            });
            verify(recipient.connection).send(any(net.minecraft.network.protocol.Packet.class));
            globals.verifyNoInteractions();
            assertFalse(result.get().isDone());
            assertFalse(parent.isDone());
            child.complete(null);
            result.get().join();
            parent.join();
        } finally {
            child.complete(null);
        }
    }

    @Test void ordinaryLoadedOwnedNativeFactoryAndCoreSendTheActualPacketInTheSameCallWithoutTicketsOrGlobalTasks() throws Exception {
        var fixture = new CoreFixture();
        var recipient = mock(ServerPlayer.class);
        when(recipient.level()).thenReturn(fixture.world);
        when(recipient.distanceToSqr(Vec3.ZERO)).thenReturn(1D);
        recipient.connection = mock(ServerGamePacketListenerImpl.class);
        var field = PlayerList.class.getField("realPlayers");
        field.setAccessible(true);
        field.set(fixture.players, new java.util.concurrent.CopyOnWriteArrayList<>(List.of(recipient)));
        try (var ticks = mockStatic(TickThread.class);
             var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
             var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            fixture.owned(ticks);
            ticks.when(() -> TickThread.isTickThreadFor(recipient)).thenReturn(true);
            var actual = fixture.world.explode0Async(null, fixture.damage, new net.minecraft.world.level.ExplosionDamageCalculator(),
                    0, 0, 0, 4, false, net.minecraft.world.level.Level.ExplosionInteraction.NONE,
                    ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, net.minecraft.util.random.WeightedList.of(),
                    net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
            assertTrue(actual.isDone(), "Ordinary native explosions must not wait another region/global tick");
            assertFalse(actual.join().wasCanceled);
            verify(recipient.connection).send(any(net.minecraft.network.protocol.Packet.class));
            leases.verifyNoInteractions();
            globals.verifyNoInteractions();
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

    @Test void anUnloadedOrdinaryCoreKeepsTheTicketBackedFallbackAndDoesNotStartEffectsBeforeAdmission() throws Exception {
        var fixture = new CoreFixture();
        doReturn(null).when(fixture.world).getChunkIfLoaded(anyInt(), anyInt());
        var pending = new CompletableFuture<CompletableFuture<Integer>>();
        try (var ticks = mockStatic(TickThread.class); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            fixture.owned(ticks);
            leases.when(() -> CarpetRegionLease.<CompletableFuture<Integer>>runValue(eq(fixture.world), eq(-1), eq(-1), eq(0), eq(0), any())).thenReturn(pending);
            var explosion = new ServerExplosion(fixture.world, null, fixture.damage, new net.minecraft.world.level.ExplosionDamageCalculator(),
                    Vec3.ZERO, 4F, false, net.minecraft.world.level.Explosion.BlockInteraction.KEEP);
            var actual = explosion.carpetExplodeAsync();
            assertFalse(actual.isDone());
            leases.verify(() -> CarpetRegionLease.runValue(eq(fixture.world), eq(-1), eq(-1), eq(0), eq(0), any()));
            verify(fixture.world, never()).gameEvent(isNull(), eq(net.minecraft.world.level.gameevent.GameEvent.EXPLODE), any(Vec3.class));
            pending.complete(CompletableFuture.completedFuture(0));
            assertEquals(0, actual.join());
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        } finally {
            pending.complete(CompletableFuture.completedFuture(0));
        }
    }

    @Test void nativeCoreAdmissionDoesNotLoadOrOwnTheSeparateSixtyFourBlockPacketFootprint() throws Exception {
        var fixture = new CoreFixture();
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(eq(fixture.world), anyInt(), anyInt())).thenAnswer(call -> {
                int x = call.getArgument(1), z = call.getArgument(2);
                return x >= -1 && x <= 0 && z >= -1 && z <= 0;
            });
            assertTrue(fun.bm.lophine.carpet.CarpetSynchronousExplosionPreflight.canRunCore(fixture.world, null, fixture.damage,
                    new net.minecraft.world.level.ExplosionDamageCalculator(), Vec3.ZERO, 4F));
            assertThrows(fun.bm.lophine.carpet.CarpetSynchronousExplosionScope.Unavailable.class, () ->
                    fun.bm.lophine.carpet.CarpetSynchronousExplosionPreflight.require(fixture.world, null, fixture.damage,
                            new net.minecraft.world.level.ExplosionDamageCalculator(), Vec3.ZERO, 4F));
            verify(fixture.world, never()).getLocalPlayers();
        }
    }

    @Test void aForeignNativeSourceRejectsInlineAdmissionBeforeItsMutableFieldsAreRead() throws Exception {
        var fixture = new CoreFixture();
        var source = mock(Entity.class);
        try (var ticks = mockStatic(TickThread.class)) {
            fixture.owned(ticks);
            assertFalse(fun.bm.lophine.carpet.CarpetSynchronousExplosionPreflight.canRunCore(fixture.world, source, fixture.damage,
                    new net.minecraft.world.level.EntityBasedExplosionDamageCalculator(source), Vec3.ZERO, 4F));
            verify(source, never()).level();
            verify(source, never()).blockPosition();
            verify(fixture.world, never()).gameEvent(isNull(), eq(net.minecraft.world.level.gameevent.GameEvent.EXPLODE), any(Vec3.class));
        }
    }

    @Test void anUnexpectedCommittedChildWaitsBeforeActualPacketsAndKeepsTheInitiatingNativeFlags() throws Exception {
        var fixture = new CoreFixture();
        var recipient = mock(ServerPlayer.class);
        when(recipient.level()).thenReturn(fixture.world);
        when(recipient.distanceToSqr(Vec3.ZERO)).thenReturn(1D);
        recipient.connection = mock(ServerGamePacketListenerImpl.class);
        var field = PlayerList.class.getField("realPlayers");
        field.setAccessible(true);
        field.set(fixture.players, new java.util.concurrent.CopyOnWriteArrayList<>(List.of(recipient)));
        var child = new CompletableFuture<Void>();
        doAnswer(call -> { ScarpetNativeWork.record(child); return null; }).when(fixture.world)
                .gameEvent(isNull(), eq(net.minecraft.world.level.gameevent.GameEvent.EXPLODE), any(Vec3.class));
        doAnswer(call -> { assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get()); return null; }).when(recipient.connection)
                .send(any(net.minecraft.network.protocol.Packet.class));
        boolean previous = ScarpetRuntime.FILL_SKIP_UPDATES.get();
        try (var ticks = mockStatic(TickThread.class);
             var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
             var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            fixture.owned(ticks);
            ticks.when(() -> TickThread.isTickThreadFor(recipient)).thenReturn(true);
            ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
            var actual = fixture.world.explode0Async(null, fixture.damage, new net.minecraft.world.level.ExplosionDamageCalculator(),
                    0, 0, 0, 4, false, net.minecraft.world.level.Level.ExplosionInteraction.NONE,
                    ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, net.minecraft.util.random.WeightedList.of(),
                    net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
            ScarpetRuntime.FILL_SKIP_UPDATES.set(false);
            assertFalse(actual.isDone());
            assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            assertFalse(fun.bm.lophine.carpet.CarpetSynchronousExplosionScope.active(fixture.world));
            verify(recipient.connection, never()).send(any(net.minecraft.network.protocol.Packet.class));
            child.complete(null);
            assertTrue(actual.isDone());
            assertFalse(actual.join().wasCanceled);
            verify(recipient.connection).send(any(net.minecraft.network.protocol.Packet.class));
            assertFalse(ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            leases.verifyNoInteractions();
            globals.verifyNoInteractions();
        } finally {
            child.complete(null);
            ScarpetRuntime.FILL_SKIP_UPDATES.set(previous);
        }
    }

    @Test void aGenuineFastCoreFailureClearsLiveCachesAndDoesNotRetryOrSendAPacket() throws Exception {
        var fixture = new CoreFixture();
        var failed = new IllegalStateException("actual native game event failed");
        doThrow(failed).when(fixture.world).gameEvent(isNull(), eq(net.minecraft.world.level.gameevent.GameEvent.EXPLODE), any(Vec3.class));
        var explosion = new AtomicReference<ServerExplosion>();
        try (var ticks = mockStatic(TickThread.class);
             var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
             var packets = mockStatic(ScarpetExplosionPackets.class)) {
            fixture.owned(ticks);
            var actual = fixture.world.explode0Async(null, fixture.damage, new net.minecraft.world.level.ExplosionDamageCalculator(),
                    0, 0, 0, 4, false, net.minecraft.world.level.Level.ExplosionInteraction.NONE,
                    ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, net.minecraft.util.random.WeightedList.of(),
                    net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE, explosion::set);
            assertSame(failed, assertThrows(java.util.concurrent.CompletionException.class, actual::join).getCause());
            assertNotNull(explosion.get());
            for (String name : List.of("blockCache", "chunkCache", "directMappedBlockCache", "mutablePos")) {
                var field = ServerExplosion.class.getDeclaredField(name);
                field.setAccessible(true);
                assertNull(field.get(explosion.get()), name);
            }
            leases.verifyNoInteractions();
            packets.verifyNoInteractions();
            assertFalse(fun.bm.lophine.carpet.CarpetSynchronousExplosionScope.active(fixture.world));
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

    @Test void anAcceptedBlockPhaseKeepsItsActualNativeChildAndLeaseUntilTheMutationReallyFinishes() {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var explosion = mock(ServerExplosion.class);
        when(world.getServer()).thenReturn(server);
        when(explosion.level()).thenReturn(world);
        when(explosion.center()).thenReturn(Vec3.ZERO);
        var child = new CompletableFuture<Void>();
        var held = new java.util.concurrent.atomic.AtomicBoolean();
        var actual = new AtomicReference<CompletableFuture<Integer>>();
        try (var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            leases.when(() -> CarpetRegionLease.<CompletableFuture<Integer>>runValue(eq(world), eq(0), eq(0), eq(0), eq(0), any())).thenAnswer(call -> {
                Function<CarpetRegionLease.Lease<CompletableFuture<Integer>>, CompletableFuture<Integer>> action = call.getArgument(5);
                held.set(true);
                var phase = action.apply(null);
                phase.whenComplete((value, failure) -> held.set(false));
                return CompletableFuture.completedFuture(phase);
            });
            var parent = ScarpetNativeWork.observeNative(null, () -> {
                actual.set(ScarpetExplosionActors.blockPhase(explosion, List.of(BlockPos.ZERO), () -> {
                    ScarpetNativeWork.record(child);
                    return 9;
                }));
                return null;
            });
            ScarpetNativeWork.trackNative(server, parent);
            var drain = ScarpetNativeWork.whenIdle(server);
            assertTrue(held.get(), "An immutable phase result must not release its real mutation children");
            assertFalse(actual.get().isDone());
            assertFalse(parent.isDone());
            assertFalse(drain.isDone());
            child.complete(null);
            assertEquals(9, actual.get().join());
            parent.join();
            assertFalse(held.get());
            assertTrue(drain.isDone());
        } finally {
            child.complete(null);
        }
    }

    private static final class CoreFixture {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class, CALLS_REAL_METHODS);
        final PlayerList players = mock(PlayerList.class);
        final DamageSource damage = mock(DamageSource.class);
        CoreFixture() throws Exception {
            var serverField = ServerLevel.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(world, server);
            var chunk = mock(net.minecraft.world.level.chunk.LevelChunk.class);
            when(chunk.getBlockEntities()).thenReturn(java.util.Map.of());
            doReturn(chunk).when(world).getChunkIfLoaded(anyInt(), anyInt());
            doReturn(List.of()).when(world).getEntities(nullable(Entity.class), any(net.minecraft.world.phys.AABB.class), any(java.util.function.Predicate.class));
            doReturn(false).when(world).isInWorldBounds(any(BlockPos.class));
            doReturn(net.minecraft.util.RandomSource.create(1L)).when(world).getRandom();
            doNothing().when(world).gameEvent(isNull(), eq(net.minecraft.world.level.gameevent.GameEvent.EXPLODE), any(Vec3.class));
            when(server.getPlayerList()).thenReturn(players);
            var list = PlayerList.class.getField("realPlayers");
            list.setAccessible(true);
            list.set(players, new java.util.concurrent.CopyOnWriteArrayList<ServerPlayer>());
        }
        void owned(org.mockito.MockedStatic<TickThread> ticks) {
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), anyInt(), anyInt())).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
        }
    }
}
