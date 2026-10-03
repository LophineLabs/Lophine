// SPDX-License-Identifier: MIT
// Copyright (c) 2020 gnembon; adapted from Carpet f358000b175ddbcf1dd0bc59641c715fb0545664.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

/** The original husk predicate reads published structure starts, including during chunk generation. */
public final class CarpetStructureSpawns {
    private CarpetStructureSpawns() {}
    public static boolean surfaceSky(ServerLevelAccessor level, EntityType<?> type, BlockPos pos) {
        if (level.canSeeSky(pos)) return true;
        if (!GeneralCompatConfig.huskSpawningInTemples || type != EntityTypes.HUSK) return false;
        var world = level.getLevel();
        var pyramid = world.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(BuiltinStructures.DESERT_PYRAMID);
        if (pyramid == null) return false;
        var structures = world.structureManager();
        if (level instanceof WorldGenRegion generation) structures = structures.forWorldGenRegion(generation);
        return structures.getStructureAt(pos, pyramid).isValid();
    }
}
