package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "language")
public class LanguageConfig implements IConfigModule {
    @HotReloadUnsupported
    @ConfigInfo(name = "lang", comments = """
            Please use the key from https://minecraft.wiki/w/Language
            Sample of format: en_us zh_cn zh_hk zh_tw
            ATTENTION: If you want to edit language for carpet system,
            please edit it in carpet config file.""")
    public static String lang = "en_us";

    @ConfigInfo(name = "full_blocking_load", comments = """
            Whether to allow blocking server loading when loading localized language.
            If you want only use your localized language to shown in your terminal,
            you need to enable it.
            
            WARNING: This may slow down the startup speed!""")
    public static boolean full_blocking_load = false;
}