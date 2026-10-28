package me.earthme.luminol.config;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Per-{@link net.minecraft.world.level.Level} copy of every world-config key,
 * with that world's overrides applied on top of the live global values.
 */
public final class WorldConfigHolder {
    public static final WorldConfigHolder EMPTY = new WorldConfigHolder("", Map.of(), new Object[0]);

    private final String worldName;
    private final Map<String, WorldConfigInstance> byConfigName;
    private volatile Object[] values;

    WorldConfigHolder(@NotNull String worldName, @NotNull Map<String, WorldConfigInstance> byConfigName) {
        this(worldName, byConfigName, null);
    }

    private WorldConfigHolder(
            @NotNull String worldName,
            @NotNull Map<String, WorldConfigInstance> byConfigName,
            @Nullable Object[] values
    ) {
        this.worldName = worldName;
        this.byConfigName = byConfigName;
        if (values != null) {
            this.values = values;
            return;
        }
        for (WorldConfigInstance instance : byConfigName.values()) {
            instance.attach(this);
        }
        this.values = buildSnapshot();
    }

    public static WorldConfigHolder empty() {
        return EMPTY;
    }

    public String worldName() {
        return worldName;
    }

    public @Nullable WorldConfigInstance get(String configName) {
        return byConfigName.get(configName);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(WorldValue<T> key) {
        Object[] snap = this.values;
        int index = key.index();
        if (index < 0 || index >= snap.length) {
            return null;
        }
        return (T) snap[index];
    }

    /**
     * Resolve {@code key} from this world's copy of {@code configName}.
     * Missing entries fall back to the global default.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String configName, String key) {
        WorldValue<?> worldKey = WorldValues.keyOf(configName, key);
        if (worldKey != null) {
            Object value = get(worldKey);
            if (value != null) {
                return (T) value;
            }
        }
        WorldConfigInstance instance = byConfigName.get(configName);
        if (instance != null) {
            return instance.get(key);
        }
        ConfigsInstance global = ConfigManager.getConfigs(configName);
        return global == null ? null : (T) global.getLiveFallback(key);
    }

    void rebuildSnapshot() {
        if (this.values.length == 0 && byConfigName.isEmpty()) {
            return;
        }
        this.values = buildSnapshot();
    }

    private Object[] buildSnapshot() {
        Object[] next = WorldValues.copyGlobals();
        for (WorldConfigInstance instance : byConfigName.values()) {
            instance.applyOverridesTo(next);
        }
        return next;
    }

    public void save() {
        byConfigName.values().forEach(WorldConfigInstance::save);
    }

    public void reload() {
        byConfigName.values().forEach(WorldConfigInstance::reload);
        rebuildSnapshot();
    }

    void close() {
        byConfigName.values().forEach(WorldConfigInstance::close);
    }

    void reload(String configName) {
        WorldConfigInstance instance = byConfigName.get(configName);
        if (instance != null) {
            instance.reload();
        }
        rebuildSnapshot();
    }
}
