package me.earthme.luminol.commands.bar;

import me.earthme.luminol.commands.bar.sub.ToggleCommand;
import me.earthme.luminol.enums.EnumBarType;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.RootNode;

public class BarCommand extends RootNode {
    private static final String PERM_BASE = "luminol.commands.bar";

    public BarCommand() {
        super("bar", PERM_BASE);
        children(
                new BarSubcommand(EnumBarType.TPS),
                new BarSubcommand(EnumBarType.MEMORY),
                new BarSubcommand(EnumBarType.REGION)
        );
    }

    public static boolean hasPermission(@NotNull CommandSender sender, String... subcommand) {
        return hasPermission(PERM_BASE, sender, subcommand);
    }

    @Override
    public void register() {
        super.register();
        children.forEach(child -> {
            if (child instanceof BarSubcommand barSubcommand) {
                barSubcommand.getChildren().forEach(subChild -> {
                    if (subChild instanceof ToggleCommand toggleCommand) {
                        toggleCommand.register();
                    }
                });
            }
        });
    }

    @Override
    public void unregister() {
        super.unregister();
        children.forEach(child -> {
            if (child instanceof BarSubcommand barSubcommand) {
                barSubcommand.getChildren().forEach(subChild -> {
                    if (subChild instanceof ToggleCommand toggleCommand) {
                        toggleCommand.unregister();
                    }
                });
            }
        });
    }
}
