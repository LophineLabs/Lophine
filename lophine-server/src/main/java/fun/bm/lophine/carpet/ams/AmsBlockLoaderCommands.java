// SPDX-License-Identifier: LGPL-3.0-or-later
// Command syntax from Carpet AMS Addition 66b8c3b0.
package fun.bm.lophine.carpet.ams;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import fun.bm.lophine.carpet.CarpetCommandPermissions;
import fun.bm.lophine.carpet.ams.AmsBlockLoaderConfig.Condition;
import fun.bm.lophine.carpet.ams.AmsBlockLoaderConfig.Config;
import fun.bm.lophine.carpet.ams.AmsBlockLoaderConfig.Group;
import fun.bm.lophine.carpet.ams.AmsBlockLoaderConfig.Type;
import fun.bm.lophine.carpet.org.OrgCommandNativeEffects;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class AmsBlockLoaderCommands {
    private static final String HELP_URL = "https://carpet.mcams.club/Commands/";
    private static final String PREFIX = "command.blockChunkloader.";

    private AmsBlockLoaderCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext access) {
        var root = Commands.literal("blockChunkloader")
                .requires(source -> Arrays.stream(Type.values()).anyMatch(type -> permitted(source, type)))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("list").executes(context -> {
                    for (Type type : Type.values()) {
                        if (permitted(context.getSource(), type)) show(context.getSource(), type);
                    }
                    return 1;
                }));
        for (Type type : Type.values()) {
            root.then(Commands.literal(type.id()).requires(source -> permitted(source, type))
                    .then(Commands.literal("show").executes(context -> show(context.getSource(), type)))
                    .then(Commands.literal("enabled").then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(context -> edit(context, type, config -> config.enabled = BoolArgumentType.getBool(context, "enabled")))))
                    .then(Commands.literal("time").then(Commands.argument("time", IntegerArgumentType.integer(1, 300))
                            .executes(context -> edit(context, type, config -> config.time = IntegerArgumentType.getInteger(context, "time")))))
                    .then(Commands.literal("range").then(word("range").suggests((context, builder) ->
                            SharedSuggestionProvider.suggest(AmsBlockLoaderConfig.RANGES, builder))
                            .executes(context -> edit(context, type, config -> config.range = StringArgumentType.getString(context, "range")))))
                    .then(Commands.literal("reset").executes(context -> edit(context, type, config -> {
                        var defaults = new Config();
                        config.enabled = defaults.enabled;
                        config.time = defaults.time;
                        config.range = defaults.range;
                        config.groups = defaults.groups;
                    })))
                    .then(Commands.literal("test").then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(context -> test(context, type))))
                    .then(groupCommands(type))
                    .then(conditionCommands(type, access)));
        }
        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> groupCommands(Type type) {
        var mode = groupArgument(type);
        for (String name : new String[]{"all", "any"}) {
            mode.then(Commands.literal(name).executes(context ->
                    edit(context, type, config -> group(context, config).any = "any".equals(name))));
        }
        return Commands.literal("group")
                .then(Commands.literal("list").executes(context -> groups(context.getSource(), AmsBlockLoaderConfig.get(type))))
                .then(Commands.literal("show").then(groupArgument(type).executes(context -> showGroup(context, type, true))))
                .then(Commands.literal("create").then(word("name").executes(context -> edit(context, type, config -> {
                    String name = StringArgumentType.getString(context, "name");
                    if (config.groups.putIfAbsent(name, new Group()) != null) throw new IllegalArgumentException(name);
                }))))
                .then(Commands.literal("remove").then(groupArgument(type).executes(context -> edit(context, type, config -> {
                    group(context, config);
                    config.groups.remove(StringArgumentType.getString(context, "group"));
                }))))
                .then(Commands.literal("clear").executes(context -> edit(context, type, config -> config.groups = new Config().groups)))
                .then(Commands.literal("mode").then(mode))
                .then(Commands.literal("enabled").then(groupArgument(type).then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(context -> edit(context, type, config -> group(context, config).enabled = BoolArgumentType.getBool(context, "enabled"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> conditionCommands(Type type, CommandBuildContext access) {
        var offset = Commands.argument("dz", IntegerArgumentType.integer(-32, 32));
        var direction = word("direction").suggests((context, builder) ->
                SharedSuggestionProvider.suggest(Arrays.stream(Direction.values()).map(Direction::getName), builder));
        var distance = Commands.argument("distance", IntegerArgumentType.integer(1, 32));
        for (String operation : new String[]{"is", "not"}) {
            boolean inverted = "not".equals(operation);
            offset.then(Commands.literal(operation).then(Commands.argument("block", BlockPredicateArgument.blockPredicate(access))
                    .executes(context -> add(context, type, IntegerArgumentType.getInteger(context, "dx"),
                            IntegerArgumentType.getInteger(context, "dy"), IntegerArgumentType.getInteger(context, "dz"), inverted))));
            distance.then(Commands.literal(operation).then(Commands.argument("block", BlockPredicateArgument.blockPredicate(access))
                    .executes(context -> {
                        Direction step = Direction.byName(StringArgumentType.getString(context, "direction"));
                        if (step == null) return invalid(context.getSource(), "direction");
                        int length = IntegerArgumentType.getInteger(context, "distance");
                        return add(context, type, step.getStepX() * length, step.getStepY() * length, step.getStepZ() * length, inverted);
                    })));
        }
        return Commands.literal("condition")
                .then(Commands.literal("list").then(groupArgument(type).executes(context -> showGroup(context, type, false))))
                .then(Commands.literal("clear").then(groupArgument(type).executes(context ->
                        edit(context, type, config -> group(context, config).conditions.clear()))))
                .then(Commands.literal("remove").then(groupArgument(type).then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(context -> edit(context, type, config -> {
                            int id = IntegerArgumentType.getInteger(context, "id");
                            if (!group(context, config).conditions.removeIf(condition -> condition.id == id))
                                throw new IllegalArgumentException("#" + id);
                        })))))
                .then(Commands.literal("add").then(groupArgument(type)
                        .then(Commands.literal("offset").then(Commands.argument("dx", IntegerArgumentType.integer(-32, 32))
                                .then(Commands.argument("dy", IntegerArgumentType.integer(-32, 32)).then(offset))))
                        .then(Commands.literal("direction").then(direction.then(distance)))));
    }

    private static int add(CommandContext<CommandSourceStack> context, Type type, int dx, int dy, int dz, boolean inverted) {
        var range = context.getNodes().getLast().getRange();
        String block = range.get(context.getInput());
        return edit(context, type, config -> group(context, config).conditions.add(new Condition(dx, dy, dz, inverted, block)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> word(String name) {
        return Commands.argument(name, StringArgumentType.word());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> groupArgument(Type type) {
        return word("group").suggests((context, builder) ->
                SharedSuggestionProvider.suggest(AmsBlockLoaderConfig.get(type).groups.keySet(), builder));
    }

    private static Group group(CommandContext<CommandSourceStack> context, Config config) {
        String name = StringArgumentType.getString(context, "group");
        Group group = config.groups.get(name);
        if (group == null) throw new IllegalArgumentException(name);
        return group;
    }

    private static boolean permitted(CommandSourceStack source, Type type) {
        return CarpetCommandPermissions.canUse(source, type.permission());
    }

    private static int edit(CommandContext<CommandSourceStack> context, Type type, Consumer<Config> change) {
        var source = context.getSource();
        return OrgCommandNativeEffects.command(source, 1, () -> AmsNativeCommandEffects.then(
                OrgCommandNativeEffects.file(source.getServer(), () -> {
                    try {
                        AmsBlockLoaderConfig.edit(source.getServer(), type, change);
                        return null;
                    } catch (IllegalArgumentException | IllegalStateException | java.io.UncheckedIOException failure) {
                        return failure;
                    }
                }), failure -> AmsNativeCommandEffects.source(source, () -> {
                    if (failure != null) return invalid(source, failure.getMessage());
                    tell(source, "saved", type.id());
                    return 1;
                })));
    }

    private static int test(CommandContext<CommandSourceStack> context, Type type) {
        var source = context.getSource();
        var world = source.getLevel();
        var pos = BlockPosArgument.getBlockPos(context, "pos");
        if (world.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            tell(source, "unloaded");
            return 0;
        }
        var config = AmsBlockLoaderConfig.get(type);
        return OrgCommandNativeEffects.command(source, 1, () -> {
            var output = new java.util.ArrayList<MutableComponent>();
            var work = CompletableFuture.completedFuture(true);
            for (var entry : config.groups.entrySet()) {
                work = AmsNativeCommandEffects.then(work, matched -> {
                    Group group = entry.getValue();
                    var label = message(source, "test_group", entry.getKey());
                    if (!group.enabled) output.add(label.append(message(source, "group_disabled")));
                    if (!group.enabled || group.conditions.isEmpty()) return CompletableFuture.completedFuture(matched);
                    return AmsNativeCommandEffects.then(group.evaluate(world, pos), result -> {
                        output.add(label.append(result(source, result)));
                        var conditions = AmsNativeCommandEffects.sequence(source.getServer(), group.conditions.iterator(), condition ->
                                condition.evaluate(world, pos).thenAccept(value ->
                                        output.add(message(source, "test_condition", condition.id).append(result(source, value)))));
                        return AmsNativeCommandEffects.then(conditions, ignored ->
                                CompletableFuture.completedFuture(matched && result == AmsBlockLoaderConfig.Result.MATCH));
                    });
                });
            }
            return AmsNativeCommandEffects.then(work, matched -> AmsNativeCommandEffects.source(source, () -> {
                output.forEach(line -> source.sendSuccess(() -> line, false));
                tell(source, "test_result", matched);
                return 1;
            }));
        });
    }

    private static MutableComponent result(CommandSourceStack source, AmsBlockLoaderConfig.Result result) {
        return message(source, result.name().toLowerCase(Locale.ROOT));
    }

    private static int show(CommandSourceStack source, Type type) {
        var config = AmsBlockLoaderConfig.get(type);
        tell(source, "show", type.id(), config.enabled, config.time, config.range);
        return groups(source, config);
    }

    private static int groups(CommandSourceStack source, Config config) {
        config.groups.forEach((name, group) -> source.sendSuccess(() -> groupSummary(source, name, group), false));
        return 1;
    }

    private static int showGroup(CommandContext<CommandSourceStack> context, Type type, boolean heading) {
        var source = context.getSource();
        String name = StringArgumentType.getString(context, "group");
        Group group = AmsBlockLoaderConfig.get(type).groups.get(name);
        if (group == null) {
            source.sendFailure(message(source, "missing_group", name));
            return 0;
        }
        if (heading) source.sendSuccess(() -> groupSummary(source, name, group), false);
        for (var condition : group.conditions) {
            source.sendSuccess(() -> message(source, "condition", condition.id, condition.dx, condition.dy, condition.dz)
                    .append(message(source, condition.inverted ? "not" : "is")).append(" ").append(condition.block), false);
        }
        return 1;
    }

    private static MutableComponent groupSummary(CommandSourceStack source, String name, Group group) {
        return message(source, "group", name)
                .append(message(source, group.enabled ? "group_enabled" : "group_disabled"))
                .append(message(source, "group_mode")).append(message(source, group.any ? "any" : "all"))
                .append(message(source, "group_details", group.conditions.size()));
    }

    private static int help(CommandSourceStack source) {
        tell(source, "help");
        source.sendSuccess(() -> Component.literal(HELP_URL).withStyle(style -> style
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(HELP_URL))).withUnderlined(true)), false);
        return 1;
    }

    private static int invalid(CommandSourceStack source, String detail) {
        source.sendFailure(message(source, "invalid", detail));
        return 0;
    }

    private static void tell(CommandSourceStack source, String key, Object... arguments) {
        source.sendSuccess(() -> message(source, key, arguments), false);
    }

    private static MutableComponent message(CommandSourceStack source, String key, Object... arguments) {
        return AmsTranslations.message(source, PREFIX + key, arguments);
    }
}
