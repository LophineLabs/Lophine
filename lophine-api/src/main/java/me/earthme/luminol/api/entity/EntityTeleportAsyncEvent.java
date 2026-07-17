package me.earthme.luminol.api.entity;

import org.apache.commons.lang3.Validate;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A simple event fired when a teleportAsync was called
 *
 * @see org.bukkit.entity.Entity#teleportAsync(org.bukkit.Location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause)
 * @see org.bukkit.entity.Entity#teleportAsync(org.bukkit.Location)
 * (Also fired when teleportAsync called from nms)
 */
public class EntityTeleportAsyncEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Entity entity;
    private final PlayerTeleportEvent.TeleportCause teleportCause;
    private final Location destination;

    public EntityTeleportAsyncEvent(Entity entity, PlayerTeleportEvent.TeleportCause teleportCause, Location destination) {
        Validate.notNull(entity, "entity cannot be a null value!");
        Validate.notNull(teleportCause, "teleportCause cannot be a null value!");
        Validate.notNull(destination, "destination cannot be a null value!");

        this.entity = entity;
        this.teleportCause = teleportCause;
        this.destination = destination;
    }

    /**
     * Get the entity is about to be teleported
     *
     * @return that entity
     */
    public @NotNull Entity getEntity() {
        return this.entity;
    }

    /**
     * Get the cause of the teleport
     *
     * @return the cause
     */
    public @NotNull PlayerTeleportEvent.TeleportCause getTeleportCause() {
        return this.teleportCause;
    }

    /**
     * Get the destination of the teleport
     *
     * @return the destination
     */
    public @NotNull Location getDestination() {
        return this.destination;
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
