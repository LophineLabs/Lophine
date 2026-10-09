package me.earthme.luminol.config;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;

import java.util.ArrayList;
import java.util.List;

/** Shared paths for file loading, runtime edits, defaults and rule bindings. */
public final class ConfigPaths {
    private ConfigPaths() {
    }

    public static List<String> section(ConfigClassInfo module, ConfigInfo entry) {
        List<String> path = new ArrayList<>();
        String category = module.category().getBaseKeyName();
        if (category != null) path.add(category);
        path.addAll(List.of(module.directory()));
        path.add(entry.section().isEmpty() ? module.name() : entry.section());
        return path;
    }

    public static String resolve(ConfigClassInfo module, ConfigInfo entry) {
        List<String> path = section(module, entry);
        path.addAll(List.of(entry.directory()));
        path.add(entry.name());
        return String.join(".", path);
    }

    public static String legacy(ConfigClassInfo module, ConfigInfo entry) {
        List<String> path = section(module, entry);
        path.set(path.size() - 1, module.name());
        path.addAll(List.of(entry.directory()));
        path.add(entry.name());
        return String.join(".", path);
    }
}
