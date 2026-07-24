package me.earthme.luminol.config.modules.misc;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import gg.pufferfish.pufferfish.sentry.SentryManager;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.CommandSuggestions;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "sentry")
public class SentryConfig implements IConfigModule {

    @ConfigInfo(name = "dsn")
    public static String sentryDsn = "";

    @CommandSuggestions(suggest = {"OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL"})
    @ConfigInfo(name = "log_level")
    public static String logLevel = "WARN";

    @ConfigInfo(name = "only_log_thrown")
    public static boolean onlyLogThrown = true;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> exs) {
        String sentryEnvironment = System.getenv("SENTRY_DSN");

        sentryDsn = sentryEnvironment != null && !sentryEnvironment.isBlank()
                ? sentryEnvironment
                : configInstance.getOrElse("sentry.dsn", sentryDsn);

        logLevel = configInstance.getOrElse("sentry.log-level", logLevel);
        onlyLogThrown = configInstance.getOrElse("sentry.only-log-thrown", onlyLogThrown);

        if (sentryDsn != null && !sentryDsn.isBlank()) {
            SentryManager.init(Level.getLevel(logLevel));
        }
    }
}