package fun.bm.lophine.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "vanilla-like-experience")
public class VanillaLikeExperienceConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            Restore a more vanilla-like technical gameplay experience by bypassing some Paper safety and behavior changes.""")
    public static boolean enabled = false;
}
