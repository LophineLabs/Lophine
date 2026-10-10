package fun.bm.lophine.carpet;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TisLifetimeAggregationTest {
    @Test
    void aggregatesConcurrentRegionReportsWithoutLosingCountsOrChangingSnapshots() throws Exception {
        var stats = new TisLifetimeTracker.Stats();
        var reason = new TisLifetimeTracker.Reason("death", "{\"damageSource\":\"generic\"}");
        int regions = 8;
        int perRegion = 2000;
        var ready = new CountDownLatch(regions);
        var begin = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(regions)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int region = 0; region < regions; ++region) {
                int owner = region;
                jobs.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        begin.await();
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                    for (int i = 0; i < perRegion; ++i) {
                        stats.spawn(reason, 7);
                        stats.remove(reason, 5, new TisLifetimeTracker.Point(owner + 1, "minecraft:overworld", Vec3.ZERO, new Vec3(owner, 0, 0)));
                        if (i % 100 == 0) {
                            var current = stats.snapshot();
                            assertEquals(current.removed(), current.lifetime().count());
                        }
                    }
                }));
            }
            ready.await();
            begin.countDown();
            for (var job : jobs) job.get();
        }
        var snapshot = stats.snapshot();
        assertEquals((long) regions * perRegion, snapshot.spawned());
        assertEquals(snapshot.spawned(), snapshot.removed());
        assertEquals(snapshot.spawned() * 7, snapshot.spawnExtra());
        assertEquals(snapshot.removed() * 5, snapshot.removeExtra());
        assertEquals(1, snapshot.lifetime().minimum().time());
        assertEquals(8, snapshot.lifetime().maximum().time());
        assertEquals(36L * perRegion, snapshot.lifetime().sum());
        assertEquals(snapshot.removed(), snapshot.removeReasons().get(reason).count());
        stats.remove(reason, 1, new TisLifetimeTracker.Point(20, "minecraft:overworld", Vec3.ZERO, Vec3.ZERO));
        assertEquals((long) regions * perRegion, snapshot.removed());
        assertEquals(8, snapshot.lifetime().maximum().time());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.removeReasons().clear());
    }
}
