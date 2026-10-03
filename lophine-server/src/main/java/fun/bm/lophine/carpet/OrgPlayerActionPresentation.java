// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1 action display names.
package fun.bm.lophine.carpet;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Saved action names use source deserialization semantics without sampling live player state.
 */
public final class OrgPlayerActionPresentation {
    private static final String PREFIX = "carpet-org-addition.command.playerAction.";
    private static final List<String> ORDER = List.of("stop", "categorize", "empty_the_container", "fill_the_container", "crafting_table_craft", "inventory_craft", "rename", "stonecutting", "trade", "fishing", "plant", "bedrock", "goto", "librarian", "enchanting");

    private OrgPlayerActionPresentation() {
    }

    public static Component profileName(JsonObject script) {
        String name = "stop";
        JsonObject data = new JsonObject();
        if (script != null) {
            if (script.has("name")) {
                name = script.get("name").getAsString();
                if (script.has("data")) data = script.getAsJsonObject("data");
            } else for (String candidate : ORDER)
                if (script.has(candidate)) {
                    name = candidate;
                    data = script.getAsJsonObject(candidate);
                    break;
                }
        }
        String key = switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "categorize", "sorting" -> "sorting";
            case "empty_the_container", "empty" -> "clean";
            case "fill_the_container", "fill" -> "fill";
            case "crafting_table_craft", "craft_table" -> "craft.crafting_table";
            case "inventory_craft", "craft_inventory" -> "craft.inventory";
            case "rename", "stonecutting", "trade", "fishing", "librarian", "enchanting" ->
                    name.toLowerCase(java.util.Locale.ROOT);
            case "plant" -> OrgHiddenPlayerActions.enabled() ? "plant" : "stop";
            case "bedrock" ->
                    OrgHiddenPlayerActions.enabled() && (!data.has("region_type") || List.of("cuboid", "cylinder").contains(data.get("region_type").getAsString())) ? "bedrock" : "stop";
            default -> "stop";
        };
        return Component.translatableWithFallback(PREFIX + key, OrgRuleTranslations.text(PREFIX + key, switch (key) {
            case "craft.crafting_table" -> "Crafting at a crafting table";
            case "craft.inventory" -> "Crafting in inventory";
            case "clean" -> "Emptying a container";
            case "fill" -> "Filling a container";
            case "sorting" -> "Sorting items";
            default -> key;
        }));
    }
}
