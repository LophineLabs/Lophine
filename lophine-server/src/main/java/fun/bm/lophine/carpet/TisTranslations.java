// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Original TIS server translation semantics, including nested arguments, hover and HUD text. */
public final class TisTranslations {
    private static final String PREFIX = "carpettisaddition.";
    private static final Map<String, Map<String, String>> TRANSLATIONS = load();
    private TisTranslations() {}

    private static Map<String, Map<String, String>> load() {
        Map<String, Map<String, String>> languages = new HashMap<>();
        for (String language : new String[] {"en_us", "zh_cn"}) {
            String path = "/carpet/upstream/tis/" + language + ".yml";
            try (var input = TisTranslations.class.getResourceAsStream(path)) {
                if (input == null) throw new IllegalStateException("Missing TIS translation resource " + path);
                var yaml = new YamlConfiguration();
                // Upstream uses "." as a leaf meaning its parent key, and also embeds dots in keys.
                yaml.options().pathSeparator('\u0000');
                yaml.load(new InputStreamReader(input, StandardCharsets.UTF_8));
                Map<String, String> values = new HashMap<>();
                flatten(yaml, "", values);
                languages.put(language, Map.copyOf(values));
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            catch (org.bukkit.configuration.InvalidConfigurationException failure) { throw new IllegalStateException(failure); }
        }
        return Map.copyOf(languages);
    }

    private static void flatten(ConfigurationSection section, String prefix, Map<String, String> output) {
        section.getValues(false).forEach((key, value) -> {
            String full = key.equals(".") ? prefix : prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof ConfigurationSection nested) flatten(nested, full, output);
            else output.put(full, value == null ? "" : value.toString());
        });
    }

    static String targetLanguage(String clientLanguage) {
        return "translation".equals(GeneralCompatConfig.ultraSecretSetting) ? serverLanguage()
            : clientLanguage == null ? "en_us" : clientLanguage.toLowerCase(Locale.ROOT);
    }

    public static String serverLanguage() {
        String language = GeneralCompatConfig.language;
        return language.equalsIgnoreCase("none") ? "en_us" : language.toLowerCase(Locale.ROOT);
    }

    public static MutableComponent text(String suffix, Object... args) {
        String key = suffix.startsWith(PREFIX) ? suffix : PREFIX + suffix;
        return Component.translatableWithFallback(key, TRANSLATIONS.get("en_us").getOrDefault(key, key), args);
    }

    public static Component message(CommandSourceStack source, String suffix, Object... args) {
        Component message = text(suffix, args);
        return source.getEntity() instanceof ServerPlayer ? message : translateText(message, serverLanguage());
    }

    public static Component translate(Component message, ServerPlayer player) {
        if (!hasTisText(message)) return message;
        return translateText(message, targetLanguage(player.language));
    }

    private static boolean hasTisText(Component message) {
        if (message.getContents() instanceof TranslatableContents contents) {
            if (contents.getKey().startsWith(PREFIX)) return true;
            for (Object arg : contents.getArgs()) if (arg instanceof Component component && hasTisText(component)) return true;
        }
        if (message.getStyle().getHoverEvent() instanceof HoverEvent.ShowText hover && hasTisText(hover.value())) return true;
        for (Component sibling : message.getSiblings()) if (hasTisText(sibling)) return true;
        return false;
    }

    public static MutableComponent translateText(Component original, String language) {
        language = language.toLowerCase(Locale.ROOT);
        MutableComponent result;
        if (original.getContents() instanceof TranslatableContents contents) {
            Object[] args = contents.getArgs().clone();
            for (int i = 0; i < args.length; i++) if (args[i] instanceof Component component) args[i] = translateText(component, language);
            if (contents.getKey().startsWith(PREFIX)) {
                String pattern = TRANSLATIONS.getOrDefault(language, Map.of()).get(contents.getKey());
                if (pattern == null) pattern = TRANSLATIONS.get("en_us").get(contents.getKey());
                if (pattern == null) result = Component.translatableWithFallback(contents.getKey(), contents.getFallback(), args);
                else {
                    try { result = args.length == 0 ? Component.literal(pattern) : AmsTranslations.formatPattern(pattern, args); }
                    catch (IllegalArgumentException mismatch) { result = Component.literal(pattern); }
                }
            } else result = Component.translatableWithFallback(contents.getKey(), contents.getFallback(), args);
        } else result = original.plainCopy();
        var style = original.getStyle();
        if (style.getHoverEvent() instanceof HoverEvent.ShowText hover) {
            style = style.withHoverEvent(new HoverEvent.ShowText(translateText(hover.value(), language)));
        }
        result.setStyle(style);
        for (Component sibling : original.getSiblings()) result.append(translateText(sibling, language));
        return result;
    }
}
