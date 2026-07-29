/*
 * This file is part of Kaiiju (https://github.com/KaiijuMC/Kaiiju)
 *
 * Kaiiju is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Kaiiju is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Kaiiju. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.kaiijumc.kaiiju;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;

public class KaiijuEntityThrottler {
    private static class TickInfo {
        int currentTick;
        int continueFrom;
        int toTick;
        int toRemove;
    }

    public static class EntityThrottlerReturn {
        public boolean skip;
        public boolean remove;
    }

    private final Object2ObjectOpenHashMap<KaiijuEntityLimits.EntityLimit, TickInfo> entityLimitTickInfoMap = new Object2ObjectOpenHashMap<>();

    public void tickLimiterStart() {
        for (TickInfo tickInfo : entityLimitTickInfoMap.values()) {
            tickInfo.currentTick = 0;
        }
    }

    public EntityThrottlerReturn tickLimiterShouldSkip(Entity entity) {
        EntityThrottlerReturn retVal = new EntityThrottlerReturn();
        if (entity.isRemoved()) return retVal;

        KaiijuEntityLimits.EntityLimit entityLimit = entity.getType().entityLimit;

        if (entityLimit != null) {
            TickInfo tickInfo = entityLimitTickInfoMap.computeIfAbsent(entityLimit, el -> {
                TickInfo newTickInfo = new TickInfo();
                newTickInfo.toTick = entityLimit.limit();
                return newTickInfo;
            });

            tickInfo.currentTick++;
            if (tickInfo.currentTick <= tickInfo.toRemove && entityLimit.removal() > 0) {
                retVal.skip = false;
                retVal.remove = true;
                return retVal;
            }

            if (tickInfo.currentTick < tickInfo.continueFrom) {
                retVal.skip = true;
                return retVal;
            }
            if (tickInfo.currentTick - tickInfo.continueFrom < tickInfo.toTick) {
                retVal.skip = false;
                return retVal;
            }
            retVal.skip = true;
            return retVal;
        } else {
            retVal.skip = false;
            return retVal;
        }
    }

    public void tickLimiterFinish(RegionizedWorldData regionizedWorldData) {
        for (var entry : entityLimitTickInfoMap.entrySet()) {
            KaiijuEntityLimits.EntityLimit entityLimit = entry.getKey();
            TickInfo tickInfo = entry.getValue();

            int additionals = 0;
            int nextContinueFrom = tickInfo.continueFrom + tickInfo.toTick;
            if (nextContinueFrom >= tickInfo.currentTick) {
                additionals = entityLimit.limit() - (tickInfo.currentTick - tickInfo.continueFrom);
                nextContinueFrom = 0;
            }
            tickInfo.continueFrom = nextContinueFrom;
            tickInfo.toTick = entityLimit.limit() + additionals;

            if (tickInfo.toRemove == 0 && tickInfo.currentTick > entityLimit.removal()) {
                tickInfo.toRemove = tickInfo.currentTick - entityLimit.removal();
            } else if (tickInfo.toRemove != 0) {
                tickInfo.toRemove = 0;
            }
        }
    }
}