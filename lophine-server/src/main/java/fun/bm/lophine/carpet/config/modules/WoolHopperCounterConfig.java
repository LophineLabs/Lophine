package fun.bm.lophine.carpet.config.modules;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fun.bm.lophine.command.counter.CounterCommand;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "hopper_counter", directory = {"carpet"})
public class WoolHopperCounterConfig implements IConfigModule {
    @ConfigInfo(name = "hopperCounters")
    public static boolean hopperCounters = false;

    @ConfigInfo(name = "hopperCountersUnlimitedSpeed")
    public static boolean hopperCountersUnlimitedSpeed = false;

    @DoNotLoad
    private static CounterCommand counterCommand = null;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> exs) {
        if (hopperCounters) {
            if (counterCommand == null) {
                counterCommand = new CounterCommand();
            }
            counterCommand.register();
        }
    }

    @Override
    public void onUnloaded(CommentedFileConfig configInstance) {
        if (counterCommand != null) {
            counterCommand.unregister();
        }
    }
}
