package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Original full Native ray traversal is the oracle for the cross-dimension real-source cursor.
 */
public class ScarpetExplosionRaysTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @SuppressWarnings("unchecked")
    @Test
    void crossDimensionPrimedMinecartVirtualCalculatorsPreserveOriginalRaysAndRng() throws Exception {
        compare(false, false);
    }

    @Test
    void optimizedCarpetRaysPreserveTheOriginalSourceAndRngOrder() throws Exception {
        compare(true, false);
    }

    @Test
    void aDelayedForeignSourceDoesNotRollTheFirstRayBeforeItsInitialCacheResistance() throws Exception {
        compare(false, true);
    }

    private void compare(boolean optimized, boolean delayed) throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var sourceWorld = mock(ServerLevel.class);
        var source = mock(MinecartTNT.class, CALLS_REAL_METHODS);
        var owner = new AtomicReference<ServerLevel>();
        var sourceTasks = new ArrayDeque<Consumer<Entity>>();
        var reference = new AtomicBoolean();
        var rngCalls = new AtomicInteger();
        var sourceCalls = new AtomicInteger();
        boolean oldOptimized = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.optimizedTNT;
        boolean oldDisabled = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage;
        try (var ticks = mockStatic(TickThread.class); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
             var global = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class); var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.optimizedTNT = optimized;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage = false;
            var config = mock(io.papermc.paper.configuration.GlobalConfiguration.class);
            config.unsupportedSettings = mock(io.papermc.paper.configuration.GlobalConfiguration.UnsupportedSettings.class);
            global.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);
            CraftServer craft = mock(CraftServer.class);
            when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            var sf = MinecraftServer.class.getField("server");
            sf.setAccessible(true);
            sf.set(server, craft);
            when(world.getServer()).thenReturn(server);
            when(world.getWorld()).thenReturn(mock(CraftWorld.class));
            when(world.getHeight()).thenReturn(384);
            when(world.getMinY()).thenReturn(-64);
            var region = mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);
            when(craft.getRegionScheduler()).thenReturn(region);
            Consumer<Runnable> atWorld = action -> {
                var prior = owner.getAndSet(world);
                try {
                    action.run();
                } finally {
                    owner.set(prior);
                }
            };
            Consumer<Runnable> atSource = action -> {
                var prior = owner.getAndSet(sourceWorld);
                try {
                    action.run();
                } finally {
                    owner.set(prior);
                }
            };
            doAnswer(call -> {
                atWorld.accept(call.getArgument(4));
                return null;
            }).when(region).execute(any(), any(org.bukkit.World.class), anyInt(), anyInt(), any(Runnable.class));
            var craftEntity = mock(CraftEntity.class);
            doReturn(craftEntity).when(source).getBukkitEntity();
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var field = CraftEntity.class.getField("taskScheduler");
            field.setAccessible(true);
            field.set(craftEntity, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                Consumer<Entity> action = call.getArgument(0);
                if (delayed && !reference.get()) sourceTasks.add(action);
                else atSource.accept(() -> action.accept(source));
                return true;
            });
            doReturn(sourceWorld).when(source).level();
            doReturn(BlockPos.ZERO).when(source).blockPosition();
            doAnswer(call -> {
                assertTrue(owner.get() == sourceWorld || reference.get() && owner.get() == world);
                sourceCalls.incrementAndGet();
                return true;
            }).when(source).isPrimed();
            ticks.when(() -> TickThread.isTickThreadFor(source)).thenAnswer(call -> owner.get() == sourceWorld || reference.get() && owner.get() == world);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenAnswer(call -> owner.get() == world);
            leases.when(() -> fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                var value = new AtomicReference<Object>();
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>, Object> action = call.getArgument(5);
                atWorld.accept(() -> value.set(action.apply(null)));
                return CompletableFuture.completedFuture(value.get());
            });
            Function<BlockPos, BlockState> state = pos -> pos.getY() == 1 ? Blocks.RAIL.defaultBlockState() : Blocks.STONE.defaultBlockState();
            when(world.getBlockState(any())).thenAnswer(call -> {
                assertSame(world, owner.get());
                return state.apply(call.getArgument(0));
            });
            when(world.isInWorldBounds(any())).thenReturn(true);
            var chunk = mock(LevelChunk.class);
            when(world.getChunk(anyInt(), anyInt())).thenReturn(chunk);
            when(((ca.spottedleaf.moonrise.patches.getblock.GetBlockChunk) chunk).moonrise$getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
                assertSame(world, owner.get());
                return state.apply(new BlockPos(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            });
            var random = mock(RandomSource.class);
            when(random.nextFloat()).thenAnswer(call -> {
                assertSame(world, owner.get());
                rngCalls.incrementAndGet();
                return .5F;
            });
            when(world.getRandom()).thenReturn(random);
            var begin = ServerExplosion.class.getDeclaredMethod("carpetBeginExplosion");
            begin.setAccessible(true);
            var expectedRef = new AtomicReference<CompletableFuture<List<BlockPos>>>();
            reference.set(true);
            var expectedExplosion = new ServerExplosion(world, source, mock(DamageSource.class), new EntityBasedExplosionDamageCalculator(source), new Vec3(.5, .5, .5), 2F, false, Explosion.BlockInteraction.DESTROY);
            atWorld.accept(() -> {
                try {
                    expectedRef.set((CompletableFuture<List<BlockPos>>) begin.invoke(expectedExplosion));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            List<BlockPos> expected = expectedRef.get().get(3, TimeUnit.SECONDS);
            int expectedRng = rngCalls.get();
            rngCalls.set(0);
            sourceCalls.set(0);
            reference.set(false);
            var actualRef = new AtomicReference<CompletableFuture<List<BlockPos>>>();
            var actualExplosion = new ServerExplosion(world, source, mock(DamageSource.class), new EntityBasedExplosionDamageCalculator(source), new Vec3(.5, .5, .5), 2F, false, Explosion.BlockInteraction.DESTROY);
            atWorld.accept(() -> {
                try {
                    actualRef.set((CompletableFuture<List<BlockPos>>) begin.invoke(actualExplosion));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            if (delayed) {
                assertFalse(actualRef.get().isDone());
                assertEquals(0, rngCalls.get());
                assertEquals(0, sourceCalls.get());
                int steps = 0;
                while (!actualRef.get().isDone()) {
                    assertFalse(sourceTasks.isEmpty());
                    assertTrue(++steps < 10000);
                    var action = sourceTasks.removeFirst();
                    atSource.accept(() -> action.accept(source));
                }
            }
            List<BlockPos> actual = actualRef.get().get(3, TimeUnit.SECONDS);
            assertEquals(expected, actual);
            assertEquals(expectedRng, rngCalls.get());
            assertTrue(sourceCalls.get() > 0);
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.optimizedTNT = oldOptimized;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage = oldDisabled;
        }
    }
}
