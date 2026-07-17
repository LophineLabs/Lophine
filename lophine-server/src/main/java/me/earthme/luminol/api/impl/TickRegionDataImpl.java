package me.earthme.luminol.api.impl;

import io.papermc.paper.threadedregions.TickRegions;
import me.earthme.luminol.api.RegionStats;
import me.earthme.luminol.api.TickRegionData;
import org.bukkit.World;

public class TickRegionDataImpl implements TickRegionData {
    private final TickRegions.TickRegionData internal;

    public TickRegionDataImpl(TickRegions.TickRegionData internal) {
        this.internal = internal;
    }

    @Override
    public World getWorld() {
        return this.internal.world.getWorld();
    }

    @Override
    public long getCurrentTickCount() {
        return this.internal.getCurrentTick();
    }

    @Override
    public RegionStats getRegionStats() {
        return this.internal.getRegionStats().regionStatsAPI;
    }

}