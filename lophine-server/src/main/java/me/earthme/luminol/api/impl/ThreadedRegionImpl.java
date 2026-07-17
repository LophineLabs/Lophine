package me.earthme.luminol.api.impl;

import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import me.earthme.luminol.api.ThreadedRegion;
import me.earthme.luminol.api.TickRegionData;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Location;
import org.bukkit.World;

import javax.annotation.Nullable;

public class ThreadedRegionImpl implements ThreadedRegion {
    private final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> internal;

    public ThreadedRegionImpl(ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> internal) {
        this.internal = internal;
    }

    @Nullable
    @Override
    public Location getCenterChunkPos() {
        final ChunkPos centerChunkPos = this.internal.getCenterChunk();

        if (centerChunkPos == null) {
            return null;
        }

        return new Location(this.internal.regioniser.world.getWorld(), centerChunkPos.getMiddleBlockX(), 0, centerChunkPos.getMiddleBlockZ());
    }

    @Override
    public double getDeadSectionPercent() {
        return this.internal.getDeadSectionPercent();
    }

    @Override
    public TickRegionData getTickRegionData() {
        return this.internal.getData().tickRegionDataAPI;
    }

    @Nullable
    @Override
    public World getWorld() {
        return this.internal.regioniser.world.getWorld();
    }

    @Override
    public long getId() {
        return this.internal.id;
    }
}