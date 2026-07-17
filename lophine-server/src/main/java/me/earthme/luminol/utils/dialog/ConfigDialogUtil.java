package me.earthme.luminol.utils.dialog;

import com.mojang.datafixers.util.Pair;
import me.earthme.luminol.api.config.ConfigDataPair;
import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ConfigDialogUtil {
    public static Holder<Dialog> createHolder(String title, Set<ConfigDataPair> configs, String commandPrefix) {
        return DialogUtil.createHolder(title, generateConfigMap(configs), commandPrefix);
    }

    public static DialogUtil.DialogBuilder addInputs(Set<ConfigDataPair> configs, String commandPrefix, @NotNull DialogUtil.DialogBuilder builder) {
        return DialogUtil.addInputs(generateConfigMap(configs), commandPrefix, builder);
    }

    private static @NonNull Map<String, Pair<Object, String>> generateConfigMap(Set<ConfigDataPair> configs) {
        Map<String, Pair<Object, String>> map = new TreeMap<>();
        for (ConfigDataPair config : configs) {
            String key = config.key();
            Object value = config.value();
            String[] suggestions = config.suggestions();
            String comment = config.comment();
            String addition1 = "";
            if (comment != null && !comment.isEmpty()) {
                addition1 = "Comments: " + comment;
            }

            String addition2 = "";

            if (suggestions != null && suggestions.length > 0) {
                StringBuilder addition = new StringBuilder("Suggestions: ");
                boolean first = true;
                for (String suggestion : suggestions) {
                    if (!first) {
                        addition.append(", ");
                    } else {
                        first = false;
                    }
                    addition.append(suggestion);
                }
                addition2 = addition.toString();
            }
            String addition = addition1.isEmpty() ? addition2 : addition2.isEmpty() ? addition1 : addition1 + "\n" + addition2;
            map.put(key, Pair.of(value, addition));
        }
        return map;
    }
}
