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

package org.leavesmc.leaves.protocol.jade.provider.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.EntityAccessor;
import org.leavesmc.leaves.protocol.jade.provider.ServerDataProvider;

public enum NextEntityDropProvider implements ServerDataProvider<EntityAccessor> {
    INSTANCE;

    private static final Identifier MC_NEXT_ENTITY_DROP = JadeProtocol.mc_id("next_entity_drop");

    @Override
    public void appendServerData(CompoundTag tag, @NotNull EntityAccessor accessor) {
        int max = 24000 * 2;
        if (accessor.getEntity() instanceof Chicken chicken) {
            if (!chicken.isBaby() && chicken.eggTime < max) {
                tag.putInt("NextEggIn", chicken.eggTime);
            }
        } else if (accessor.getEntity() instanceof Armadillo armadillo) {
            if (!armadillo.isBaby() && armadillo.scuteTime < max) {
                tag.putInt("NextScuteIn", armadillo.scuteTime);
            }
        } else if (accessor.getEntity() instanceof Sniffer sniffer) {
            long time = sniffer.getBrain().getTimeUntilExpiry(MemoryModuleType.SNIFF_COOLDOWN);
            if (time > 0 && time < max) {
                tag.putInt("NextSniffIn", (int) time);
            }
        }
    }

    @Override
    public Identifier getUid() {
        return MC_NEXT_ENTITY_DROP;
    }
}
