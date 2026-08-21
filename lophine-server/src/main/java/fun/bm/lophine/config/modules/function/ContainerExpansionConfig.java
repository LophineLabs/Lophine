package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.config.flags.CommandSuggestions;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumLoadType;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "container_expansion")
public class ContainerExpansionConfig {
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @CommandSuggestions(suggest = {"1", "2", "3", "4", "5", "6"})
    @ConfigInfo(name = "barrel_rows")
    public static int barrelRows = 3;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @CommandSuggestions(suggest = {"1", "2", "3", "4", "5", "6"})
    @ConfigInfo(name = "enderchest_rows")
    public static int enderchestRows = 3;

    @CommandSuggestions(suggest = {"1", "2", "32", "64"})
    @ConfigInfo(name = "shulker_stackable_count", directory = {"shulker_box"})
    public static int shulkerCount = 1;

    @ConfigInfo(name = "same_nbt_shulker_stackable", directory = {"shulker_box"})
    public static boolean nbtShulkerStackable = false;
}