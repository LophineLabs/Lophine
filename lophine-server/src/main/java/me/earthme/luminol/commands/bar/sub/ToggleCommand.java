package me.earthme.luminol.commands.bar.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.PaperCommands;
import me.earthme.luminol.commands.bar.BarCommand;
import me.earthme.luminol.enums.EnumBarType;
import me.earthme.luminol.functions.bars.TickableStatusBarList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ToggleCommand extends LiteralNode {
    private final EnumBarType barType;

    public ToggleCommand(EnumBarType barType) {
        super("toggle");
        this.barType = barType;
        children(
                PlayerArg::new
        );
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        if (!(context.getSender() instanceof Player player)) {
            context.getSender().sendMessage(Component.text("Only player can display bars!").color(TextColor.color(255, 0, 0)));
            return true;
        }
        return execute0(context, player);
    }

    public boolean execute0(@NotNull CommandContext context, Player player) {
        final TickableStatusBarList barList = ((CraftPlayer) player).getHandle().statusBarList;

        boolean enabled = barList.isEnabled(this.barType);

        if (!enabled) {
            context.getSender().sendMessage(Component.text("Bar type with " + this.barType.getName() + " was already disabled!").color(TextColor.color(255, 0, 0)));
            return true;
        }

        if (barList.isVisible(this.barType)) {
            context.getSender().sendMessage(Component.text("Disabled Bar type with " + this.barType.getName() + " for " + player.getName()).color(TextColor.color(0, 255, 0)));
            barList.setVisible(this.barType, false);
            return true;
        }

        context.getSender().sendMessage(Component.text("Enabled Bar type with " + this.barType.getName() + " for " + player.getName()).color(TextColor.color(0, 255, 0)));
        barList.setVisible(this.barType, true);
        return true;
    }

    private class PlayerArg extends ArgumentNode<String> {
        protected PlayerArg() {
            super("player", StringArgumentType.string());
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(@NotNull CommandContext context, @NotNull SuggestionsBuilder builder) {
            Bukkit.getServer().getOnlinePlayers().forEach(player -> builder.suggest(player.getName()));
            return builder.buildFuture();
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) {
            String name = context.getArgument(PlayerArg.class);
            Player player = Bukkit.getServer().getPlayer(name);
            if (player == null) {
                player = Bukkit.getServer().getPlayer(UUID.fromString(name));
                if (player == null) {
                    context.getSender().sendMessage(Component.text("Player " + name + " was not found!").color(TextColor.color(255, 0, 0)));
                    return true;
                }
            }
            return execute0(context, player);
        }
    }

    protected ArgumentBuilder<CommandSourceStack, ?> compile0() {
        ArgumentBuilder<CommandSourceStack, ?> builder = Commands.literal(this.barType.getCommandName()).requires(this::requires);

        if (canExecute()) {
            builder = builder.executes(mojangCtx -> {
                CommandContext ctx = new CommandContext(mojangCtx);
                return execute(ctx) ? 1 : 0;
            });
        }

        return builder;
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return BarCommand.hasPermission(source.getSender(), this.barType.getName(), this.name);
    }

    @SuppressWarnings("unchecked")
    public void register() { // register for old version command
        PaperCommands.INSTANCE.setValid();
        PaperCommands.INSTANCE.getDispatcher().register((LiteralArgumentBuilder<CommandSourceStack>) compile0());
        PaperCommands.INSTANCE.invalidate();
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
    }

    public void unregister() { // unregister for old version command
        PaperCommands.INSTANCE.setValid();
        PaperCommands.INSTANCE.getDispatcher().getRoot().removeCommand(this.barType.getCommandName());
        PaperCommands.INSTANCE.invalidate();
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
    }
}
