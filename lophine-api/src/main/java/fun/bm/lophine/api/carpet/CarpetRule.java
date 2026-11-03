package fun.bm.lophine.api.carpet;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An immutable point-in-time rule view. Values are command-form strings; list
 * values use comma separators. The default is the built-in default, rather than
 * an override saved in the configuration file. Descriptions come from pinned metadata.
 *
 * @param name            rule name
 * @param path            full configuration path
 * @param origin          source project
 * @param type            declared value type
 * @param value           live value
 * @param defaultValue    built-in default, or null if unavailable
 * @param description     source description
 * @param categories      rule categories
 * @param suggestions     suggested inputs
 * @param available       whether this rule is currently accessible
 * @param restartRequired whether changes require saving and restarting
 */
public record CarpetRule(String name, String path, String origin, String type, String value,
                         @Nullable String defaultValue, String description, List<String> categories,
                         List<String> suggestions, boolean available, boolean restartRequired) {
    /**
     * Creates a snapshot with copied categories and suggestions.
     *
     * @param name            rule name
     * @param path            configuration path
     * @param origin          source project
     * @param type            declared type
     * @param value           live value
     * @param defaultValue    built-in default
     * @param description     source description
     * @param categories      categories to copy
     * @param suggestions     suggestions to copy
     * @param available       whether accessible
     * @param restartRequired whether restart is required
     */
    public CarpetRule {
        Objects.requireNonNull(name);
        Objects.requireNonNull(path);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(type);
        Objects.requireNonNull(value);
        Objects.requireNonNull(description);
        categories = List.copyOf(categories);
        suggestions = List.copyOf(suggestions);
    }
}
