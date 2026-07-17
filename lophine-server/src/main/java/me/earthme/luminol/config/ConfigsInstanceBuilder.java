package me.earthme.luminol.config;

import me.earthme.luminol.api.config.LuminolConfigBuilder;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public class ConfigsInstanceBuilder implements LuminolConfigBuilder {
    // Factory methods for creating ConfigsInstance objects
    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull String name,
            @NotNull String pack
    ) {
        return this.of(loader, new File(name + "_config"), name, pack);
    }

    public ConfigsInstance of(
            @NotNull String name,
            @NotNull String pack
    ) {
        return this.of(MinecraftServer.class.getClassLoader(), name, pack);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String pack
    ) {
        return this.of(loader, base, name, name + "_global_config.toml", pack);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String pack
    ) {
        return this.of(MinecraftServer.class.getClassLoader(), base, name, pack);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String pack
    ) {
        return this.of(loader, base, name, file_name, name + "config", pack);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String pack
    ) {
        return this.of(MinecraftServer.class.getClassLoader(), base, name, file_name, pack);
    }

    public ConfigsInstance of(
            @NotNull ClassLoader loader,
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack
    ) {
        return new ConfigsInstance(loader, base, name, file_name, command_name, pack);
    }

    public ConfigsInstance of(
            @NotNull File base,
            @NotNull String name,
            @NotNull String file_name,
            @NotNull String command_name,
            @NotNull String pack
    ) {
        return new ConfigsInstance(MinecraftServer.class.getClassLoader(), base, name, file_name, command_name, pack);
    }
}
