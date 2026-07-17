package me.earthme.luminol.enums;

import com.mojang.datafixers.util.Pair;
import me.earthme.luminol.functions.bars.TickableStatusBar;
import me.earthme.luminol.functions.bars.impl.Membar;
import me.earthme.luminol.functions.bars.impl.RegionBar;
import me.earthme.luminol.functions.bars.impl.TpsBar;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.function.Supplier;

public enum EnumBarType {
    TPS(
            TpsBar.class,
            "tps",
            "function.tpsbar.enabled",
            TpsBar::buildSettings
    ),
    MEMORY(
            Membar.class,
            "memory",
            "membar",
            "function.membar.enabled",
            Membar::buildSettings
    ),
    REGION(
            RegionBar.class,
            "region",
            "function.regionbar.enabled",
            RegionBar::buildSettings
    );

    private final Class<? extends TickableStatusBar> clazz;
    private final String name;
    private final String commandName;
    private final String configPath;
    private final String configOrigin;
    private final Supplier<Map<String, Object>> settingsProvider;

    EnumBarType(Class<? extends TickableStatusBar> clazz, String name, String configPath, Supplier<Map<String, Object>> settingsProvider) {
        this(clazz, name, name + "bar", configPath, settingsProvider);
    }

    EnumBarType(Class<? extends TickableStatusBar> clazz, String name, Pair<String, String> configPath, Supplier<Map<String, Object>> settingsProvider) {
        this(clazz, name, name + "bar", configPath, settingsProvider);
    }

    EnumBarType(Class<? extends TickableStatusBar> clazz, String name, String commandName, String configPath, Supplier<Map<String, Object>> settingsProvider) {
        this(clazz, name, commandName, new Pair<>("luminol", configPath), settingsProvider);
    }

    EnumBarType(Class<? extends TickableStatusBar> clazz, String name, String commandName, @NonNull Pair<String, String> configPath, Supplier<Map<String, Object>> settingsProvider) {
        this.clazz = clazz;
        this.name = name;
        this.commandName = commandName;
        this.configPath = configPath.getSecond();
        this.configOrigin = configPath.getFirst();
        this.settingsProvider = settingsProvider;
    }

    @NotNull
    public TickableStatusBar newBar(Player player) {
        try {
            return this.clazz.getConstructor(Player.class).newInstance(player);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> getSettings() {
        return this.settingsProvider.get();
    }

    public String getCommandName() {
        return this.commandName;
    }

    public String getName() {
        return this.name;
    }

    public String getConfigOrigin() {
        return this.configOrigin;
    }

    public String getConfigPath() {
        return this.configPath;
    }
}
