package me.earthme.luminol.commands.bar;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.earthme.luminol.commands.bar.sub.ConfigEditCommand;
import me.earthme.luminol.commands.bar.sub.ToggleCommand;
import me.earthme.luminol.enums.EnumBarType;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandNode;
import org.leavesmc.leaves.command.LiteralNode;

import java.util.List;

public class BarSubcommand extends LiteralNode {
    public BarSubcommand(EnumBarType barType) {
        super(barType.getName());
        children(
                new ToggleCommand(barType),
                new ConfigEditCommand(barType)
        );
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return hasPermission(source.getSender());
    }

    protected boolean hasPermission(CommandSender sender) {
        return BarCommand.hasPermission(sender, this.name);
    }

    public List<CommandNode> getChildren() {
        return children;
    }
}
