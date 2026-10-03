// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.CarpetEventServer.Event;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Native entry points capture mutable arguments on their region actor.
 */
public final class ScarpetNativeEvents {
    private ScarpetNativeEvents() {
    }

    public static boolean startFallFlying(ServerPlayer player) {
        Event.PLAYER_DEPLOYS_ELYTRA.onPlayerEvent(player);
        return player.tryToStartFallFlying();
    }

    public static void releaseItem(ServerPlayer player) {
        InteractionHand hand = player.getUsedItemHand();
        ItemStack stack = player.getUseItem().copy();
        player.releaseUsingItem();
        Event.PLAYER_RELEASED_ITEM.onItemAction(player, hand, stack);
    }

    public static void entityAdded(Entity entity, boolean created) {
        Event spawned = Event.ENTITY_HANDLER.get(entity.getType());
        if (spawned != null && spawned.isNeeded()) spawned.onEntityAction(entity, created);
        Event loaded = Event.ENTITY_LOAD.get(entity.getType());
        if (loaded != null && loaded.isNeeded()) loaded.onEntityAction(entity, true);
    }
}
