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
import org.spongepowered.configurate.objectmapping.meta.PostProcess;

import java.util.List;

@SuppressWarnings({"FieldMayBeFinal", "unused"})
@ConfigSerializable
public class MixinConfiguration {
    private String packageName;
    private List<String> mixins = List.of();
    private String accessWidener;

    @PostProcess
    public void postProcess() {
        if (mixins.isEmpty()) {
            return;
        }
        if (packageName == null) {
            throw new IllegalStateException("Already define mixins: " + mixins + ", but no mixin package-name provided");
        }
    }

    public List<String> getMixins() {
        return mixins;
    }

    public String getAccessWidener() {
        return accessWidener;
    }

    public String getPackageName() {
        return packageName;
    }
}
