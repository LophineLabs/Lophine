package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real production countdown entry and actor bodies, including physical child receipt boundaries.
 */
public class OrgCreeperCommandCompletionTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() throws Exception {
        OrgInventoryPersistenceTest.bootstrap();
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            var server = mock(org.bukkit.Server.class);
            when(server.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(server);
            Class.forName("org.leavesmc.leaves.plugin.MinecraftInternalPlugin");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors;
        final ServerLevel spawnWorld;
        final AtomicReference<ServerLevel> playerWorld = new AtomicReference<>();
        final AtomicReference<Vec3> playerPosition = new AtomicReference<>(new Vec3(15.5, 64, 15.5));
        final AtomicReference<Vec3> creeperPosition = new AtomicReference<>(Vec3.ZERO);
        final AtomicInteger samples = new AtomicInteger(), explosions = new AtomicInteger(), leaseCalls = new AtomicInteger();
        final ServerTickRateManager timer = mock(ServerTickRateManager.class);
        final CompletableFuture<ServerExplosion> explosion = new CompletableFuture<>();
        final CompletableFuture<Void> removedChild = new CompletableFuture<>(), spawnChild = new CompletableFuture<>();
        final AtomicReference<Creeper> creeper = new AtomicReference<>();
        final UUID creeperId = UUID.randomUUID();
        final MockedStatic<CarpetRegionLease> leases;
        final MockedConstruction<Creeper> construction;
        boolean frozen, spawnAccepted = true, delayedSpawn;

        Fixture(Path directory) throws Exception {
            actors = new OrgInventoryPersistenceTest.Fixture(directory);
            when(actors.viewer.player().blockPosition()).thenReturn(BlockPos.ZERO);
            when(actors.viewer.player().position()).thenReturn(Vec3.ZERO);
            spawnWorld = actors.target.player().level();
            playerWorld.set(spawnWorld);
            doAnswer(call -> playerWorld.get()).when(actors.target.player()).level();
            doAnswer(call -> {
                assertSame(actors.target.player(), actors.owner.get());
                return playerPosition.get();
            }).when(actors.target.player()).position();
            doAnswer(call -> {
                assertSame(actors.target.player(), actors.owner.get());
                return BlockPos.containing(playerPosition.get());
            }).when(actors.target.player()).blockPosition();
            var rng = mock(net.minecraft.util.RandomSource.class);
            when(actors.target.player().getRandom()).thenReturn(rng);
            when(rng.nextInt(anyInt())).thenAnswer(call -> {
                assertSame(actors.target.player(), actors.owner.get());
                assertEquals(1, call.<Integer>getArgument(0));
                return 0;
            });
            when(actors.server.tickRateManager()).thenReturn(timer);
            when(timer.runsNormally()).thenAnswer(call -> {
                samples.incrementAndGet();
                return !frozen;
            });
            BlockState air = mock(BlockState.class), solid = mock(BlockState.class);
            when(air.isAir()).thenReturn(true);
            when(solid.isRedstoneConductor(eq(spawnWorld), any(BlockPos.class))).thenReturn(true);
            // Only one valid Source candidate lies across both chunk boundaries.
            when(spawnWorld.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
                assertSame(actors.target.player(), actors.owner.get());
                BlockPos pos = call.getArgument(0);
                return pos.equals(new BlockPos(18, 64, 18)) || pos.equals(new BlockPos(18, 65, 18)) ? air : solid;
            });
            actors.ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(spawnWorld), anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(call -> actors.owner.get() == actors.target.player());
            when(spawnWorld.addFreshEntity(any(Creeper.class), eq(org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM))).thenAnswer(call -> {
                assertSame(actors.target.player(), actors.owner.get());
                if (delayedSpawn) ScarpetNativeWork.record(spawnChild);
                return spawnAccepted;
            });
            construction = mockConstruction(Creeper.class, (created, context) -> {
                assertEquals(java.util.List.of(net.minecraft.world.entity.EntityTypes.CREEPER, spawnWorld), context.arguments());
                creeper.set(created);
                when(created.level()).thenReturn(spawnWorld);
                when(created.blockPosition()).thenAnswer(call -> BlockPos.containing(creeperPosition.get()));
                when(created.position()).thenAnswer(call -> creeperPosition.get());
                when(created.getX()).thenAnswer(call -> creeperPosition.get().x);
                when(created.getUUID()).thenReturn(creeperId);
                doAnswer(call -> {
                    assertSame(actors.target.player(), actors.owner.get());
                    creeperPosition.set(call.getArgument(0));
                    return null;
                }).when(created).snapTo(any(Vec3.class), anyFloat(), anyFloat());
                doAnswer(call -> {
                    assertSame(created, actors.owner.get());
                    ScarpetNativeWork.record(removedChild);
                    return null;
                }).when(created).discard();
                var bukkit = mock(org.bukkit.craftbukkit.entity.CraftCreeper.class);
                when(created.getBukkitEntity()).thenReturn(bukkit);
                var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
                var field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
                field.setAccessible(true);
                field.set(bukkit, scheduler);
                when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                    long now = actors.clocks.computeIfAbsent(creeperId, ignored -> new java.util.concurrent.atomic.AtomicLong()).get();
                    actors.scheduled.computeIfAbsent(creeperId, ignored -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(new OrgInventoryPersistenceTest.Fixture.Scheduled(call.getArgument(0), call.getArgument(1), now + 1));
                    return true;
                });
            });
            leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
            leases.when(() -> CarpetRegionLease.runValue(eq(spawnWorld), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class))).thenAnswer(call -> {
                assertSame(actors.target.player(), actors.owner.get());
                assertEquals(0, call.<Integer>getArgument(1));
                assertEquals(0, call.<Integer>getArgument(2));
                assertEquals(1, call.<Integer>getArgument(3));
                assertEquals(1, call.<Integer>getArgument(4));
                leaseCalls.incrementAndGet();
                return CompletableFuture.completedFuture(call.<Function<?, ?>>getArgument(5).apply(null));
            });
            var craft = mock(org.bukkit.craftbukkit.CraftServer.class);
            var region = mock(io.papermc.paper.threadedregions.scheduler.FoliaRegionScheduler.class);
            when(craft.getRegionScheduler()).thenReturn(region);
            var serverField = net.minecraft.server.MinecraftServer.class.getField("server");
            serverField.setAccessible(true);
            serverField.set(actors.server, craft);
            doAnswer(call -> {
                actors.owner.set(creeper.get());
                call.<Runnable>getArgument(4).run();
                return null;
            }).when(region).execute(any(), any(), anyInt(), anyInt(), any(Runnable.class));
            actors.owner.set(null);
        }

        CompletableFuture<Boolean> start() {
            return OrgCreeperCommandTask.start(actors.server, actors.target.player());
        }

        void cycle() {
            actors.drain(actors.target);
            drainCreeper();
            actors.drain(actors.viewer);
            actors.owner.set(null);
        }

        void drainCreeper() {
            Entity entity = creeper.get();
            if (entity == null) return;
            actors.owner.set(entity);
            var queue = actors.scheduled.get(creeperId);
            if (queue == null) return;
            long now = actors.clocks.computeIfAbsent(creeperId, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
            int count = queue.size();
            for (int i = 0; i < count; i++) {
                var task = queue.poll();
                if (task == null) break;
                if (task.due() <= now) task.work().accept(entity);
                else queue.add(task);
            }
        }

        void until(java.util.function.BooleanSupplier done) {
            for (int i = 0; i < 200 && !done.getAsBoolean(); i++) cycle();
            assertTrue(done.getAsBoolean(), () -> "Source samples=" + samples.get() + ", explosions=" + explosions.get());
        }

        void explosionWorld(ServerLevel world) {
            when(world.explode0Async(same(creeper.get()), isNull(), isNull(), anyDouble(), anyDouble(), anyDouble(), eq(3F), eq(false), eq(net.minecraft.world.level.Level.ExplosionInteraction.NONE), same(net.minecraft.core.particles.ParticleTypes.EXPLOSION), same(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER), same(net.minecraft.world.level.Level.DEFAULT_EXPLOSION_BLOCK_PARTICLES), same(net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE))).thenAnswer(call -> {
                assertSame(creeper.get(), actors.owner.get());
                assertEquals(18.5, call.<Double>getArgument(3));
                assertEquals(playerPosition.get().y, call.<Double>getArgument(4));
                assertEquals(playerPosition.get().z, call.<Double>getArgument(5));
                explosions.incrementAndGet();
                return explosion;
            });
        }

        @Override
        public void close() {
            removedChild.complete(null);
            spawnChild.complete(null);
            explosion.complete(mock(ServerExplosion.class));
            for (int i = 0; i < 90; i++) cycle();
            leases.close();
            construction.close();
            actors.close();
        }
    }

    @Test
    void fullSpawnIncludesBoundaryCandidateRealPlayerRngAndPrivateReceiptSurvivesCancelledCaller() throws Exception {
        try (var f = new Fixture(directory)) {
            f.delayedSpawn = true;
            var caller = f.start();
            var idle = ScarpetNativeWork.whenIdle(f.actors.server);
            assertFalse(idle.isDone());
            assertTrue(caller.cancel(false));
            f.cycle();
            assertEquals(new Vec3(18.5, 64, 18.5), f.creeperPosition.get());
            assertEquals(1, f.leaseCalls.get());
            assertEquals(0, f.samples.get());
            assertFalse(idle.isDone());
            verify(f.creeper.get(), never()).setNoAi(anyBoolean());
            f.spawnChild.complete(null);
            f.playerPosition.set(new Vec3(40, 64, 18.5));
            f.until(() -> f.samples.get() > 0);
            assertFalse(idle.isDone());
            f.removedChild.complete(null);
            f.until(idle::isDone);
            assertTrue(caller.isCancelled());
            verify(f.creeper.get()).playSound(net.minecraft.sounds.SoundEvents.CREEPER_PRIMED, 1F, .5F);
        }
    }

    @Test
    void originalThirtyTickExplosionUsesCurrentPlayerWorldMixedCoordinatesAndRemovalOnFollowingTick() throws Exception {
        try (var f = new Fixture(directory)) {
            var actual = f.start();
            f.until(() -> f.creeper.get() != null);
            var current = mock(ServerLevel.class);
            when(current.getServer()).thenReturn(f.actors.server);
            f.playerWorld.set(current);
            f.playerPosition.set(new Vec3(18.5, 65, 18.75));
            f.explosionWorld(current);
            f.until(() -> f.explosions.get() == 1);
            assertEquals(30, f.samples.get());
            assertFalse(actual.isDone());
            verify(f.creeper.get(), never()).discard();
            for (int i = 0; i < 5; i++) f.cycle();
            assertEquals(30, f.samples.get());
            f.explosion.complete(mock(ServerExplosion.class));
            f.until(() -> f.samples.get() == 31);
            assertFalse(actual.isDone());
            verify(f.creeper.get()).discard();
            f.removedChild.complete(null);
            f.until(actual::isDone);
            assertTrue(actual.join());
            assertEquals(1, f.explosions.get());
        }
    }

    @Test
    void freezeSkipsBothSourceTickAndStoppedDistanceCheckUntilResume() throws Exception {
        try (var f = new Fixture(directory)) {
            f.frozen = true;
            var actual = f.start();
            f.until(() -> f.creeper.get() != null);
            f.playerPosition.set(new Vec3(50, 64, 50));
            for (int i = 0; i < 8; i++) f.cycle();
            assertFalse(actual.isDone());
            verify(f.creeper.get(), never()).playSound(any(), anyFloat(), anyFloat());
            verify(f.creeper.get(), never()).discard();
            f.frozen = false;
            f.until(() -> mockingDetails(f.creeper.get()).getInvocations().stream().anyMatch(call -> call.getMethod().getName().equals("discard")));
            assertFalse(actual.isDone());
            f.removedChild.complete(null);
            f.until(actual::isDone);
            assertTrue(actual.join());
            verify(f.creeper.get()).gameEvent(net.minecraft.world.level.gameevent.GameEvent.PRIME_FUSE);
        }
    }

    @Test
    void genuineExplosionFailureRemovesRealCreeperAndRemainsFailureAfterRemovalChildren() throws Exception {
        try (var f = new Fixture(directory)) {
            var actual = f.start();
            f.until(() -> f.creeper.get() != null);
            f.explosionWorld(f.spawnWorld);
            f.until(() -> f.explosions.get() == 1);
            var nativeFailure = new IllegalStateException("actual explosion failed");
            f.explosion.completeExceptionally(nativeFailure);
            assertFalse(actual.isDone());
            f.removedChild.complete(null);
            f.until(actual::isDone);
            Throwable error = assertThrows(CompletionException.class, actual::join);
            while (error.getCause() != null) error = error.getCause();
            assertSame(nativeFailure, error);
            verify(f.creeper.get()).discard();
        }
    }

    @Test
    void utilityActualCommandCallbackWaitsCountdownExplosionAndRemovalChildren() throws Exception {
        try (var f = new Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            var source = mock(net.minecraft.commands.CommandSourceStack.class);
            when(source.getServer()).thenReturn(f.actors.server);
            when(source.getEntity()).thenReturn(f.actors.viewer.player());
            when(source.getPlayer()).thenReturn(f.actors.viewer.player());
            when(source.callback()).thenReturn(net.minecraft.commands.CommandResultCallback.EMPTY);
            var method = OrgUtilityCommands.class.getDeclaredMethod("startCreeper", net.minecraft.commands.CommandSourceStack.class, net.minecraft.server.level.ServerPlayer.class);
            method.setAccessible(true);
            var parent = ScarpetNativeWork.observeNative(f.actors.viewer.player(), () -> {
                try {
                    return (int) method.invoke(null, source, f.actors.target.player());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            var result = scope.resultFuture(source);
            assertNotNull(result);
            assertFalse(result.isDone());
            f.until(() -> f.creeper.get() != null);
            f.explosionWorld(f.spawnWorld);
            f.until(() -> f.explosions.get() == 1);
            assertFalse(parent.isDone());
            f.explosion.complete(mock(ServerExplosion.class));
            f.until(() -> f.samples.get() == 31);
            assertFalse(result.isDone());
            f.removedChild.complete(null);
            f.until(result::isDone);
            assertEquals(1, result.join());
            assertEquals(1, parent.join());
        }
    }

    @Test
    void failedSpawnNativeChildStillWaitsActualRemovalAndKeepsOriginalFailure() throws Exception {
        try (var f = new Fixture(directory)) {
            f.delayedSpawn = true;
            var actual = f.start();
            f.until(() -> f.creeper.get() != null);
            var failure = new IllegalStateException("physical spawn child failed");
            f.spawnChild.completeExceptionally(failure);
            assertFalse(actual.isDone());
            verify(f.creeper.get()).discard();
            f.removedChild.complete(null);
            f.until(actual::isDone);
            Throwable error = assertThrows(CompletionException.class, actual::join);
            while (error.getCause() != null) error = error.getCause();
            assertSame(failure, error);
        }
    }

    @Test
    void acceptedCountdownRetainsRealSourceRuleFlagsAndShutdownDrainWaitsPhysicalRemoval() throws Exception {
        try (var f = new Fixture(directory)) {
            var caller = new OrgGameplayHelper.NativeRuleScopes(true, f.actors.target.player(), false, true).call(f::start);
            f.until(() -> f.creeper.get() != null);
            doAnswer(call -> {
                assertSame(f.creeper.get(), f.actors.owner.get());
                assertSame(f.actors.target.player(), OrgGameplayHelper.blockBreaker());
                assertTrue(OrgGameplayHelper.toolNoBreakActive());
                assertFalse(OrgGameplayHelper.allowShulkerStacking());
                assertTrue(fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));
                return null;
            }).when(f.creeper.get()).playSound(any(), anyFloat(), anyFloat());
            f.until(() -> f.samples.get() > 0);
            f.frozen = true;
            ScarpetNativeWork.beginDrain(f.actors.server);
            var idle = ScarpetNativeWork.whenIdle(f.actors.server);
            assertFalse(idle.isDone());
            f.until(() -> mockingDetails(f.creeper.get()).getInvocations().stream().anyMatch(call -> call.getMethod().getName().equals("discard")));
            assertFalse(caller.isDone());
            assertFalse(idle.isDone());
            f.removedChild.complete(null);
            f.until(caller::isDone);
            assertTrue(caller.join());
            idle.join();
            assertNull(OrgGameplayHelper.blockBreaker());
            assertFalse(OrgGameplayHelper.toolNoBreakActive());
        }
    }
}
