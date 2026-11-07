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

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.BlockState;
import org.leavesmc.leaves.protocol.servux.litematics.utils.NbtUtils;

import javax.annotation.Nullable;

public class LitematicaBlockStatePaletteLinear implements LitematicaBlockStatePalette {

    private final BlockState[] states;
    private final LitematicaBlockStateContainer blockStateContainer;
    private final int bits;
    private int currentSize;

    public LitematicaBlockStatePaletteLinear(int bitsIn, LitematicaBlockStateContainer blockStateContainer) {
        this.states = new BlockState[1 << bitsIn];
        this.bits = bitsIn;
        this.blockStateContainer = blockStateContainer;
    }

    @Override
    public int idFor(BlockState state) {
        for (int i = 0; i < this.currentSize; ++i) {
            if (this.states[i] == state) {
                return i;
            }
        }

        final int size = this.currentSize;

        if (size < this.states.length) {
            this.states[size] = state;
            ++this.currentSize;
            return size;
        } else {
            return this.blockStateContainer.onResize(this.bits + 1, state);
        }
    }

    @Override
    @Nullable
    public BlockState getBlockState(int indexKey) {
        return indexKey >= 0 && indexKey < this.currentSize ? this.states[indexKey] : null;
    }

    public void requestNewId(BlockState state) {
        final int size = this.currentSize;

        if (size < this.states.length) {
            this.states[size] = state;
            ++this.currentSize;
        }
    }

    @Override
    public ListTag writeToNBT() {
        ListTag tagList = new ListTag();

        for (int id = 0; id < this.currentSize; ++id) {
            BlockState state = this.states[id];

            if (state == null) {
                state = LitematicaBlockStateContainer.AIR_BLOCK_STATE;
            }

            CompoundTag tag = NbtUtils.writeBlockState(state);
            tagList.add(tag);
        }

        return tagList;
    }
}
