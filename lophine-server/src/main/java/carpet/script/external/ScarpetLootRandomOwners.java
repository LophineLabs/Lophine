// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.world.level.storage.loot.LootContext;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Loot ORIGIN and the native caller's RNG ownership are independent for cross-world damage contexts.
 */
public final class ScarpetLootRandomOwners {
    private static final Map<LootContext, ScarpetAttackEnchantments.SourceAdmission> OWNERS = Collections.synchronizedMap(new WeakHashMap<>());

    private ScarpetLootRandomOwners() {
    }

    public static void bind(LootContext context, ScarpetAttackEnchantments.SourceAdmission admission) {
        OWNERS.put(context, admission);
    }

    public static boolean bound(LootContext context) {
        return OWNERS.containsKey(context);
    }

    public static <T> CompletableFuture<T> consume(LootContext context, Supplier<T> body) {
        var admission = OWNERS.get(context);
        if (admission == null) return ScarpetLootActors.original(context, body);
        return admission.actualCaller() == null ? ScarpetLootActors.original(admission.world(), net.minecraft.world.phys.Vec3.atCenterOf(admission.callerPosition()), body)
                : ScarpetLootConditions.actor(admission.actualCaller(), body);
    }
}
