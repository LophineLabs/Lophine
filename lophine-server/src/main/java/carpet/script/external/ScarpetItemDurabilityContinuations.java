// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.advancements.triggers.CriteriaTriggers;

/** The original item remains owned throughout listener matching, rewards, damage and the on-break tail. */
public final class ScarpetItemDurabilityContinuations {
    private ScarpetItemDurabilityContinuations() { }
    public static CompletableFuture<Void> apply(ServerPlayer player, ItemStack original, int newDamage, Runnable originalTail) {
        return ScarpetLootActors.jobNative(player.level(), () ->
            CriteriaTriggers.ITEM_DURABILITY_CHANGED.carpetTriggerNativeAsync(player, instance ->
                ScarpetLootConditions.actor(player, () -> instance.matches(original, newDamage)))
            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetExplosionActors.admitTarget(player, () ->
                ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(player, () -> { originalTail.run(); return (Void)null; }))))));
    }
}
