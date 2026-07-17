package me.earthme.luminol.api;

import org.bukkit.World;

/**
 * A mirror of folia's tick region data
 */
public interface TickRegionData {
    /**
     * Get the world it's currently holding
     *
     * @return the world
     */
    World getWorld();

    /**
     * Get the current tick count
     *
     * @return the current tick count
     */
    long getCurrentTickCount();

    /**
     * Get the region stats
     *
     * @return the region stats
     */
    RegionStats getRegionStats();
}
