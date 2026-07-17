package me.earthme.luminol.api.entity.player;

import org.apache.commons.lang3.Validate;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A simple event fired when the respawn process of player is done
 */
public class PostPlayerRespawnEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;

    public PostPlayerRespawnEvent(Player player) {
        Validate.notNull(player, "Player cannot be a null value!");

        this.player = player;
    }

    /**
     * Get the respawned player
     *
     * @return the player
     */
    public @NotNull Player getPlayer() {
        return this.player;
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
