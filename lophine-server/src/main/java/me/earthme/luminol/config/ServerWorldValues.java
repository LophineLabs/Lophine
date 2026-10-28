package me.earthme.luminol.config;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValueBuilder;
import me.earthme.luminol.api.config.WorldValues;
import net.minecraft.world.level.Level;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server factory for {@link WorldValue}: assigns snapshot indexes, binds config paths,
 * and resolves values from each world's copy.
 */
public class ServerWorldValues extends WorldValues {
    private final List<WorldValue<?>> allKeys = new CopyOnWriteArrayList<>();
    private final Map<String, WorldValue<?>> byId = new ConcurrentHashMap<>();
    private final AtomicInteger nextIndex = new AtomicInteger();

    @Override
    protected <T> @NotNull WorldValueBuilder<T> createBuilder() {
        return new BuilderImpl<>();
    }

    @Override
    protected <T> T resolveCurrent(@NotNull WorldValue<T> key) {
        RegionizedWorldData data = TickRegionScheduler.getCurrentRegionizedWorldData();
        if (data == null) {
            return key.global();
        }
        return valueOf(data.world, key);
    }

    @Override
    protected <T> T resolve(@NotNull World world, @NotNull WorldValue<T> key) {
        if (world instanceof CraftWorld craftWorld) {
            return valueOf(craftWorld.getHandle(), key);
        }
        return key.global();
    }

    @Override
    protected void bindValue(@NotNull WorldValue<?> key, @NotNull String configName, @NotNull String path) {
        byId.put(id(configName, path), key);
    }

    @Override
    protected @Nullable WorldValue<?> findValue(@NotNull String configName, @NotNull String path) {
        return byId.get(id(configName, path));
    }

    @Override
    protected Object @NotNull [] snapshotGlobals() {
        Object[] copy = new Object[nextIndex.get()];
        for (WorldValue<?> key : allKeys) {
            int index = key.index();
            if (index >= 0 && index < copy.length) {
                copy[index] = key.global();
            }
        }
        return copy;
    }

    private static <T> T valueOf(Level level, WorldValue<T> key) {
        T value = level.luminolWorldConfig().get(key);
        return value != null ? value : key.global();
    }

    private static String id(String configName, String path) {
        return configName + '\0' + path;
    }

    private final class BuilderImpl<T> implements WorldValueBuilder<T> {
        private boolean hasGlobal;
        private T global;

        @Override
        public @NotNull WorldValueBuilder<T> global(T value) {
            this.global = value;
            this.hasGlobal = true;
            return this;
        }

        @Override
        public @NotNull WorldValue<T> build() {
            if (!hasGlobal) {
                throw new IllegalStateException("WorldValue builder requires global()");
            }
            SimpleWorldValue<T> key = new SimpleWorldValue<>(global);
            synchronized (ServerWorldValues.this) {
                key.index = nextIndex.getAndIncrement();
                allKeys.add(key);
            }
            return key;
        }
    }

    private static final class SimpleWorldValue<T> extends WorldValue<T> {
        private volatile int index = -1;
        private volatile T global;

        private SimpleWorldValue(T global) {
            this.global = global;
        }

        @Override
        public T get() {
            return WorldValues.get(this);
        }

        @Override
        public T get(@Nullable World world) {
            return WorldValues.get(world, this);
        }

        @Override
        public T global() {
            return global;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void setGlobal(Object value) {
            this.global = (T) value;
        }
    }
}
