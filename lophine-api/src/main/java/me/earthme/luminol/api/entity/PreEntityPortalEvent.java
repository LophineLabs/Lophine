package me.earthme.luminol.api.entity;

import org.apache.commons.lang3.Validate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A simple event created for missing teleport events api of folia
 * This event will be fired when a portal teleportation is about to happen
 */
public class PreEntityPortalEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Entity entity;
    private final Location portalPos;
    private final World destination;

    private boolean cancelled = false;

    public PreEntityPortalEvent(Entity entity, Location portalPos, World destination) {
        Validate.notNull(entity, "entity cannot be null!");
        Validate.notNull(portalPos, "portalPos cannot be null!");
        Validate.notNull(destination, "destination cannot be null!");

        this.entity = entity;
        this.portalPos = portalPos;
        this.destination = destination;
    }

    /**
     * Get the entity that is about to teleport
     *
     * @return the entity
     */
    public @NotNull Entity getEntity() {
        return this.entity;
    }

    /**
     * Get the location of the portal
     *
     * @return the portal location
     */
    public @NotNull Location getPortalPos() {
        return this.portalPos;
    }

    /**
     * Get the destination world
     *
     * @return the destination world
     */
    public @NotNull World getDestination() {
        return this.destination;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
