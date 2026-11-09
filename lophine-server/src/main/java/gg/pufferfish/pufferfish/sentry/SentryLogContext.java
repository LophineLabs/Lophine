package gg.pufferfish.pufferfish.sentry;

import io.sentry.Breadcrumb;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;

/** Bounded, process-wide context: Folia errors may be emitted on a different thread. */
final class SentryLogContext {
    static final int MAX_MESSAGE_LENGTH = 2048;
    private final int capacity;
    private final ArrayDeque<Breadcrumb> entries = new ArrayDeque<>();

    SentryLogContext(int capacity) {
        this.capacity = Math.clamp(capacity, 0, 100);
    }

    void record(LogEvent event) {
        if (this.capacity == 0 || !event.getLevel().isMoreSpecificThan(Level.INFO)
                || event.getLevel() == Level.OFF) {
            return;
        }
        Breadcrumb breadcrumb = new Breadcrumb(new Date(event.getTimeMillis()));
        breadcrumb.setCategory(event.getLoggerName());
        breadcrumb.setType("log");
        breadcrumb.setLevel(PufferfishSentryAppender.getLevel(event.getLevel()));
        breadcrumb.setMessage(truncate(event.getMessage().getFormattedMessage()));
        breadcrumb.setData("thread_name", event.getThreadName());
        synchronized (this.entries) {
            if (this.entries.size() == this.capacity) {
                this.entries.removeFirst();
            }
            this.entries.addLast(breadcrumb);
        }
    }

    List<Breadcrumb> snapshot() {
        synchronized (this.entries) {
            return new ArrayList<>(this.entries);
        }
    }

    static String truncate(String message) {
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }
}
