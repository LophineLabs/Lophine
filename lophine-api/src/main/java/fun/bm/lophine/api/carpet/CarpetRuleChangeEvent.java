package fun.bm.lophine.api.carpet;

import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Notification after a live rule value changes, dispatched on the Folia global
 * region. This event is not cancellable and grants no entity/region ownership.
 * It covers native rule commands, this API, single configuration applications and
 * completed file reloads. Startup initialization, unchanged values, restart-only
 * saves and direct writes to internal fields do not produce this event.
 */
public final class CarpetRuleChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CarpetRule previous;
    private final CarpetRule current;
    private final Cause cause;
    private final @Nullable String pluginName;

    /**
     * Creates a notification describing a live rule change.
     *
     * @param previous   value before the change
     * @param current    value after the change
     * @param cause      operation that changed the value
     * @param pluginName requesting plugin name for API changes, otherwise null
     */
    public CarpetRuleChangeEvent(CarpetRule previous, CarpetRule current, Cause cause, @Nullable String pluginName) {
        this.previous = Objects.requireNonNull(previous);
        this.current = Objects.requireNonNull(current);
        this.cause = Objects.requireNonNull(cause);
        this.pluginName = pluginName;
    }

    /**
     * Returns the old snapshot.
     *
     * @return the old snapshot
     */
    public CarpetRule getPreviousRule() {
        return this.previous;
    }

    /**
     * Returns the new snapshot.
     *
     * @return the new snapshot
     */
    public CarpetRule getRule() {
        return this.current;
    }

    /**
     * Returns the mutation origin.
     *
     * @return the mutation origin
     */
    public Cause getCause() {
        return this.cause;
    }

    /**
     * Returns requesting plugin name, or null for non-API changes.
     *
     * @return requesting plugin name, or null for non-API changes
     */
    public @Nullable String getPluginName() {
        return this.pluginName;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Returns handlers for this event.
     *
     * @return handlers for this event
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** Origin of a live mutation. */
    public enum Cause {
        /** A Carpet rule command or its Scarpet command wrapper. */
        COMMAND,
        /** A plugin invoked CarpetApi.setRule. */
        API,
        /** A completed configuration reload. */
        RELOAD,
        /** A configuration application outside the native rule command. */
        CONFIGURATION
    }
}
