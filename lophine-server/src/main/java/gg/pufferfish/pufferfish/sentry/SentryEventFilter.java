package gg.pufferfish.pufferfish.sentry;

import io.papermc.paper.ServerBuildInfo;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.SentryException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

final class SentryEventFilter {
    private final SentryLogContext context;
    private final SentryRateLimiter limiter;
    private final LongSupplier clock;
    private final SentryLevel minimumLevel;
    private final boolean onlyThrown;
    private final String release;
    private final Map<String, String> tags;

    SentryEventFilter(SentryLogContext context, SentryRateLimiter limiter, LongSupplier clock,
                      SentryLevel minimumLevel, boolean onlyThrown, String release, Map<String, String> tags) {
        this.context = context;
        this.limiter = limiter;
        this.clock = clock;
        this.minimumLevel = minimumLevel;
        this.onlyThrown = onlyThrown;
        this.release = release;
        this.tags = Map.copyOf(tags);
    }

    static Map<String, String> buildTags(ServerBuildInfo build) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("server.brand", build.brandName());
        tags.put("minecraft.version", build.minecraftVersionId());
        tags.put("server.build", build.buildNumber().isPresent() ? Integer.toString(build.buildNumber().getAsInt()) : "DEV");
        tags.put("server.commit", build.gitCommit().orElse("unknown"));
        tags.put("server.branch", build.gitBranch().orElse("unknown"));
        tags.put("java.version", System.getProperty("java.version", "unknown"));
        return tags;
    }

    SentryEvent beforeSend(SentryEvent event) {
        boolean unhandled = event.getExceptions() != null && event.getExceptions().stream()
                .map(SentryException::getMechanism)
                .anyMatch(mechanism -> mechanism != null && Boolean.FALSE.equals(mechanism.isHandled()));
        if (unhandled) {
            event.setLevel(SentryLevel.FATAL);
            event.setTag("error.category", "unhandled_exception");
        }
        SentryLevel level = event.getLevel();
        if (level == null || level.ordinal() < this.minimumLevel.ordinal()
                || (this.onlyThrown && level != SentryLevel.FATAL
                && (event.getExceptions() == null || event.getExceptions().isEmpty()))) {
            return null;
        }
        if (!this.limiter.allow(event, this.clock.getAsLong())) {
            return null;
        }
        event.setRelease(this.release);
        this.tags.forEach(event::setTag);
        // Do not depend on the emitting thread's SDK scope. Never retain mutable Log4j events.
        event.setBreadcrumbs(this.context.snapshot());
        return event;
    }
}
