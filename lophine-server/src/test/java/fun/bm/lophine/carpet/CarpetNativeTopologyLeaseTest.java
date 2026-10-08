package fun.bm.lophine.carpet;

import io.papermc.paper.threadedregions.ThreadedRegionizer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real Native topology and occupancy bits; world ticking callbacks are supplied by the fixture.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class CarpetNativeTopologyLeaseTest {
    private ThreadedRegionizer topology() {
        var callbacks = mock(ThreadedRegionizer.RegionCallbacks.class);
        when(callbacks.createNewData(any())).thenAnswer(call -> mock(ThreadedRegionizer.ThreadedRegionData.class));
        return new ThreadedRegionizer(2, 0.5, 1, 1, 3, mock(ServerLevel.class), callbacks);
    }

    @Test
    void aColdGapUsesRealTopologyMembershipWithoutInventingAnyLoadedChunk() {
        var topology = topology();
        topology.addChunk(0, 0);
        topology.addChunk(64, 0);
        assertNotSame(topology.getRegionAtSynchronised(0, 0), topology.getRegionAtSynchronised(64, 0));
        for (int x = 0; x <= 64; x += 8) topology.carpetPinSection(x, 0);
        var owner = topology.getRegionAtSynchronised(0, 0);
        assertEquals(2, owner.getOwnedChunks().size());
        for (int x = 0; x <= 64; x += 8) assertSame(owner, topology.getRegionAtSynchronised(x, 0));
        for (int x = 0; x <= 64; x += 8) topology.carpetUnpinSection(x, 0);
        assertEquals(2, owner.getOwnedChunks().size());
    }

    @Test
    void overlappingPinsDoNotStealPhysicalBitsAcrossRepeatedNativeLoadUnload() {
        var topology = topology();
        for (int epoch = 0; epoch < 500; epoch++) {
            topology.carpetPinSection(0, 0);
            topology.carpetPinSection(7, 7);
            var owner = topology.getRegionAtSynchronised(0, 0);
            assertTrue(owner.getOwnedChunks().isEmpty());
            topology.addChunk(0, 0);
            topology.addChunk(7, 7);
            assertEquals(2, owner.getOwnedChunks().size());
            topology.carpetUnpinSection(0, 0);
            topology.removeChunk(0, 0);
            assertEquals(1, owner.getOwnedChunks().size());
            topology.removeChunk(7, 7);
            assertTrue(owner.getOwnedChunks().isEmpty());
            assertSame(owner, topology.getRegionAtSynchronised(7, 7));
            topology.carpetUnpinSection(7, 7);
            assertTrue(owner.tryMarkTicking(() -> false));
            owner.markNotTicking();
        }
    }
}
