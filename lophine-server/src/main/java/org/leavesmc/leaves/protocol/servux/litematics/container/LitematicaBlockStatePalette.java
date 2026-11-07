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

package org.leavesmc.leaves.protocol.servux.litematics.container;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.leavesmc.leaves.protocol.servux.litematics.utils.NbtUtils;

import javax.annotation.Nullable;

public interface LitematicaBlockStatePalette {
    /**
     * Gets the palette id for the given block state and adds
     * the state to the palette if it doesn't exist there yet.
     */
    int idFor(BlockState state);

    /**
     * Gets the block state by the palette id.
     */
    @Nullable
    BlockState getBlockState(int indexKey);

    void requestNewId(BlockState state);

    default void readFromNBT(ListTag tagList) {
        Registry<Block> lookup = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BLOCK);

        final int size = tagList.size();

        for (int i = 0; i < size; ++i) {
            final int paletteIndex = i;
            CompoundTag tag = tagList.getCompound(i)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid block state palette entry at index " + paletteIndex));
            BlockState state = NbtUtils.readBlockState(lookup, tag);

            if (i > 0 || state != LitematicaBlockStateContainer.AIR_BLOCK_STATE) {
                this.requestNewId(state);
            }
        }
    }

    ListTag writeToNBT();
}
