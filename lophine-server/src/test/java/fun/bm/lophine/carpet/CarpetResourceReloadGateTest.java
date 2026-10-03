package fun.bm.lophine.carpet;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CarpetResourceReloadGateTest {
    @Test
    void ackWaitsForBothTickAndBetweenTickExecutionsAndRejectsNewSplitOrCreatedActors() {
        var gate = new CarpetResourceReloadCoordinator.Gate();
        assertTrue(gate.enter()); // actual tick
        assertTrue(gate.enter()); // actual between-tick task batch
        var acknowledgement = gate.pause();
        assertTrue(gate.paused());
        assertFalse(acknowledgement.isDone());
        assertFalse(gate.enter()); // any new region identity goes through the same gate
        gate.exit();
        assertFalse(acknowledgement.isDone());
        gate.exit();
        assertTrue(acknowledgement.isDone());
        gate.resume();
        assertFalse(gate.paused());
        assertTrue(gate.enter());
        gate.exit();
    }

    @Test
    void ownersExitWithoutWaitingForGlobalAndManyRejectedEntrantsNeverJoinTheAck() throws Exception {
        var gate = new CarpetResourceReloadCoordinator.Gate();
        var entered = new CountDownLatch(8);
        var leave = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; ++i) jobs.add(pool.submit(() -> {
                assertTrue(gate.enter());
                entered.countDown();
                try { assertTrue(leave.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                finally { gate.exit(); }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var acknowledgement = gate.pause();
            for (int i = 0; i < 10000; ++i) assertFalse(gate.enter());
            assertFalse(acknowledgement.isDone());
            leave.countDown();
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
            assertTrue(acknowledgement.isDone());
            gate.resume();
            assertTrue(gate.enter());
            gate.exit();
        } finally { leave.countDown(); gate.resume(); }
    }

    @Test
    void cancellingAWaitPreservesExistingReadersForTheNextBarrierAndDoesNotBlockThem() {
        var gate = new CarpetResourceReloadCoordinator.Gate();
        assertTrue(gate.enter());
        var abandoned = gate.pause();
        gate.resume(); // timeout/shutdown cancellation
        assertTrue(gate.enter());
        var next = gate.pause();
        gate.exit();
        assertFalse(next.isDone());
        gate.exit();
        assertTrue(next.isDone());
        assertFalse(abandoned.isDone()); // old epoch cannot acknowledge the new generation
        gate.resume();
        for (int i = 0; i < 100; ++i) {
            assertTrue(gate.enter());
            var ready = gate.pause();
            gate.exit();
            assertTrue(ready.isDone());
            gate.resume();
        }
    }
}
