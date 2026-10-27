package me.earthme.luminol.config;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolve a world-config field for the given level, or the current region world.
 * Each {@link Level} holds a full copy of every bound key with that world's overrides applied.
 * {@link Key} identity is assigned at construction; hot-path reads are an array index.
 */
public final class WorldValues {
    private static final List<Key<?>> ALL_KEYS = new CopyOnWriteArrayList<>();
    private static final Map<String, Key<?>> BY_ID = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Key<?>>> ANONYMOUS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();

    private WorldValues() {
    }

    public static <T> Key<T> key() {
        Key<T> key = new Key<>();
        synchronized (WorldValues.class) {
            key.index = NEXT_INDEX.getAndIncrement();
            ALL_KEYS.add(key);
        }
        return key;
    }

    static void bind(Key<?> key, String configName, String path, Field field) {
        synchronized (WorldValues.class) {
            if (key.index < 0) {
                key.index = NEXT_INDEX.getAndIncrement();
                ALL_KEYS.add(key);
            }
            key.field = field;
            field.setAccessible(true);
            BY_ID.put(id(configName, path), key);
        }
    }

    static Key<?> findOrCreateKey(Class<?> owner, String fieldName) {
        Key<?> declared = findDeclaredKey(owner, fieldName);
        if (declared != null) {
            return declared;
        }
        return ANONYMOUS
                .computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(fieldName, ignored -> new Key<>());
    }

    static Key<?> keyOf(String configName, String path) {
        return BY_ID.get(id(configName, path));
    }

    static Object[] copyGlobals() {
        List<Key<?>> keys = ALL_KEYS;
        Object[] copy = new Object[NEXT_INDEX.get()];
        for (int i = 0, size = keys.size(); i < size; i++) {
            Key<?> key = keys.get(i);
            int index = key.index;
            if (index >= 0 && index < copy.length) {
                copy[index] = key.readGlobal();
            }
        }
        return copy;
    }

    public static <T> T get(Key<T> key, T fallback) {
        return get(null, key, fallback);
    }

    public static <T> T get(@Nullable Level level, Key<T> key, T fallback) {
        if (key == null || key.index < 0) {
            return fallback;
        }
        if (level == null) {
            RegionizedWorldData data = TickRegionScheduler.getCurrentRegionizedWorldData();
            if (data != null) {
                level = data.world;
            }
        }
        if (level == null) {
            return fallback;
        }
        T value = level.luminolWorldConfig().get(key);
        return value != null ? value : fallback;
    }

    private static String id(String configName, String path) {
        return configName + '\0' + path;
    }

    private static @Nullable Key<?> findDeclaredKey(Class<?> owner, String fieldName) {
        Key<?> best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() != Key.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            int rank = matchRank(field.getName(), fieldName);
            if (rank < 0 || rank >= bestRank) {
                continue;
            }
            field.setAccessible(true);
            try {
                Object value = field.get(null);
                if (value instanceof Key<?> found) {
                    best = found;
                    bestRank = rank;
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        return best;
    }

    /**
     * Lower is better. {@code -1} means no match.
     */
    private static int matchRank(String keyFieldName, String configFieldName) {
        if (keyFieldName.equals(configFieldName)) {
            return 0;
        }
        if (keyFieldName.equalsIgnoreCase(configFieldName)) {
            return 1;
        }
        if (stripUnderscores(keyFieldName).equalsIgnoreCase(configFieldName)
                || stripUnderscores(keyFieldName).equalsIgnoreCase(stripUnderscores(configFieldName))) {
            return 2;
        }
        return -1;
    }

    private static String stripUnderscores(String name) {
        if (name.indexOf('_') < 0) {
            return name;
        }
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (character != '_') {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    public static final class Key<T> {
        volatile int index = -1;
        private Field field;

        private Key() {
        }

        Object readGlobal() {
            Field bound = field;
            if (bound == null) {
                return null;
            }
            try {
                return bound.get(null);
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
    }
}
