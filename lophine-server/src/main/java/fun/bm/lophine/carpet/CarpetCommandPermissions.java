package fun.bm.lophine.carpet;

import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class CarpetCommandPermissions {
    private CarpetCommandPermissions() {
    }

    public static boolean canUse(CommandSourceStack source, Object value) {
        if (value instanceof Boolean enabled) return enabled;
        return switch (value.toString().toLowerCase(Locale.ROOT)) {
            case "true", "0" -> true;
            case "1" -> Commands.LEVEL_MODERATORS.check(source.permissions());
            case "ops", "2" -> Commands.LEVEL_GAMEMASTERS.check(source.permissions());
            case "3" -> Commands.LEVEL_ADMINS.check(source.permissions());
            case "4" -> Commands.LEVEL_OWNERS.check(source.permissions());
            default -> false;
        };
    }
}
