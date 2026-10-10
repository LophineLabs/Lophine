package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.configuration.WorldConfiguration;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.*;

class TisSpawnFuseSourceTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private boolean extra, height, health, oldHealth;
    private double jockey, leader;
    private int fuse;

    @BeforeEach
    void save() {
        extra = GeneralCompatConfig.naturalSpawningUse13HeightmapExtra;
        height = GeneralCompatConfig.naturalSpawningUse13Heightmap;
        health = GeneralCompatConfig.leaderZombieSpawnWithMaxHealthDisabled;
        jockey = GeneralCompatConfig.spawnJockeyProbably;
        leader = GeneralCompatConfig.spawnLeaderZombieProbably;
        fuse = GeneralCompatConfig.tntFuseDuration;
        oldHealth = fun.bm.lophine.config.modules.function.OldFeatureConfig.oldLeaderZombieHealth;
    }

    @AfterEach
    void restore() {
        GeneralCompatConfig.naturalSpawningUse13HeightmapExtra = extra;
        GeneralCompatConfig.naturalSpawningUse13Heightmap = height;
        GeneralCompatConfig.leaderZombieSpawnWithMaxHealthDisabled = health;
        GeneralCompatConfig.spawnJockeyProbably = jockey;
        GeneralCompatConfig.spawnLeaderZombieProbably = leader;
        GeneralCompatConfig.tntFuseDuration = fuse;
        fun.bm.lophine.config.modules.function.OldFeatureConfig.oldLeaderZombieHealth = oldHealth;
    }

    private static void field(Object object, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(object, value);
    }

    @Test
    void actualHeightSamplerCapturesMinYOnceAndSpecialBlockShortCircuitsOpacity() {
        GeneralCompatConfig.naturalSpawningUse13HeightmapExtra = true;
        var world = mock(ServerLevel.class);
        when(world.getMinY()).thenReturn(0);
        var chunk = mock(ChunkAccess.class);
        var slime = mock(BlockState.class);
        when(slime.getBlock()).thenReturn(Blocks.SLIME_BLOCK);
        var air = mock(BlockState.class);
        when(air.getBlock()).thenReturn(Blocks.AIR);
        when(air.getLightDampening()).thenReturn(0);
        var stone = mock(BlockState.class);
        when(stone.getBlock()).thenReturn(Blocks.STONE);
        when(stone.getLightDampening()).thenReturn(15);
        var reads = new ArrayList<Integer>();
        when(chunk.getBlockState(any())).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            reads.add(pos.getY());
            return switch (pos.getY()) {
                case 3 -> slime;
                case 2 -> air;
                default -> stone;
            };
        });
        assertEquals(1, NaturalSpawner.carpetSample13SpawnHeight(world, chunk, 0, 0, 3));
        assertEquals(List.of(3, 2, 1), reads);
        verify(world, times(1)).getMinY();
        verify(slime, never()).canOcclude();
        verify(slime, never()).useShapeForLightOcclusion();
        verify(slime, never()).getLightDampening();
        GeneralCompatConfig.naturalSpawningUse13HeightmapExtra = false;
        when(slime.getLightDampening()).thenReturn(15);
        assertEquals(3, NaturalSpawner.carpetSample13SpawnHeight(world, chunk, 0, 0, 3));
        verify(slime).canOcclude();
        verify(slime).getLightDampening();
    }

    @Test
    void actualSpawnReportIncludesSourceSecondHeightSampleBeforeSpawns() {
        GeneralCompatConfig.naturalSpawningUse13Heightmap = true;
        var world = mock(ServerLevel.class);
        var chunk = mock(ChunkAccess.class);
        BlockPos pos = new BlockPos(2, 10, -3);
        when(world.getChunk(pos)).thenReturn(chunk);
        when(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 2, -3)).thenReturn(15);
        when(world.getMinY()).thenReturn(0);
        var stone = mock(BlockState.class);
        when(stone.getBlock()).thenReturn(Blocks.STONE);
        when(stone.getLightDampening()).thenReturn(15);
        when(chunk.getBlockState(any())).thenReturn(stone);
        var source = mock(ServerChunkCache.class);
        var generator = mock(ChunkGenerator.class);
        when(world.getChunkSource()).thenReturn(source);
        when(source.getGenerator()).thenReturn(generator);
        when(world.getBlockState(pos.below())).thenReturn(Blocks.STONE.defaultBlockState());
        when(generator.getMobsAt(eq(world), any(), any(), eq(pos))).thenReturn(WeightedList.of());
        {
            var lines = CarpetSpawnProbe.report(pos, world).stream().map(net.minecraft.network.chat.Component::getString).toList();
            assertEquals(3, lines.size());
            assertEquals("Maximum spawn Y value for (+2, -3) is 16. You are 6 blocks below it. (13 ver)", lines.get(1));
            assertEquals("Spawns:", lines.get(2));
            verify(chunk, times(2)).getHeight(Heightmap.Types.WORLD_SURFACE, 2, -3);
        }
    }

    @Test
    void actualLeaderGetterPrecedesRuleConditionAndConditionReadsCurrentFlag() throws Exception {
        GeneralCompatConfig.spawnLeaderZombieProbably = 1;
        GeneralCompatConfig.leaderZombieSpawnWithMaxHealthDisabled = true;
        fun.bm.lophine.config.modules.function.OldFeatureConfig.oldLeaderZombieHealth = false;
        var zombie = mock(Zombie.class, call -> call.getMethod().getName().equals("handleAttributes") ? call.callRealMethod() : RETURNS_DEFAULTS.answer(call));
        var random = mock(RandomSource.class);
        field(zombie, Entity.class, "random", random);
        when(zombie.getAttribute(any())).thenReturn(mock(AttributeInstance.class));
        doAnswer(call -> {
            GeneralCompatConfig.leaderZombieSpawnWithMaxHealthDisabled = false;
            return 40F;
        }).when(zombie).getMaxHealth();
        Method method = Zombie.class.getDeclaredMethod("handleAttributes", float.class, EntitySpawnReason.class);
        method.setAccessible(true);
        method.invoke(zombie, 0F, EntitySpawnReason.NATURAL);
        verify(random).nextFloat();
        verify(zombie).getMaxHealth();
        verify(zombie).setHealth(40F);
        clearInvocations(zombie);
        GeneralCompatConfig.leaderZombieSpawnWithMaxHealthDisabled = true;
        doReturn(40F).when(zombie).getMaxHealth();
        method.invoke(zombie, 0F, EntitySpawnReason.NATURAL);
        verify(zombie).getMaxHealth();
        verify(zombie, never()).setHealth(anyFloat());
    }

    @Test
    void actualLeaderFloatSentinelRetainsOriginalNegativeDifficultyComparison() throws Exception {
        GeneralCompatConfig.spawnLeaderZombieProbably = 1;
        var zombie = mock(Zombie.class, call -> call.getMethod().getName().equals("handleAttributes") ? call.callRealMethod() : RETURNS_DEFAULTS.answer(call));
        field(zombie, Entity.class, "random", mock(RandomSource.class));
        when(zombie.getAttribute(any())).thenReturn(mock(AttributeInstance.class));
        Method method = Zombie.class.getDeclaredMethod("handleAttributes", float.class, EntitySpawnReason.class);
        method.setAccessible(true);
        method.invoke(zombie, Float.NEGATIVE_INFINITY, EntitySpawnReason.NATURAL);
        verify(zombie, never()).getMaxHealth();
        verify(zombie, never()).setCanBreakDoors(true);
    }

    @Test
    void actualStriderOriginalDrawRunsBeforeRuleStateAndSecondHookReadsCurrentFlag() {
        GeneralCompatConfig.spawnJockeyProbably = 1;
        var strider = mock(Strider.class, call -> call.getMethod().getName().equals("finalizeSpawn") ? call.callRealMethod() : RETURNS_DEFAULTS.answer(call));
        var world = mock(ServerLevel.class);
        var random = mock(RandomSource.class);
        when(world.getRandom()).thenReturn(random);
        when(world.getLevel()).thenReturn(world);
        when(strider.getAttribute(any())).thenReturn(mock(AttributeInstance.class));
        when(random.nextInt(30)).thenAnswer(call -> {
            GeneralCompatConfig.spawnJockeyProbably = 0;
            return 0;
        });
        when(random.nextInt(10)).thenAnswer(call -> {
            GeneralCompatConfig.spawnJockeyProbably = -1;
            return 1;
        });
        var data = strider.finalizeSpawn(world, mock(DifficultyInstance.class), EntitySpawnReason.NATURAL, null);
        assertInstanceOf(AgeableMob.AgeableMobGroupData.class, data);
        verify(random).nextInt(30);
        verify(random).nextInt(10);
        verify(world, never()).isSpawningMonsters();
    }

    private static final List<Integer> CONSTRUCTOR_FUSES = new ArrayList<>();

    private static class ObservedTnt extends PrimedTnt {
        ObservedTnt(Level world) {
            super(EntityTypes.TNT, world);
        }

        ObservedTnt(Level world, double x, double y, double z, LivingEntity owner) {
            super(world, x, y, z, owner);
        }

        @Override
        public void setFuse(int value) {
            super.setFuse(value);
            CONSTRUCTOR_FUSES.add(value);
        }
    }

    private static ServerLevel actualWorld() throws Exception {
        var world = mock(ServerLevel.class);
        field(world, Level.class, "spigotConfig", mock(org.spigotmc.SpigotWorldConfig.class));
        var paper = mock(WorldConfiguration.class);
        paper.entities = mock(WorldConfiguration.Entities.class);
        paper.entities.spawning = mock(WorldConfiguration.Entities.Spawning.class);
        paper.entities.spawning.despawnTime = new Reference2ObjectOpenHashMap<>();
        when(world.paperConfig()).thenReturn(paper);
        when(world.getServer()).thenReturn(mock(MinecraftServer.class));
        return world;
    }

    @Test
    void actualBothConstructorsHaveSourceTailAndKeepOriginalMiddleVanillaFuse() throws Exception {
        var world = actualWorld();
        GeneralCompatConfig.tntFuseDuration = 7;
        CONSTRUCTOR_FUSES.clear();
        var bare = new ObservedTnt(world);
        assertEquals(7, bare.getFuse());
        assertEquals(List.of(7), CONSTRUCTOR_FUSES);
        CONSTRUCTOR_FUSES.clear();
        var positioned = new ObservedTnt(world, 5, 64, -3, null);
        assertEquals(7, positioned.getFuse());
        assertEquals(List.of(7, 80, 7), CONSTRUCTOR_FUSES);
        assertEquals(5, positioned.getX());
        assertEquals(-3, positioned.getZ());
    }

    @Test
    void actualExplodedTntPassesSourceFloorFourBeforeShortFuseRandomAndKeepsAddFalse() {
        var world = mock(ServerLevel.class);
        var rules = mock(GameRules.class);
        when(world.getGameRules()).thenReturn(rules);
        when(rules.get(GameRules.TNT_EXPLODES)).thenReturn(true);
        var random = mock(RandomSource.class);
        when(world.getRandom()).thenReturn(random);
        var explosion = mock(Explosion.class);
        var block = mock(TntBlock.class, CALLS_REAL_METHODS);
        try (var primed = mockConstruction(PrimedTnt.class, (tnt, context) -> when(tnt.getFuse()).thenReturn(0)); var statics = mockStatic(PrimedTnt.class, CALLS_REAL_METHODS)) {
            block.wasExploded(world, BlockPos.ZERO, explosion);
            var actual = primed.constructed().getFirst();
            statics.verify(() -> PrimedTnt.getRandomShortFuse(4, random));
            verify(random).nextInt(1);
            verify(actual).setFuse(0);
            verify(world).addFreshEntity(actual);
        }
    }
}
