package fun.bm.lophine.carpet;

import ca.spottedleaf.leafprofiler.LProfilerRegistry;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Independent observation of native Folia timers; never acquires a native profiler handle. */
public final class CarpetProfileObserver {
    private static final Object LIFECYCLE = new Object();
    private static final Map<Thread, Work> WORK = new HashMap<>();
    private static final ThreadLocal<Work> CURRENT_WORK = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<Span>> TIMERS = ThreadLocal.withInitial(ArrayDeque::new);
    private static volatile Session active;
    private static final Key GLOBAL = new Key("global", -1L);
    private static final Scope NOOP = new Scope(null);

    private CarpetProfileObserver() {}

    public record Key(String dimension, long regionId) {}
    public record Timing(long nanos, long selfNanos, long calls) {}
    public record RegionResult(Key key, Map<String, Timing> timers, Map<String, Long> counters) {}
    public record Result(long startNanos, long endNanos, List<RegionResult> regions) {}

    private static final class Work {
        final Key key;
        final int id;
        final String name;
        final long start;
        volatile Span span;

        Work(Key key, int id, String name, long start) {
            this.key = key; this.id = id; this.name = name; this.start = start;
        }
    }

    static final class Session {
        final long start;
        private final ConcurrentHashMap<Key, Record> records = new ConcurrentHashMap<>();
        private volatile long end = Long.MAX_VALUE;

        Session(long start) { this.start = start; }

        private Record record(Key key) {
            Record existing = records.get(key);
            if (existing != null) return existing;
            synchronized (this) {
                if (end != Long.MAX_VALUE) return null;
                return records.computeIfAbsent(key, Record::new);
            }
        }

        Span begin(Key key, int id, String name, long now, Span parent) {
            Record record = record(key);
            if (record == null) return null;
            synchronized (record) {
                if (record.closed || end != Long.MAX_VALUE) return null;
                Span span = new Span(this, record, id, name, Math.max(start, now), parent);
                record.open.put(span, Boolean.TRUE);
                return span;
            }
        }

        void counter(Key key, String name, long count) {
            Record record = record(key);
            if (record == null) return;
            synchronized (record) {
                if (!record.closed && end == Long.MAX_VALUE) record.counters.merge(name, count, Long::sum);
            }
        }

        Result finish(long now) {
            List<Record> all;
            synchronized (this) {
                if (end != Long.MAX_VALUE) throw new IllegalStateException("Profile already closed");
                end = Math.max(start, now);
                all = new ArrayList<>(records.values());
            }
            List<RegionResult> result = new ArrayList<>(all.size());
            for (Record record : all) {
                synchronized (record) {
                    // Children commit first so inclusive and self times have the same clipped endpoint.
                    for (Span span : new ArrayList<>(record.open.keySet())) finishSpan(span, end);
                    record.closed = true;
                    Map<String, Timing> timers = new HashMap<>();
                    record.timers.forEach((name, value) -> timers.put(name,
                        new Timing(value.nanos, value.selfNanos, value.calls)));
                    result.add(new RegionResult(record.key, Map.copyOf(timers), Map.copyOf(record.counters)));
                }
            }
            return new Result(start, end, List.copyOf(result));
        }
    }

    private static final class Record {
        final Key key;
        final Map<String, MutableTiming> timers = new HashMap<>();
        final Map<String, Long> counters = new HashMap<>();
        final IdentityHashMap<Span, Boolean> open = new IdentityHashMap<>();
        boolean closed;
        Record(Key key) { this.key = key; }
    }

    private static final class MutableTiming { long nanos, selfNanos, calls; }

    static final class Span {
        final Session session;
        final Record record;
        final int id;
        final String name;
        final long start;
        final Span parent;
        final List<Span> children = new ArrayList<>();
        long childrenNanos;
        volatile boolean done;

        Span(Session session, Record record, int id, String name, long start, Span parent) {
            this.session = session; this.record = record; this.id = id;
            this.name = name; this.start = start;
            this.parent = parent != null && parent.session == session && parent.record == record && !parent.done ? parent : null;
            if (this.parent != null) this.parent.children.add(this);
        }

        void finish(long now) {
            synchronized (record) { finishSpan(this, Math.min(now, session.end)); }
        }
    }

    private static void finishSpan(Span span, long end) {
        if (span.done) return;
        while (!span.children.isEmpty()) finishSpan(span.children.getLast(), end);
        long nanos = Math.max(0L, end - span.start);
        MutableTiming timing = span.record.timers.computeIfAbsent(span.name, ignored -> new MutableTiming());
        timing.nanos += nanos;
        timing.selfNanos += Math.max(0L, nanos - span.childrenNanos);
        timing.calls++;
        span.done = true;
        span.record.open.remove(span);
        if (span.parent != null) {
            span.parent.childrenNanos += nanos;
            span.parent.children.remove(span);
        }
    }

    public static boolean active() { return active != null; }

    static Session begin(long now) {
        synchronized (LIFECYCLE) {
            if (active != null) throw new IllegalStateException("Profile already active");
            Session session = new Session(now);
            // These snapshots were taken by their owners. Do not inspect foreign world data here.
            for (Work work : WORK.values()) work.span = session.begin(work.key, work.id, work.name, now, null);
            active = session;
            return session;
        }
    }

    static Result finish(Session session, long now) {
        synchronized (LIFECYCLE) {
            if (active == session) active = null;
            return session.finish(now);
        }
    }

    public static void reset() {
        synchronized (LIFECYCLE) {
            if (active != null) { active.finish(System.nanoTime()); active = null; }
            WORK.clear();
            CURRENT_WORK.remove();
        }
    }

    private static Key currentRegion() {
        var region = TickRegionScheduler.getCurrentRegion();
        var data = TickRegionScheduler.getCurrentRegionizedWorldData();
        return region == null || data == null ? null : new Key(data.world.dimension().identifier().toString(), region.id);
    }

    private static Key timerRegion() {
        Key key = currentRegion();
        if (key != null) return key;
        Work work = CURRENT_WORK.get();
        return work == null ? null : work.key;
    }

    /** Root lifecycle is retained even while sampling is off, to include boundary-crossing ticks. */
    public static void startWork(int id) {
        Key key = currentRegion();
        if (key != null) startWork(key, id, timerName(id), System.nanoTime());
    }

    static void startWork(Key key, int id, String name, long now) {
        synchronized (LIFECYCLE) {
            Work previous = WORK.remove(Thread.currentThread());
            if (previous != null && previous.span != null) previous.span.finish(now);
            Work work = new Work(key, id, name, now);
            WORK.put(Thread.currentThread(), work);
            CURRENT_WORK.set(work);
            if (active != null) work.span = active.begin(key, id, name, now, null);
        }
    }

    public static void stopWork(int id) { stopWork(id, System.nanoTime()); }

    static void stopWork(int id, long now) {
        synchronized (LIFECYCLE) {
            Work work = WORK.get(Thread.currentThread());
            if (work == null) { CURRENT_WORK.remove(); return; }
            if (work.id != id) return;
            WORK.remove(Thread.currentThread());
            CURRENT_WORK.remove();
            if (work.span != null) work.span.finish(now);
        }
    }

    private static String timerName(int id) {
        var entry = LProfilerRegistry.GLOBAL_REGISTRY.getById(id);
        return entry == null ? "Unknown native timer " + id : entry.name();
    }

    private static Span parent(Session session, Key key) {
        ArrayDeque<Span> stack = TIMERS.get();
        while (!stack.isEmpty() && stack.peekLast().done) stack.removeLast();
        Span span = stack.peekLast();
        if (span != null && span.session == session && span.record.key.equals(key)) return span;
        Work work = CURRENT_WORK.get();
        return work == null ? null : work.span;
    }

    public static void startTimer(int id) {
        Session session = active;
        if (session == null) return;
        Key key = timerRegion();
        if (key == null) return;
        Span span = session.begin(key, id, timerName(id), System.nanoTime(), parent(session, key));
        if (span != null) TIMERS.get().addLast(span);
    }

    /** Called for the otherwise no-op native dynamic timer path. */
    public static int startDynamic(Supplier<String> name) {
        if (active == null) return -1;
        int id = LProfilerRegistry.GLOBAL_REGISTRY.getOrCreateType(
            ca.spottedleaf.profiler.ProfilerRegistry.ProfileType.TIMER, name.get());
        startTimer(id);
        return id;
    }

    public static void stopTimer(int id) {
        // Old closed spans are cheap to prune on the next callback, without a thread ownership handoff.
        ArrayDeque<Span> stack = TIMERS.get();
        if (stack.isEmpty()) return;
        for (var iterator = stack.descendingIterator(); iterator.hasNext();) {
            Span span = iterator.next();
            if (span.id != id) continue;
            span.finish(System.nanoTime());
            iterator.remove();
            break;
        }
        while (!stack.isEmpty() && stack.peekLast().done) stack.removeLast();
    }

    public static void addCounter(int id, long count) {
        Session session = active;
        if (session == null) return;
        Key key = timerRegion();
        if (key != null) session.counter(key, timerName(id), count);
    }

    public static Scope global(String name) {
        Session session = active;
        if (session == null) return NOOP;
        Span span = session.begin(GLOBAL, Integer.MIN_VALUE, name, System.nanoTime(), parent(session, GLOBAL));
        if (span == null) return NOOP;
        TIMERS.get().addLast(span);
        return new Scope(span);
    }

    public static Scope globalWorld(net.minecraft.server.level.ServerLevel world, String name) {
        if (active == null) return NOOP;
        return global(world.dimension().identifier() + " - " + name);
    }

    public static final class Scope implements AutoCloseable {
        private final Span span;
        private Scope(Span span) { this.span = span; }
        @Override public void close() {
            if (span == null) return;
            span.finish(System.nanoTime());
            TIMERS.get().removeLastOccurrence(span);
        }
    }
}
