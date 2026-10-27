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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-world copy of keys stored as {@link WorldValues.Key}.
 * Runtime values live on the attached {@link WorldConfigHolder} snapshot:
 * global defaults, then this world's file overrides.
 * GUI submit does not add keys that still match that global default.
 * Keys already stored for a world stay in the overlay, including after they are set back to the default.
 */
public class WorldConfigInstance {
    private final ConfigsInstance parent;
    private final String worldName;
    private final File configFile;
    private Map<String, Object> overrides;
    private CommentedFileConfig file;
    private WorldConfigHolder holder;

    private WorldConfigInstance(@NotNull ConfigsInstance parent, @NotNull String worldName, @NotNull File configFile) {
        this.parent = parent;
        this.worldName = worldName;
        this.configFile = configFile;
    }

    static @Nullable WorldConfigInstance load(@NotNull ConfigsInstance parent, @NotNull String worldName, @NotNull File worldFolder) {
        if (!parent.isWorldConfig()) {
            return null;
        }
        WorldConfigInstance instance = new WorldConfigInstance(parent, worldName, resolveFile(parent, worldFolder));
        instance.reload();
        return instance;
    }

    static File resolveFile(@NotNull ConfigsInstance parent, @NotNull File worldFolder) {
        File dimensionConfig = new File(worldFolder, "dimension_config");
        File systemFolder = new File(dimensionConfig, parent.getBaseConfigFolder().getName());
        return new File(systemFolder, parent.getName() + "_world_config.toml");
    }

    void attach(@NotNull WorldConfigHolder holder) {
        this.holder = holder;
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
        if (!parent.isWorldConfig(key)) {
            return false;
        }
        try {
            stage(key, value, true);
            persistOverrides();
            publish();
            return true;
        } catch (Exception e) {
            parent.logger.error("Failed to set world config {} for {}", key, worldName, e);
            return false;
        }
    }

    /**
     * Apply a batch of submitted values, then write the overlay once.
     * New keys that still match the live global default are skipped.
     * Keys already present in this world's overlay are kept even when set back to that default.
     */
    public void applySubmitted(@NotNull Map<String, ?> values) {
        if (values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!parent.isWorldConfig(entry.getKey())) {
                continue;
            }
            try {
                stage(entry.getKey(), entry.getValue(), false);
            } catch (Exception e) {
                parent.logger.error("Failed to set world config {} for {}", entry.getKey(), worldName, e);
            }
        }
        persistOverrides();
        publish();
    }

    public void reset(String key) {
        dropOverride(key);
        persistOverrides();
        publish();
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
        if (configFile.exists() && configFile.length() > 0) {
            ensureFile();
            loadOverrides();
            closeFile();
        } else if (configFile.exists() && configFile.length() == 0) {
            deleteFileIfPresent();
        }
    }

    public void close() {
        closeFile();
    }

    void applyOverridesTo(Object[] snapshot) {
        Map<String, Object> map = overrides;
        if (map == null || map.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            WorldValues.Key<?> key = WorldValues.keyOf(parent.getName(), entry.getKey());
            if (key == null) {
                continue;
            }
            int index = key.index;
            if (index >= 0 && index < snapshot.length) {
                snapshot[index] = entry.getValue();
            }
        }
    }

    private void publish() {
        WorldConfigHolder attached = holder;
        if (attached != null) {
            attached.rebuildSnapshot();
        }
    }

    private void stage(String key, Object value, boolean explicit) throws Exception {
        Object converted = parent.convertValue(key, value);
        if (converted == null) {
            dropOverride(key);
            return;
        }
        if (!explicit && !hasOverride(key) && sameValue(converted, parent.getLiveFallback(key))) {
            return;
        }
        putOverride(key, converted);
    }

    private void putOverride(String key, Object value) {
        Map<String, Object> map = overrides;
        if (map == null) {
            map = new ConcurrentHashMap<>(8);
            overrides = map;
        }
        map.put(key, value);
    }

    private void dropOverride(String key) {
        Map<String, Object> map = overrides;
        if (map == null) {
            return;
        }
        map.remove(key);
        if (map.isEmpty()) {
            overrides = null;
        }
    }

    private void persistOverrides() {
        Map<String, Object> map = overrides;
        if (map == null || map.isEmpty()) {
            closeFile();
            deleteFileIfPresent();
            return;
        }
        ensureFile();
        file.clear();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            file.set(entry.getKey(), serialize(entry.getValue()));
            copyComment(entry.getKey());
        }
        file.save();
    }

    private void deleteFileIfPresent() {
        if (!configFile.exists()) {
            return;
        }
        if (!configFile.delete()) {
            parent.logger.warn("Failed to delete unmodified world config {}", configFile.getPath());
        }
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

    private static boolean sameValue(Object left, Object right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue()) == 0;
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int i = 0; i < leftList.size(); i++) {
                if (!sameValue(leftList.get(i), rightList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(serialize(left), serialize(right))
                || Objects.equals(String.valueOf(left), String.valueOf(right));
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
