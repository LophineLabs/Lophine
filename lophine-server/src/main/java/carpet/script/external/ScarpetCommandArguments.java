// SPDX-License-Identifier: MIT
package carpet.script.external;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.ScoreHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Actual Brigadier parsed selectors are resolved through owner fan-in, preserving native parse/permission limits.
 */
public final class ScarpetCommandArguments {
    private ScarpetCommandArguments() {
    }

    public static Collection<? extends Entity> entities(CommandContext<CommandSourceStack> context, String name) {
        return EntityActors.select(context.getSource(), context.getArgument(name, EntitySelector.class));
    }

    public static Collection<NameAndId> profiles(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        GameProfileArgument.Result parsed = context.getArgument(name, GameProfileArgument.Result.class);
        if (!(parsed instanceof GameProfileArgument.SelectorResult selected))
            return parsed.getNames(context.getSource());
        List<NameAndId> result = new ArrayList<>();
        for (Entity entity : EntityActors.select(context.getSource(), selected.carpetSelector()))
            if (entity instanceof ServerPlayer player)
                result.add(ScarpetRuntime.atEntity(player, player::nameAndId));
        if (result.isEmpty()) throw EntityArgument.NO_PLAYERS_FOUND.create();
        return result;
    }

    public static Collection<ScoreHolder> scoreHolders(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        ScoreHolderArgument.Result parsed = context.getArgument(name, ScoreHolderArgument.Result.class);
        List<ScoreHolder> result = new ArrayList<>();
        if (parsed instanceof ScoreHolderArgument.SelectorResult selected) {
            for (Entity entity : EntityActors.select(context.getSource(), selected.carpetSelector()))
                result.add(ScoreHolder.forNameOnly(ScarpetRuntime.atEntity(entity, entity::getScoreboardName)));
        } else if (parsed instanceof ScoreHolderArgument.CarpetNamedResult named) {
            String text = named.name();
            if (text.equals("*"))
                throw EntityArgument.NO_ENTITIES_FOUND.create(); // getNames uses an empty default wildcard upstream
            if (!text.startsWith("#")) {
                UUID uuid = null;
                try {
                    uuid = UUID.fromString(text);
                } catch (IllegalArgumentException ignored) {
                }
                if (uuid != null) {
                    List<ServerLevel> worlds = ScarpetRuntime.atGlobal(context.getSource().getServer(), () -> java.util.stream.StreamSupport.stream(context.getSource().getServer().getAllLevels().spliterator(), false).toList());
                    for (ServerLevel world : worlds) {
                        Entity entity = EntityActors.byId(world, null, uuid);
                        if (entity != null)
                            result.add(ScoreHolder.forNameOnly(ScarpetRuntime.atEntity(entity, entity::getScoreboardName)));
                    }
                } else {
                    ServerPlayer player = EntityActors.player(context.getSource().getServer(), text);
                    if (player != null)
                        result.add(ScoreHolder.forNameOnly(ScarpetRuntime.atEntity(player, player::getScoreboardName)));
                }
            }
            if (result.isEmpty()) result.add(ScoreHolder.forNameOnly(text));
        } else
            throw new carpet.script.exception.InternalExpressionException("Unrecognized native score holder parse result");
        if (result.isEmpty()) throw EntityArgument.NO_ENTITIES_FOUND.create();
        return result;
    }

    public static Component message(CommandContext<CommandSourceStack> context, String name) {
        MessageArgument.Message parsed = context.getArgument(name, MessageArgument.Message.class);
        if (parsed.parts().length == 0 || !context.getSource().permissions().hasPermission(Permissions.COMMANDS_ENTITY_SELECTORS))
            return Component.literal(parsed.text());
        MutableComponent result = Component.literal(parsed.text().substring(0, parsed.parts()[0].start()));
        int readTo = parsed.parts()[0].start();
        for (MessageArgument.Part part : parsed.parts()) {
            if (readTo < part.start()) result.append(parsed.text().substring(readTo, part.start()));
            List<Component> names = new ArrayList<>();
            for (Entity entity : EntityActors.select(context.getSource(), part.selector()))
                names.add(ScarpetRuntime.atEntity(entity, () -> entity.getDisplayName().copy()));
            result.append(ComponentUtils.formatList(names, java.util.function.Function.identity()));
            readTo = part.end();
        }
        if (readTo < parsed.text().length()) result.append(parsed.text().substring(readTo));
        return result;
    }
}
