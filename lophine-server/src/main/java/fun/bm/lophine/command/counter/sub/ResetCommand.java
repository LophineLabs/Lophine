package fun.bm.lophine.command.counter.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fun.bm.lophine.command.counter.CounterSubCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.util.HopperCounter;

import java.util.concurrent.CompletableFuture;

public class ResetCommand extends CounterSubCommand {
    public ResetCommand() {
        super("reset");
        children(
                DyeColorArg::new
        );
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        HopperCounter.resetAll(MinecraftServer.getServer(), false);
        context.getSender().sendMessage(Component.text("Restarted all counters."));
        return true;
    }

    private static class DyeColorArg extends ArgumentNode<String> {
        protected DyeColorArg() {
            super("color", StringArgumentType.string());
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            String color0 = context.getArgument(DyeColorArg.class);
            if (color0.equals("all")) {
                HopperCounter.resetAll(MinecraftServer.getServer(), false);
                context.getSender().sendMessage(Component.text("Restarted all counters."));
                return true;
            }
            DyeColor color = DyeColor.byName(color0, null);
            if (color == null) return true;
            HopperCounter counter = HopperCounter.getCounter(color);
            counter.reset(MinecraftServer.getServer());
            context.getSender().sendMessage(Component.join(JoinConfiguration.noSeparators(),
                    Component.text("Restarted "),
                    Component.text(color.getName(), TextColor.color(color.getTextColor())),
                    Component.text(" counter.")
            ));
            return true;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            String path = context.getArgumentOrDefault(DyeColorArg.class, "");
            if ("all".startsWith(path)) {
                builder.suggest("all");
            }
            for (DyeColor value : DyeColor.values()) {
                String color = value.getName();
                if (color.startsWith(path)) {
                    builder.suggest(color);
                }
            }
            return builder.buildFuture();
        }
    }
}
