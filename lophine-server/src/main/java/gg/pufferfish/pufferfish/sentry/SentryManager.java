/*
 * This file is part of Pufferfish (https://github.com/pufferfish-gg/Pufferfish)
 *
 * Pufferfish is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pufferfish is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Pufferfish. If not, see <https://www.gnu.org/licenses/>.
 */

package gg.pufferfish.pufferfish.sentry;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.protocol.SentryId;
import io.papermc.paper.ServerBuildInfo;
import me.earthme.luminol.config.modules.misc.SentryConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SentryManager {

    private static final Logger logger = LogManager.getLogger(SentryManager.class);

    private static Settings active;
    private static PufferfishSentryAppender appender;

    private record Settings(String dsn, Level level, boolean onlyThrown, int duplicateSeconds,
                            int eventWindowSeconds, int maxEvents, int crashWindowSeconds, int maxCrashes, int contextLines) {}

    public static synchronized void init(Level logLevel) {
        configure(SentryConfig.resolveDsn(), logLevel, SentryConfig.onlyLogThrown,
                SentryConfig.rateLimitWindowSeconds, SentryConfig.eventWindowSeconds, SentryConfig.maxEventsPerWindow,
                SentryConfig.crashWindowSeconds, SentryConfig.maxCrashesPerWindow, SentryConfig.contextLines);
    }

    // Retain the original entry point; its window now controls duplicate suppression only.
    public static synchronized void configure(String dsn, Level logLevel, boolean onlyThrown,
                                               int windowSeconds, int maxEvents, int contextLines) {
        configure(dsn, logLevel, onlyThrown, windowSeconds, SentryConfig.eventWindowSeconds, maxEvents,
                SentryConfig.crashWindowSeconds, SentryConfig.maxCrashesPerWindow, contextLines);
    }

    public static synchronized void configure(String dsn, Level logLevel, boolean onlyThrown,
                                               int duplicateSeconds, int eventWindowSeconds, int maxEvents,
                                               int crashWindowSeconds, int maxCrashes, int contextLines) {
        Level threshold = logLevel == null || logLevel.isLessSpecificThan(Level.ERROR) ? Level.ERROR : logLevel;
        Settings settings = new Settings(dsn == null ? "" : dsn.trim(), threshold, onlyThrown,
                Math.clamp(duplicateSeconds, 1, 86400), Math.clamp(eventWindowSeconds, 1, 86400), Math.clamp(maxEvents, 1, 1000),
                Math.clamp(crashWindowSeconds, 1, 86400), Math.clamp(maxCrashes, 1, 1000), Math.clamp(contextLines, 0, 100));
        if (settings.equals(active)) {
            return;
        }
        close();
        if (settings.dsn().isEmpty() || threshold == Level.OFF) {
            if (settings.dsn().isEmpty() && threshold != Level.OFF) {
                logger.info("Sentry reporting is inactive: no runtime or embedded build DSN is configured.");
            }
            return;
        }
        try {
            ServerBuildInfo build = ServerBuildInfo.buildInfo();
            String release = build.brandName() + "@" + build.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE);
            SentryLogContext context = new SentryLogContext(settings.contextLines());
            SentryEventFilter filter = new SentryEventFilter(context,
                    new SentryRateLimiter(settings.duplicateSeconds(), settings.eventWindowSeconds(), settings.maxEvents(),
                            settings.crashWindowSeconds(), settings.maxCrashes()), System::nanoTime,
                    threshold == Level.FATAL ? SentryLevel.FATAL : SentryLevel.ERROR, onlyThrown,
                    release, SentryEventFilter.buildTags(build));
            Sentry.init(options -> {
                options.setDsn(settings.dsn());
                options.setRelease(release);
                options.setMaxBreadcrumbs(settings.contextLines());
                options.addInAppInclude("net.minecraft.");
                options.addInAppInclude("io.papermc.paper.");
                options.addInAppInclude("org.bukkit.craftbukkit.");
                options.addInAppInclude("fun.bm.lophine.");
                options.addInAppInclude("me.earthme.luminol.");
                options.addInAppInclude("gg.pufferfish.");
                options.setBeforeSend((event, hint) -> filter.beforeSend(event));
                // Our limiter handles repetition and fatal escalation across all SDK entry points.
                options.setEnableDeduplication(false);
                options.setEnableUncaughtExceptionHandler(true);
                options.setEnableShutdownHook(true);
                options.setFlushTimeoutMillis(2000);
                options.setShutdownTimeoutMillis(2000);
            });

            appender = new PufferfishSentryAppender(threshold, context, event -> {
                SentryId id = Sentry.captureEvent(event);
                // Fatal tick failures may stop the process immediately after this log statement.
                if (!SentryId.EMPTY_ID.equals(id) && "server_crash".equals(event.getTag("error.category"))) {
                    Sentry.flush(2000);
                }
            });
            appender.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
            active = settings;
            logger.info("Sentry logging started! Duplicate cooldown: {} seconds; up to {} errors per {} seconds and {} crashes per {} seconds (independent quotas).",
                    settings.duplicateSeconds(), settings.maxEvents(), settings.eventWindowSeconds(),
                    settings.maxCrashes(), settings.crashWindowSeconds());
        } catch (Exception e) {
            // init may have installed SDK integrations before failing; roll them back as well.
            if (appender != null) {
                ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(appender);
                appender.stop();
                appender = null;
            }
            Sentry.close();
            logger.warn("Failed to initialize sentry!", e);
        }
    }

    public static synchronized void close() {
        if (appender != null) {
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(appender);
            appender.stop();
            appender = null;
        }
        if (active != null) {
            Sentry.close();
            active = null;
        }
    }
}
