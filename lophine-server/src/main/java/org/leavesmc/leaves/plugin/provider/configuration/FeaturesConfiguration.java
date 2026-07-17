// This file is licensed under the MIT license.
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

package org.leavesmc.leaves.plugin.provider.configuration;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.List;

@SuppressWarnings("FieldMayBeFinal")
@ConfigSerializable
public class FeaturesConfiguration {
    private List<String> required = List.of();
    private List<String> optional = List.of();

    public List<String> getRequired() {
        return required;
    }

    public List<String> getOptional() {
        return optional;
    }
}