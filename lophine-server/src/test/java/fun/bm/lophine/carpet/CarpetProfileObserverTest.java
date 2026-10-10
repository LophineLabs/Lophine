package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CarpetProfileObserverTest {
    private static final CarpetProfileObserver.Key REGION = new CarpetProfileObserver.Key("test:dimension", 7);

    @Test
    void aDelayedPreviousWindowCallbackCannotReplaceTheCurrentOwnerRoot() {
        var work = new CarpetProfileObserver.Work(REGION, 123, "Full Tick", 20);
        var first = new CarpetProfileObserver.Session(100);
        work.begin(first);
        first.finish(170);
        var second = new CarpetProfileObserver.Session(200);
        work.begin(second);
        work.begin(first); // an owner callback captured the previous active window before replacement
        work.finish(second, 250);
        assertEquals(new CarpetProfileObserver.Timing(50, 50, 1),
                second.finish(270).regions().getFirst().timers().get("Full Tick"));
    }

    @Test
    void ownerWorkDoesNotWaitForTheGlobalWindowLifecycleMonitor() throws Exception {
        CarpetProfileObserver.reset();
        var field = CarpetProfileObserver.class.getDeclaredField("LIFECYCLE");
        field.setAccessible(true);
        try (var pool = Executors.newSingleThreadExecutor()) {
            synchronized (field.get(null)) {
                pool.submit(() -> {
                    CarpetProfileObserver.startWork(REGION, 123, "Full Tick", 20);
                    CarpetProfileObserver.stopWork(123, 90);
                }).get(5, TimeUnit.SECONDS);
            }
        } finally {
            CarpetProfileObserver.reset();
        }
    }

    @Test
    void simultaneousNativeOwnersRetainClippedRootsAcrossWindowReplacement() throws Exception {
        CarpetProfileObserver.reset();
        var entered = new java.util.concurrent.CountDownLatch(8);
        var leave = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int owner = 0; owner < 8; owner++) {
                final int id = owner;
                jobs.add(pool.submit(() -> {
                    CarpetProfileObserver.startWork(new CarpetProfileObserver.Key("test:dimension", id), 123, "Full Tick", 20);
                    entered.countDown();
                    try {
                        assertTrue(leave.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    } finally {
                        CarpetProfileObserver.stopWork(123, 250);
                    }
                }));
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var first = CarpetProfileObserver.begin(100);
                var firstReport = CarpetProfileObserver.finish(first, 170);
                assertEquals(8, firstReport.regions().size());
                for (var region : firstReport.regions())
                    assertEquals(new CarpetProfileObserver.Timing(70, 70, 1), region.timers().get("Full Tick"));
                var second = CarpetProfileObserver.begin(200);
                leave.countDown();
                for (var job : jobs) job.get(5, TimeUnit.SECONDS);
                var secondReport = CarpetProfileObserver.finish(second, 270);
                assertEquals(8, secondReport.regions().size());
                for (var region : secondReport.regions())
                    assertEquals(new CarpetProfileObserver.Timing(50, 50, 1), region.timers().get("Full Tick"));
            } finally {
                leave.countDown();
            }
        } finally {
            leave.countDown();
            CarpetProfileObserver.reset();
        }
    }

    @Test
    void observesTheActualNoOpHandleIncludingDynamicNamesAndCounters() {
        CarpetProfileObserver.reset();
        try {
            long start = System.nanoTime();
            CarpetProfileObserver.startWork(REGION, 123, "Full Tick", start);
            var session = CarpetProfileObserver.begin(start);
            var handle = ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle.NO_OP_HANDLE;
            assertNull(handle.profiler);
            handle.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_TICK);
            int dynamic = handle.getOrCreateTimerAndStart(() -> "Entity Tick: test:no_op_observer");
            assertTrue(dynamic >= 0);
            handle.addCounter(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_SCHEDULERS_TICKED, 9);
            handle.stopTimer(dynamic);
            handle.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_TICK);
            var report = CarpetProfileObserver.finish(session, System.nanoTime()).regions().getFirst();
            assertEquals(1, report.timers().get("Entity Tick: test:no_op_observer").calls());
            assertEquals(1, report.timers().get("Entity Tick").calls());
            assertEquals(9L, report.counters().get("Entity Schedulers Ticked"));
            assertNull(handle.profiler);
            CarpetProfileObserver.stopWork(123, System.nanoTime());
        } finally {
            CarpetProfileObserver.reset();
        }
    }

    @Test
    void clipsARegionTickThatWasAlreadyRunningAtTheWindowHead() {
        CarpetProfileObserver.reset();
        try {
            CarpetProfileObserver.startWork(REGION, 123, "Full Tick", 20);
            var session = CarpetProfileObserver.begin(100);
            var result = CarpetProfileObserver.finish(session, 170);
            assertEquals(70, result.regions().getFirst().timers().get("Full Tick").nanos());
            CarpetProfileObserver.stopWork(123, 230);
            // Later native stop must never append to the immutable report.
            assertEquals(70, result.regions().getFirst().timers().get("Full Tick").nanos());
        } finally {
            CarpetProfileObserver.reset();
        }
    }

    @Test
    void independentlyAccountsInclusiveAndSelfTimeAndClipsStillOpenChildren() {
        var session = new CarpetProfileObserver.Session(100);
        var root = session.begin(REGION, 1, "Full Tick", 90, null);
        var outer = session.begin(REGION, 2, "Entities", 110, root);
        var child = session.begin(REGION, 3, "Entity Tick: test:pig", 130, outer);
        child.finish(150);
        session.begin(REGION, 4, "Entity Tick: test:cow", 170, outer);
        var report = session.finish(200).regions().getFirst().timers();
        assertEquals(new CarpetProfileObserver.Timing(100, 10, 1), report.get("Full Tick"));
        assertEquals(new CarpetProfileObserver.Timing(90, 40, 1), report.get("Entities"));
        assertEquals(new CarpetProfileObserver.Timing(20, 20, 1), report.get("Entity Tick: test:pig"));
        assertEquals(new CarpetProfileObserver.Timing(30, 30, 1), report.get("Entity Tick: test:cow"));
    }

    @Test
    void preservesAStillRunningNativeTickAcrossReplacementWindows() {
        CarpetProfileObserver.reset();
        try {
            CarpetProfileObserver.startWork(REGION, 123, "In Between Tick", 20);
            var first = CarpetProfileObserver.begin(100);
            assertEquals(70, CarpetProfileObserver.finish(first, 170).regions().getFirst().timers().get("In Between Tick").nanos());
            var second = CarpetProfileObserver.begin(200);
            CarpetProfileObserver.stopWork(123, 250);
            assertEquals(50, CarpetProfileObserver.finish(second, 270).regions().getFirst().timers().get("In Between Tick").nanos());
        } finally {
            CarpetProfileObserver.reset();
        }
    }

    @Test
    void capturesActualDistinctRegionIdentitiesAndConcurrentCountersWithoutClaimingNativeProfiler() throws Exception {
        var session = new CarpetProfileObserver.Session(0);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int owner = 0; owner < 8; owner++) {
                final int id = owner;
                jobs.add(pool.submit(() -> {
                    var key = new CarpetProfileObserver.Key("test:dimension", id);
                    for (int tick = 0; tick < 1000; tick++) {
                        var root = session.begin(key, 1, "Full Tick", tick * 10, null);
                        var entity = session.begin(key, 2, "Entity Tick: test:pig", tick * 10 + 1, root);
                        session.counter(key, "Entities Ticked", 1);
                        entity.finish(tick * 10 + 4);
                        root.finish(tick * 10 + 8);
                    }
                }));
            }
            for (var job : jobs) job.get();
        }
        var result = session.finish(10000);
        assertEquals(8, result.regions().size());
        for (var region : result.regions()) {
            assertEquals(1000L, region.counters().get("Entities Ticked"));
            assertEquals(new CarpetProfileObserver.Timing(8000, 5000, 1000), region.timers().get("Full Tick"));
            assertEquals(new CarpetProfileObserver.Timing(3000, 3000, 1000), region.timers().get("Entity Tick: test:pig"));
        }
        assertNull(session.begin(REGION, 3, "Too Late", 10001, null));
    }
}
