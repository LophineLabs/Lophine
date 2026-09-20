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

package org.leavesmc.leaves.protocol.jade.provider.block;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.BlockAccessor;
import org.leavesmc.leaves.protocol.jade.provider.ItemStorageProvider;
import org.leavesmc.leaves.protocol.jade.provider.StreamServerDataProvider;

import static net.minecraft.world.level.block.CampfireBlock.FACING;

public enum ChiseledBookshelfProvider implements StreamServerDataProvider<BlockAccessor, ItemStack> {
    INSTANCE;

    private static final Identifier MC_CHISELED_BOOKSHELF = JadeProtocol.mc_id("shelf");

    @Override
    public @Nullable ItemStack streamData(@NotNull BlockAccessor accessor) {
        int slot = ((ChiseledBookShelfBlock) accessor.getBlock()).getHitSlot(accessor.getHitResult(), accessor.getBlockState().getValue(FACING)).orElse(-1);
        if (slot == -1) {
            return null;
        }
        return ((ChiseledBookShelfBlockEntity) accessor.getBlockEntity()).getItem(slot);
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, ItemStack> streamCodec() {
        return ItemStack.OPTIONAL_STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return MC_CHISELED_BOOKSHELF;
    }

    @Override
    public int getDefaultPriority() {
        return ItemStorageProvider.getBlock().getDefaultPriority() + 1;
    }
}
