package fun.bm.lophine.carpet.org;

import com.google.gson.JsonParser;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.utils.FileResolver;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pinned upstream translations used by server-side rule discovery and feedback.
 */
public final class OrgRuleTranslations {
    private static final Map<String, Map<String, String>> TRANSLATIONS = loadAll();

    private OrgRuleTranslations() {
    }

    private static Map<String, Map<String, String>> loadAll() {
        Map<String, Map<String, String>> languages = new HashMap<>();
        for (var resource : FileResolver.listAll("/assets/carpet", FileResolver.LANGUAGE_NAME, ".json", ".yml")) {
            Map<String, String> entries = languages.computeIfAbsent(resource.name(), ignored -> new HashMap<>());
            try (InputStream input = FileResolver.open(resource.path())) {
                if (resource.path().endsWith(".json")) {
                    var parsed = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                    if (!parsed.isJsonObject()) continue;
                    parsed.getAsJsonObject().entrySet().forEach(entry -> {
                        if (entry.getValue().isJsonPrimitive())
                            entries.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                    });
                } else {
                    var configuration = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
                    var section = configuration.getConfigurationSection("carpettisaddition.carpet_translations");
                    if (section == null) {
                        section = configuration.getConfigurationSection("carpetamsaddition.carpet_translations");
                    }
                    if (section != null) {
                        for (String key : section.getKeys(true)) {
                            if (section.isString(key)) entries.putIfAbsent("carpet." + key, section.getString(key));
                        }
                    }
                }
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Unable to load bundled Carpet translations", exception);
            }
        }
        languages.replaceAll((language, entries) -> Map.copyOf(entries));
        return Map.copyOf(languages);
    }

    public static String text(String key, String fallback) {
        String language = GeneralCompatConfig.language.toLowerCase(java.util.Locale.ROOT);
        return TRANSLATIONS.getOrDefault(language, Map.of()).getOrDefault(key,
                TRANSLATIONS.get("en_us").getOrDefault(key, fallback));
    }

    public static String name(String rule) {
        return text("carpet.rule." + rule + ".name", rule);
    }

    public static String description(String rule) {
        return text("carpet.rule." + rule + ".desc", "");
    }
}
