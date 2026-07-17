package me.earthme.luminol.api.portal;

import org.apache.commons.lang3.Validate;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A event fired when the portal process started locating the destination position
 * Notice: If you changed the destination to an another position in end teleportation.The end platform won't create under the entity and won't create
 * if the position is out of current tick region
 */
public class PortalLocateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Location original;
    private final Location destination;

    public PortalLocateEvent(Location original, Location destination) {
        Validate.notNull(original, "original couldn't be null!");
        Validate.notNull(destination, "destination couldn't be null!");

        this.original = original;
        this.destination = destination;
    }

    /**
     * Get the destination position of this teleportation
     *
     * @return the destination position
     */
    public Location getDestination() {
        return this.destination;
    }

    /**
     * Get the original portal position of this teleportation
     *
     * @return the original portal position
     */
    public Location getOriginal() {
        return this.original;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
