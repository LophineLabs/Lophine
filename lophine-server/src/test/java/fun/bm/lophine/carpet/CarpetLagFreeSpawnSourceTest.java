package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetLagFreeSpawnSourceTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void privateUnregisteredPrototypeIsReusedUntilTheRealNativeSpawnAttemptConsumesIt() {
        var world = mock(ServerLevel.class);
        var data = mock(io.papermc.paper.threadedregions.RegionizedWorldData.class);
        when(world.getCurrentWorldData()).thenReturn(data);
        var type = mock(EntityType.class);
        var first = mock(Mob.class);
        var next = mock(Mob.class);
        when(type.create(world, EntitySpawnReason.NATURAL)).thenReturn(first, next);
        try (var ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)) {
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(first)).thenReturn(false);
            assertSame(first, LagFreeSpawningHelper.getOrCreateMob(world, type));
            assertSame(first, LagFreeSpawningHelper.getOrCreateMob(world, type));
            verify(type, times(1)).create(world, EntitySpawnReason.NATURAL);
            LagFreeSpawningHelper.markSpawned(world, type);
            assertSame(next, LagFreeSpawningHelper.getOrCreateMob(world, type));
            verify(type, times(2)).create(world, EntitySpawnReason.NATURAL);
        }
    }

    @Test
    void prototypeCreationWithoutRegionDataDoesNotLoseTheActualCreatedMob() {
        var world = mock(ServerLevel.class);
        var type = mock(EntityType.class);
        var mob = mock(Mob.class);
        when(type.create(world, EntitySpawnReason.NATURAL)).thenReturn(mob);
        assertSame(mob, LagFreeSpawningHelper.getOrCreateMob(world, type));
        verify(type, times(1)).create(world, EntitySpawnReason.NATURAL);
    }

    @Test
    void originalSmallMobFastPathUsesTheSourceXWidthConditionAndSkipsTheFullCollisionScan() {
        var world = mock(ServerLevel.class);
        var box = new AABB(-23, 73, 37, -22.5, 75, 40);
        var positions = new java.util.ArrayList<BlockPos>();
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            positions.add(((BlockPos) call.getArgument(0)).immutable());
            return Blocks.AIR.defaultBlockState();
        });
        try (var ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)) {
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(world, box)).thenReturn(true);
            assertTrue(LagFreeSpawningHelper.hasNoCollision(world, box));
            assertEquals(java.util.List.of(new BlockPos(-23, 73, 37), new BlockPos(-23, 74, 37)), positions);
            verify(world, never()).noCollision(box);
        }
    }
}
