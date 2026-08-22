package fun.bm.lophine.utils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import me.earthme.luminol.config.modules.function.PortalRateLimiterConfig;

/**
 * Destination-region token bucket for cross-region portal transfers.
 *
 * <p>Tokens are replenished by the destination region's tick. A source region
 * therefore cannot continue feeding entities at 20 TPS when the destination
 * region has fallen behind.</p>
 */
public final class PortalIngressController {
    private final AtomicInteger availableTokens = new AtomicInteger(
            Math.max(
                    1,
                    Math.max(
                            PortalRateLimiterConfig.portalIngressTokensPerTick,
                            PortalRateLimiterConfig.portalIngressBurstCapacity
                    )
            )
    );
    private final LongAdder admittedEntities = new LongAdder();
    private final LongAdder deferredEntities = new LongAdder();

    public void refillForDestinationTick() {
        if (!PortalRateLimiterConfig.destinationBackpressureEnabled) {
            return;
        }

        final int refill = Math.max(1, PortalRateLimiterConfig.portalIngressTokensPerTick);
        final int capacity = Math.max(refill, PortalRateLimiterConfig.portalIngressBurstCapacity);
        this.availableTokens.getAndUpdate(current -> Math.min(capacity, current + refill));
    }

    public boolean tryAcquire(final int entityCount) {
        if (!PortalRateLimiterConfig.destinationBackpressureEnabled) {
            return true;
        }

        final int required = Math.max(1, entityCount);
        for (;;) {
            final int current = this.availableTokens.get();
            if (current < required) {
                this.deferredEntities.add(required);
                return false;
            }
            if (this.availableTokens.compareAndSet(current, current - required)) {
                this.admittedEntities.add(required);
                return true;
            }
        }
    }

    public int availableTokens() {
        return this.availableTokens.get();
    }

    public long admittedEntities() {
        return this.admittedEntities.sum();
    }

    public long deferredEntities() {
        return this.deferredEntities.sum();
    }
}
