package me.earthme.luminol.config;

import me.earthme.luminol.api.config.ConfigsInstanceHooks;
import me.earthme.luminol.api.config.LuminolConfigBuilder;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.HashMap;

public class ConfigsInstanceBuilder implements LuminolConfigBuilder {
    // Factory methods for creating ConfigsInstance objects
    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull String name,
            @NotNull String pack
    ) {
        return this.of(loader, name, pack, true);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull String name,
            @NotNull String pack,
            boolean isWorldConfig
    ) {
        return this.of(loader, new File(name + "_config"), name, pack, isWorldConfig);
    }

    public ConfigsInstance of(
            @NotNull String name,
            @NotNull String pack
    ) {
        return this.of(name, pack, true);
    }

    public ConfigsInstance of(
            @NotNull String name,
            @NotNull String pack,
            boolean isWorldConfig
    ) {
        return this.of(MinecraftServer.class.getClassLoader(), name, pack, isWorldConfig);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String pack
    ) {
        return this.of(loader, base, name, pack, true);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String pack,
            boolean isWorldConfig
    ) {
        return this.of(loader, base, name, name + "_global_config.toml", pack, isWorldConfig);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String pack
    ) {
        return this.of(base, name, pack, true);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String pack,
            boolean isWorldConfig
    ) {
        return this.of(MinecraftServer.class.getClassLoader(), base, name, pack, isWorldConfig);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String pack
    ) {
        return this.of(loader, base, name, file_name, pack, true);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String pack,
            boolean isWorldConfig
    ) {
        return this.of(loader, base, name, file_name, name + "config", pack, isWorldConfig);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String pack
    ) {
        return this.of(base, name, file_name, pack, true);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String pack,
            boolean isWorldConfig
    ) {
        return this.of(MinecraftServer.class.getClassLoader(), base, name, file_name, pack, isWorldConfig);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack
    ) {
        return this.of(loader, base, name, file_name, command_name, pack, true);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack,
            boolean isWorldConfig
    ) {
        return this.of(loader, base, name, file_name, command_name, pack, ConfigsInstanceHooks.NONE, isWorldConfig);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack
    ) {
        return this.of(base, name, file_name, command_name, pack, true);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack,
            boolean isWorldConfig
    ) {
        return this.of(MinecraftServer.class.getClassLoader(), base, name, file_name, command_name, pack, isWorldConfig);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack,
            @NotNull ConfigsInstanceHooks hooks
    ) {
        return this.of(base, name, file_name, command_name, pack, hooks, true);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack,
            @NotNull ConfigsInstanceHooks hooks,
            boolean isWorldConfig
    ) {
        return this.of(MinecraftServer.class.getClassLoader(), base, name, file_name, command_name, pack, hooks, isWorldConfig);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack,
            @NotNull ConfigsInstanceHooks hooks
    ) {
        return this.of(loader, base, name, file_name, command_name, pack, hooks, true);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack,
            @NotNull ConfigsInstanceHooks hooks,
            boolean isWorldConfig
    ) {
        return new ConfigsInstance(loader, base, name, file_name, command_name, pack, hooks, isWorldConfig ? new HashMap<>(32) : null);
    }
}
