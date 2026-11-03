package me.earthme.luminol.commands.bar.sub;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.command.CommandHolder;
import fun.bm.lophine.utils.ServerI18nUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.earthme.luminol.commands.bar.BarCommand;
import me.earthme.luminol.enums.EnumBarType;
import me.earthme.luminol.functions.bars.TickableStatusBarList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

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
        return execute0(context, Set.of(((CraftPlayer) player).getHandle()), null);
    }

    public boolean execute0(@NotNull CommandContext context, PlayerSelectorArgumentResolver resolver, @Nullable Boolean value) throws CommandSyntaxException {
        Set<ServerPlayer> players = new HashSet<>();
        resolver.resolve(context.getSource()).forEach(player -> players.add(((CraftPlayer) player).getHandle()));
        return execute0(context, players, value);
    }

    public boolean execute0(@NotNull CommandContext context, Collection<ServerPlayer> players, @Nullable Boolean value) {
        CommandSender sender = context.getSender();
        for (ServerPlayer player : players) {
            player.getBukkitEntity().taskScheduler.scheduleOrExecute(scheduled -> {
                toggleBar(sender, (ServerPlayer) scheduled, value);
            });
        }
        return true;
    }

    private void toggleBar(CommandSender sender, ServerPlayer player, @Nullable Boolean value) {
        final TickableStatusBarList barList = player.statusBarList;

        if (!barList.isEnabled(this.barType)) {
            sender.sendMessage(Component.text(ServerI18nUtil.getFormatedLocalizedText("luminol.command.bar.toggle.already_disabled", this.barType.getName()), NamedTextColor.RED));
            return;
        }

        boolean currentValue = barList.isVisible(this.barType);
        if (value != null && value == currentValue) {
            sender.sendMessage(Component.text(ServerI18nUtil.getFormatedLocalizedText("luminol.command.bar.toggle.already_" + value, this.barType.getName(), player.getScoreboardName()), NamedTextColor.RED));
            return;
        }

        boolean newVisible = value != null ? value : !currentValue;
        barList.setVisible(this.barType, newVisible);
        sender.sendMessage(Component.text(ServerI18nUtil.getFormatedLocalizedText("luminol.command.bar.toggle." + newVisible, this.barType.getName(), player.getScoreboardName()), NamedTextColor.GREEN));
    }

    private class PlayerArg extends ArgumentNode<PlayerSelectorArgumentResolver> {
        protected PlayerArg() {
            super("player", ArgumentTypes.players());
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            PlayerSelectorArgumentResolver resolver = context.getArgument(PlayerArg.class);
            return execute0(context, resolver, null);
        }
    }

    private class BoolArg extends ArgumentNode<Boolean> {
        protected BoolArg() {
            super("bool", BoolArgumentType.bool());
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            PlayerSelectorArgumentResolver resolver = context.getArgument(PlayerArg.class);
            boolean value = context.getArgument(BoolArg.class);
            execute0(context, resolver, value);
            return true;
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
