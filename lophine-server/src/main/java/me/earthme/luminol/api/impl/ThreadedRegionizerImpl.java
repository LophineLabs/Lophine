package me.earthme.luminol.api.impl;

import io.papermc.paper.threadedregions.TickRegions;
import me.earthme.luminol.api.ThreadedRegion;
import me.earthme.luminol.api.ThreadedRegionizer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ThreadedRegionizerImpl implements ThreadedRegionizer {
    private final ServerLevel internal;

    public ThreadedRegionizerImpl(ServerLevel internal) {
        this.internal = internal;
    }

    @Override
    public Collection<ThreadedRegion> getAllRegions() {
        final List<ThreadedRegion> ret = new ArrayList<>();

        this.internal.regioniser.computeForAllRegions(region -> ret.add(region.threadedRegionAPI));

        return ret;
    }

    @Override
    public ThreadedRegion getAtSynchronized(int chunkX, int chunkZ) {
        final io.papermc.paper.threadedregions.ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> got = this.internal.regioniser.getRegionAtSynchronised(chunkX, chunkZ);

        if (got == null) {
            return null;
        }

        return got.threadedRegionAPI;
    }

    @Override
    public ThreadedRegion getAtUnSynchronized(int chunkX, int chunkZ) {
        final io.papermc.paper.threadedregions.ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> got = this.internal.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ);

        if (got == null) {
            return null;
        }

        return got.threadedRegionAPI;
    }
}