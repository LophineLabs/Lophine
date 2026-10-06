package fun.bm.lophine.carpet;

import net.minecraft.server.ServerTickRateManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CarpetTickConcurrencyTest {
    @Test
    void ordinaryRegionTickDoesNotWaitForAnotherRegionOrGlobalSprintLock() throws Exception {
        var manager = new ServerTickRateManager(null);
        try (var worker = Executors.newSingleThreadExecutor()) {
            synchronized (manager) {
                worker.submit(manager::endTickWork).get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void sprintAccountingIsPerThreadAndRejectsRecordsFromPreviousSprint() throws Exception {
        var manager = new ServerTickRateManager(null);
        var total = field("sprintTimeSpend", manager, AtomicLong.class);
        set("scheduledCurrentSprintTicks", manager, 10L);
        set("remainingSprintTicks", manager, 10L);
        assertTrue(manager.checkShouldSprintThisTick());
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(manager::endTickWork).get(5, TimeUnit.SECONDS);
            assertEquals(0L, total.get()); // another region cannot consume this thread's record
        }
        manager.endTickWork();
        assertTrue(total.get() > 0L);
        long accounted = total.get();
        manager.endTickWork();
        assertEquals(accounted, total.get());
        assertTrue(manager.checkShouldSprintThisTick());
        set("carpetSprintEpoch", manager, 1L);
        manager.endTickWork();
        assertEquals(accounted, total.get());
    }

    private static void set(String name, ServerTickRateManager manager, long value) throws Exception {
        var field = ServerTickRateManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(manager, value);
    }

    private static <T> T field(String name, ServerTickRateManager manager, Class<T> type) throws Exception {
        var field = ServerTickRateManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(manager));
    }
}
