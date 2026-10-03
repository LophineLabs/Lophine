/* SPDX-License-Identifier: LGPL-3.0-or-later
 * Adapted from Carpet TIS Addition 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
 */
package fun.bm.lophine.carpet;

import com.mojang.brigadier.suggestion.Suggestions;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;

public final class TisTickRules {
    private TisTickRules() {
    }

    public static boolean canUse(final CommandSourceStack source) {
        Object configured = GeneralCompatConfig.tickCommandPermission;
        String permission = configured.toString();
        if (GeneralCompatConfig.tickCommandCarpetfied && permission.equals("3")) permission = "2";
        return CarpetCommandPermissions.canUse(source, permission);
    }

    public static boolean enhance() { return GeneralCompatConfig.tickCommandCarpetfied || GeneralCompatConfig.tickCommandEnhance; }
    public static boolean toggleFreeze() { return GeneralCompatConfig.tickCommandCarpetfied || GeneralCompatConfig.tickFreezeCommandToggleable; }
    public static boolean deepFreeze() { return GeneralCompatConfig.tickCommandCarpetfied || GeneralCompatConfig.tickFreezeDeepCommand; }
    public static boolean profiler() { return GeneralCompatConfig.tickCommandCarpetfied || GeneralCompatConfig.tickProfilerCommandsReintroduced; }
    public static boolean warp() { return GeneralCompatConfig.tickCommandCarpetfied || GeneralCompatConfig.tickWarpCommandAsAnAlias; }

    public static Suggestions filterSuggestions(final String text, final Suggestions suggestions) {
        String command = text.startsWith("/") ? text.substring(1) : text;
        if ((command.startsWith("tick sprint ") || warp() && command.startsWith("tick warp ")) && !enhance()
            && command.chars().filter(character -> character == ' ').count() == 2L) {
            List<String> unavailable = List.of("status", "health", "entities");
            return new Suggestions(suggestions.getRange(), suggestions.getList().stream().filter(suggestion -> !unavailable.contains(suggestion.getText())).toList());
        }
        return suggestions;
    }
}
