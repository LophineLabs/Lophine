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

package org.leavesmc.leaves.protocol.servux.litematics.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NbtUtils {

    public static BlockState readBlockState(HolderGetter<Block> blocks, CompoundTag tag) {
        if (tag.contains("Name")) {
            // Litematica keeps its legacy palette schema independently of the vanilla NBT schema.
            CompoundTag nativeTag = tag.copy();
            nativeTag.put("id", tag.get("Name"));
            tag.getCompound("Properties").ifPresent(properties -> nativeTag.put("properties", properties));
            return net.minecraft.nbt.NbtUtils.readBlockState(blocks, nativeTag);
        }
        return net.minecraft.nbt.NbtUtils.readBlockState(blocks, tag);
    }

    public static CompoundTag writeBlockState(BlockState state) {
        CompoundTag nativeTag = net.minecraft.nbt.NbtUtils.writeBlockState(state);
        CompoundTag tag = new CompoundTag();
        tag.put("Name", nativeTag.get("id"));
        nativeTag.getCompound("properties").ifPresent(properties -> tag.put("Properties", properties));
        return tag;
    }

    public static void writeBlockPosToTag(Vec3i pos, CompoundTag tag) {
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
    }

    @Nullable
    public static BlockPos readBlockPos(@Nullable CompoundTag tag) {
        if (tag != null && tag.contains("x") && tag.contains("y") && tag.contains("z")) {
            return new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
        }

        return null;
    }

    public static void writeEntityPositionToTag(Vec3 pos, CompoundTag tag) {
        ListTag posList = new ListTag();

        posList.add(DoubleTag.valueOf(pos.x));
        posList.add(DoubleTag.valueOf(pos.y));
        posList.add(DoubleTag.valueOf(pos.z));
        tag.put("Pos", posList);
    }

    @Nullable
    public static Vec3 readVec3(@Nullable CompoundTag tag) {
        if (tag != null && tag.contains("dx") && tag.contains("dy") && tag.contains("dz")) {
            return new Vec3(tag.getDoubleOr("dx", 0.0), tag.getDoubleOr("dy", 0.0), tag.getDoubleOr("dz", 0.0));
        }
        return null;
    }

    @Nullable
    public static Vec3 readEntityPositionFromTag(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains("Pos")) {
            return null;
        }
        ListTag tagList = tag.getListOrEmpty("Pos");
        if (tagList.size() != 3) {
            return null;
        }
        return new Vec3(tagList.getDoubleOr(0, 0.0), tagList.getDoubleOr(1, 0.0), tagList.getDoubleOr(2, 0.0));
    }

    @Nullable
    public static Vec3i readVec3iFromTag(@Nullable CompoundTag tag) {
        if (tag != null && tag.contains("x") && tag.contains("y") && tag.contains("z")) {
            return new Vec3i(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
        }
        return null;
    }

    public static BlockPos readBlockPosFromArrayTag(@NotNull CompoundTag tag, String tagName) {
        if (tag.contains(tagName)) {
            int[] pos = tag.getIntArray(tagName).orElse(new int[0]);
            if (pos.length == 3) {
                return new BlockPos(pos[0], pos[1], pos[2]);
            }
        }
        return null;
    }
}
