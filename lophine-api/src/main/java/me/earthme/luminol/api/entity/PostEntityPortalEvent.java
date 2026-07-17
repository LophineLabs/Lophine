package me.earthme.luminol.api.entity;

import org.apache.commons.lang3.Validate;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A simple event created for missing teleport events api of folia
 * This event is fired when the entity portal process has been done
 */
public class PostEntityPortalEvent extends Event {
    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Entity teleportedEntity;

    public PostEntityPortalEvent(Entity teleportedEntity) {
        Validate.notNull(teleportedEntity, "teleportedEntity cannot be null!");

        this.teleportedEntity = teleportedEntity;
    }

    /**
     * Get the entity which was teleported
     *
     * @return the entity which was teleported
     */
    public Entity getTeleportedEntity() {
        return this.teleportedEntity;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
