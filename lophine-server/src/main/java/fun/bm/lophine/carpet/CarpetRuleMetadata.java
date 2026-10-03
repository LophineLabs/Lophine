// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import com.google.gson.Gson;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Metadata extracted from the pinned upstream declarations, rather than inferred from field names.
 */
public final class CarpetRuleMetadata {
    private static final Set<String> HIDDEN_RULES = Set.of(
            "applyToolEffectsImmediately", "forceRestock", "autoSyncPlayerStatus", "totemOfUndyingInvincibleTime",
            "experienceOrbMerge", "quickShulker", "disableFurnaceDropExperience", "itemPickupRangeExpand",
            "itemPickupRangeExpandPlayerControl", "truePeacefulMode", "fakePlayerAutoRestock", "noToolBreak"
    );

    public record Rule(String name, String project, String type, String description,
                       List<String> extra, List<String> categories, List<String> options,
                       boolean strict, String source) {
        public List<String> categories() {
            if (!isHidden(name) || categories.contains("hidden")) return categories;
            List<String> result = new ArrayList<>(categories);
            result.add("hidden");
            return List.copyOf(result);
        }

        public String displayName() {
            return OrgRuleTranslations.name(name);
        }

        public String displayDescription() {
            return OrgRuleTranslations.text("carpet.rule." + name + ".desc", description);
        }

        public List<String> displayExtra() {
            List<String> result = new ArrayList<>();
            for (int i = 0; ; i++) {
                String translated = OrgRuleTranslations.text("carpet.rule." + name + ".extra." + i, null);
                if (translated == null && i >= extra.size()) break;
                result.add(translated == null ? extra.get(i) : translated);
            }
            return List.copyOf(result);
        }

        public boolean matches(String term) {
            String lower = term.toLowerCase(Locale.ROOT);
            return name.toLowerCase(Locale.ROOT).contains(lower) || categories.contains(term)
                    || Arrays.asList(displayDescription().toLowerCase(Locale.ROOT).split("\\W+")).contains(lower);
        }
    }

    private static final Map<String, Rule> RULES = load();

    private CarpetRuleMetadata() {
    }

    public static boolean isHidden(String name) {
        return HIDDEN_RULES.contains(name);
    }

    private static Map<String, Rule> load() {
        try (var stream = CarpetRuleMetadata.class.getResourceAsStream("/carpet/upstream/rules.json")) {
            if (stream == null) throw new IllegalStateException("Missing pinned Carpet rule metadata");
            Rule[] rules = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), Rule[].class);
            Map<String, Rule> result = new TreeMap<>();
            for (Rule rule : rules) result.putIfAbsent(rule.name, rule);
            return Collections.unmodifiableMap(result);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    public static Rule get(String name) {
        return RULES.getOrDefault(name, new Rule(name, "Lophine", "", "", List.of(), List.of(), List.of(), false, ""));
    }

    public static SortedSet<String> categories() {
        var result = new TreeSet<String>();
        for (String name : CarpetRuleRegistry.availableNames()) result.addAll(get(name).categories());
        return Collections.unmodifiableSortedSet(result);
    }

    public static List<String> options(String name) {
        List<String> options = get(name).options();
        if (!options.isEmpty()) return options;
        return CarpetRuleRegistry.get(name).field().getType() == boolean.class ? List.of("true", "false") : List.of();
    }
}
