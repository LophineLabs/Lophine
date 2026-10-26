package me.earthme.luminol.config;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.lang.StackWalker.StackFrame;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolve a world-config field for the given level, or the current region world.
 * Paths come from {@code @ConfigClassInfo} / {@code @ConfigInfo} at load time.
 * The calling getter's method name identifies the field and must match it.
 * Missing world values fall back to the supplied global default.
 */
public final class WorldValues {
    private static final StackWalker WALKER = StackWalker.getInstance(
            Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE),
            8
    );
    private static final ClassValue<ModuleBindings> BINDINGS = new ClassValue<>() {
        @Override
        protected ModuleBindings computeValue(@NonNull Class<?> type) {
            return new ModuleBindings();
        }
    };

    static void register(Class<?> module, String fieldName, String configName, String key) {
        ModuleBindings bindings = BINDINGS.get(module);
        bindings.configName = configName;
        bindings.fieldToKey.put(fieldName, key);
    }

    public static <T> T get(T fallback) {
        return get(null, fallback);
    }

    public static <T> T get(@Nullable Level level, T fallback) {
        StackFrame frame = caller();
        if (frame == null) {
            return fallback;
        }
        ModuleBindings bindings = BINDINGS.get(frame.getDeclaringClass());
        String key = bindings.fieldToKey.get(frame.getMethodName());
        if (key == null || bindings.configName == null) {
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
        WorldConfigHolder holder = level.luminolWorldConfig();
        if (holder == null) {
            return fallback;
        }
        WorldConfigInstance instance = holder.get(bindings.configName);
        if (instance == null) {
            return fallback;
        }
        T override = instance.getOverride(key);
        return override != null ? override : fallback;
    }

    private static @Nullable StackFrame caller() {
        Class<WorldValues> self = WorldValues.class;
        return WALKER.walk(frames -> frames
                .filter(frame -> frame.getDeclaringClass() != self)
                .filter(frame -> BINDINGS.get(frame.getDeclaringClass()).fieldToKey.containsKey(frame.getMethodName()))
                .findFirst()
                .orElse(null));
    }

    private static final class ModuleBindings {
        String configName;
        final Map<String, String> fieldToKey = new HashMap<>(8);
    }
}
