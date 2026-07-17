package me.earthme.luminol.functions.bars.impl;

import ca.spottedleaf.common.time.TickData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import me.earthme.luminol.config.modules.function.TpsBarConfig;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class TpsBar extends TickableStatusBar {
    public TpsBar(Player player) {
        super(player);
    }

    public static @NonNull @UnmodifiableView Map<String, Object> buildSettings() {
        final HashMap<String, Object> ret = new HashMap<>();

        ret.put(TickableStatusBar.SETTING_KEY_ENABLED, TpsBarConfig.tpsbarEnabled);
        ret.put(TickableStatusBar.SETTING_DISPLAY, TpsBarConfig.display);
        ret.put(TickableStatusBar.SETTING_KEY_UPDATE_INTERVALS, TpsBarConfig.updateInterval);

        return Collections.unmodifiableMap(ret);
    }

    @Override
    public void updateDisplay(@Nullable BossBar bar, @NotNull Player owner) {
        final EnumStatusBarDisplay display = this.getDisplay();
        final CraftPlayer apiOwner = (CraftPlayer) owner.getBukkitEntity();

        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = TickRegionScheduler.getCurrentRegion();
        final TickData.TickReportData reportData = region.getData().getRegionSchedulingHandle().getTickReport5s(System.nanoTime());
        final TickData.SegmentData tpsData = reportData.tpsData().segmentAll();

        final double tps = tpsData.average();
        final double mspt = reportData.timePerTickData().segmentAll().average() / 1.0E6;

        final Component message = MiniMessage.miniMessage().deserialize(
                TpsBarConfig.tpsBarFormat,
                Placeholder.component("tps", getTpsComponent(tps)),
                Placeholder.component("mspt", getMsptComponent(mspt)),
                Placeholder.component("ping", getPingComponent(apiOwner.getPing())),
                Placeholder.component("chunkhot", getChunkHotComponent(apiOwner.getNearbyChunkHot()))
        );

        switch (display) {
            case ACTION_BAR -> apiOwner.sendActionBar(message);

            case BOSS_BAR ->
                    bar.name(message).color(barColorForTps(tps)).progress((float) Math.clamp(mspt / 50, 0, (float) 1));

            case TAB_LIST -> apiOwner.sendPlayerListFooter(message);

            default -> throw new IllegalStateException();
        }
    }

    private static @NotNull Component getPingComponent(int ping) {
        return MiniMessage.miniMessage().deserialize(textPlaceholderForPing(ping), Placeholder.parsed("text", String.valueOf(ping)));
    }

    private static @NotNull Component getMsptComponent(double mspt) {
        return MiniMessage.miniMessage().deserialize(textPlaceholderForMspt(mspt), Placeholder.parsed("text", String.format("%." + TpsBarConfig.precisionOfMSPT + "f", mspt)));
    }

    private static @NotNull Component getChunkHotComponent(long chunkHot) {
        return MiniMessage.miniMessage().deserialize(textPlaceholderForChunkHot(chunkHot), Placeholder.parsed("text", String.valueOf(chunkHot)));
    }

    private static @NotNull Component getTpsComponent(double tps) {
        return MiniMessage.miniMessage().deserialize(textPlaceholderForTps(tps), Placeholder.parsed("text", String.format("%." + TpsBarConfig.precisionOfTPS + "f", tps)));
    }

    private static String textPlaceholderForPing(int ping) {
        if (ping == -1) {
            return TpsBarConfig.pingColors.get(3);
        }

        if (ping <= 80) {
            return TpsBarConfig.pingColors.get(0);
        }

        if (ping <= 160) {
            return TpsBarConfig.pingColors.get(1);
        }

        return TpsBarConfig.pingColors.get(2);
    }

    private static String textPlaceholderForChunkHot(long chunkHot) {
        if (chunkHot == -1) {
            return TpsBarConfig.chunkHotColors.get(3);
        }

        if (chunkHot <= 300000L) {
            return TpsBarConfig.chunkHotColors.get(0);
        }

        if (chunkHot <= 500000L) {
            return TpsBarConfig.chunkHotColors.get(1);
        }

        return TpsBarConfig.chunkHotColors.get(2);
    }

    private static String textPlaceholderForMspt(double mspt) {
        if (mspt == -1) {
            return TpsBarConfig.tpsColors.get(3);
        }

        if (mspt <= 25) {
            return TpsBarConfig.tpsColors.get(0);
        }

        if (mspt <= 50) {
            return TpsBarConfig.tpsColors.get(1);
        }

        return TpsBarConfig.tpsColors.get(2);
    }

    private static BossBar.Color barColorForTps(double tps) {
        if (tps == -1) {
            return TpsBarConfig.barColors.get(3);
        }

        if (tps >= 19) {
            return TpsBarConfig.barColors.get(0);
        }

        if (tps >= 15) {
            return TpsBarConfig.barColors.get(1);
        }

        return TpsBarConfig.barColors.get(2);
    }

    private static String textPlaceholderForTps(double tps) {
        if (tps == -1) {
            return TpsBarConfig.tpsColors.get(3);
        }

        if (tps >= 19) {
            return TpsBarConfig.tpsColors.get(0);
        }

        if (tps >= 15) {
            return TpsBarConfig.tpsColors.get(1);
        }

        return TpsBarConfig.tpsColors.get(2);
    }
}
