package me.earthme.luminol.functions.bars;

import com.mojang.logging.LogUtils;
import me.earthme.luminol.enums.EnumBarType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.Map;

public class TickableStatusBarList {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final EnumMap<EnumBarType, TickableStatusBar> managedBars = new EnumMap<>(EnumBarType.class);
    private final Player player;

    public TickableStatusBarList(Player player) {
        this.player = player;

        for (EnumBarType type : EnumBarType.values()) {
            final TickableStatusBar bar = type.newBar(this.player);

            bar.initSettings(type.getSettings());

            this.managedBars.put(type, bar);
        }
    }

    public static void raiseGlobalReload(EnumBarType type) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        final PlayerList playerList = server.getPlayerList();
        if (playerList == null) {
            return;
        }

        for (Player playerInList : playerList.getPlayers()) {
            playerInList.getBukkitEntity().taskScheduler.scheduleOrExecute(_ -> playerInList.statusBarList.reload(type));
        }
    }

    public static void raiseGlobalReload() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        final PlayerList playerList = server.getPlayerList();
        if (playerList == null) {
            return;
        }

        for (Player playerInList : playerList.getPlayers()) {
            playerInList.getBukkitEntity().taskScheduler.scheduleOrExecute(_ -> playerInList.statusBarList.reloadAll());
        }
    }

    public void reloadAll() {
        for (EnumBarType type : EnumBarType.values()) {
            this.reload(type);
        }
    }

    public void reload(EnumBarType type) {
        final TickableStatusBar bar = this.managedBars.get(type);
        if (bar == null) {
            LOGGER.warn("Reloading a non-existed bar {} !", type);
            return;
        }

        bar.applySettings(type.getSettings());
    }

    public void tick() {
        for (TickableStatusBar bar : this.managedBars.values()) {
            bar.tick();
        }
    }

    public void load(@NonNull ValueInput input) {
        final ValueInput statusBarsInput = input
                .child("luminol")
                .flatMap(luminol -> luminol.child("status_bars"))
                .orElse(null); // I hate these optionals (x)

        // does not have this data
        if (statusBarsInput == null) {
            return;
        }

        for (Map.Entry<EnumBarType, TickableStatusBar> barEntry : this.managedBars.entrySet()) {
            final EnumBarType type = barEntry.getKey();
            final TickableStatusBar bar = barEntry.getValue();
            final String categoryName = type.getName();

            if (bar != null) {
                final ValueInput inputOfThisBar = statusBarsInput.child(categoryName).orElse(null);

                // does not have this data
                if (inputOfThisBar == null) {
                    continue;
                }

                bar.load(inputOfThisBar);
                continue;
            }

            LOGGER.warn("Skipping loading null status bar {} !", categoryName);
        }
    }

    public void save(@NonNull ValueOutput output) {
        final ValueOutput statusBarsOutput = output
                .child("luminol")
                .child("status_bars");

        for (Map.Entry<EnumBarType, TickableStatusBar> barEntry : this.managedBars.entrySet()) {
            final EnumBarType type = barEntry.getKey();
            final TickableStatusBar bar = barEntry.getValue();
            final String categoryName = type.getName();

            if (bar != null) {
                final ValueOutput outOfThisBar = statusBarsOutput.child(categoryName);

                bar.store(outOfThisBar);
                continue;
            }

            LOGGER.warn("Skipping storing null status bar {} !", categoryName);
        }
    }

    public void setVisible(EnumBarType type, boolean visible) {
        final TickableStatusBar bar = this.managedBars.get(type);

        if (bar == null) {
            LOGGER.warn("Bar with type {} does not exist! Skipping visibility updates.", type);
            return;
        }

        bar.setVisible(visible);
    }

    public boolean isVisible(EnumBarType type) {
        final TickableStatusBar bar = this.managedBars.get(type);

        if (bar == null) {
            return false;
        }

        return bar.isVisible();
    }

    public boolean isEnabled(EnumBarType type) {
        final TickableStatusBar bar = this.managedBars.get(type);

        if (bar == null) {
            return false;
        }

        return bar.isEnabled();
    }
}
