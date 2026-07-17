package me.earthme.luminol.api;

/**
 * A simple package of folia's tick region state.It linked to the RegionStats of the nms part so that</br>
 * You could call these methods to get the status of this tick region</br>
 */
public interface RegionStats {
    /**
     * Get the entity count in this tick region
     *
     * @return the entity count
     */
    int getEntityCount();

    /**
     * Get the player count in this tick region
     *
     * @return the player count
     */
    int getPlayerCount();

    /**
     * Get the chunk count in this tick region
     *
     * @return the chunk count
     */
    int getChunkCount();
}
