package fun.bm.lophine.command.tps;

import fun.bm.lophine.command.tps.sub.RegionCommand;
import fun.bm.lophine.command.tps.sub.ServerCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.RootNode;

public class TpsCommand extends RootNode {
    public static final String PERM_BASE = "bukkit.command.tps";

    public TpsCommand() {
        super("tps", PERM_BASE);
        children(
                ServerCommand::new,
                RegionCommand::new
        );
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return hasPermission(source.getSender());
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) {
        return ServerCommand.report(context.getSender(), ServerCommand.DEFAULT_REGION_COUNT);
    }

    public static boolean hasPermission(@NotNull CommandSender sender, String... subcommand) {
        return hasPermission(PERM_BASE, sender, subcommand);
    }
}
