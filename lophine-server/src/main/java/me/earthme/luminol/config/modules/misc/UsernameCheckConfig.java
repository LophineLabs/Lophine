package me.earthme.luminol.config.modules.misc;

import com.mojang.logging.LogUtils;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;
import org.slf4j.Logger;

import java.util.regex.Pattern;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "username_checks")
public class UsernameCheckConfig {
    @DoNotLoad
    private static final Logger LOGGER = LogUtils.getLogger();

    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;

    @ConfigInfo(name = "enforce_skull_validation")
    public static boolean enforceSkullValidation = true;

    @ConfigInfo(name = "allow_old_player_join")
    public static boolean allowOldPlayersJoin = false;

    @DoNotLoad
    private static final String defaultUsernameCheckRegex = "^[a-zA-Z0-9_.]*$";
    @ConfigInfo(name = "username_check_regex")
    public static final String usernameCheckRegex = defaultUsernameCheckRegex;

    @DoNotLoad
    public static Pattern usernameRegex;

    public static boolean useCustomUsernameRegex() {
        return !usernameCheckRegex.equals(defaultUsernameCheckRegex);
    }

    public static boolean shouldSkipNonPlayerNameCheck() { // helper
        return !enabled || !usernameCheckRegex.equals(defaultUsernameCheckRegex);
    }

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        try {
            usernameRegex = Pattern.compile(usernameCheckRegex);
        } catch (Exception ex) {
            LOGGER.error("Failed to parse regex! Falling back to default", ex);

            usernameRegex = Pattern.compile(defaultUsernameCheckRegex);
        }
    }
}