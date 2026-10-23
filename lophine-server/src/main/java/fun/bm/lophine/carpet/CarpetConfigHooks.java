package fun.bm.lophine.carpet;

import me.earthme.luminol.api.config.ConfigsInstanceHooks;
import me.earthme.luminol.api.config.LuminolConfigsInstance;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.config.IllegalFormatConversionExceptionWithOrigin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Carpet rule file loading: synced writes, full-file validation, default restore on reload.
 */
public final class CarpetConfigHooks implements ConfigsInstanceHooks {
    public static final CarpetConfigHooks INSTANCE = new CarpetConfigHooks();
    private static final Logger LOGGER = LoggerFactory.getLogger(CarpetConfigHooks.class);

    @Override
    public boolean synchronousFileWrites() {
        // setDefault completes only after the real write or its exception.
        return true;
    }

    @Override
    public Object beforeReload(@NotNull LuminolConfigsInstance instance) {
        return CarpetConfigLifecycle.snapshot();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterReload(@NotNull LuminolConfigsInstance instance, @Nullable Object snapshot) {
        if (snapshot != null) {
            CarpetConfigLifecycle.reloaded((Map<String, Object>) snapshot);
        }
    }

    @Override
    public void aroundLoadModules(@NotNull LuminolConfigsInstance instance, @NotNull Runnable load) throws Exception {
        try (var view = CarpetRuleRegistry.configurationView(instance::peekConfigValue)) {
            load.run();
        }
    }

    @Override
    public void beforeLoadModules(@NotNull LuminolConfigsInstance instance, boolean alreadyInit) {
        if (alreadyInit) {
            instance.restoreDefaultFields();
        }
    }

    @Override
    public boolean registerFieldBeforeFinalLoad() {
        return false;
    }

    @Override
    public void afterLoadModules(@NotNull LuminolConfigsInstance instance, boolean alreadyInit) {
        for (Object module : instance.getInstancedModules()) {
            Runnable before = () -> instance.runBeforeFinalLoad(module);
            if (alreadyInit) {
                before.run();
            } else {
                instance.registerBeforeFinalLoad(before);
            }
        }
    }

    @Override
    public Optional<Object> tryTransform(@NotNull LuminolConfigsInstance instance, @NotNull Field field, Object value) {
        if (!field.getDeclaringClass().getPackageName().equals("fun.bm.lophine.carpet.config.modules")) {
            return Optional.empty();
        }
        try {
            String text = value instanceof List<?> list
                    ? String.join(",", list.stream().map(Object::toString).toList())
                    : value.toString();
            ConfigInfo info = field.getAnnotation(ConfigInfo.class);
            String upstreamType = CarpetRuleMetadata.get(info.name()).type();
            if (field.getType() == String.class && !Set.of("", "String", "boolean", "int", "long", "double", "float").contains(upstreamType)) {
                text = text.toLowerCase(Locale.ROOT);
            }
            return Optional.of(CarpetRuleRegistry.get(info.name()).parse(text));
        } catch (RuntimeException failure) {
            LOGGER.error("Invalid Carpet rule {}: {}", field.getName(), failure.getMessage());
            throw new IllegalFormatConversionExceptionWithOrigin((char) 0, field.getType(), value);
        }
    }

    @Override
    public boolean restoreMissingToDefault() {
        return true;
    }
}
