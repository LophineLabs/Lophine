/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package fun.bm.lophine.carpet;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.List;

public class CarpetInternalPlugin extends PluginBase {

    public static final CarpetInternalPlugin INSTANCE = new CarpetInternalPlugin();

    private boolean enabled = true;

    private final PluginDescriptionFile pdf;
    private final java.util.logging.Logger logger;

    public CarpetInternalPlugin() {
        String pluginName = "LophineCarpet";
        pdf = new PluginDescriptionFile(pluginName, "1.0", "nms");
        logger = java.util.logging.Logger.getLogger(pluginName);
        logger.setParent(getServer().getLogger());
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    @Deprecated // Required by Plugin; internal callers use Paper's getPluginMeta().
    public @NotNull PluginDescriptionFile getDescription() {
        return pdf;
    }

    @Override
    public @NotNull PluginMeta getPluginMeta() {
        return pdf;
    }

    @Override
    public @NotNull java.util.logging.Logger getLogger() {
        return logger;
    }

    @Override
    public @NotNull Server getServer() {
        return Bukkit.getServer();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public @NotNull PluginLoader getPluginLoader() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public @NotNull File getDataFolder() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public @NotNull FileConfiguration getConfig() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public InputStream getResource(@NotNull String filename) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void saveConfig() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void saveDefaultConfig() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void saveResource(@NotNull String resourcePath, boolean replace) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void reloadConfig() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void onDisable() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void onLoad() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void onEnable() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public boolean isNaggable() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void setNaggable(boolean canNag) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, String id) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(@NotNull String worldName, @Nullable String id) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public @NotNull LifecycleEventManager<@NotNull Plugin> getLifecycleManager() {
        throw new UnsupportedOperationException("Not supported.");
    }
}
