// This file is licensed under the MIT license.
package org.leavesmc.leaves.plugin;

import java.util.HashSet;
import java.util.Set;

import static org.leavesmc.leaves.plugin.Features.*;

public class ServerFeatureManager implements FeatureManager {
    public static ServerFeatureManager INSTANCE = new ServerFeatureManager();
    private final Set<String> availableFeatures = new HashSet<>();

    private ServerFeatureManager() {
        availableFeatures.add(FAKEPLAYER); // Lophine - fakeplayer support
        availableFeatures.add(PHOTOGRAPHER); // Lophine - Replay Mod API support
        availableFeatures.add(UPDATE_SUPPRESSION_EVENT); // Lophine - update suppression event support
        if (Boolean.getBoolean("morninggloryclip.enable.mixin")) {
            availableFeatures.add(MIXIN);
        }
    }

    @Override
    public Set<String> getAvailableFeatures() {
        return availableFeatures;
    }

    @Override
    public boolean isFeatureAvailable(String feature) {
        return availableFeatures.contains(feature);
    }
}
