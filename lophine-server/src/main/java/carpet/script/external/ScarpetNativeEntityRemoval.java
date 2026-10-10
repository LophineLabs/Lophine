// SPDX-License-Identifier: LGPL-3.0-or-later
package carpet.script.external;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Original RemoveEntityUtils filters all inputs, then removes the actual queue including appended dragon parts.
 */
public final class ScarpetNativeEntityRemoval {
    private ScarpetNativeEntityRemoval() {
    }

    public static CompletableFuture<Integer> removeAll(Collection<? extends Entity> selected) {
        List<? extends Entity> original = List.copyOf(selected);
        var queue = new ArrayDeque<Entity>();
        CompletableFuture<Void> prepared = CompletableFuture.completedFuture(null);
        for (Entity entity : original) {
            prepared = prepared.thenCompose(ScarpetRuntime.captureNativeFunction(ignored ->
                    ScarpetNativeDeathActors.entity(entity, () -> {
                        if (canRemove(entity)) queue.add(entity);
                        return (Void) null;
                    })));
        }
        var actual = prepared.thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> removeNext(queue, 0)));
        ScarpetNativeWork.record(actual);
        return actual;
    }

    public static boolean canRemove(Entity entity) {
        return !(entity instanceof Player) && EntitySelector.NO_SPECTATORS.test(entity);
    }

    private static CompletableFuture<Integer> removeNext(ArrayDeque<Entity> queue, int count) {
        if (queue.isEmpty()) return CompletableFuture.completedFuture(count);
        Entity entity = queue.remove();
        return ScarpetNativeDeathActors.entity(entity, () -> {
            entity.carpetCleanRemoval = true;
            if (entity instanceof EnderDragon dragon) java.util.Collections.addAll(queue, dragon.getSubEntities());
            entity.carpetDiscardCleanly();
            return count + 1;
        }).thenCompose(ScarpetRuntime.captureNativeFunction(next -> removeNext(queue, next)));
    }
}
