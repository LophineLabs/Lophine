package fun.bm.lophine.command.counter.sub;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.command.counter.CounterSubCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.util.HopperCounter;

public class ToggleCommand extends CounterSubCommand {
    public ToggleCommand() {
        super("toggle");
        children(
                BooleanArg::new
        );
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        if (!HopperCounter.isEnabled()) {
            HopperCounter.setEnabled(true);
            context.getSender().sendMessage(Component.text("Hopper Counter now is enabled.", NamedTextColor.AQUA));
        } else {
            HopperCounter.setEnabled(false);
            context.getSender().sendMessage(Component.text("Hopper Counter now is disabled.", NamedTextColor.RED));
        }
        return true;
    }

    private static class BooleanArg extends ArgumentNode<Boolean> {
        protected BooleanArg() {
            super("enabled", BoolArgumentType.bool());
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            boolean enabled = context.getArgument(BooleanArg.class);
            if (enabled == HopperCounter.isEnabled()) {
                context.getSender().sendMessage(Component.text("Hopper Counter is already " + (enabled ? "enabled" : "disabled") + ".", NamedTextColor.GRAY));
            } else {
                HopperCounter.setEnabled(enabled);
                if (enabled) {
                    context.getSender().sendMessage(Component.text("Hopper Counter now is enabled.", NamedTextColor.AQUA));
                } else {
                    context.getSender().sendMessage(Component.text("Hopper Counter now is disabled.", NamedTextColor.RED));
                }
            }
            return true;
        }
    }
}
