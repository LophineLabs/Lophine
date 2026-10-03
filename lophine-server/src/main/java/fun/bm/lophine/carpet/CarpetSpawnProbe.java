// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;

import org.slf4j.LoggerFactory;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import static net.minecraft.world.entity.MobCategory.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public final class CarpetSpawnProbe {
    private static void killEntity(Entity entity) {
        if (entity.isRemoved()) return;
        List<Entity> passengers = List.copyOf(entity.getPassengers());
        Entity vehicle = entity.getVehicle();
        entity.discard();
        passengers.forEach(CarpetSpawnProbe::killEntity);
        if (vehicle != null && !vehicle.isRemoved()) killEntity(vehicle);
    }
    // yeeted from NaturalSpawner - temporary access fix
    private static WeightedList<MobSpawnSettings.SpawnerData> getSpawnEntries(ServerLevel serverLevel, StructureManager structureManager, ChunkGenerator chunkGenerator, MobCategory mobCategory, BlockPos blockPos, @Nullable Holder<Biome> holder) {
        return NaturalSpawner.isInNetherFortressBounds(blockPos, serverLevel, mobCategory, structureManager) ? NetherFortressStructure.FORTRESS_ENEMIES : chunkGenerator.getMobsAt(serverLevel, structureManager, mobCategory, blockPos);
    }

    public static List<Component> report(BlockPos pos, ServerLevel worldIn)
    {
        List<Component> rep = new ArrayList<>();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        ChunkAccess chunk = worldIn.getChunk(pos);
        int lc = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
        String relativeHeight = (y == lc) ? "right at it." : String.format("%d blocks %s it.", Mth.abs(y - lc), (y >= lc) ? "above" : "below");
        rep.add(CarpetMessenger.s(String.format("Maximum spawn Y value for (%+d, %+d) is %d. You are " + relativeHeight, x, z, lc)));
        if (GeneralCompatConfig.naturalSpawningUse13Heightmap) {
            int carpet13Y = NaturalSpawner.carpetSample13SpawnHeight(worldIn, chunk, x, z,
                chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)) + 1;
            String carpet13Relative = y == carpet13Y ? "right at it." : String.format("%d blocks %s it.", Mth.abs(y - carpet13Y), y >= carpet13Y ? "above" : "below");
            rep.add(CarpetMessenger.s(String.format("Maximum spawn Y value for (%+d, %+d) is %d. You are %s (13 ver)", x, z, carpet13Y, carpet13Relative)));
        }
        rep.add(CarpetMessenger.s("Spawns:"));
        for (MobCategory category : MobCategory.values())
        {
            String categoryCode = String.valueOf(category).substring(0, 3);
            WeightedList<MobSpawnSettings.SpawnerData> lst = getSpawnEntries(worldIn, worldIn.structureManager(), worldIn.getChunkSource().getGenerator(), category, pos, worldIn.getBiome(pos));
            if (lst != null && !lst.isEmpty())
            {
                for (Weighted<MobSpawnSettings.SpawnerData> wspawnEntry : lst.unwrap())
                {
                    MobSpawnSettings.SpawnerData spawnEntry = wspawnEntry.value();
                    if (SpawnPlacements.getPlacementType(spawnEntry.type()) == null)
                        continue; // vanilla bug
                    boolean canSpawn = SpawnPlacements.isSpawnPositionOk(spawnEntry.type(), worldIn, pos);
                    int willSpawn = -1;
                    boolean fits = false;
                    
                    Mob mob;
                    try
                    {
                        mob = (Mob) spawnEntry.type().create(worldIn, EntitySpawnReason.NATURAL);
                    }
                    catch (Exception e)
                    {
                        LoggerFactory.getLogger("CarpetSpawnProbe").warn("Exception while creating mob for spawn reporter", e);
                        return rep;
                    }
                    
                    if (canSpawn)
                    {
                        willSpawn = 0;
                        for (int attempt = 0; attempt < 50; ++attempt)
                        {
                            float f = x + 0.5F;
                            float f1 = z + 0.5F;
                            mob.snapTo(f, y, f1, worldIn.getRandom().nextFloat() * 360.0F, 0.0F);
                            fits = worldIn.noCollision(mob);
                            EntityType<?> etype = mob.getType();

                            for (int i = 0; i < 20; ++i)
                            {
                                if (
                                        SpawnPlacements.checkSpawnRules(etype,worldIn, EntitySpawnReason.NATURAL, pos, worldIn.getRandom()) &&
                                        SpawnPlacements.isSpawnPositionOk(etype, worldIn, pos) &&
                                        mob.checkSpawnRules(worldIn, EntitySpawnReason.NATURAL)
                                    // && mob.canSpawn(worldIn) // entity collisions // mostly - except ocelots
                                )
                                {
                                    if (etype == EntityTypes.OCELOT)
                                    {
                                        BlockState blockState = worldIn.getBlockState(pos.below());
                                        if ((pos.getY() < worldIn.getSeaLevel()) || !(blockState.is(Blocks.GRASS_BLOCK) || blockState.is(BlockTags.LEAVES))) {
                                           continue;
                                        }
                                    }
                                    willSpawn += 1;
                                }
                            }
                            mob.finalizeSpawn(worldIn, worldIn.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.NATURAL, null);
                            // the code invokes onInitialSpawn after getCanSpawHere
                            fits = fits && worldIn.noCollision(mob);
                            
                            killEntity(mob);
                            
                            try
                            {
                                mob = (Mob) spawnEntry.type().create(worldIn, EntitySpawnReason.NATURAL);
                            }
                            catch (Exception e)
                            {
                                LoggerFactory.getLogger("CarpetSpawnProbe").warn("Exception while creating mob for spawn reporter", e);
                                return rep;
                            }
                        }
                    }
                    
                    String mobTypeName = mob.getType().getDescription().getString();
                    //String pack_size = Integer.toString(mob.getMaxSpawnClusterSize());//String.format("%d-%d", animal.minGroupCount, animal.maxGroupCount);
                    int weight = wspawnEntry.weight();
                    if (canSpawn)
                    {
                        String color = (fits && willSpawn > 0) ? "e" : "gi";
                        rep.add(CarpetMessenger.c(
                                String.format("%s %s: %s (%d:%d-%d/%d), can: ", color, categoryCode, mobTypeName, weight, spawnEntry.count().minInclusive(), spawnEntry.count().maxInclusive(),  mob.getMaxSpawnClusterSize()),
                                "l YES",
                                color + " , fit: ",
                                (fits ? "l YES" : "r NO"),
                                color + " , will: ",
                                ((willSpawn > 0)?"l ":"r ") + Math.round((double)willSpawn) / 10 + "%"
                        ));
                    }
                    else
                    {
                        rep.add(CarpetMessenger.c(String.format("gi %s: %s (%d:%d-%d/%d), can: ", categoryCode, mobTypeName, weight, spawnEntry.count().minInclusive(), spawnEntry.count().maxInclusive(), mob.getMaxSpawnClusterSize()), "n NO"));
                    }
                    killEntity(mob);
                }
            }
        }
        return rep;
    }
}
