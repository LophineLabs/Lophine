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

package org.leavesmc.leaves.protocol.jade.tool;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class ShearsToolHandler {

    private static final ShearsToolHandler INSTANCE = new ShearsToolHandler();

    private final List<ItemStack> tools;

    public ShearsToolHandler() {
        this.tools = List.of(Items.SHEARS.getDefaultInstance());
    }

    public static ShearsToolHandler getInstance() {
        return INSTANCE;
    }

    public ItemStack test(BlockState state) {
        for (ItemStack toolItem : tools) {
            if (toolItem.isCorrectToolForDrops(state)) {
                return toolItem;
            }
            Tool tool = toolItem.get(DataComponents.TOOL);
            if (tool != null && tool.getMiningSpeed(state) > tool.defaultMiningSpeed()) {
                return toolItem;
            }
        }
        return ItemStack.EMPTY;
    }
}
