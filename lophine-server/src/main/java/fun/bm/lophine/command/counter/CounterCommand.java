package fun.bm.lophine.command.counter;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.command.counter.sub.DisplayCommand;
import fun.bm.lophine.command.counter.sub.ResetCommand;
import fun.bm.lophine.command.counter.sub.ToggleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.RootNode;
import org.leavesmc.leaves.util.HopperCounter;

public class CounterCommand extends RootNode {
    private static final String PERM_BASE = "lophine.commands.counter";

    public CounterCommand() {
        super("counter", "lophine.commands.counter");
        children(
                ToggleCommand::new,
                ResetCommand::new,
                DisplayCommand::new
        );
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        context.getSender().sendMessage(Component.join(JoinConfiguration.noSeparators(),
                Component.text("Hopper Counter: ", NamedTextColor.GRAY),
                Component.text(HopperCounter.isEnabled(), HopperCounter.isEnabled() ? NamedTextColor.AQUA : NamedTextColor.GRAY)
        ));
        return true;
    }

    public static boolean hasPermission(@NotNull CommandSender sender, String... subcommand) {
        return hasPermission(PERM_BASE, sender, subcommand);
    }
}
