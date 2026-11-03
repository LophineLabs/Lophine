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

package org.leavesmc.leaves.bot.agent.actions;

import net.minecraft.world.item.Item;

public abstract class AbstractStateBotAction<E extends AbstractStateBotAction<E>> extends AbstractBotAction<E> {
    public AbstractStateBotAction(String name) {
        super(name);
        this.setDoNumber(-1);
    }

    public AbstractStateBotAction(String name, String guiTitle, String guiDescription, Item guiItem) {
        super(name, guiTitle, guiDescription, guiItem);
        this.setDoNumber(-1);
    }

    public AbstractStateBotAction(String name, String guiTitle, String guiDescription, Item guiItem, boolean guiConfirmable) {
        super(name, guiTitle, guiDescription, guiItem, guiConfirmable);
        this.setDoNumber(-1);
    }
}
