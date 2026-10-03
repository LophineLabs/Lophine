// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;


import com.google.common.collect.Sets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

public class CarpetMobAI
{
    private static Map<EntityType<?>, Set<TrackingType>> aiTrackers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<net.minecraft.world.entity.npc.villager.Villager> bedQueries=java.util.Collections.newSetFromMap(
        java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>()));

    public static void resetTrackers()
    {
        aiTrackers.clear();
        bedQueries.clear();
    }

    public static boolean isTracking(Entity e, TrackingType type)
    {
        if (e.level().isClientSide())
            return false;
        Set<TrackingType> currentTrackers = aiTrackers.get(e.getType());
        if (currentTrackers == null)
            return false;
        return currentTrackers.contains(type);
    }

    public static void clearTracking(final MinecraftServer server, EntityType<? extends Entity> etype)
    {
        aiTrackers.remove(etype);
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            for (var world : server.getAllLevels()) world.regioniser.computeForAllRegions(region -> {
                var center = region.getCenterChunk(); if (center == null) return;
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(world, center.x(), center.z(), () -> {
                    for (var entity : world.getCurrentWorldData().getLoadedEntities()) if (entity.getType() == etype && entity.hasCustomName()) {
                        entity.setCustomNameVisible(false); entity.setCustomName(null);
                    }
                });
            });
        });
    }

    public static void startTracking(EntityType<?> e, TrackingType type)
    {
        aiTrackers.putIfAbsent(e,java.util.concurrent.ConcurrentHashMap.newKeySet());
        aiTrackers.get(e).add(type);
    }

    public static void showNearbyBeds(net.minecraft.world.entity.npc.villager.Villager villager,net.minecraft.server.level.ServerPlayer source) {
        if(bedQueries.add(villager)) showNearbyBeds(villager,source,0);
    }

    private static void showNearbyBeds(net.minecraft.world.entity.npc.villager.Villager villager,net.minecraft.server.level.ServerPlayer source,int attempt) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(villager,"Carpet AI query must capture its villager on its owner");
        if(villager.isRemoved() || !isTracking(villager,TrackingType.BREEDING)) { bedQueries.remove(villager); return; }
        ServerLevel world=(ServerLevel)villager.level();
        BlockPos center=villager.blockPosition().immutable();
        int radius=Math.max(80,(int)Math.ceil(villager.getNavigation().carpetMaxPathLength())+24);
        CarpetRegionLease.<Boolean>runValue(world,(center.getX()-radius)>>4,(center.getZ()-radius)>>4,
            (center.getX()+radius)>>4,(center.getZ()+radius)>>4,lease -> {
                if(!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(villager)) return false;
                if(villager.isRemoved() || !isTracking(villager,TrackingType.BREEDING)) return true;
                if(villager.level()!=world || !villager.blockPosition().equals(center)
                    || Math.max(80,(int)Math.ceil(villager.getNavigation().carpetMaxPathLength())+24)>radius) return false;
                villager.carpetShowNearbyBeds();
                return true;
            }).whenComplete((shown,failure) -> {
                if(failure==null && !Boolean.TRUE.equals(shown) && attempt<4) {
                    boolean accepted=villager.getBukkitEntity().taskScheduler.schedule(owned -> showNearbyBeds(villager,source,attempt+1),
                        retired -> bedQueries.remove(villager),1);
                    if(!accepted) bedQueries.remove(villager);
                    return;
                }
                bedQueries.remove(villager);
                if(failure!=null || !Boolean.TRUE.equals(shown)) source.getBukkitEntity().taskScheduler.schedule(owned ->
                    ((net.minecraft.server.level.ServerPlayer)owned).sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "AI bed query failed: "+(failure==null ? "villager moved while acquiring its region" : failure.getMessage()))),null,1);
            });
    }

    public static Stream<String> availbleTypes(CommandSourceStack source)
    {
        Set<EntityType<?>> types = new HashSet<>();
        for (TrackingType type: TrackingType.values())
        {
            types.addAll(type.types);
        }
        return types.stream().map(t -> source.registryAccess().lookupOrThrow(Registries.ENTITY_TYPE).getKey(t).getPath());
    }

    public static Stream<String> availableFor(EntityType<?> entityType)
    {
        Set<TrackingType> availableOptions = new HashSet<>();
        for (TrackingType type: TrackingType.values())
            if (type.types.contains(entityType))
                availableOptions.add(type);
        return availableOptions.stream().map(t -> t.name().toLowerCase());
    }

    public enum TrackingType
    {
        IRON_GOLEM_SPAWNING(Set.of(EntityTypes.VILLAGER)),
        BREEDING(Set.of(EntityTypes.VILLAGER));
        public final Set<EntityType<?>> types;
        TrackingType(Set<EntityType<?>> applicableTypes)
        {
            types = applicableTypes;
        }
    }
}
