/*
 * This file is part of Lophine.
 *
 * It implements the Jade 26.2 shelf data contract using the Minecraft server APIs.
 */

package org.leavesmc.leaves.protocol.jade.provider.block;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SelectableSlotContainer;
import net.minecraft.world.level.block.entity.ListBackedContainer;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.BlockAccessor;
import org.leavesmc.leaves.protocol.jade.provider.ItemStorageProvider;
import org.leavesmc.leaves.protocol.jade.provider.StreamServerDataProvider;

/**
 * Supplies the item in the shelf slot the player is looking at.
 */
public enum ShelfProvider implements StreamServerDataProvider<BlockAccessor, ItemStack> {
    INSTANCE;

    private static final Identifier UID = JadeProtocol.mc_id("shelf");

    @Override
    public @Nullable ItemStack streamData(BlockAccessor accessor) {
        if (!(accessor.getBlock() instanceof SelectableSlotContainer selectable)
                || !(accessor.getBlockEntity() instanceof ListBackedContainer inventory)) {
            return null;
        }
        int slot = selectable.getHitSlot(accessor.getHitResult(), accessor.getHitResult().getDirection()).orElse(-1);
        if (slot < 0 || slot >= inventory.getContainerSize()) {
            return null;
        }
        ItemStack item = inventory.getItem(slot);
        return item.isEmpty() ? null : item;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, ItemStack> streamCodec() {
        return ItemStack.OPTIONAL_STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return UID;
    }

    @Override
    public int getDefaultPriority() {
        return ItemStorageProvider.getBlock().getDefaultPriority() + 1;
    }
}
