package me.earthme.luminol.functions.bars.impl;

import ca.spottedleaf.common.time.TickData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import me.earthme.luminol.config.modules.function.RegionBarConfig;
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

import java.text.DecimalFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class RegionBar extends TickableStatusBar {
    private final ThreadLocal<DecimalFormat> ONE_DECIMAL_PLACES = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.0"));

    public RegionBar(Player player) {
        super(player);
    }

    public static @NonNull @UnmodifiableView Map<String, Object> buildSettings() {
        final HashMap<String, Object> ret = new HashMap<>();

        ret.put(TickableStatusBar.SETTING_KEY_ENABLED, RegionBarConfig.regionbarEnabled);
        ret.put(TickableStatusBar.SETTING_DISPLAY, RegionBarConfig.display);
        ret.put(TickableStatusBar.SETTING_KEY_UPDATE_INTERVALS, RegionBarConfig.updateInterval);

        return Collections.unmodifiableMap(ret);
    }

    @Override
    public void updateDisplay(@Nullable BossBar bar, @NonNull Player owner) {
        final EnumStatusBarDisplay display = this.getDisplay();
        final CraftPlayer apiOwner = (CraftPlayer) owner.getBukkitEntity();

        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = TickRegionScheduler.getCurrentRegion();
        final TickData.TickReportData reportData = region.getData().getRegionSchedulingHandle().getTickReport5s(System.nanoTime());
        final TickRegions.RegionStats regionStats = region.getData().getRegionStats();

        final double utilisation = reportData.utilisation();
        final int chunkCount = regionStats.getChunkCount();
        final int playerCount = regionStats.getPlayerCount();
        final int entityCount = regionStats.getEntityCount();

        final double utilisationPercent = utilisation * 100.0;
        final String formattedUtil = ONE_DECIMAL_PLACES.get().format(utilisationPercent);
        final Component message = MiniMessage.miniMessage().deserialize(
                RegionBarConfig.regionBarFormat,
                Placeholder.component("util", getUtilComponent(formattedUtil)),
                Placeholder.component("chunks", getChunksComponent(chunkCount)),
                Placeholder.component("players", getPlayersComponent(playerCount)),
                Placeholder.component("entities", getEntitiesComponent(entityCount))
        );

        switch (display) {
            case ACTION_BAR -> apiOwner.sendActionBar(message);

            case BOSS_BAR ->
                    bar.name(message).color(barColorForUtil(utilisationPercent)).progress((float) Math.clamp(utilisation, 0, 1.0));

            case TAB_LIST -> apiOwner.sendPlayerListFooter(message);

            default -> throw new IllegalStateException();
        }
    }

    private static @NotNull Component getEntitiesComponent(int entities) {
        final String content = "<text>";
        return MiniMessage.miniMessage().deserialize(content, Placeholder.parsed("text", String.valueOf(entities)));
    }

    private static @NotNull Component getPlayersComponent(int players) {
        final String content = "<text>";
        return MiniMessage.miniMessage().deserialize(content, Placeholder.parsed("text", String.valueOf(players)));
    }

    private static @NotNull Component getChunksComponent(int chunks) {
        final String content = "<text>";
        return MiniMessage.miniMessage().deserialize(content, Placeholder.parsed("text", String.valueOf(chunks)));
    }

    private static @NotNull Component getUtilComponent(String formattedUtil) {
        return MiniMessage.miniMessage().deserialize(textPlaceholderForUtil(Double.parseDouble(formattedUtil)), Placeholder.parsed("text", formattedUtil + "%"));
    }

    private static BossBar.Color barColorForUtil(double util) {
        if (util > 100) {
            return RegionBarConfig.barColors.get(3);
        }

        if (util >= 70) {
            return RegionBarConfig.barColors.get(2);
        }

        if (util >= 50) {
            return RegionBarConfig.barColors.get(1);
        }

        return RegionBarConfig.barColors.get(0);
    }

    private static String textPlaceholderForUtil(double util) {
        if (util > 100) {
            return RegionBarConfig.utilColors.get(3);
        }

        if (util >= 70) {
            return RegionBarConfig.utilColors.get(2);
        }

        if (util >= 50) {
            return RegionBarConfig.utilColors.get(1);
        }

        return RegionBarConfig.utilColors.get(0);
    }
}
