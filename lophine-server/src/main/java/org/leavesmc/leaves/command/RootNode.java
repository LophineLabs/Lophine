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

package org.leavesmc.leaves.command;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.PaperCommands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import static org.leavesmc.leaves.command.CommandUtils.registerPermissions;

public abstract class RootNode extends LiteralNode {
    private final String permissionBase;

    public RootNode(String name, String permissionBase) {
        super(name);
        this.permissionBase = permissionBase;
    }

    @Override
    protected ArgumentBuilder<CommandSourceStack, ?> compile() {
        registerPermissions(permissionBase, this.children);
        return super.compile();
    }

    public static boolean hasPermission(String permissionBase, @NotNull CommandSender sender, String... subcommand) {
        if (sender.hasPermission(permissionBase)) return true;
        String currentPermission = permissionBase;
        for (String sub : subcommand) {
            currentPermission += "." + sub;
            if (sender.hasPermission(currentPermission)) return true;
        }
        return false;
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return children.stream().anyMatch(child -> child.requires(source));
    }

    @SuppressWarnings("unchecked")
    public void register() {
        PaperCommands.INSTANCE.setValid();
        PaperCommands.INSTANCE.getDispatcher().register((LiteralArgumentBuilder<CommandSourceStack>) compile());
        PaperCommands.INSTANCE.invalidate();
        Bukkit.getOnlinePlayers().forEach(org.bukkit.entity.Player::updateCommands);
    }

    public void unregister() {
        PaperCommands.INSTANCE.setValid();
        PaperCommands.INSTANCE.getDispatcher().getRoot().removeCommand(name);
        PaperCommands.INSTANCE.invalidate();
        Bukkit.getOnlinePlayers().forEach(org.bukkit.entity.Player::updateCommands);
    }
}
