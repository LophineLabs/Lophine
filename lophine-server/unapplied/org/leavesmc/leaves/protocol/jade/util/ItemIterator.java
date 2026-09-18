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

package org.leavesmc.leaves.protocol.jade.util;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public abstract class ItemIterator<T> {
    public static final AtomicLong version = new AtomicLong();
    protected final Function<Object, @Nullable T> containerFinder;
    protected final int fromIndex;
    protected boolean finished;
    protected int currentIndex;
    protected float progress;

    protected ItemIterator(Function<Object, @Nullable T> containerFinder, int fromIndex) {
        this.containerFinder = containerFinder;
        this.currentIndex = this.fromIndex = fromIndex;
    }

    public @Nullable T find(Object target) {
        return containerFinder.apply(target);
    }

    public final boolean isFinished() {
        return finished;
    }

    public long getVersion(T container) {
        return version.getAndIncrement();
    }

    public abstract Stream<ItemStack> populate(T container);

    protected abstract int getSlotCount(T container);

    public void reset() {
        currentIndex = fromIndex;
        finished = false;
    }

    public void afterPopulate(T container, int count) {
        currentIndex += count;
        if (count == 0 || currentIndex >= 10000) {
            finished = true;
        }
        progress = (float) (currentIndex - fromIndex) / (getSlotCount(container) - fromIndex);
    }

    public float getCollectingProgress() {
        return Float.NaN;
    }

    public static abstract class SlottedItemIterator<T> extends ItemIterator<T> {

        public SlottedItemIterator(Function<Object, @Nullable T> containerFinder, int fromIndex) {
            super(containerFinder, fromIndex);
        }

        protected abstract ItemStack getItemInSlot(T container, int slot);

        @Override
        public Stream<ItemStack> populate(T container) {
            int slotCount = getSlotCount(container);
            int toIndex = currentIndex + ItemCollector.MAX_SIZE * 2;
            if (toIndex >= slotCount) {
                toIndex = slotCount;
                finished = true;
            }
            return IntStream.range(currentIndex, toIndex).mapToObj(slot -> getItemInSlot(container, slot));
        }

        @Override
        public float getCollectingProgress() {
            return progress;
        }
    }

    public static class ContainerItemIterator extends SlottedItemIterator<Container> {
        public ContainerItemIterator(int fromIndex) {
            this(Container.class::cast, fromIndex);
        }

        public ContainerItemIterator(Function<Object, @Nullable Container> containerFinder, int fromIndex) {
            super(containerFinder, fromIndex);
        }

        @Override
        protected int getSlotCount(Container container) {
            return container.getContainerSize();
        }

        @Override
        protected ItemStack getItemInSlot(Container container, int slot) {
            return container.getItem(slot);
        }
    }
}
