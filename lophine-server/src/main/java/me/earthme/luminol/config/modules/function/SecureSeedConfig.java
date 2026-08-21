package me.earthme.luminol.config.modules.function;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

import java.security.SecureRandom;
import java.util.Base64;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "secure_seed")
public class SecureSeedConfig {
    @ConfigInfo(name = "enabled")
    @HotReloadUnsupported
    public static boolean enabled = false;

    @ConfigInfo(name = "version")
    @HotReloadUnsupported
    public static int version = 1;

    @ConfigInfo(name = "salt")
    @HotReloadUnsupported
    public static String salt = generateSalt();

    private static String generateSalt() {
        byte[] saltBytes = new byte[32];
        new SecureRandom().nextBytes(saltBytes);
        return Base64.getEncoder().encodeToString(saltBytes);
    }
}
