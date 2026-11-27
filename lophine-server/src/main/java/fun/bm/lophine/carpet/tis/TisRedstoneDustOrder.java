// SPDX-License-Identifier: LGPL-3.0-or-later
// Adapted from Carpet TIS Addition 87ea33e9564767afa395d51e0486bd6152673c11.
package fun.bm.lophine.carpet.tis;

import com.google.common.collect.Sets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class TisRedstoneDustOrder {
    private static final int WORLD_LIMIT = 30_000_000;

    private TisRedstoneDustOrder() {
    }

    public static Collection<BlockPos> order(Set<BlockPos> updates, Level level, BlockPos origin, String mode) {
        if (mode.equals("false") || mode.equals("none")) {
            return updates;
        }
        var random = ThreadLocalRandom.current();
        var ordered = new ArrayList<>(updates);
        if (mode.equals("random_pos")) {
            BlockPos sample = new BlockPos(random.nextInt(-WORLD_LIMIT, WORLD_LIMIT),
                    level.getMinY() + random.nextInt(level.getHeight()), random.nextInt(-WORLD_LIMIT, WORLD_LIMIT));
            Set<BlockPos> sampleUpdates = Sets.newHashSet();
            sampleUpdates.add(sample);
            for (Direction direction : Direction.values()) {
                sampleUpdates.add(sample.relative(direction));
            }
            int targetIndex = 0;
            for (BlockPos sampleUpdate : sampleUpdates) {
                BlockPos target = origin.offset(sampleUpdate.subtract(sample));
                int sourceIndex = ordered.indexOf(target);
                if (sourceIndex >= 0) {
                    Collections.swap(ordered, targetIndex++, sourceIndex);
                }
            }
        } else {
            Collections.shuffle(ordered, random);
        }
        return ordered;
    }
}
