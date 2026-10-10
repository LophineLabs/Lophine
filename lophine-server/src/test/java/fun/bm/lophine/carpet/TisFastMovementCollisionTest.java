package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TisFastMovementCollisionTest {
    @Test
    void matchesNativeCollisionOrderAcrossSignsTiesAndOversizedShapes() {
        Random random = new Random(62_33_2026L);
        AABB entity = new AABB(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);
        for (int sample = 0; sample < 500; ++sample) {
            var obstacles = new ArrayList<AABB>();
            for (int i = 0; i < 100; ++i) {
                double x = random.nextInt(-16, 17), y = random.nextInt(-16, 17), z = random.nextInt(-16, 17);
                obstacles.add(new AABB(x, y, z, x + 1.0, y + (i % 7 == 0 ? 1.5 : 1.0), z + 1.0));
            }
            Vec3 move = sample % 5 == 0 ? new Vec3(15.0, -15.0, 15.0)
                    : new Vec3(random.nextDouble(-20, 20), random.nextDouble(-20, 20), random.nextDouble(-20, 20));
            Vec3 expected = CollisionUtil.performCollisions(move, entity, List.of(), obstacles);
            Vec3 actual = TisFastEntityMovement.collide(move, entity, List.of(), (search, voxels, boxes) ->
                    obstacles.stream().filter(search::intersects).forEach(boxes::add));
            assertEquals(expected.x, actual.x, 1.0E-12, "X sample " + sample);
            assertEquals(expected.y, actual.y, 1.0E-12, "Y sample " + sample);
            assertEquals(expected.z, actual.z, 1.0E-12, "Z sample " + sample);
        }
        assertTrue(TisFastEntityMovement.threshold(new Vec3(20, 20, 20)));
        assertFalse(TisFastEntityMovement.threshold(new Vec3(100, 0, 0)));
        assertFalse(TisFastEntityMovement.threshold(new Vec3(1, 1, 1)));
    }
}
