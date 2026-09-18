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

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.*;
import net.minecraft.core.IdMapper;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.provider.JadeProvider;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

public class HierarchyLookup<T extends JadeProvider> implements IHierarchyLookup<T> {
    private final Class<?> baseClass;
    private final Cache<Class<?>, List<T>> resultCache = CacheBuilder.newBuilder().build();
    private final boolean singleton;
    protected boolean idMapped;
    @Nullable
    protected IdMapper<T> idMapper;
    private ListMultimap<Class<?>, T> objects = ArrayListMultimap.create();

    public HierarchyLookup(Class<?> baseClass) {
        this(baseClass, false);
    }

    public HierarchyLookup(Class<?> baseClass, boolean singleton) {
        this.baseClass = baseClass;
        this.singleton = singleton;
    }

    @Override
    public void idMapped() {
        this.idMapped = true;
    }

    @Override
    @Nullable
    public IdMapper<T> idMapper() {
        return idMapper;
    }

    @Override
    public void register(Class<?> clazz, T provider) {
        Preconditions.checkArgument(isClassAcceptable(clazz), "Class %s is not acceptable", clazz);
        Objects.requireNonNull(provider.getUid());
        JadeProtocol.priorities.put(provider);
        objects.put(clazz, provider);
    }

    @Override
    public boolean isClassAcceptable(Class<?> clazz) {
        return baseClass.isAssignableFrom(clazz);
    }

    @Override
    public List<T> get(Class<?> clazz) {
        try {
            return resultCache.get(clazz, () -> {
                List<T> list = Lists.newArrayList();
                getInternal(clazz, list);
                list = ImmutableList.sortedCopyOf(COMPARATOR, list);
                if (singleton && !list.isEmpty()) {
                    return ImmutableList.of(list.getFirst());
                }
                return list;
            });
        } catch (ExecutionException e) {
            JadeProtocol.LOGGER.warn("HierarchyLookup error", e);
        }
        return List.of();
    }

    private void getInternal(Class<?> clazz, List<T> list) {
        if (clazz != baseClass && clazz != Object.class) {
            getInternal(clazz.getSuperclass(), list);
        }
        list.addAll(objects.get(clazz));
    }

    @Override
    public boolean isEmpty() {
        return objects.isEmpty();
    }

    @Override
    public Stream<Map.Entry<Class<?>, Collection<T>>> entries() {
        return objects.asMap().entrySet().stream();
    }

    @Override
    public void invalidate() {
        resultCache.invalidateAll();
    }

    @Override
    public void loadComplete(PriorityStore<Identifier, JadeProvider> priorityStore) {
        objects.asMap().forEach((clazz, list) -> {
            if (list.size() < 2) {
                return;
            }
            Set<Identifier> set = Sets.newHashSetWithExpectedSize(list.size());
            for (T provider : list) {
                if (set.contains(provider.getUid())) {
                    throw new IllegalStateException("Duplicate UID: %s for %s".formatted(provider.getUid(), list.stream()
                            .filter(p -> p.getUid().equals(provider.getUid()))
                            .map(p -> p.getClass().getName())
                            .toList()
                    ));
                }
                set.add(provider.getUid());
            }
        });

        objects = ImmutableListMultimap.<Class<?>, T>builder()
                .orderValuesBy(Comparator.comparingInt(priorityStore::byValue))
                .putAll(objects)
                .build();

        if (idMapped) {
            idMapper = createIdMapper();
        }
    }
}