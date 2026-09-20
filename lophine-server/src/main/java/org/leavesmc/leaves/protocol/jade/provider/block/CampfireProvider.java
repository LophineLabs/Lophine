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

import com.google.common.collect.Lists;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.Accessor;
import org.leavesmc.leaves.protocol.jade.provider.ServerExtensionProvider;
import org.leavesmc.leaves.protocol.jade.util.ViewGroup;

import java.util.List;

public enum CampfireProvider implements ServerExtensionProvider<ItemStack> {
    INSTANCE;

    private static final Identifier MC_CAMPFIRE = JadeProtocol.mc_id("campfire");

    @Override
    public @Nullable @Unmodifiable List<ViewGroup<ItemStack>> getGroups(@NotNull Accessor<?> request) {
        if (request.getTarget() instanceof CampfireBlockEntity campfire) {
            List<ItemStack> list = Lists.newArrayList();
            for (int i = 0; i < campfire.cookingTime.length; i++) {
                ItemStack stack = campfire.getItems().get(i);
                if (stack.isEmpty()) {
                    continue;
                }
                stack = stack.copy();

                int time = campfire.cookingTime[i] - campfire.cookingProgress[i];
                CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).update(tag -> tag.putInt("jade:cooking", time));
                stack.set(DataComponents.CUSTOM_DATA, customData);

                list.add(stack);
            }
            return List.of(new ViewGroup<>(list));
        }
        return null;
    }

    @Override
    public Identifier getUid() {
        return MC_CAMPFIRE;
    }
}
