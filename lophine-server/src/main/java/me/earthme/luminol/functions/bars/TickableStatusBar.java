package me.earthme.luminol.functions.bars;

import me.earthme.luminol.enums.EnumStatusBarDisplay;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public abstract class TickableStatusBar {
    public static final String SETTING_KEY_UPDATE_INTERVALS = "update_intervals";
    public static final String SETTING_KEY_ENABLED = "enabled";
    public static final String SETTING_DISPLAY = "display";
    public static final String SETTING_ALLOW_PLAYER_DISPLAY_SWITCH = "allow_player_display_switch";

    protected final Player player;
    private BossBar bar = null;

    private long tickedCount = 0;
    private boolean lastIsVisible = false;
    private EnumStatusBarDisplay lastDisplay;

    private boolean visible = false;
    private boolean enabled = false;
    private boolean allowPlayerDisplaySwitch = false;

    private int updateIntervalInTicks;
    private EnumStatusBarDisplay display;
    private EnumStatusBarDisplay storedDisplay;

    public TickableStatusBar(Player player) {
        this.player = player;
    }

    public EnumStatusBarDisplay getDisplay() {
        return this.display;
    }

    /**
     * Handle the update of the status bar display update
     *
     * @param bar   the bossbar instance if the display mode is BOSS_BAR, else it's null
     * @param owner the player that is displayed for
     * @see EnumStatusBarDisplay
     */
    public abstract void updateDisplay(@Nullable BossBar bar, Player owner);

    public void handleDisplayUpdate(Player owner, EnumStatusBarDisplay old, EnumStatusBarDisplay newDisplay) {
        if (old == EnumStatusBarDisplay.TAB_LIST && newDisplay != EnumStatusBarDisplay.TAB_LIST) {
            final CraftPlayer apiOwner = (CraftPlayer) owner.getBukkitEntity();

            // reset the display
            apiOwner.sendPlayerListFooter(Component.empty());
        }
    }

    public BossBar newBar() {
        return BossBar.bossBar(Component.text(""), 0.0F, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20);
    }

    public void initSettings(@NotNull Map<String, Object> settings) {
        this.applySettings(settings);

        // actual we need sync it here
        this.lastDisplay = this.display;
    }

    public void applySettings(@NotNull Map<String, Object> settings) {
        this.updateIntervalInTicks = (int) settings.getOrDefault(SETTING_KEY_UPDATE_INTERVALS, 20);
        this.enabled = (boolean) settings.getOrDefault(SETTING_KEY_ENABLED, false);
        this.allowPlayerDisplaySwitch = (boolean) settings.getOrDefault(SETTING_ALLOW_PLAYER_DISPLAY_SWITCH, false);

        // pre init(the value might not be initialized if it's a new player)
        if (this.storedDisplay == null) {
            this.storedDisplay = (EnumStatusBarDisplay) settings.getOrDefault(SETTING_DISPLAY, EnumStatusBarDisplay.BOSS_BAR);
        }

        // pre init(the value might not be initialized if it's a new player)
        // also force update when custom switch is not allowed
        if (this.display == null || !this.allowPlayerDisplaySwitch) {
            this.display = (EnumStatusBarDisplay) settings.getOrDefault(SETTING_DISPLAY, EnumStatusBarDisplay.BOSS_BAR);
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    protected void tick() {
        final CraftPlayer apiPlayer = (CraftPlayer) this.player.getBukkitEntity();
        boolean barUpdateRequired = this.tickedCount % this.updateIntervalInTicks == 0;

        final boolean isActualVisible = this.visible && this.enabled;

        final boolean usesBossbarBefore = this.lastDisplay == EnumStatusBarDisplay.BOSS_BAR;
        final boolean usesBossbar = this.display == EnumStatusBarDisplay.BOSS_BAR;

        // reduce allocations
        if (this.bar == null && usesBossbar) {
            this.bar = this.newBar();
        }

        // handle display updates
        // bossbar -> other
        if (usesBossbarBefore && !usesBossbar) {
            apiPlayer.hideBossBar(this.bar);
        }
        // other -> bossbar
        if (!usesBossbarBefore && usesBossbar) {
            apiPlayer.showBossBar(this.bar);
        }
        // sync display state
        if (this.lastDisplay != this.display) {
            this.handleDisplayUpdate(this.player, this.lastDisplay, this.display);

            this.lastDisplay = this.display;
        }

        // handle visible state upgrade / downgrade
        // visible -> invisible
        if (this.lastIsVisible && !isActualVisible) {
            if (usesBossbar) {
                // go hide
                apiPlayer.hideBossBar(this.bar);
            }
        }

        // invisible -> visible
        if (!this.lastIsVisible && isActualVisible) {
            if (usesBossbar) {
                // make it visible then
                apiPlayer.showBossBar(this.bar);
            }

            // force update once
            this.updateDisplay(this.bar, this.player);
            // skip unnecessary updates
            barUpdateRequired = false;
        }

        // refresh the old value after sync
        if (this.lastIsVisible != isActualVisible) {
            this.lastIsVisible = isActualVisible;
        }

        // we only updates the displayed value when it's visible
        if (isActualVisible) {
            if (barUpdateRequired) {
                this.updateDisplay(this.bar, this.player);
            }
        }

        this.tickedCount++;
    }

    // note: the visible update is performed by the tick logic, we don't actively update it
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isVisible() {
        return this.visible;
    }

    public void store(@NotNull ValueOutput output) {
        output.putBoolean("visible", this.visible);
        output.putByte("display", (byte) this.storedDisplay.ordinal());
    }

    public void load(@NotNull ValueInput input) {
        this.visible = input.getBooleanOr("visible", false);

        EnumStatusBarDisplay display = EnumStatusBarDisplay.fromOrdinal(input.getByteOr("display", (byte) 0));
        // null -> not found
        // also we only change it when custom switch is enabled
        if (display == null) {
            // init (by default it's that configured value)
            this.storedDisplay = this.display;
        } else {
            // value is present, sync
            this.storedDisplay = display;

            // then sync to mainline if custom switch is allowed
            if (this.allowPlayerDisplaySwitch) {
                this.display = display;
            }
        }
    }
}
