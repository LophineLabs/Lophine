/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.util;

import com.google.common.cache.LoadingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.block.state.pattern.BlockPattern;

// Powered by Carpet-AMS-Addition(https://github.com/Minecraft-AMS/Carpet-AMS-Addition)
public class BlockPatternHelper {
    public static BlockPattern.BlockPatternMatch partialSearchAround(BlockPattern pattern, Level world, BlockPos pos) {
        LoadingCache<BlockPos, BlockInWorld> loadingCache = BlockPattern.createLevelCache(world, false);
        int i = Math.max(Math.max(pattern.getWidth(), pattern.getHeight()), pattern.getDepth());

        for (BlockPos blockPos : BlockPos.betweenClosed(pos, pos.offset(i - 1, 0, i - 1))) {
            for (Direction direction : Direction.values()) {
                for (Direction direction2 : Direction.values()) {
                    BlockPattern.BlockPatternMatch result;
                    if (direction2 == direction || direction2 == direction.getOpposite() || (result = pattern.matches(blockPos, direction, direction2, loadingCache)) == null) {
                        continue;
                    }
                    return result;
                }
            }
        }
        return null;
    }
}
