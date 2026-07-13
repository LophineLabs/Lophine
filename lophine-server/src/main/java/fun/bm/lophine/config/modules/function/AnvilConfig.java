package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "anvil")
public class AnvilConfig implements IConfigModule {
    @ConfigInfo(name = "allow_inapplicable_enchants", comments = """
            Allows applying enchantments on tools or armour that are normally not applicable.
            For example, sharpness on a pickaxe.""")
    public static boolean allow_inapplicable_enchants = false;
    @ConfigInfo(name = "allow_incompatible_enchants",comments = """
            Allows applying enchantments together that are normally incompatible.
            For example, protection and fire protection or fortune and silk touch.""")
    public static boolean allow_incompatible_enchants = false;
    @ConfigInfo(name = "allow_higher_enchants_levels",comments = """
            Allows the ability to increase enchantments passed their maximum level.
            For example, efficiency V + efficiency V = efficiency VI.""")
    public static boolean allow_higher_enchants_levels = false;
    @ConfigInfo(name="replace_incompatible_enchants",comments = """
             When applying enchantments together that are incompatible,
             instead of using the enchantment in the base item,
             the enchantment will be replaced by the enchantment on the secondary item.""")
    public static boolean replace_incompatible_enchants = false;
    /*    anvil:
      allow-inapplicable-enchants: false
      allow-incompatible-enchants: false
      allow-higher-enchants-levels: false
      replace-incompatible-enchants: false
     */
}
