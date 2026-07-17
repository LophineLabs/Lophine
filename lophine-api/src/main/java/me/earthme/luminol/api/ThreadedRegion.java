package me.earthme.luminol.api;

import org.bukkit.Location;
import org.bukkit.World;

import org.jetbrains.annotations.Nullable;

/**
 * A mirror of folia's ThreadedRegion</br>
 * Including some handy methods to get the information of the tick region</br>
 * Note: You should call these methods inside this tick region's thread context
 */
public interface ThreadedRegion {
    /**
     * Get the center chunk pos of this tick region</br>
     * Note:</br>
     * 1.Global region will return a null value(But we don't finish the global region yet()</br>
     * 2.You should call these methods inside this tick region's thread context
     *
     * @return The center chunk pos
     */
    @Nullable
    Location getCenterChunkPos();

    /**
     * Get the dead section percent of this tick region
     * Note: </br>
     * 1.Dead percent is mean the percent of the unloaded chunk count of this tick region, which is also used for determine
     * that the tick region should or not check for splitting</br>
     * 2.You should call these methods inside this tick region's thread context
     *
     * @return The dead section percent
     */
    double getDeadSectionPercent();

    /**
     * Get the tick region data of this tick region</br>
     * Note:</br>
     * 1.You should call this method inside this tick region's thread context</br>
     * 2.You should call these methods inside this tick region's thread context
     *
     * @return The tick region data
     */
    TickRegionData getTickRegionData();

    /**
     * Get the world of this tick region</br>
     * Note: Global region will return a null value too
     *
     * @return The world of this tick region
     */
    @Nullable
    World getWorld();

    /**
     * Get the id of the tick region</br>
     *
     * @return The id of the tick region
     */
    long getId();
}
