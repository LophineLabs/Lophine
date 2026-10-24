package me.earthme.luminol.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-world overlay for keys marked {@code @ConfigInfo(isWorldConfig = true)}.
 * Missing world values fall back to the parent instance's live global default.
 */
public class WorldConfigInstance {
    private final ConfigsInstance parent;
    private final String worldName;
    private final File configFile;
    private Map<String, Object> overrides;
    private CommentedFileConfig file;

    private WorldConfigInstance(@NotNull ConfigsInstance parent, @NotNull String worldName, @NotNull File configFile) {
        this.parent = parent;
        this.worldName = worldName;
        this.configFile = configFile;
    }

    static @Nullable WorldConfigInstance load(@NotNull ConfigsInstance parent, @NotNull String worldName, @NotNull File worldFolder) {
        if (!parent.usesWorldConfig()) {
            return null;
        }
        File configFile = resolveFile(parent, worldFolder);
        createIfAbsent(configFile);
        WorldConfigInstance instance = new WorldConfigInstance(parent, worldName, configFile);
        instance.reload();
        return instance;
    }

    static File resolveFile(@NotNull ConfigsInstance parent, @NotNull File worldFolder) {
        File dimensionConfig = new File(worldFolder, "dimension_config");
        File systemFolder = new File(dimensionConfig, parent.getBaseConfigFolder().getName());
        return new File(systemFolder, parent.getName() + "_world_config.toml");
    }

    public String worldName() {
        return worldName;
    }

    public ConfigsInstance parent() {
        return parent;
    }

    /**
     * World override if present, otherwise the global default.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        T override = getOverride(key);
        return override != null ? override : (T) parent.getLiveFallback(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOverride(String key) {
        Map<String, Object> map = overrides;
        return map == null ? null : (T) map.get(key);
    }

    public boolean hasOverride(String key) {
        Map<String, Object> map = overrides;
        return map != null && map.containsKey(key);
    }

    public boolean set(String key, Object value) {
        if (!parent.isWorldConfigKey(key)) {
            return false;
        }
        try {
            Object converted = parent.convertValue(key, value);
            putOverride(key, converted);
            ensureFile();
            file.set(key, serialize(converted));
            copyComment(key);
            file.save();
            return true;
        } catch (Exception e) {
            parent.logger.error("Failed to set world config {} for {}", key, worldName, e);
            return false;
        }
    }

    public void reset(String key) {
        Map<String, Object> map = overrides;
        if (map != null) {
            map.remove(key);
        }
        if (file == null) {
            return;
        }
        file.remove(key);
        file.save();
    }

    public void save() {
        if (file == null) {
            return;
        }
        file.save();
    }

    public void reload() {
        overrides = null;
        closeFile();
        if (!configFile.exists() || configFile.length() == 0) {
            return;
        }
        ensureFile();
        loadOverrides();
        closeFile();
    }

    public void close() {
        closeFile();
    }

    private void putOverride(String key, Object value) {
        Map<String, Object> map = overrides;
        if (map == null) {
            map = new ConcurrentHashMap<>(8);
            overrides = map;
        }
        map.put(key, value);
    }

    private void loadOverrides() {
        if (file == null) {
            return;
        }
        for (String key : parent.getWorldConfigKeys()) {
            Object raw = file.get(key);
            if (raw == null) {
                continue;
            }
            try {
                putOverride(key, parent.convertValue(key, raw));
            } catch (Exception e) {
                parent.logger.error("Invalid world config {} in {} for {}, using default", key, configFile.getPath(), worldName, e);
            }
        }
    }

    private static void createIfAbsent(@NotNull File configFile) {
        try {
            File parentDir = configFile.getParentFile();
            if (parentDir != null) {
                parentDir.mkdirs();
            }
            if (!configFile.exists() && !configFile.createNewFile()) {
                throw new IOException("Could not create " + configFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create world config " + configFile, e);
        }
    }

    private void ensureFile() {
        if (file != null) {
            return;
        }
        createIfAbsent(configFile);
        file = CommentedFileConfig.of(configFile);
        file.load();
    }

    private void copyComment(String key) {
        if (file.getComment(key) != null || parent.getFileInstance() == null) {
            return;
        }
        String comment = parent.getFileInstance().getComment(key);
        if (comment != null && !comment.isBlank()) {
            file.setComment(key, comment);
        }
    }

    private void closeFile() {
        if (file == null) {
            return;
        }
        file.close();
        file = null;
    }

    static Object serialize(Object value) {
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Enum<?>) {
            List<String> names = new ArrayList<>(list.size());
            for (Object item : list) {
                names.add(item instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(item));
            }
            return names;
        }
        return value;
    }
}
