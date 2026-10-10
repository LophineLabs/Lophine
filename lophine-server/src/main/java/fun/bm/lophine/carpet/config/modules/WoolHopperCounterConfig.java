package fun.bm.lophine.carpet.config.modules;

import fun.bm.lophine.command.counter.CounterCommand;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumRunnableType;

@ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "hopper_counter", directory = {"carpet"})
public class WoolHopperCounterConfig {
    @ConfigInfo(name = "hopperCounters")
    public static volatile boolean hopperCounters = false;

    @ConfigInfo(name = "hopperCountersUnlimitedSpeed", section = "tis")
    public static volatile boolean hopperCountersUnlimitedSpeed = false;

    @DoNotLoad
    private static CounterCommand counterCommand = null;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        if (hopperCounters) {
            if (counterCommand == null) {
                counterCommand = new CounterCommand();
            }
            counterCommand.register();
        }
    }

    @NeedRun(when = EnumRunnableType.ON_UNLOAD)
    public void onUnloaded() {
        if (counterCommand != null) {
            counterCommand.unregister();
        }
    }
}
