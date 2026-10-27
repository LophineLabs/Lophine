package me.earthme.luminol.utils.dialog;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import fun.bm.lophine.utils.ServerI18nUtil;
import me.earthme.luminol.api.config.enums.EnumConfigData;
import me.earthme.luminol.config.ConfigManager;
import me.earthme.luminol.config.ConfigsInstance;
import me.earthme.luminol.config.WorldConfigHolder;
import me.earthme.luminol.config.WorldConfigInstance;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.commands.functions.StringTemplate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Type;
import java.util.*;

public class ConfigCommandDialog {
    private static final Set<EnumConfigData> allNeedFeatures = Set.of(
            EnumConfigData.VALUE,
            EnumConfigData.COMMENT,
            EnumConfigData.SUGGESTIONS,
            EnumConfigData.LOCALIZED_NAME,
            EnumConfigData.UNIQUE_ID,
            EnumConfigData.CAN_DEFINED_BY_WORLD
    );

    public static void openGui(Player player, String name, ConfigsInstance config) {
        openGui(player, name, config, "");
    }

    public static void openGui(Player player, String name, ConfigsInstance config, String[] args) {
        openGui(player, name, config, args.length == 1 ? "" : args[1]);
    }

    public static void openGui(Player player, String name, ConfigsInstance config, String prefix) {
        open(player, name, config, prefix, null, name + " open-gui ", name + " submit ");
    }

    public static void openWorldGui(Player player, String commandName, ConfigsInstance config, ServerLevel level, String prefix) {
        WorldConfigInstance world = resolveWorldConfig(player, config, level);
        if (world == null) {
            return;
        }
        String worldId = level.dimension().identifier().toString();
        open(
                player,
                commandName + " [" + worldId + "]",
                config,
                prefix,
                world,
                commandName + " dimension " + worldId + " open-gui ",
                commandName + " dimension " + worldId + " submit "
        );
    }

    private static WorldConfigInstance resolveWorldConfig(Player player, ConfigsInstance config, ServerLevel level) {
        if (!config.isWorldConfig()) {
            player.sendSystemMessage(Component.literal(ServerI18nUtil.getLocalizedText("general.command.config.dimension.unsupported")));
            return null;
        }
        WorldConfigHolder holder = level.luminolWorldConfig();
        WorldConfigInstance world = holder == null ? null : holder.get(config.getName());
        if (world == null) {
            holder = ConfigManager.loadWorldConfigs(level);
            level.luminolWorldConfig(holder);
            world = holder.get(config.getName());
        }
        if (world == null) {
            player.sendSystemMessage(Component.literal(ServerI18nUtil.getLocalizedText("general.command.config.dimension.unsupported")));
        }
        return world;
    }

    private static void open(Player player, String title, ConfigsInstance config, String prefix, WorldConfigInstance world, String navPrefix, String submitPrefix) {
        boolean worldGui = world != null;
        if (prefix.equals("full")) {
            player.openDialog(
                    ConfigDialogUtil.createHolder(
                            title,
                            worldGui
                                    ? config.getWorldData(world, config.getAllWorldConfigPaths(""), allNeedFeatures)
                                    : config.getData(config.getAllConfigPaths(""), allNeedFeatures),
                            submitPrefix
                    ));
            return;
        }

        List<String> keyList = worldGui
                ? config.completeWorldConfigPath(prefix.isEmpty() ? prefix : prefix + ".")
                : config.completeConfigPath(prefix.isEmpty() ? prefix : prefix + ".");
        List<String> keySingleConfigs = worldGui ? config.getSingleWorldConfig(prefix) : config.getSingleConfig(prefix);
        keyList.removeAll(keySingleConfigs);
        DialogUtil.DialogBuilder builder = new DialogUtil.DialogBuilder();

        for (String key : keyList) {
            List<String> childPaths = worldGui ? config.completeWorldConfigPath(key + ".") : config.completeConfigPath(key + ".");
            List<String> childKeySingleConfigs = worldGui ? config.getSingleWorldConfig(key) : config.getSingleConfig(key);

            if (!childPaths.isEmpty() || !childKeySingleConfigs.isEmpty()) {
                String vName = ServerI18nUtil.getLocalizedText(config.getName() + "." + key);
                if (vName.isEmpty()) {
                    vName = ServerI18nUtil.getFormatedLocalizedTextOrDefault("general." + key, key);
                }
                String raw = navPrefix + key + "$(missing)";
                StringTemplate template = StringTemplate.fromString(raw);
                CommandTemplate commandTemplate = new CommandTemplate(new ParsedTemplate(raw, template));
                builder.addButton(
                        DialogUtil.createButton(
                                Component.literal(vName),
                                300,
                                Optional.of(commandTemplate)
                        ));
            }
        }

        ConfigDialogUtil.addInputs(
                worldGui
                        ? config.getWorldData(world, keySingleConfigs, allNeedFeatures)
                        : config.getData(keySingleConfigs, allNeedFeatures),
                submitPrefix,
                builder
        );

        if (prefix.isEmpty()) {
            String raw = navPrefix + "full$(missing)";
            StringTemplate template = StringTemplate.fromString(raw);
            CommandTemplate commandTemplate = new CommandTemplate(new ParsedTemplate(raw, template));
            builder.addButton(
                    DialogUtil.createButton(
                            Component.translatable(ServerI18nUtil.getLocalizedText("general.dialog.show_all")),
                            300,
                            Optional.of(commandTemplate)
                    ));
        }

        if (builder.getInputCount() == 0) {
            builder.addButton(
                    DialogUtil.createButton(
                            Component.translatable(ServerI18nUtil.getLocalizedText("general.dialog.close")),
                            300,
                            Optional.empty()
                    ));
        }

        builder.setTitle(title)
                .setPause(false)
                .setColumns(1);
        player.openDialog(
                DialogUtil.transformToHolder(
                        builder.build()
                ));
    }

    public static void processSubmit(CommandSender sender, ConfigsInstance config, String[] args) {
        String fullText = String.join(" ", args);
        Gson gson = new Gson();
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> map = gson.fromJson(fullText, type);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            try {
                int id = Integer.parseInt(entry.getKey());
                config.setConfig(config.getConfigPathById(id), entry.getValue());
            } catch (Exception _) {
            }
        }
        config.reloadAsync(true).thenAccept(_ -> sender.sendMessage(
                net.kyori.adventure.text.Component
                        .text(ServerI18nUtil.getLocalizedText("general.config.apply.success"))
                        .color(TextColor.color(0, 255, 0))
        ));
    }

    public static void processSubmitWorld(CommandSender sender, ConfigsInstance config, ServerLevel level, String json) {
        if (level == null) {
            sender.sendMessage(
                    net.kyori.adventure.text.Component
                            .text(ServerI18nUtil.getLocalizedText("general.command.config.dimension.missing"))
                            .color(TextColor.color(255, 0, 0))
            );
            return;
        }
        WorldConfigHolder holder = level.luminolWorldConfig();
        WorldConfigInstance world = holder == null ? null : holder.get(config.getName());
        if (world == null) {
            sender.sendMessage(
                    net.kyori.adventure.text.Component
                            .text(ServerI18nUtil.getLocalizedText("general.command.config.dimension.unsupported"))
                            .color(TextColor.color(255, 0, 0))
            );
            return;
        }
        Gson gson = new Gson();
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> map = gson.fromJson(json, type);
        Map<String, Object> updates = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            try {
                int id = Integer.parseInt(entry.getKey());
                String path = config.getConfigPathById(id);
                if (path != null && config.isWorldConfig(path)) {
                    updates.put(path, entry.getValue());
                }
            } catch (Exception _) {
            }
        }
        world.applySubmitted(updates);
        sender.sendMessage(
                net.kyori.adventure.text.Component
                        .text(ServerI18nUtil.getLocalizedText("general.config.apply.success"))
                        .color(TextColor.color(0, 255, 0))
        );
    }
}
