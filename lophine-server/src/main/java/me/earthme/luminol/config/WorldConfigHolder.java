package me.earthme.luminol.config;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-{@link net.minecraft.world.level.Level} copy of every world-config key,
 * with that world's overrides applied on top of the live global values.
 * <p>
 * Config instances are stored in parallel arrays rather than a {@code Map} so that
 * the tick hot path ({@link #get(WorldValue)}) reads the snapshot array directly and
 * the rebuild path iterates a contiguous range without allocating an entry iterator.
 * The number of registered world configs is small, so a linear scan on
 * {@link #get(String)} beats HashMap hashing on every call.
 */
public final class WorldConfigHolder {
    public static final WorldConfigHolder EMPTY =
            new WorldConfigHolder("", new String[0], new WorldConfigInstance[0], new Object[0]);

    private final String worldName;
    private final String[] configNames;
    private final WorldConfigInstance[] instances;
    private volatile Object[] values;

    WorldConfigHolder(@NotNull String worldName, @NotNull String[] configNames, @NotNull WorldConfigInstance[] instances) {
        this(worldName, configNames, instances, null);
    }

    private WorldConfigHolder(
            @NotNull String worldName,
            @NotNull String[] configNames,
            @NotNull WorldConfigInstance[] instances,
            @Nullable Object[] values
    ) {
        this.worldName = worldName;
        this.configNames = configNames;
        this.instances = instances;
        if (values != null) {
            this.values = values;
            return;
        }
        for (WorldConfigInstance instance : instances) {
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
        String[] names = this.configNames;
        WorldConfigInstance[] local = this.instances;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(configName)) {
                return local[i];
            }
        }
        return null;
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
        WorldConfigInstance instance = get(configName);
        if (instance != null) {
            return instance.get(key);
        }
        ConfigsInstance global = ConfigManager.getConfigs(configName);
        return global == null ? null : (T) global.getLiveFallback(key);
    }

    void rebuildSnapshot() {
        if (this.values.length == 0 && instances.length == 0) {
            return;
        }
        this.values = buildSnapshot();
    }

    private Object[] buildSnapshot() {
        Object[] next = WorldValues.copyGlobals();
        for (WorldConfigInstance instance : instances) {
            instance.applyOverridesTo(next);
        }
        return next;
    }

    public void save() {
        for (WorldConfigInstance instance : instances) {
            instance.save();
        }
    }

    public void reload() {
        for (WorldConfigInstance instance : instances) {
            instance.reload();
        }
        rebuildSnapshot();
    }

    void close() {
        for (WorldConfigInstance instance : instances) {
            instance.close();
        }
    }

    void reload(String configName) {
        WorldConfigInstance instance = get(configName);
        if (instance != null) {
            instance.reload();
        }
        rebuildSnapshot();
    }
}
