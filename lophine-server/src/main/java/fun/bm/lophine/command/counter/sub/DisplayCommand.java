package fun.bm.lophine.command.counter.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fun.bm.lophine.command.counter.CounterSubCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.util.HopperCounter;

import java.util.concurrent.CompletableFuture;

public class DisplayCommand extends CounterSubCommand {
    public DisplayCommand() {
        super("display");
        children(
                DyeColorArg::new
        );
    }

    public static void displayCounter(CommandContext context, @NotNull HopperCounter counter, boolean realTime) {
        for (Component component : counter.format(MinecraftServer.getServer(), realTime)) {
            context.getSender().sendMessage(component);
        }
    }

    private static class DyeColorArg extends ArgumentNode<String> {
        protected DyeColorArg() {
            super("color", StringArgumentType.string());
            children(
                    TimeArg::new
            );
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            String color0 = context.getArgument(DyeColorArg.class);
            DyeColor color = DyeColor.byName(color0, null);
            if (color == null) return true;
            HopperCounter counter = HopperCounter.getCounter(color);
            displayCounter(context, counter, false);
            return true;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            String path = context.getArgumentOrDefault(DyeColorArg.class, "");
            for (DyeColor value : DyeColor.values()) {
                String color = value.getName();
                if (color.startsWith(path)) {
                    builder.suggest(color);
                }
            }
            return builder.buildFuture();
        }

        private static class TimeArg extends ArgumentNode<String> {
            protected TimeArg() {
                super("time", StringArgumentType.string());
            }

            @Override
            protected boolean execute(@NotNull CommandContext context) {
                String color0 = context.getArgument(DyeColorArg.class);
                DyeColor color = DyeColor.byName(color0, null);
                if (color == null) return true;
                HopperCounter counter = HopperCounter.getCounter(color);
                String timeType = context.getArgument(TimeArg.class);
                switch (timeType) {
                    case "realtime" -> displayCounter(context, counter, true);
                    case "gametick" -> displayCounter(context, counter, false);
                    default ->
                            context.getSender().sendMessage(Component.text("Invalid time type: " + timeType, NamedTextColor.RED));
                }
                return true;
            }

            protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
                builder.suggest("realtime");
                builder.suggest("gametick");
                return builder.buildFuture();
            }
        }
    }
}
