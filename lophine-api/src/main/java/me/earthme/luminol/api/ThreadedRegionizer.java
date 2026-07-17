package me.earthme.luminol.api;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * A mirror of folia's ThreadedRegionizer
 */
public interface ThreadedRegionizer {
    /**
     * Get all the tick regions
     *
     * @return Temporary copied collection of all tick regions
     */
    Collection<ThreadedRegion> getAllRegions();

    /**
     * Get the tick region at the given chunk coordinates
     *
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return The tick region at the given chunk coordinates
     */
    @Nullable
    ThreadedRegion getAtSynchronized(int chunkX, int chunkZ);

    /**
     * Get the tick region at the given chunk coordinates
     *
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @return The tick region at the given chunk coordinates
     */
    @Nullable
    ThreadedRegion getAtUnSynchronized(int chunkX, int chunkZ);

    /**
     * Get the tick region at the given location
     *
     * @param pos The location
     * @return The tick region at the given location
     */
    @Nullable
    default ThreadedRegion getAtSynchronized(@NotNull Location pos) {
        return this.getAtSynchronized(pos.getBlockX() >> 4, pos.getBlockZ() >> 4);
    }

    /**
     * Get the tick region at the given location
     *
     * @param pos The location
     * @return The tick region at the given location
     */
    @Nullable
    default ThreadedRegion getAtUnSynchronized(@NotNull Location pos) {
        return this.getAtUnSynchronized(pos.getBlockX() >> 4, pos.getBlockZ() >> 4);
    }
}
