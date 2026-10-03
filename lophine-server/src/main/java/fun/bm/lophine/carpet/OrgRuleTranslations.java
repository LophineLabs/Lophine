package fun.bm.lophine.carpet;

import com.google.gson.JsonParser;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
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
        for (String language : java.util.List.of("en_us", "fr_fr", "es_ar", "pt_br", "zh_cn", "zh_tw")) {
            Map<String, String> entries = new HashMap<>();
            for (String project : java.util.List.of("carpet", "tis", "org", "ams")) {
                String root = "/carpet/upstream/" + project + "/" + language;
                try (InputStream json = OrgRuleTranslations.class.getResourceAsStream(root + ".json")) {
                    if (json != null) {
                        JsonParser.parseReader(new InputStreamReader(json, StandardCharsets.UTF_8)).getAsJsonObject()
                                .entrySet().forEach(entry -> {
                                    if (entry.getValue().isJsonPrimitive())
                                        entries.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                                });
                    }
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Unable to load bundled Carpet translations", exception);
                }
                try (InputStream yaml = OrgRuleTranslations.class.getResourceAsStream(root + ".yml")) {
                    if (yaml != null) {
                        var configuration = YamlConfiguration.loadConfiguration(new InputStreamReader(yaml, StandardCharsets.UTF_8));
                        var section = configuration.getConfigurationSection(project.equals("tis") ? "carpettisaddition.carpet_translations" : "carpetamsaddition.carpet_translations");
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
            languages.put(language, Map.copyOf(entries));
        }
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
