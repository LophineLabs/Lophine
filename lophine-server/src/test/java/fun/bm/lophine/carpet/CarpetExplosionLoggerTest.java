package fun.bm.lophine.carpet;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarpetExplosionLoggerTest {
    @Test
    void countsSimultaneousRegionExplosionsUniquelyAndStartsOneTickHeader() throws Exception {
        CarpetExplosionLogger.reset();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<CarpetExplosionLogger.TickNumber>>();
            for (int i = 0; i < 1000; ++i) jobs.add(pool.submit(() -> CarpetExplosionLogger.next(100)));
            var values = new HashSet<Integer>();
            int headers = 0;
            for (var job : jobs) {
                var number = job.get();
                values.add(number.number());
                if (number.first()) ++headers;
            }
            assertEquals(1000, values.size());
            assertEquals(1, headers);
            var nextTick = CarpetExplosionLogger.next(101);
            assertEquals(1, nextTick.number());
            assertTrue(nextTick.first());
        } finally {
            CarpetExplosionLogger.reset();
        }
    }

    @Test
    void recoversInitializedTntAngleInAllQuadrants() {
        for (double angle : new double[]{0.0, 0.1, Math.PI / 2, Math.PI, 4.0, 5.8}) {
            Vec3 movement = new Vec3(-Math.sin(angle) * 0.02, 0.2, -Math.cos(angle) * 0.02);
            assertEquals(angle, CarpetExplosionLogger.initializedAngle(movement), 1.0E-12);
        }
    }
}
