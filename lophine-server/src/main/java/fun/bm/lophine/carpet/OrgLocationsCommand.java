package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.ArrayList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

public final class OrgLocationsCommand {
    private OrgLocationsCommand() {}

    public static OrgWaypointStore store(MinecraftServer server) { return new OrgWaypointStore(server.getWorldPath(LevelResource.ROOT)); }

    public static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> names() {
        return (context, builder) -> {
            try { return SharedSuggestionProvider.suggest(store(context.getSource().getServer()).names().stream().map(StringArgumentType::escapeIfRequired), builder); }
            catch (java.io.IOException exception) { return builder.buildFuture(); }
        };
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locations")
            .requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandLocations))
            .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.string())
                .executes(context -> mutate(context, "add", null, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> mutate(context, "add", BlockPosArgument.getBlockPos(context, "pos"), null)))))
            .then(Commands.literal("set").then(Commands.argument("name", StringArgumentType.string()).suggests(names())
                .executes(context -> mutate(context, "set", null, null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> mutate(context, "set", BlockPosArgument.getBlockPos(context, "pos"), null)))))
            .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.string()).suggests(names())
                .executes(context -> mutate(context, "remove", null, null))))
            .then(Commands.literal("supplement").then(Commands.argument("name", StringArgumentType.string()).suggests(names())
                .then(Commands.literal("comment").executes(context -> mutate(context, "comment", null, ""))
                    .then(Commands.argument("comment", StringArgumentType.string()).executes(context -> mutate(context, "comment", null, StringArgumentType.getString(context, "comment")))))
                .then(Commands.literal("another_pos").executes(context -> mutate(context, "another", null, null))
                    .then(Commands.argument("anotherPos", BlockPosArgument.blockPos()).executes(context -> mutate(context, "another", BlockPosArgument.getBlockPos(context, "anotherPos"), null))))))
            .then(Commands.literal("list").executes(context -> list(context.getSource(), null))
                .then(Commands.argument("filter", StringArgumentType.string()).executes(context -> list(context.getSource(), StringArgumentType.getString(context, "filter")))))
            .then(Commands.literal("here").executes(context -> here(context.getSource()))));
    }

    private static int mutate(CommandContext<CommandSourceStack> context, String operation, BlockPos requested, String comment) throws CommandSyntaxException {
        ServerPlayer player = operation.equals("remove") || operation.equals("comment") ? null : context.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(context, "name");
        var source = context.getSource();
        return OrgCommandNativeEffects.command(source, 1, () -> TisCommandContinuations.then(player == null ? java.util.concurrent.CompletableFuture.<Snapshot>completedFuture(null) : OrgMenuNativeEffects.run(player, () ->
            new Snapshot(requested == null ? player.blockPosition().immutable() : requested.immutable(),
                player.level().dimension().identifier().toString(), player.getScoreboardName())), snapshot ->
            TisCommandContinuations.then(OrgCommandNativeEffects.file(source.getServer(), () -> {
            try {
                OrgWaypointStore store = store(source.getServer());
                if (operation.equals("remove")) {
                    if (!store.remove(name)) throw new IllegalArgumentException("Waypoint not found");
                } else {
                    if (operation.equals("add")) store.save(new OrgWaypointStore.Waypoint(name, snapshot.position(), snapshot.dimension(), snapshot.creator(), "", null), false);
                    else store.update(name, waypoint -> switch (operation) {
                        case "set" -> waypoint.position(snapshot.position());
                        case "comment" -> waypoint.comment(comment);
                        case "another" -> {
                            if (!waypoint.dimension().equals("minecraft:overworld") && !waypoint.dimension().equals("minecraft:the_nether")) {
                                throw new IllegalArgumentException("Opposite coordinates are only available for overworld/nether waypoints");
                            }
                            yield waypoint.another(snapshot.position());
                        }
                        default -> waypoint;
                    });
                }
                String key = "carpet-org-addition.command.locations.";
                return Component.literal(switch (operation) {
                    case "add" -> translated(key + "add.success", "Added waypoint %s at %s", name, snapshot.position().toShortString());
                    case "remove" -> translated(key + "remove.success", "Removed waypoint %s", name);
                    case "set" -> translated(key + "modify", "Changed waypoint %s", name);
                    case "comment" -> comment == null || comment.isEmpty() ? translated(key + "comment.remove", "Removed waypoint %s comment", name)
                        : translated(key + "comment.add", "Added %s as waypoint %s comment", comment, name);
                    case "another" -> translated(key + "another.add", "Added opposite coordinates");
                    default -> throw new IllegalArgumentException("Unknown waypoint operation");
                });
            } catch (java.io.IOException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }), message -> TisCommandContinuations.feedback(source, () -> { source.sendSuccess(() -> message, false); return 1; }))));
    }

    private record Snapshot(BlockPos position, String dimension, String creator) {}
    private static String translated(String key, String fallback, Object... values) { return String.format(java.util.Locale.ROOT, OrgRuleTranslations.text(key, fallback), values); }

    private static int list(CommandSourceStack source, String filter) {
        return OrgCommandNativeEffects.command(source, 1, () -> TisCommandContinuations.then(OrgCommandNativeEffects.file(source.getServer(), () -> {
        try {
            OrgWaypointStore store = store(source.getServer());
            ArrayList<Component> rows = new ArrayList<>();
            for (String name : store.names()) {
                if (filter != null && !(name + ".json").contains(filter)) continue;
                try {
                    var waypoint = store.load(name);
                    rows.add(line(waypoint));
                } catch (java.io.IOException | RuntimeException exception) { com.mojang.logging.LogUtils.getLogger().warn("Cannot load Carpet Org waypoint {}", name, exception); }
            }
            return rows;
        } catch (java.io.IOException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }), rows -> TisCommandContinuations.feedback(source, () -> {
            if (rows.isEmpty()) source.sendSuccess(() -> Component.literal(OrgRuleTranslations.text("carpet-org-addition.command.locations.list.empty", "No waypoints found")), false);
            else OrgPages.print(source, rows);
            return rows.size();
        })));
    }

    private static int here(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return OrgCommandNativeEffects.command(source, 1, () -> TisCommandContinuations.then(OrgMenuNativeEffects.run(player, () -> {
            ServerPlayer target = player;
            String dimension=target.level().dimension().identifier().toString(),key="carpet-org-addition.command.locations.here";
            Component name=target.getDisplayName().copy();
            if(target.level().dimension()==Level.OVERWORLD||target.level().dimension()==Level.NETHER){boolean overworld=target.level().dimension()==Level.OVERWORLD;var other=BlockPos.containing(target.getX()*(overworld?1D/8D:8D),target.getY(),target.getZ()*(overworld?1D/8D:8D));return localized(key+".cross","%s in %s: %s <-- %s",name,dimensionName(dimension),coordinates(target.blockPosition(),color(dimension)),coordinates(other,overworld?net.minecraft.ChatFormatting.RED:net.minecraft.ChatFormatting.GREEN));}
            return localized(key,"%s in %s: %s",name,dimensionName(dimension),coordinates(target.blockPosition(),dimension.equals("minecraft:the_end")?net.minecraft.ChatFormatting.DARK_PURPLE:null));
        }), message -> TisCommandContinuations.then(OrgCommandNativeEffects.broadcast(source.getServer(), message), ignored -> java.util.concurrent.CompletableFuture.completedFuture(1))));
    }

    private static Component localized(String key,String fallback,Object... values){return Component.translatableWithFallback(key,OrgRuleTranslations.text(key,fallback),values);}
    private static net.minecraft.ChatFormatting color(String dimension){return switch(dimension){case "minecraft:the_nether"->net.minecraft.ChatFormatting.RED;case "minecraft:the_end"->net.minecraft.ChatFormatting.DARK_PURPLE;default->net.minecraft.ChatFormatting.GREEN;};}
    private static Component dimensionName(String dimension){return switch(dimension){case "minecraft:overworld"->localized("carpet-org-addition.dimension.overworld","Overworld");case "minecraft:the_nether"->localized("carpet-org-addition.dimension.the_nether","Nether");case "minecraft:the_end"->localized("carpet-org-addition.dimension.the_end","End");default->Component.literal(dimension);};}
    private static Component coordinates(BlockPos position,net.minecraft.ChatFormatting color){var value=Component.literal("["+position.toShortString()+"]").withStyle(style->style.withClickEvent(new net.minecraft.network.chat.ClickEvent.CopyToClipboard(position.getX()+" "+position.getY()+" "+position.getZ())));return color==null?value:value.withStyle(color);}
    static Component line(OrgWaypointStore.Waypoint waypoint){String key="carpet-org-addition.command.locations.where";var name=Component.literal("["+waypoint.name().split("\\.")[0]+"]");if(!waypoint.comment().isEmpty())name.withStyle(style->style.withHoverEvent(new HoverEvent.ShowText(Component.literal(waypoint.comment()))));if(waypoint.another()!=null&&(waypoint.dimension().equals("minecraft:overworld")||waypoint.dimension().equals("minecraft:the_nether"))){String other=waypoint.dimension().equals("minecraft:overworld")?"minecraft:the_nether":"minecraft:overworld";return localized(key+".cross","%s in %s: %s(%s: %s)",name,dimensionName(waypoint.dimension()),coordinates(waypoint.position(),color(waypoint.dimension())),dimensionName(other),coordinates(waypoint.another(),color(other)));}return localized(key,"%s in %s: %s",name,dimensionName(waypoint.dimension()),coordinates(waypoint.position(),color(waypoint.dimension())));}
}
