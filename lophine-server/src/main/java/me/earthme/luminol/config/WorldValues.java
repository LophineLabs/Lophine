package me.earthme.luminol.config;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolve a world-config field for the given level, or the current region world.
 * Each {@link Level} holds a full copy of every bound key with that world's overrides applied.
 * A {@link Key} is both the global default and the snapshot index.
 */
public final class WorldValues {
    private static final List<Key<?>> ALL_KEYS = new CopyOnWriteArrayList<>();
    private static final Map<String, Key<?>> BY_ID = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();

    private WorldValues() {
    }

    public static <T> Key<T> key(T global) {
        Key<T> key = new Key<>(global);
        synchronized (WorldValues.class) {
            key.index = NEXT_INDEX.getAndIncrement();
            ALL_KEYS.add(key);
        }
        return key;
    }

    static void bind(Key<?> key, String configName, String path) {
        BY_ID.put(id(configName, path), key);
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
                copy[index] = key.global;
            }
        }
        return copy;
    }

    public static <T> T get(Key<T> key) {
        return get(null, key);
    }

    public static <T> T get(@Nullable Level level, Key<T> key) {
        if (key == null) {
            return null;
        }
        if (level == null) {
            RegionizedWorldData data = TickRegionScheduler.getCurrentRegionizedWorldData();
            if (data != null) {
                level = data.world;
            }
        }
        if (level == null) {
            return key.global;
        }
        T value = level.luminolWorldConfig().get(key);
        return value != null ? value : key.global;
    }

    private static String id(String configName, String path) {
        return configName + '\0' + path;
    }

    public static final class Key<T> {
        volatile int index = -1;
        volatile T global;

        private Key(T global) {
            this.global = global;
        }

        public T global() {
            return global;
        }

        public T get() {
            return WorldValues.get(this);
        }

        public T get(@Nullable Level level) {
            return WorldValues.get(level, this);
        }

        @SuppressWarnings("unchecked")
        void setGlobal(Object value) {
            this.global = (T) value;
        }
    }
}
