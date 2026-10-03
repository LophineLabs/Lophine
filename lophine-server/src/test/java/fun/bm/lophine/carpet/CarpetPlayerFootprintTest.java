package fun.bm.lophine.carpet;

import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CarpetPlayerFootprintTest {
    @Test
    void footprintIncludesNativeEntityLookupPaddingAcrossPositiveAndNegativeChunkSeams() {
        assertEquals(new CarpetPlayerTargetArea.Bounds(-1, -1, 1, 1),
            CarpetPlayerTargetArea.Bounds.query(new AABB(1, 0, 1, 15, 2, 15)));
        assertEquals(new CarpetPlayerTargetArea.Bounds(-2, -2, -1, -1),
            CarpetPlayerTargetArea.Bounds.query(new AABB(-16.2, 0, -16.2, -12, 2, -12)));
        var box = new AABB(15.7, 64, -0.3, 16.3, 65.8, 0.3).expandTowards(5, 0, 0).inflate(1);
        var bounds = CarpetPlayerTargetArea.Bounds.query(box);
        assertEquals(0, bounds.minX()); assertEquals(1, bounds.maxX());
        assertEquals(-1, bounds.minZ()); assertEquals(0, bounds.maxZ());
    }

    @Test
    void statusBlockSelectionRetainsTheExactOriginalSphereAndUniqueImmutablePositions() {
        BlockPos origin = new BlockPos(16, 64, -16);
        var one = OrgPlayerStatusSync.positions(origin, 1);
        assertEquals(7, one.size());
        assertEquals(33, OrgPlayerStatusSync.positions(origin, 2).size());
        var eight = OrgPlayerStatusSync.positions(origin, 8);
        assertEquals(2109, eight.size());
        assertEquals(eight.size(), new HashSet<>(eight).size());
        for (var pos : eight) assertTrue(origin.distSqr(pos) <= 64);
        assertTrue(eight.contains(new BlockPos(8, 64, -16)));
        assertTrue(eight.contains(new BlockPos(24, 64, -16)));
    }
}
