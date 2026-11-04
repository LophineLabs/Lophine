package fun.bm.lophine.command.tps.sub;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import fun.bm.lophine.command.tps.TpsReport;
import fun.bm.lophine.command.tps.TpsSubCommand;
import fun.bm.lophine.utils.ServerI18nUtil;
import fun.bm.lophine.utils.markdown.MarkdownArgs;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;

public class ServerCommand extends TpsSubCommand {
    public static final int DEFAULT_REGION_COUNT = 3;

    public ServerCommand() {
        super("server");
        children(CountArg::new);
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) {
        return report(context.getSender(), DEFAULT_REGION_COUNT);
    }

    public static boolean report(CommandSender sender, int regionCount) {
        if (regionCount < 0) {
            sender.sendMessage(ServerI18nUtil.getLocalizedComponent(
                    "lophine.command.tps.server.invalid_count",
                    MarkdownArgs.create().set("count", regionCount)
            ));
            return true;
        }
        return TpsReport.reportServer(sender, regionCount);
    }

    private static class CountArg extends ArgumentNode<Integer> {
        protected CountArg() {
            super("count", IntegerArgumentType.integer(0));
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            return report(context.getSender(), context.getArgument(CountArg.class));
        }
    }
}
