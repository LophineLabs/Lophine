package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetStructureSpawnSourceTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void nativeSurfacePredicateAllowsOnlyTempleHusksAndKeepsTheOriginalMonsterChecks() {
        boolean old = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.huskSpawningInTemples;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.huskSpawningInTemples = true;
            var world = mock(ServerLevel.class);
            when(world.getLevel()).thenReturn(world);
            var access = mock(RegistryAccess.class);
            var structures = mock(Registry.class);
            var pyramid = mock(Structure.class);
            when(world.registryAccess()).thenReturn(access);
            when(access.lookupOrThrow(Registries.STRUCTURE)).thenReturn(structures);
            when(structures.getValue(BuiltinStructures.DESERT_PYRAMID)).thenReturn(pyramid);
            var manager = mock(net.minecraft.world.level.StructureManager.class);
            when(world.structureManager()).thenReturn(manager);
            var start = mock(StructureStart.class);
            var pos = new BlockPos(-23, 73, 37);
            when(manager.getStructureAt(pos, pyramid)).thenReturn(start);
            when(start.isValid()).thenReturn(true);
            var random = net.minecraft.util.RandomSource.create(137);
            try (var monsters = mockStatic(Monster.class, CALLS_REAL_METHODS)) {
                monsters.when(() -> Monster.checkMonsterSpawnRules(EntityTypes.HUSK, world, EntitySpawnReason.NATURAL, pos, random)).thenReturn(true);
                assertTrue(Monster.checkSurfaceMonstersSpawnRules(EntityTypes.HUSK, world, EntitySpawnReason.NATURAL, pos, random));
                when(start.isValid()).thenReturn(false);
                assertFalse(Monster.checkSurfaceMonstersSpawnRules(EntityTypes.HUSK, world, EntitySpawnReason.NATURAL, pos, random));
                when(world.canSeeSky(pos)).thenReturn(true);
                assertTrue(Monster.checkSurfaceMonstersSpawnRules(EntityTypes.HUSK, world, EntitySpawnReason.NATURAL, pos, random));
                monsters.when(() -> Monster.checkMonsterSpawnRules(EntityTypes.HUSK, world, EntitySpawnReason.NATURAL, pos, random)).thenReturn(false);
                assertFalse(Monster.checkSurfaceMonstersSpawnRules(EntityTypes.HUSK, world, EntitySpawnReason.NATURAL, pos, random));
            }
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.huskSpawningInTemples = old;
        }
    }

    @Test
    void falseRuleAndOtherMonstersDoNotReadStructureStartsAndSpawnerReasonStillBypassesSky() {
        boolean old = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.huskSpawningInTemples;
        try {
            var world = mock(ServerLevel.class);
            var pos = BlockPos.ZERO;
            var random = net.minecraft.util.RandomSource.create(137);
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.huskSpawningInTemples = true;
            assertFalse(CarpetStructureSpawns.surfaceSky(world, EntityTypes.ZOMBIE, pos));
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.huskSpawningInTemples = false;
            assertFalse(CarpetStructureSpawns.surfaceSky(world, EntityTypes.HUSK, pos));
            verify(world, never()).getLevel();
            try (var monsters = mockStatic(Monster.class, CALLS_REAL_METHODS)) {
                monsters.when(() -> Monster.checkMonsterSpawnRules(EntityTypes.HUSK, world, EntitySpawnReason.SPAWNER, pos, random)).thenReturn(true);
                assertTrue(Monster.checkSurfaceMonstersSpawnRules(EntityTypes.HUSK, world, EntitySpawnReason.SPAWNER, pos, random));
            }
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.huskSpawningInTemples = old;
        }
    }

    @Test
    void piglinBruteActuallyUsesGroundPlacementOnlyWhenTheRuleIsEnabled() {
        boolean old = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.piglinsSpawningInBastions;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.piglinsSpawningInBastions = false;
            var original = SpawnPlacements.getPlacementType(EntityTypes.PIGLIN_BRUTE);
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.piglinsSpawningInBastions = true;
            assertSame(SpawnPlacementTypes.ON_GROUND, SpawnPlacements.getPlacementType(EntityTypes.PIGLIN_BRUTE));
            assertSame(SpawnPlacementTypes.ON_GROUND, SpawnPlacements.getPlacementType(EntityTypes.PIGLIN));
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.piglinsSpawningInBastions = false;
            assertSame(original, SpawnPlacements.getPlacementType(EntityTypes.PIGLIN_BRUTE));
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.piglinsSpawningInBastions = old;
        }
    }
}
