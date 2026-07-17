package me.earthme.luminol.config.modules.optimizations;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.kaiijumc.kaiiju.KaiijuEntityLimits;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "kaiiju_entity_limiter")
public class KaiijuEntityLimiterConfig implements IConfigModule {
    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> e) {
        KaiijuEntityLimits.init();
    }
}