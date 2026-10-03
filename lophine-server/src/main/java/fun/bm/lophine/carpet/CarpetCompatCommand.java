package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CarpetCompatCommand {
    private CarpetCompatCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final net.minecraft.commands.CommandBuildContext buildContext) {
        CarpetDistanceCommand.register(dispatcher);
        CarpetAMSCommands.register(dispatcher);
        OrgRulePlayerPreferences.register(dispatcher);
        OrgFakePlayerActionCommands.register(dispatcher, buildContext);
        OrgHiddenActionCommands.register(dispatcher);
        OrgPlayerManagerCommands.register(dispatcher, buildContext);
        OrgMailCommands.register(dispatcher);
        OrgFinderCommands.register(dispatcher, buildContext);
        CarpetLogCommand.register(dispatcher);
        TisUtilityCommands.register(dispatcher);
        TisRefreshCommand.register(dispatcher);
        TisRaycastCommand.register(dispatcher);
        CarpetProfileCommand.register(dispatcher);
        CarpetSpawnCommand.register(dispatcher, null);
        CarpetInfoCommand.register(dispatcher, null);
        CarpetDrawCommand.register(dispatcher, buildContext);
        CarpetMobAICommand.register(dispatcher, buildContext);
        CarpetPerimeterInfoCommand.register(dispatcher, buildContext);
        TisRaidCommand.register(dispatcher);
        TisXpCounter.register(dispatcher);
        TisSpeedTestCommand.register(dispatcher);
        TisManipulateCommand.register(dispatcher, buildContext);
        TisLifetimeTracker.register(dispatcher);
        dispatcher.register(Commands.literal("carpet")
                .requires(CarpetCompatCommand::canUse)
                .executes(context -> list(context.getSource(), "modified", null))
                .then(Commands.literal("list").executes(context -> list(context.getSource(), "all", null))
                        .then(Commands.literal("defaults").executes(context -> list(context.getSource(), "defaults", null)))
                        .then(Commands.argument("tag", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(CarpetRuleMetadata.categories(), builder))
                                .executes(context -> list(context.getSource(), "matching", StringArgumentType.getString(context, "tag")))))
                .then(Commands.literal("setDefault").then(ruleArgument()
                        .then(valueArgument().executes(context -> change(
                                context.getSource(), StringArgumentType.getString(context, "rule"), StringArgumentType.getString(context, "value"), true, false)))))
                .then(Commands.literal("removeDefault").then(ruleArgument().executes(context -> change(
                        context.getSource(), StringArgumentType.getString(context, "rule"), null, true, true))))
                .then(ruleArgument()
                        .executes(context -> query(context.getSource(), StringArgumentType.getString(context, "rule")))
                        .then(valueArgument().executes(context -> change(
                                context.getSource(), StringArgumentType.getString(context, "rule"), StringArgumentType.getString(context, "value"), false, false))))
        );
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> ruleArgument() {
        return Commands.argument("rule", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(CarpetRuleRegistry.availableNames(), builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> valueArgument() {
        return Commands.argument("value", StringArgumentType.greedyString()).suggests((context, builder) -> {
            String rule = StringArgumentType.getString(context, "rule");
            return SharedSuggestionProvider.suggest(CarpetRuleMetadata.options(rule), builder);
        });
    }

    private static boolean canUse(final CommandSourceStack source) {
        String level = GeneralCompatConfig.carpetCommandPermissionLevel;
        if ("4".equals(level)) return Commands.LEVEL_OWNERS.check(source.permissions());
        return ("ops".equalsIgnoreCase(level) || "2".equals(level)) && Commands.LEVEL_GAMEMASTERS.check(source.permissions());
    }

    private static String tr(String suffix, String fallback) {
        return OrgRuleTranslations.text("carpet.settings.command." + suffix, fallback);
    }

    private static String stringValue(Object value) {
        return value instanceof List<?> list ? list.isEmpty() ? "none" : String.join(",", list.stream().map(Object::toString).toList()) : String.valueOf(value);
    }

    private static int list(final CommandSourceStack source, String mode, String term) {
        var config = CarpetRuleRegistry.config();
        String title = switch (mode) {
            case "modified" -> String.format(tr("current_settings_header", "Current %s settings"), "Lophine Carpet");
            case "defaults" ->
                    String.format(tr("current_from_file_header", "Current %s Startup Settings from %s"), "Lophine Carpet", "lophine-carpet.toml");
            case "matching" ->
                    String.format(tr("mod_settings_matching", "%s settings matching \"%s\""), "Lophine Carpet", term);
            default -> String.format(tr("all_mod_settings", "All %s settings"), "Lophine Carpet");
        };
        CarpetMessenger.m(source, "wb " + title + ":");
        int count = 0;
        for (String name : CarpetRuleRegistry.availableNames()) {
            var binding = CarpetRuleRegistry.get(name);
            Object original = config.getDefaultConfig(binding.path());
            if (mode.equals("modified") && Objects.equals(binding.value(), original)) continue;
            Object value = mode.equals("defaults") ? config.getFileInstance().get(binding.path()) : binding.value();
            if (mode.equals("defaults") && (value == null || Objects.equals(value, original))) continue;
            var metadata = CarpetRuleMetadata.get(name);
            if (mode.equals("matching") && !metadata.matches(term)) continue;
            CarpetMessenger.m(source, "c " + metadata.displayName(), "!/carpet " + name, "^g " + metadata.displayDescription(),
                    "w : " + stringValue(value));
            count++;
        }
        if (mode.equals("modified")) {
            List<Object> tags = new ArrayList<>();
            tags.add("w " + tr("browse_categories", "Browse categories") + ": ");
            for (String category : CarpetRuleMetadata.categories()) {
                tags.add("c [" + OrgRuleTranslations.text("carpet.category." + category, category) + "]");
                tags.add("!/carpet list " + category);
                tags.add("w  ");
            }
            CarpetMessenger.m(source, tags.toArray());
        }
        return count;
    }

    private static int query(final CommandSourceStack source, final String name) {
        try {
            CarpetRuleRegistry.requireAvailable(name);
            CarpetRuleRegistry.Binding binding = CarpetRuleRegistry.get(name);
            Object value = binding.value();
            Object original = CarpetRuleRegistry.config().getDefaultConfig(binding.path());
            var metadata = CarpetRuleMetadata.get(name);
            CarpetMessenger.m(source, "wb " + metadata.displayName(), "!/carpet " + name, "^g refresh");
            CarpetMessenger.m(source, "w " + metadata.displayDescription());
            for (String line : metadata.displayExtra()) CarpetMessenger.m(source, "w " + line);
            List<Object> tags = new ArrayList<>();
            tags.add("w " + tr("tags", "Tags") + ": ");
            for (String category : metadata.categories()) {
                tags.add("c [" + OrgRuleTranslations.text("carpet.category." + category, category) + "]");
                tags.add("!/carpet list " + category);
                tags.add("w  ");
            }
            CarpetMessenger.m(source, tags.toArray());
            CarpetMessenger.m(source, "w " + tr("current_value", "current value") + ": ", "wb " + stringValue(value),
                    "g  (" + (Objects.equals(value, original) ? "default" : "modified") + ")");
            List<Object> options = new ArrayList<>();
            options.add("w Options: ");
            for (String option : CarpetRuleMetadata.options(name)) {
                options.add("c [" + option + "]");
                options.add("!/carpet " + name + " " + option);
                options.add("w  ");
            }
            CarpetMessenger.m(source, options.toArray());
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int change(final CommandSourceStack source, final String name, final String text, final boolean persist, final boolean reset) {
        if (name.equals("carpetCommandPermissionLevel") && !Commands.LEVEL_OWNERS.check(source.permissions())) {
            source.sendFailure(Component.literal("Changing carpetCommandPermissionLevel requires owner permission."));
            return 0;
        }
        return CarpetRuleChanges.change(source, name, text, persist, reset);
    }
}
