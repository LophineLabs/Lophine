package fun.bm.lophine.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.classgraph.ClassGraph;
import me.earthme.luminol.config.ConfigPaths;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumLoadType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigLanguageCoverageTest {
    private static final Map<String, String> PACKAGES = Map.of(
            "luminol", "me.earthme.luminol.config.modules",
            "lophine", "fun.bm.lophine.config.modules",
            "lophine_carpet", "fun.bm.lophine.carpet.config.modules"
    );

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn", "zh_tw"})
    void everyRegisteredSettingAndSectionHasALocalizedNameAndDescription(String language) throws Exception {
        JsonObject translations = load(language);
        Set<String> required = new TreeSet<>();
        int fields = 0;
        for (var pack : PACKAGES.entrySet()) {
            try (var scan = new ClassGraph().enableClassInfo().enableAnnotationInfo()
                    .ignoreClassVisibility().acceptPackages(pack.getValue()).scan()) {
                for (var metadata : scan.getClassesWithAnnotation(ConfigClassInfo.class.getName())) {
                    // Inspect declarations without initializing configuration modules or their runtime callbacks.
                    Class<?> module = Class.forName(metadata.getName(), false, getClass().getClassLoader());
                    ConfigClassInfo info = module.getAnnotation(ConfigClassInfo.class);
                    if (info.category() == EnumConfigCategory.REMOVED) continue;
                    List<String> modulePath = new ArrayList<>();
                    if (info.category().getBaseKeyName() != null) modulePath.add(info.category().getBaseKeyName());
                    modulePath.addAll(List.of(info.directory()));
                    modulePath.add(info.name());
                    addSectionNames(required, pack.getKey() + "." + String.join(".", modulePath));
                    for (var field : module.getDeclaredFields()) {
                        ConfigInfo entry = field.getAnnotation(ConfigInfo.class);
                        DoNotLoad loading = field.getAnnotation(DoNotLoad.class);
                        if (entry == null || !Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                                || loading != null && loading.when() == EnumLoadType.ALWAYS) continue;
                        String key = pack.getKey() + "." + ConfigPaths.resolve(info, entry);
                        required.add(key);
                        required.add(key + ".comment");
                        addSectionNames(required, key.substring(0, key.lastIndexOf('.')));
                        fields++;
                    }
                }
            }
        }
        List<String> missing = required.stream().filter(key -> !translations.has(key)
                || !translations.get(key).isJsonPrimitive()
                || !translations.get(key).getAsJsonPrimitive().isString()
                || translations.get(key).getAsString().isBlank()).toList();
        assertTrue(fields > 600, "Configuration discovery must cover all three registered packages");
        assertEquals(List.of(), missing, language + " is missing names or descriptions");
        System.out.println(language + ": " + fields + " settings and " + required.size() + " required localization keys verified");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh_cn", "zh_tw"})
    void localizedResourcesHaveAllEnglishKeysAndPreserveFormattingTokens(String language) throws Exception {
        JsonObject english = load("en_us");
        JsonObject localized = load(language);
        assertEquals(english.keySet(), localized.keySet(), language + " must not rely on missing-key fallback");
        var placeholders = java.util.regex.Pattern.compile("\\{\\d+\\}|%(?:\\d+\\$)?[sdif]|</?(?:text|used|available|util|chunks|players|entities|tps|mspt|ping|chunkhot)>");
        for (String key : english.keySet()) {
            assertFalse(localized.get(key).getAsString().isBlank(), language + ": " + key);
            var originalTokens = placeholders.matcher(english.get(key).getAsString()).results().map(java.util.regex.MatchResult::group).sorted().toList();
            var translatedTokens = placeholders.matcher(localized.get(key).getAsString()).results().map(java.util.regex.MatchResult::group).sorted().toList();
            assertEquals(originalTokens, translatedTokens, language + ": " + key + " must preserve formatting tokens");
        }
    }

    private static void addSectionNames(Set<String> required, String section) {
        while (section.indexOf('.') >= 0) {
            required.add(section);
            required.add(section + ".comment");
            section = section.substring(0, section.lastIndexOf('.'));
        }
    }

    private static JsonObject load(String language) throws Exception {
        try (var stream = ConfigLanguageCoverageTest.class.getResourceAsStream("/assets/lophine/lang/" + language + ".json")) {
            assertNotNull(stream, "Missing locale " + language);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
