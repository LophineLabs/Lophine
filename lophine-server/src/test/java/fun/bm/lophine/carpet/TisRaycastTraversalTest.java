package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TisRaycastTraversalTest {
    @Test
    void streamingWalkerMatchesVanillaEpsilonTieBreakingAndNegativeCoordinates() throws Exception {
        verify(new Vec3(0, 0, 0), new Vec3(32, 0, 0));
        verify(new Vec3(16, -3, -16), new Vec3(-16, 29, 16));
        verify(new Vec3(0.5, 0.5, 0.5), new Vec3(-0.5, -0.5, -0.5));
        verify(new Vec3(-32, 4, 0), new Vec3(-32, -4, 0));
        verify(new Vec3(2, 2, 2), new Vec3(2, 2, 2));
        Random random = new Random(0xCA4FE7L);
        for (int i = 0; i < 200; ++i) {
            verify(new Vec3(random.nextDouble() * 128 - 64, random.nextDouble() * 32 - 16, random.nextDouble() * 128 - 64),
                    new Vec3(random.nextDouble() * 128 - 64, random.nextDouble() * 32 - 16, random.nextDouble() * 128 - 64));
        }
    }

    private static void verify(final Vec3 from, final Vec3 to) throws Exception {
        List<BlockPos> expected = new ArrayList<>();
        BlockGetter.traverseBlocks(from, to, expected, (positions, pos) -> {
            positions.add(pos.immutable());
            return null;
        }, ignored -> null);
        Class<?> type = Class.forName("fun.bm.lophine.carpet.TisRaycastCommand$RayWalker");
        Constructor<?> constructor = type.getDeclaredConstructor(Vec3.class, Vec3.class);
        constructor.setAccessible(true);
        Object walker = constructor.newInstance(from, to);
        Field position = type.getDeclaredField("pos");
        position.setAccessible(true);
        Method advance = type.getDeclaredMethod("advance");
        advance.setAccessible(true);
        List<BlockPos> actual = new ArrayList<>();
        while (position.get(walker) != null) {
            actual.add((BlockPos) position.get(walker));
            advance.invoke(walker);
            if (actual.size() > expected.size() + 1)
                throw new AssertionError("Walker did not stop at vanilla endpoint");
        }
        assertEquals(expected, actual, () -> from + " -> " + to);
    }
}
