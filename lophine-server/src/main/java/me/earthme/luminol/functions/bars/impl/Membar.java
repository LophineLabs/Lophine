package me.earthme.luminol.functions.bars.impl;

import me.earthme.luminol.config.modules.function.MembarConfig;
import me.earthme.luminol.enums.EnumStatusBarDisplay;
import me.earthme.luminol.functions.bars.TickableStatusBar;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.world.entity.player.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.jspecify.annotations.NonNull;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Membar extends TickableStatusBar {
    public Membar(Player player) {
        super(player);
    }

    public static @NonNull @UnmodifiableView Map<String, Object> buildSettings() {
        final HashMap<String, Object> ret = new HashMap<>();

        ret.put(TickableStatusBar.SETTING_KEY_ENABLED, MembarConfig.memoryBarEnabled);
        ret.put(TickableStatusBar.SETTING_DISPLAY, MembarConfig.display);
        ret.put(TickableStatusBar.SETTING_KEY_UPDATE_INTERVALS, MembarConfig.updateInterval);

        return Collections.unmodifiableMap(ret);
    }

    @Override
    public void updateDisplay(@Nullable BossBar bar, @NonNull Player owner) {
        final EnumStatusBarDisplay display = this.getDisplay();
        final CraftPlayer apiOwner = (CraftPlayer) owner.getBukkitEntity();

        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long used = heap.getUsed();
        long xmx = heap.getMax();

        double percent = Math.clamp((float) used / xmx, 0.0F, 1.0F);
        final Component message = MiniMessage.miniMessage().deserialize(
                MembarConfig.memBarFormat,
                Placeholder.component("used", getMemoryComponent(used, xmx)),
                Placeholder.component("available", getMaxMemComponent(xmx))
        );

        switch (display) {
            case BOSS_BAR -> bar.name(message).color(barColorForMemory(percent)).progress((float) percent);

            case ACTION_BAR -> apiOwner.sendActionBar(message);

            case TAB_LIST -> apiOwner.sendPlayerListFooter(message);

            default -> throw new IllegalStateException();
        }
    }

    private static @NotNull Component getMaxMemComponent(double max) {
        final BossBar.Color colorBukkit = BossBar.Color.GREEN;
        final String colorString = colorBukkit.name();

        final String content = "<%s><text></%s>";
        final String replaced = String.format(content, colorString, colorString);

        return MiniMessage.miniMessage().deserialize(replaced, Placeholder.parsed("text", String.format("%.2f", max / (1024 * 1024))));
    }

    private static @NotNull Component getMemoryComponent(long used, long max) {
        return MiniMessage.miniMessage().deserialize(textPlaceholderForMemory(Math.clamp((float) used / max, 0.0F, 1.0F)), Placeholder.parsed("text", String.format("%.2f", (double) used / (1024 * 1024))));
    }

    private static BossBar.Color barColorForMemory(double memPercent) {
        if (memPercent == -1) {
            return MembarConfig.barColors.get(3);
        }

        if (memPercent <= 50) {
            return MembarConfig.barColors.get(0);
        }

        if (memPercent <= 70) {
            return MembarConfig.barColors.get(1);
        }

        return MembarConfig.barColors.get(2);
    }

    private static String textPlaceholderForMemory(double memPercent) {
        if (memPercent == -1) {
            return MembarConfig.memColors.get(3);
        }

        if (memPercent <= 50) {
            return MembarConfig.memColors.get(0);
        }

        if (memPercent <= 70) {
            return MembarConfig.memColors.get(1);
        }

        return MembarConfig.memColors.get(2);
    }
}
