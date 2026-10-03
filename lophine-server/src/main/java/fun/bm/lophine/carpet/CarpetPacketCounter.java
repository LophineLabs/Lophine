// SPDX-License-Identifier: LGPL-3.0-only
// Counter behavior adapted from gnembon/fabric-carpet f358000b175ddbcf1dd0bc59641c715fb0545664.
package fun.bm.lophine.carpet;

import java.util.concurrent.atomic.AtomicLong;

/** Network-thread counters; HUD snapshots are published together to regional readers. */
public final class CarpetPacketCounter {
    private static final AtomicLong RECEIVED = new AtomicLong();
    private static final AtomicLong SENT = new AtomicLong();
    private static volatile Counts displayed = new Counts(0, 0);

    private CarpetPacketCounter() {}

    public record Counts(long received, long sent) {}

    public static void received() { RECEIVED.incrementAndGet(); }
    public static void sent() { SENT.incrementAndGet(); }

    public static Counts snapshot() {
        Counts counts = new Counts(RECEIVED.getAndSet(0), SENT.getAndSet(0));
        displayed = counts;
        return counts;
    }

    public static Counts displayed() { return displayed; }
}
