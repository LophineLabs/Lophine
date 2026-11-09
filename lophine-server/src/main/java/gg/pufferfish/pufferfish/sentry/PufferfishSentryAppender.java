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

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Message;
import io.sentry.protocol.User;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;

import java.util.Map;
import java.util.function.Consumer;

public class PufferfishSentryAppender extends AbstractAppender {

    private static final Gson GSON = new Gson();
    private final Level logLevel;
    private final SentryLogContext context;
    private final Consumer<SentryEvent> sender;
    private final ThreadLocal<Boolean> appending = ThreadLocal.withInitial(() -> false);

    public PufferfishSentryAppender(Level logLevel) {
        this(logLevel, new SentryLogContext(100), Sentry::captureEvent);
    }

    PufferfishSentryAppender(Level logLevel, SentryLogContext context, Consumer<SentryEvent> sender) {
        super("PufferfishSentryAdapter", null, null);
        this.logLevel = logLevel == null || logLevel.isLessSpecificThan(Level.ERROR) ? Level.ERROR : logLevel;
        this.context = context;
        this.sender = sender;
    }

    @Override
    public void append(LogEvent logEvent) {
        String loggerName = logEvent.getLoggerName();
        if (this.appending.get() || isInternalLogger(loggerName)) {
            return;
        }
        this.appending.set(true);
        try {
            this.context.record(logEvent);
            if (this.logLevel == Level.OFF || logEvent.getLevel() == Level.OFF
                    || !logEvent.getLevel().isMoreSpecificThan(Level.ERROR)) {
                return;
            }
            String text = logEvent.getMessage().getFormattedMessage();
            boolean watchdog = (loggerName != null && loggerName.endsWith("WatchdogThread"))
                    || (logEvent.getThreadName() != null && logEvent.getThreadName().contains("Watchdog"));
            boolean watchdogHeadline = watchdog && (text.contains("has not responded") || text.contains("has stopped responding"));
            // A watchdog prints many ERROR lines for a single dump; retain those as context only.
            if (watchdog && !watchdogHeadline && logEvent.getThrown() == null) {
                return;
            }
            if (loggerName != null && loggerName.endsWith(".MinecraftServer")
                    && logEvent.getThrown() == null && text.startsWith("This crash report has been saved to:")) {
                return;
            }
            boolean crash = logEvent.getThrown() != null && loggerName != null
                    && (loggerName.endsWith(".TickRegionScheduler")
                    || loggerName.endsWith(".MinecraftServer") && (text.contains("Encountered an unexpected exception")
                    || text.contains("Uncaught exception in server thread")));
            if (!crash && !watchdogHeadline && !logEvent.getLevel().isMoreSpecificThan(this.logLevel)) {
                return;
            }
            SentryEvent event = createEvent(logEvent);
            if (crash || watchdogHeadline) {
                event.setLevel(SentryLevel.FATAL);
                event.setTag("error.category", crash ? "server_crash" : "watchdog_timeout");
            } else {
                event.setTag("error.category", "severe_error");
            }
            this.sender.accept(event);
        } catch (Exception exception) {
            // Use the appender status logger, not Log4j's root logger (which would recurse).
            error("Failed to prepare Sentry event", logEvent, exception);
        } finally {
            this.appending.remove();
        }
    }

    private SentryEvent createEvent(LogEvent e) {
        SentryEvent event = new SentryEvent(e.getThrown());

        event.setTimestamp(new java.util.Date(e.getTimeMillis()));
        Message message = new Message();
        message.setFormatted(SentryLogContext.truncate(e.getMessage().getFormattedMessage()));
        event.setMessage(message);
        event.setLevel(getLevel(e.getLevel()));
        event.setLogger(e.getLoggerName());
        event.setTransaction(e.getLoggerName());
        event.setExtra("thread_name", e.getThreadName());

        boolean hasContext = e.getContextData() != null;

        if (hasContext && e.getContextData().containsKey("pufferfishsentry_playerid")) {
            User user = new User();
            user.setId(e.getContextData().getValue("pufferfishsentry_playerid"));
            user.setUsername(e.getContextData().getValue("pufferfishsentry_playername"));
            event.setUser(user);
        }

        if (hasContext && e.getContextData().containsKey("pufferfishsentry_pluginname")) {
            event.setExtra("plugin.name", e.getContextData().getValue("pufferfishsentry_pluginname"));
            event.setExtra("plugin.version", e.getContextData().getValue("pufferfishsentry_pluginversion"));
            event.setTransaction(e.getContextData().getValue("pufferfishsentry_pluginname"));
        }

        if (hasContext && e.getContextData().containsKey("pufferfishsentry_eventdata")) {
            Map<String, String> eventFields = GSON.fromJson((String) e.getContextData().getValue("pufferfishsentry_eventdata"), new TypeToken<Map<String, String>>() {
            }.getType());
            if (eventFields != null) {
                event.setExtra("event", eventFields);
            }
        }

        return event;
    }

    static SentryLevel getLevel(Level level) {
        return switch (level.getStandardLevel()) {
            case TRACE, DEBUG -> SentryLevel.DEBUG;
            case WARN -> SentryLevel.WARNING;
            case ERROR -> SentryLevel.ERROR;
            case FATAL -> SentryLevel.FATAL;
            default -> SentryLevel.INFO;
        };
    }

    private static boolean isInternalLogger(String name) {
        return name != null && (name.startsWith("gg.pufferfish.pufferfish.sentry")
                || name.startsWith("gg.castaway.pufferfish.sentry") || name.startsWith("io.sentry"));
    }
}
