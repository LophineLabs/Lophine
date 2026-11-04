package fun.bm.lophine.command.tps.sub;

import fun.bm.lophine.command.tps.TpsReport;
import fun.bm.lophine.command.tps.TpsSubCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;

public class RegionCommand extends TpsSubCommand {
    public RegionCommand() {
        super("region");
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return super.requires(source) && source.getExecutor() instanceof Entity;
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) {
        return TpsReport.reportRegion(context.getSender());
    }
}
