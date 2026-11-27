// SPDX-License-Identifier: LGPL-3.0-or-later
// Command syntax from Carpet AMS Addition 66b8c3b0.
package fun.bm.lophine.carpet.ams;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fun.bm.lophine.carpet.CarpetCommandPermissions;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.carpet.org.OrgCommandNativeEffects;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.io.IOException;

public final class AmsLocationTagCommands {
    private static final int PAGE_SIZE = 10;
    private static final String PREFIX = "command.amsloc.";

    private AmsLocationTagCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("amsloc")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandLocationTag))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("list").executes(context -> list(context.getSource(), null, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> list(context.getSource(), null, IntegerArgumentType.getInteger(context, "page"))))
                        .then(Commands.literal("dimension").then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(context -> list(context.getSource(), DimensionArgument.getDimension(context, "dimension").dimension().identifier().toString(), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> list(context.getSource(), DimensionArgument.getDimension(context, "dimension").dimension().identifier().toString(),
                                                IntegerArgumentType.getInteger(context, "page")))))))
                .then(Commands.literal("info").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(context -> details(context.getSource(), IntegerArgumentType.getInteger(context, "id")))))
                .then(Commands.literal("config").executes(context -> recognition(context.getSource()))
                        .then(Commands.literal("marker").then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> configure(context.getSource(), StringArgumentType.getString(context, "text"), null))))
                        .then(Commands.literal("sign").then(Commands.argument("type", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        AmsLocationTags.signTypes().stream().map(net.minecraft.resources.Identifier::parse), builder))
                                .executes(context -> configure(context.getSource(), null, StringArgumentType.getString(context, "type")))))
                        .then(Commands.literal("reset").executes(context -> configure(context.getSource(), "amsloc", "minecraft:oak_sign"))))
                .then(Commands.literal("rescan").executes(context -> rescan(context.getSource()))));
    }

    private static int configure(CommandSourceStack source, String marker, String sign) {
        return OrgCommandNativeEffects.command(source, 1, () -> AmsNativeCommandEffects.then(
                OrgCommandNativeEffects.file(source.getServer(), () -> {
                    try {
                        AmsLocationTags.configure(source.getServer(), marker, sign);
                        return null;
                    } catch (IllegalArgumentException failure) {
                        return failure.getMessage();
                    } catch (IOException failure) {
                        com.mojang.logging.LogUtils.getLogger().error("Cannot configure AMS location tags", failure);
                        return "save_failed";
                    }
                }), error -> {
                    if (error != null) return AmsNativeCommandEffects.source(source, () -> failure(source, error));
                    return AmsNativeCommandEffects.then(AmsLocationTags.rescan(source.getServer()),
                            count -> AmsNativeCommandEffects.source(source, () -> {
                                tell(source, "saved");
                                return recognition(source);
                            }));
                }));
    }

    private static int rescan(CommandSourceStack source) {
        return OrgCommandNativeEffects.command(source, 1, () -> AmsNativeCommandEffects.then(AmsLocationTags.rescan(source.getServer()),
                count -> AmsNativeCommandEffects.source(source, () -> {
                    tell(source, "rescan", count);
                    return 1;
                })));
    }

    private static int list(CommandSourceStack source, String dimension, int page) {
        var entries = AmsLocationTags.entries().stream()
                .filter(entry -> dimension == null || dimension.equals(entry.dimension())).toList();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > pages) return failure(source, "page_invalid", pages);
        tell(source, "list", page, pages, entries.size());
        if (entries.isEmpty()) tell(source, "empty");
        int first = (page - 1) * PAGE_SIZE;
        for (int index = first; index < Math.min(entries.size(), first + PAGE_SIZE); index++) {
            var entry = entries.get(index);
            source.sendSuccess(() -> row(source, entry).append(" ").append(button(source, "details",
                    "/amsloc info " + entry.id(), "details_hover", entry.name())), false);
        }
        if (pages > 1) {
            String base = "/amsloc list" + (dimension == null ? " " : " dimension " + dimension + " ");
            var links = Component.empty();
            if (page > 1) links.append(button(source, "previous", base + (page - 1), "previous_hover", page - 1)).append(" ");
            if (page < pages) links.append(button(source, "next", base + (page + 1), "next_hover", page + 1));
            source.sendSuccess(() -> links, false);
        }
        return entries.size();
    }

    private static int details(CommandSourceStack source, int id) {
        var entries = AmsLocationTags.entries();
        if (id > entries.size()) return failure(source, "missing");
        source.sendSuccess(() -> row(source, entries.get(id - 1)), false);
        return 1;
    }

    private static net.minecraft.network.chat.MutableComponent row(CommandSourceStack source, AmsLocationTags.Entry entry) {
        String pos = entry.x() + " " + entry.y() + " " + entry.z();
        var dimension = switch (entry.dimension()) {
            case "minecraft:overworld" -> message(source, "overworld");
            case "minecraft:the_nether" -> message(source, "nether");
            case "minecraft:the_end" -> message(source, "end");
            default -> Component.literal(entry.dimension());
        };
        return Component.literal("#" + entry.id() + " ").append(dimension).append(" " + entry.name() + " ")
                .append(Component.literal("[" + pos + "]").withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(pos))));
    }

    private static Component button(CommandSourceStack source, String key, String command, String hover, Object value) {
        return message(source, key).withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(message(source, hover, value))));
    }

    private static int help(CommandSourceStack source) {
        for (String key : new String[]{"title", "help_list", "help_dimension", "help_info", "help_config", "help_marker",
                "help_type", "help_reset", "help_rescan", "help_sign"}) tell(source, key);
        return recognition(source);
    }

    private static int recognition(CommandSourceStack source) {
        var current = AmsLocationTags.recognition();
        tell(source, "recognition", current.marker(), current.sign());
        return 1;
    }

    private static int failure(CommandSourceStack source, String key, Object... values) {
        source.sendFailure(message(source, key, values));
        return 0;
    }

    private static void tell(CommandSourceStack source, String key, Object... values) {
        source.sendSuccess(() -> message(source, key, values), false);
    }

    private static net.minecraft.network.chat.MutableComponent message(CommandSourceStack source, String key, Object... values) {
        return AmsTranslations.message(source, PREFIX + key, values);
    }
}
