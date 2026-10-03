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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Upstream AMS translations remain server resources, so vanilla clients receive real text. */
public final class AmsTranslations {
    private static final String PREFIX = "carpetamsaddition.";
    private static final Map<String, Map<String, String>> TRANSLATIONS = load();
    private AmsTranslations() {}

    private static Map<String, Map<String, String>> load() {
        Map<String, Map<String, String>> languages = new HashMap<>();
        for (String language : new String[] {"en_us", "zh_cn"}) {
            String path = "/assets/carpetamsaddition/lang/" + language + ".yml";
            try (var input = AmsTranslations.class.getResourceAsStream(path)) {
                if (input == null) throw new IllegalStateException("Missing AMS translation resource " + path);
                var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
                Map<String, String> values = new HashMap<>();
                flatten(yaml, "", values);
                languages.put(language, Map.copyOf(values));
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        return Map.copyOf(languages);
    }

    private static void flatten(ConfigurationSection section, String prefix, Map<String, String> output) {
        section.getValues(false).forEach((key, value) -> {
            String full = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof ConfigurationSection nested) flatten(nested, full, output);
            else output.put(full, value == null ? "" : value.toString());
        });
    }

    public static String serverLanguage() {
        String language = GeneralCompatConfig.language;
        return language.equalsIgnoreCase("none") ? "en_us" : language.toLowerCase(Locale.ROOT);
    }

    public static MutableComponent message(CommandSourceStack source, String suffix, Object... args) {
        String key = suffix.startsWith(PREFIX) ? suffix : PREFIX + suffix;
        String fallback = TRANSLATIONS.getOrDefault("en_us", Map.of()).getOrDefault(key, key);
        MutableComponent message = Component.translatableWithFallback(key, fallback, args);
        return source.getEntity() instanceof ServerPlayer ? message : translateText(message, serverLanguage());
    }

    public static Component translate(Component message, ServerPlayer player) {
        String language = GeneralCompatConfig.amsTranslationMode.equalsIgnoreCase("SERVER")
            ? serverLanguage() : player.language == null ? "en_us" : player.language.toLowerCase(Locale.ROOT);
        return translateText(message, language);
    }

    public static MutableComponent translateText(Component original, String language) {
        MutableComponent result;
        if (original.getContents() instanceof TranslatableContents contents && contents.getKey().startsWith(PREFIX)) {
            String pattern = TRANSLATIONS.getOrDefault(language, Map.of()).get(contents.getKey());
            if (pattern == null) pattern = TRANSLATIONS.getOrDefault("en_us", Map.of()).get(contents.getKey());
            if (pattern == null) result = original.plainCopy();
            else {
                Object[] args = contents.getArgs().clone();
                for (int i = 0; i < args.length; ++i) if (args[i] instanceof Component component) args[i] = translateText(component, language);
                try { result = args.length == 0 ? Component.literal(pattern) : formatPattern(pattern, args); }
                catch (java.util.IllegalFormatException mismatch) { result = original.plainCopy(); }
            }
        } else if (original.getContents() instanceof TranslatableContents contents) {
            Object[] args = contents.getArgs().clone();
            for (int i = 0; i < args.length; ++i) if (args[i] instanceof Component component) args[i] = translateText(component, language);
            result = Component.translatableWithFallback(contents.getKey(), contents.getFallback(), args);
        } else result = original.plainCopy();
        result.setStyle(original.getStyle());
        for (Component sibling : original.getSiblings()) result.append(translateText(sibling, language));
        return result;
    }

    /** Component arguments keep their own style, hover event and click event. */
    public static MutableComponent formatPattern(String pattern, Object... args) {
        MutableComponent result = Component.empty();
        java.util.regex.Matcher tokens = java.util.regex.Pattern.compile("%((?:[0-9]+\\$)?[-#+ 0,(<]*[0-9]*(?:\\.[0-9]+)?)([a-zA-Z%])").matcher(pattern);
        int cursor = 0, nextArgument = 0, previousArgument = -1;
        while (tokens.find()) {
            if (tokens.start() > cursor) result.append(Component.literal(pattern.substring(cursor, tokens.start())));
            String modifiers = tokens.group(1), conversion = tokens.group(2);
            if (conversion.equals("%")) result.append(Component.literal("%"));
            else if (conversion.equals("n")) result.append(Component.literal(System.lineSeparator()));
            else {
                int dollar = modifiers.indexOf('$');
                int argument = dollar >= 0 ? Integer.parseInt(modifiers.substring(0, dollar)) - 1
                    : modifiers.indexOf('<') >= 0 ? previousArgument : nextArgument++;
                if (argument < 0 || argument >= args.length) throw new java.util.MissingFormatArgumentException(tokens.group());
                previousArgument = argument;
                Object value = args[argument];
                String singleModifiers = (dollar >= 0 ? modifiers.substring(dollar + 1) : modifiers).replace("<", "");
                if (conversion.equals("s") && value instanceof Component component) result.append(component.copy());
                else result.append(Component.literal(String.format(Locale.ROOT, "%" + singleModifiers + conversion, value)));
            }
            cursor = tokens.end();
        }
        if (cursor < pattern.length()) result.append(Component.literal(pattern.substring(cursor)));
        return result;
    }
}
