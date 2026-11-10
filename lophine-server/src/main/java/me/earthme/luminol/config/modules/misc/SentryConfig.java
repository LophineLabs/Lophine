package me.earthme.luminol.config.modules.misc;

import gg.pufferfish.pufferfish.sentry.SentryManager;
import me.earthme.luminol.api.config.annotation.CommandSuggestions;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.NeedRun;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumRunnableType;
import org.apache.logging.log4j.Level;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "sentry")
public class SentryConfig {

    // Official CI replaces this empty default with its build secret before compilation.
    public static final String DEFAULT_DSN = "";

    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;

    @ConfigInfo(name = "dsn")
    public static String sentryDsn = "";

    @CommandSuggestions(suggest = {"OFF", "FATAL", "ERROR"})
    @ConfigInfo(name = "log_level")
    public static String logLevel = "ERROR";

    @ConfigInfo(name = "only_log_thrown")
    public static boolean onlyLogThrown = false;

    @ConfigInfo(name = "rate_limit_window_seconds")
    public static int rateLimitWindowSeconds = 600;

    @ConfigInfo(name = "event_window_seconds")
    public static int eventWindowSeconds = 60;

    @ConfigInfo(name = "max_events_per_window")
    public static int maxEventsPerWindow = 50;

    @ConfigInfo(name = "crash_window_seconds")
    public static int crashWindowSeconds = 60;

    @ConfigInfo(name = "max_crashes_per_window")
    public static int maxCrashesPerWindow = 10;

    @ConfigInfo(name = "context_lines")
    public static int contextLines = 100;

    @NeedRun(when = EnumRunnableType.ON_LOADED)
    public void onLoaded() {
        if (!enabled) {
            SentryManager.close();
            return;
        }
        // Annotated fields have already been loaded through ConfigPaths. This hook also runs
        // without a file argument for runtime edits; do not read obsolete hard-coded paths here.
        SentryManager.configure(resolveDsn(), logLevel == null ? null : Level.getLevel(logLevel.toUpperCase(java.util.Locale.ROOT)),
                onlyLogThrown, rateLimitWindowSeconds, eventWindowSeconds, maxEventsPerWindow,
                crashWindowSeconds, maxCrashesPerWindow, contextLines);
    }

    public static String resolveDsn() {
        String environment = System.getenv("SENTRY_DSN");
        return environment != null && !environment.isBlank()
                ? environment
                : sentryDsn != null && !sentryDsn.isBlank() ? sentryDsn : DEFAULT_DSN;
    }
}
