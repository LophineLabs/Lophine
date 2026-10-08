package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CarpetSpawnNativeSequenceTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        try {
            net.minecraft.world.item.Items.GRAVEL.builtInRegistryHolder().components();
        } catch (NullPointerException unbound) {
            net.minecraft.world.item.Items.GRAVEL.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
        }
    }

    static final class Fixture implements AutoCloseable {
        final ServerLevel world = mock(ServerLevel.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final org.mockito.MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final List<String> trace = new ArrayList<>();

        Fixture() {
            when(world.getServer()).thenReturn(server);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
        }

        <T extends Entity> T entity(Class<T> type) {
            T value = mock(type);
            bind(value);
            return value;
        }

        void bind(Entity entity) {
            when(entity.level()).thenReturn(world);
            when(entity.blockPosition()).thenReturn(BlockPos.ZERO);
            when(entity.position()).thenReturn(Vec3.ZERO);
            when(entity.getUUID()).thenReturn(UUID.randomUUID());
            doReturn(0D).when(entity).getX();
            doReturn(0D).when(entity).getY();
            doReturn(0D).when(entity).getZ();
        }

        public void close() {
            ticks.close();
        }
    }

    @Test
    void actualGuardianEntryUsesOriginalWorldConstructorAndWaitsFinalizeThenAddBeforeDiscard() throws Exception {
        boolean before = GeneralCompatConfig.renewableSponges;
        try (var f = new Fixture()) {
            GeneralCompatConfig.renewableSponges = true;
            var guardian = mock(Guardian.class, CALLS_REAL_METHODS);
            f.bind(guardian);
            doReturn(false).when(guardian).isRemoved();
            doReturn(true).when(guardian).isNoAi();
            doReturn(false).when(guardian).hasCustomName();
            var finalized = new CompletableFuture<Void>();
            var added = new CompletableFuture<Void>();
            when(f.world.getCurrentDifficultyAt(BlockPos.ZERO)).thenReturn(mock(DifficultyInstance.class));
            try (var created = mockConstruction(ElderGuardian.class, (elder, context) -> {
                assertSame(f.world, context.arguments().get(1));
                f.bind(elder);
                when(elder.finalizeSpawn(eq(f.world), any(DifficultyInstance.class), eq(EntitySpawnReason.CONVERSION), isNull())).thenAnswer(call -> {
                    f.trace.add("finalize");
                    ScarpetNativeWork.record(finalized);
                    return null;
                });
                doAnswer(call -> {
                    f.trace.add("ai");
                    return null;
                }).when(elder).setNoAi(true);
            })) {
                when(f.world.addFreshEntity(any(ElderGuardian.class), any())).thenAnswer(call -> {
                    f.trace.add("add");
                    ScarpetNativeWork.record(added);
                    return true;
                });
                doAnswer(call -> {
                    f.trace.add("discard");
                    return null;
                }).when(guardian).discard();
                var parent = ScarpetNativeWork.observeNative(guardian, () -> {
                    guardian.thunderHit(f.world, f.entity(LightningBolt.class));
                    return null;
                });
                assertEquals(List.of("finalize"), f.trace);
                assertFalse(parent.isDone());
                assertEquals(1, created.constructed().size());
                finalized.complete(null);
                assertEquals(List.of("finalize", "ai", "add"), f.trace);
                assertFalse(parent.isDone());
                added.complete(null);
                parent.get(3, TimeUnit.SECONDS);
                assertEquals(List.of("finalize", "ai", "add", "discard"), f.trace);
            }
        } finally {
            GeneralCompatConfig.renewableSponges = before;
        }
    }

    @Test
    void actualGuardianFinalizeFailureBlocksNameAddAndDiscard() throws Exception {
        boolean before = GeneralCompatConfig.renewableSponges;
        try (var f = new Fixture()) {
            GeneralCompatConfig.renewableSponges = true;
            var guardian = mock(Guardian.class, CALLS_REAL_METHODS);
            f.bind(guardian);
            doReturn(false).when(guardian).isRemoved();
            var child = new CompletableFuture<Void>();
            when(f.world.getCurrentDifficultyAt(BlockPos.ZERO)).thenReturn(mock(DifficultyInstance.class));
            try (var created = mockConstruction(ElderGuardian.class, (elder, context) -> {
                f.bind(elder);
                when(elder.finalizeSpawn(any(), any(), any(), any())).thenAnswer(call -> {
                    ScarpetNativeWork.record(child);
                    return null;
                });
            })) {
                var parent = ScarpetNativeWork.observeNative(guardian, () -> {
                    guardian.thunderHit(f.world, f.entity(LightningBolt.class));
                    return null;
                });
                assertFalse(parent.isDone());
                verify(created.constructed().getFirst()).finalizeSpawn(eq(f.world), any(), eq(EntitySpawnReason.CONVERSION), isNull());
                child.completeExceptionally(new IllegalStateException("actual finalize"));
                assertThrows(ExecutionException.class, () -> parent.get(3, TimeUnit.SECONDS));
                verify(created.constructed().getFirst(), never()).setNoAi(anyBoolean());
                verify(f.world, never()).addFreshEntity(any(), any());
                verify(guardian, never()).discard();
            }
        } finally {
            GeneralCompatConfig.renewableSponges = before;
        }
    }

    @Test
    void actualInfestationIgnoresSpawnFalseButWaitsItsChildAnimationAndGravel() throws Exception {
        boolean before = GeneralCompatConfig.silverFishDropGravel;
        try (var f = new Fixture()) {
            GeneralCompatConfig.silverFishDropGravel = true;
            var fish = f.entity(Silverfish.class);
            var added = new CompletableFuture<Void>();
            var animation = new CompletableFuture<Void>();
            var dropped = new CompletableFuture<Void>();
            when(f.world.addFreshEntity(eq(fish), any())).thenAnswer(call -> {
                f.trace.add("add false");
                ScarpetNativeWork.record(added);
                return false;
            });
            doAnswer(call -> {
                f.trace.add("animation");
                ScarpetNativeWork.record(animation);
                return null;
            }).when(fish).spawnAnim();
            var actual = CarpetNativeSpawns.infestation(f.world, BlockPos.ZERO, () -> fish, value -> f.trace.add("place"), () -> {
                f.trace.add("gravel");
                ScarpetNativeWork.record(dropped);
            });
            assertEquals(List.of("place", "add false"), f.trace);
            assertFalse(actual.isDone());
            added.complete(null);
            assertEquals("animation", f.trace.getLast());
            animation.complete(null);
            assertEquals("gravel", f.trace.getLast());
            assertFalse(actual.isDone());
            dropped.complete(null);
            actual.get(3, TimeUnit.SECONDS);
        } finally {
            GeneralCompatConfig.silverFishDropGravel = before;
        }
    }

    @Test
    void actualLightningEntryWaitsEachVictimBeforeBookkeepingAndStopsNewTick() throws Exception {
        try (var f = new Fixture()) {
            var bolt = mock(LightningBolt.class, CALLS_REAL_METHODS);
            f.bind(bolt);
            var hits = new HashSet<Entity>();
            var field = LightningBolt.class.getDeclaredField("hitEntities");
            field.setAccessible(true);
            field.set(bolt, hits);
            var first = f.entity(Entity.class);
            var second = f.entity(Entity.class);
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                f.trace.add("first");
                ScarpetNativeWork.record(child);
                return null;
            }).when(first).thunderHit(f.world, bolt);
            doAnswer(call -> {
                f.trace.add("second");
                return null;
            }).when(second).thunderHit(f.world, bolt);
            var method = LightningBolt.class.getDeclaredMethod("carpetStrikeEntities", ServerLevel.class, List.class);
            method.setAccessible(true);
            var parent = ScarpetNativeWork.observeNative(bolt, () -> {
                try {
                    method.invoke(bolt, f.world, List.of(first, second));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
                return null;
            });
            assertEquals(List.of("first"), f.trace);
            assertTrue(hits.isEmpty());
            assertTrue(CarpetLightningStrikes.pending(bolt));
            bolt.tick();
            verify(bolt, never()).baseTick();
            child.complete(null);
            parent.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("first", "second"), f.trace);
            assertEquals(Set.of(first, second), hits);
            assertFalse(CarpetLightningStrikes.pending(bolt));
        }
    }

    @Test
    void actualLightningVictimFailureBlocksNextVictimAndHitTail() throws Exception {
        try (var f = new Fixture()) {
            var bolt = f.entity(LightningBolt.class);
            var first = f.entity(Entity.class);
            var second = f.entity(Entity.class);
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(child);
                return null;
            }).when(first).thunderHit(f.world, bolt);
            var tail = new java.util.concurrent.atomic.AtomicBoolean();
            var actual = CarpetLightningStrikes.strike(f.world, bolt, List.of(first, second), () -> tail.set(true));
            child.completeExceptionally(new IllegalStateException("actual thunder"));
            assertThrows(ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS));
            verify(second, never()).thunderHit(any(), any());
            assertFalse(tail.get());
            assertFalse(CarpetLightningStrikes.pending(bolt));
        }
    }

    @Test
    void actualInfestedBlockEntryRunsItsRealFactoryThenAnimationBeforeGravel() throws Exception {
        boolean before = GeneralCompatConfig.silverFishDropGravel;
        try (var f = new Fixture()) {
            GeneralCompatConfig.silverFishDropGravel = true;
            when(f.world.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
            when(f.world.getDifficulty()).thenReturn(net.minecraft.world.Difficulty.NORMAL);
            var added = new CompletableFuture<Void>();
            var animation = new CompletableFuture<Void>();
            try (var created = mockConstruction(Silverfish.class, (fish, context) -> {
                assertSame(f.world, context.arguments().get(1));
                f.bind(fish);
                doAnswer(call -> {
                    f.trace.add("place");
                    return null;
                }).when(fish).snapTo(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
                doAnswer(call -> {
                    f.trace.add("animation");
                    ScarpetNativeWork.record(animation);
                    return null;
                }).when(fish).spawnAnim();
            }); var blocks = mockStatic(net.minecraft.world.level.block.Block.class, CALLS_REAL_METHODS)) {
                when(f.world.addFreshEntity(any(Silverfish.class), any())).thenAnswer(call -> {
                    f.trace.add("add");
                    ScarpetNativeWork.record(added);
                    return false;
                });
                blocks.when(() -> net.minecraft.world.level.block.Block.popResource(eq(f.world), eq(BlockPos.ZERO), any(net.minecraft.world.item.ItemStack.class))).thenAnswer(call -> {
                    f.trace.add("gravel");
                    return null;
                });
                var method = net.minecraft.world.level.block.InfestedBlock.class.getDeclaredMethod("spawnInfestation", ServerLevel.class, BlockPos.class);
                method.setAccessible(true);
                var parent = ScarpetNativeWork.observeNative(null, () -> {
                    try {
                        method.invoke(net.minecraft.world.level.block.Blocks.INFESTED_STONE, f.world, BlockPos.ZERO);
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                    return null;
                });
                assertEquals(1, created.constructed().size());
                assertEquals(List.of("place", "add"), f.trace);
                assertFalse(parent.isDone());
                added.complete(null);
                assertEquals("animation", f.trace.getLast());
                assertFalse(parent.isDone());
                animation.complete(null);
                parent.get(3, TimeUnit.SECONDS);
                assertEquals(List.of("place", "add", "animation", "gravel"), f.trace);
            }
        } finally {
            GeneralCompatConfig.silverFishDropGravel = before;
        }
    }
}
