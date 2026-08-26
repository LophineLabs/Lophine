package me.earthme.luminol.utils.dialog;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import me.earthme.luminol.api.config.EnumConfigData;
import me.earthme.luminol.config.ConfigsInstance;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.commands.functions.StringTemplate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.world.entity.player.Player;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Type;
import java.util.*;

public class ConfigCommandDialog {
    private static final Set<EnumConfigData> allNeedFeatures = Set.of(EnumConfigData.VALUE, EnumConfigData.COMMENT, EnumConfigData.SUGGESTIONS);

    public static void openGui(Player player, String name, ConfigsInstance config) {
        openGui(player, name, config, "");
    }

    public static void openGui(Player player, String name, ConfigsInstance config, String[] args) {
        openGui(player, name, config, args.length == 1 ? "" : args[1]);
    }

    public static void openGui(Player player, String name, ConfigsInstance config, String prefix) {
        if (prefix.equals("full")) {
            Collection<String> allKeys = config.getAllConfigPaths("");
            player.openDialog(
                    ConfigDialogUtil.createHolder(
                            name,
                            config.getData(config.getAllConfigPaths(""), allNeedFeatures),
                            name + " submit "
                    ));
            return;
        }

        // Get all possible paths at current level
        List<String> keyList = config.completeConfigPath(prefix.isEmpty() ? prefix : prefix + ".");
        List<String> keySingleConfigs = config.getSingleConfig(prefix);
        keyList.removeAll(keySingleConfigs);
        DialogUtil.DialogBuilder builder = new DialogUtil.DialogBuilder();

        // Add navigation buttons for each sub-path
        for (String key : keyList) {
            // Check if this key has children or is a valid config node
            List<String> childPaths = config.completeConfigPath(key + ".");
            List<String> childKeySingleConfigs = config.getSingleConfig(key);

            // Always create button if there are child paths or if it's a valid config node
            if (!childPaths.isEmpty() || !childKeySingleConfigs.isEmpty()) {
                String raw = name + " open-gui " + key + "$(missing)";
                StringTemplate template = StringTemplate.fromString(raw);
                CommandTemplate commandTemplate = new CommandTemplate(new ParsedTemplate(raw, template));
                builder.addButton(
                        DialogUtil.createButton(
                                Component.translatable(key),
                                300,
                                Optional.of(commandTemplate)
                        ));
            }
        }

        ConfigDialogUtil.addInputs(
                config.getData(keySingleConfigs, allNeedFeatures),
                name + " submit ",
                builder
        );

        // Add "Show all configs" button at root level
        if (prefix.isEmpty()) {
            String raw = name + " open-gui full$(missing)";
            StringTemplate template = StringTemplate.fromString(raw);
            CommandTemplate commandTemplate = new CommandTemplate(new ParsedTemplate(raw, template));
            builder.addButton(
                    DialogUtil.createButton(
                            Component.translatable("Show all configs"),
                            300,
                            Optional.of(commandTemplate)
                    ));
        }

        if (builder.getInputCount() == 0) {
            builder.addButton(
                    DialogUtil.createButton(
                            Component.translatable("Close"),
                            300,
                            Optional.empty()
                    ));
        }

        builder.setTitle(name)
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
            config.setConfig(entry.getKey(), entry.getValue());
        }
        config.reloadAsync(true).thenAccept(nullValue -> sender.sendMessage(
                net.kyori.adventure.text.Component
                        .text("Apply config update successfully!")
                        .color(TextColor.color(0, 255, 0))
        ));
    }
}
