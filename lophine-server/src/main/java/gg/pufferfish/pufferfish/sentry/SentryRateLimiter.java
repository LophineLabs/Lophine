package gg.pufferfish.pufferfish.sentry;

import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Local quotas apply to SDK uncaught exceptions as well as Log4j events.
 */
final class SentryRateLimiter {
    private static final int MAX_FINGERPRINTS = 1024;
    private static final Pattern NUMBERS = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");
    private final long duplicateWindowNanos;
    private final Budget errors;
    private final Budget crashes;
    private boolean clockStarted;
    private long latestTime;
    private long suppressed;

    SentryRateLimiter(int duplicateSeconds, int eventWindowSeconds, int maxEvents,
                      int crashWindowSeconds, int maxCrashes) {
        this.duplicateWindowNanos = TimeUnit.SECONDS.toNanos(Math.clamp(duplicateSeconds, 1, 86400));
        this.errors = new Budget(eventWindowSeconds, maxEvents);
        this.crashes = new Budget(crashWindowSeconds, maxCrashes);
    }

    synchronized boolean allow(SentryEvent event, long now) {
        // Threads can read the clock before acquiring this monitor in a different order.
        if (this.clockStarted && now - this.latestTime < 0) {
            now = this.latestTime;
        }
        this.clockStarted = true;
        this.latestTime = now;
        String key = fingerprint(event);
        boolean crash = event.getLevel() == SentryLevel.FATAL
                && ("server_crash".equals(event.getTag("error.category"))
                || "unhandled_exception".equals(event.getTag("error.category")));
        Budget budget = crash ? this.crashes : this.errors;
        if (!budget.allow(key, now, this.duplicateWindowNanos)) {
            ++this.suppressed;
            return false;
        }
        event.setExtra("sentry.quota", crash ? "crash" : "error");
        event.setExtra("sentry.suppressed_events", this.suppressed);
        return true;
    }

    private static final class Budget {
        private final long windowNanos;
        private final int maxEvents;
        private final LinkedHashMap<String, Long> accepted = new LinkedHashMap<>();
        private final ArrayDeque<Long> recent = new ArrayDeque<>();

        private Budget(int windowSeconds, int maxEvents) {
            this.windowNanos = TimeUnit.SECONDS.toNanos(Math.clamp(windowSeconds, 1, 86400));
            this.maxEvents = Math.clamp(maxEvents, 1, 1000);
        }

        private boolean allow(String key, long now, long duplicateWindowNanos) {
            while (!this.accepted.isEmpty() && now - this.accepted.firstEntry().getValue() >= duplicateWindowNanos) {
                this.accepted.pollFirstEntry();
            }
            while (!this.recent.isEmpty() && now - this.recent.getFirst() >= this.windowNanos) {
                this.recent.removeFirst();
            }
            // Never evict a live fingerprint: doing so would let duplicates bypass their cooldown.
            // Separate histories also keep an ordinary-error flood from exhausting crash capacity.
            if (this.accepted.containsKey(key) || this.recent.size() >= this.maxEvents
                    || this.accepted.size() >= MAX_FINGERPRINTS) {
                return false;
            }
            this.accepted.put(key, now);
            this.recent.addLast(now);
            return true;
        }
    }

    static String fingerprint(SentryEvent event) {
        StringBuilder signature = new StringBuilder(event.getLevel() == SentryLevel.FATAL ? "fatal:" : "error:");
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null && !exceptions.isEmpty()) {
            // Exclude exception messages: coordinates, player IDs and counters must not defeat deduplication.
            for (SentryException exception : exceptions.subList(0, Math.min(8, exceptions.size()))) {
                signature.append(exception.getModule()).append(':').append(exception.getType());
                if (exception.getStacktrace() != null && exception.getStacktrace().getFrames() != null) {
                    List<SentryStackFrame> frames = exception.getStacktrace().getFrames();
                    for (SentryStackFrame frame : frames.subList(Math.max(0, frames.size() - 8), frames.size())) {
                        signature.append('|').append(frame.getModule()).append('.').append(frame.getFunction())
                                .append(':').append(frame.getLineno());
                    }
                }
            }
        } else {
            signature.append(event.getLogger()).append(':');
            if (event.getMessage() != null && event.getMessage().getFormatted() != null) {
                signature.append(NUMBERS.matcher(SentryLogContext.truncate(event.getMessage().getFormatted())).replaceAll("#"));
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(signature.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
