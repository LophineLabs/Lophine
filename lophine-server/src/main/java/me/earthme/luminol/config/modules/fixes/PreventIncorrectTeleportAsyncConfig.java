package me.earthme.luminol.config.modules.fixes;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "prevent_incorrect_teleport_async_calls_during_move_event")
public class PreventIncorrectTeleportAsyncConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            When enabled, the server would reject some incorrect teleportAsync calls during move events.
            And this will reduce the crashes which caused by plugins(Residence etc.)
            But you should notice that it might break the compatibility with some plugins.""")
    public static boolean enabled = false;

    @ConfigInfo(name = "throw_when_caught")
    public static boolean throwWhenCaught = true;
}
