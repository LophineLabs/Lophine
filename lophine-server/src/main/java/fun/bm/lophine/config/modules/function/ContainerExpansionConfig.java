package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.config.flags.CommandSuggestions;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "container_expansion")
public class ContainerExpansionConfig {
    @HotReloadUnsupported
    @CommandSuggestions(suggest = {"1", "2", "3", "4", "5", "6"})
    @ConfigInfo(name = "barrel_rows")
    public static int barrelRows = 3;

    @HotReloadUnsupported
    @CommandSuggestions(suggest = {"1", "2", "3", "4", "5", "6"})
    @ConfigInfo(name = "enderchest_rows")
    public static int enderchestRows = 3;

    @CommandSuggestions(suggest = {"1", "2", "32", "64"})
    @ConfigInfo(name = "shulker_stackable_count", directory = {"shulker_box"})
    public static int shulkerCount = 1;

    @ConfigInfo(name = "same_nbt_shulker_stackable", directory = {"shulker_box"})
    public static boolean nbtShulkerStackable = false;
}