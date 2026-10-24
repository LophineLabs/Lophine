package me.earthme.luminol.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * All per-world config overlays attached to a single {@link net.minecraft.world.level.Level}.
 */
public final class WorldConfigHolder {
    public static final WorldConfigHolder EMPTY = new WorldConfigHolder("", Map.of());

    private final String worldName;
    private final Map<String, WorldConfigInstance> byConfigName;

    WorldConfigHolder(@NotNull String worldName, @NotNull Map<String, WorldConfigInstance> byConfigName) {
        this.worldName = worldName;
        this.byConfigName = byConfigName;
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

    /**
     * Resolve {@code key} from this world's overlay of {@code configName}.
     * Missing world values fall back to the global default.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String configName, String key) {
        WorldConfigInstance instance = byConfigName.get(configName);
        if (instance != null) {
            return instance.get(key);
        }
        ConfigsInstance global = ConfigManager.getConfigs(configName);
        return global == null ? null : (T) global.getLiveFallback(key);
    }

    public void save() {
        byConfigName.values().forEach(WorldConfigInstance::save);
    }

    public void reload() {
        byConfigName.values().forEach(WorldConfigInstance::reload);
    }

    void close() {
        byConfigName.values().forEach(WorldConfigInstance::close);
    }

    void reload(String configName) {
        WorldConfigInstance instance = byConfigName.get(configName);
        if (instance != null) {
            instance.reload();
        }
    }
}
