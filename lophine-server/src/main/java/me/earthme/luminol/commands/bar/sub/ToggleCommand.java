package me.earthme.luminol.commands.bar.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fun.bm.lophine.command.CommandHolder;
import fun.bm.lophine.utils.ServerI18nUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.earthme.luminol.commands.bar.BarCommand;
import me.earthme.luminol.enums.EnumBarType;
import me.earthme.luminol.functions.bars.TickableStatusBarList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
            context.getSender().sendMessage(Component.text(ServerI18nUtil.getLocalizedText("general.command.only-player"), NamedTextColor.RED));
            return true;
        }
        return execute0(context, player);
    }

    public boolean execute0(@NotNull CommandContext context, Player player) {
        final TickableStatusBarList barList = ((CraftPlayer) player).getHandle().statusBarList;

        boolean enabled = barList.isEnabled(this.barType);

        if (!enabled) {
            context.getSender().sendMessage(Component.text(ServerI18nUtil.getFormatedLocalizedText("luminol.command.bar.toggle.already_disabled", this.barType.getName()), NamedTextColor.RED));
            return true;
        }

        context.getSender().sendMessage(Component.text(ServerI18nUtil.getFormatedLocalizedText("luminol.command.bar.toggle." + !barList.isVisible(this.barType), this.barType.getName(), player.getName()), NamedTextColor.GREEN));
        barList.setVisible(this.barType, !barList.isVisible(this.barType));
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
                    context.getSender().sendMessage(Component.text(ServerI18nUtil.getFormatedLocalizedText("luminol.command.bar.toggle.player_not_found", name), NamedTextColor.RED));
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
        CommandHolder.registerCommand((LiteralArgumentBuilder<CommandSourceStack>) compile0());
    }

    public void unregister() { // unregister for old version command
        CommandHolder.unregisterCommand(this.barType.getCommandName());
    }
}
