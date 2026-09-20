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

import com.google.common.collect.Streams;
import net.minecraft.core.IdMapper;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.provider.JadeProvider;

import java.util.*;
import java.util.stream.Stream;

public interface IHierarchyLookup<T extends JadeProvider> {

    Comparator<JadeProvider> COMPARATOR = Comparator.comparingInt(provider -> JadeProtocol.priorities.byValue(provider));

    default IHierarchyLookup<? extends T> cast() {
        return this;
    }

    void idMapped();

    @Nullable
    IdMapper<T> idMapper();

    default List<Identifier> mappedIds() {
        return Streams.stream(Objects.requireNonNull(idMapper()))
                .map(JadeProvider::getUid)
                .toList();
    }

    void register(Class<?> clazz, T provider);

    boolean isClassAcceptable(Class<?> clazz);

    default List<T> get(Object obj) {
        if (obj == null) {
            return List.of();
        }
        return get(obj.getClass());
    }

    List<T> get(Class<?> clazz);

    boolean isEmpty();

    Stream<Map.Entry<Class<?>, Collection<T>>> entries();

    void invalidate();

    void loadComplete(PriorityStore<Identifier, JadeProvider> priorityStore);

    default IdMapper<T> createIdMapper() {
        List<T> list = entries().flatMap(entry -> entry.getValue().stream()).toList();
        IdMapper<T> idMapper = idMapper();
        if (idMapper == null) {
            idMapper = new IdMapper<>(list.size());
        }
        for (T provider : list) {
            if (idMapper.getId(provider) == IdMapper.DEFAULT) {
                idMapper.add(provider);
            }
        }
        return idMapper;
    }
}

