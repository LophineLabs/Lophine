// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Native alliance short circuits use actual owners, including chained pet/Vex owners and native team getters.
 */
public final class ScarpetEntityAllies {
    private ScarpetEntityAllies() {
    }

    public static CompletableFuture<Boolean> allied(Entity self, Entity other) {
        if (other == null) return CompletableFuture.completedFuture(false);
        if (self == other) return CompletableFuture.completedFuture(true);
        Supplier<CompletableFuture<Boolean>> reverse = ScarpetRuntime.captureNativeContinuation(() -> considers(other, self));
        return considers(self, other).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value ? CompletableFuture.completedFuture(true) : reverse.get()));
    }

    private static CompletableFuture<Boolean> base(Entity self, Entity other) {
        return ScarpetNativeDeathActors.entity(other, other::getTeam).thenCompose(ScarpetRuntime.captureNativeFunction(team ->
                ScarpetNativeDeathActors.entity(self, () -> self.isAlliedTo(team))));
    }

    private static CompletableFuture<Boolean> illager(Entity self, Entity other) {
        return base(self, other).thenCompose(ScarpetRuntime.captureNativeFunction(passed -> {
            if (passed) return CompletableFuture.completedFuture(true);
            return ScarpetNativeDeathActors.entity(other, () -> other.is(net.minecraft.tags.EntityTypeTags.ILLAGER_FRIENDS)).thenCompose(ScarpetRuntime.captureNativeFunction(friend -> {
                if (!friend) return CompletableFuture.completedFuture(false);
                return ScarpetNativeDeathActors.entity(self, () -> self.getTeam() == null).thenCompose(ScarpetRuntime.captureNativeFunction(noTeam ->
                        noTeam ? ScarpetNativeDeathActors.entity(other, () -> other.getTeam() == null) : CompletableFuture.completedFuture(false)));
            }));
        }));
    }

    private static CompletableFuture<Boolean> considers(Entity self, Entity other) {
        if (self instanceof TamableAnimal animal) {
            return ScarpetNativeDeathActors.entity(animal, animal::isTame).thenCompose(ScarpetRuntime.captureNativeFunction(tame -> {
                if (!tame) return base(self, other);
                return rootOwner(animal).thenCompose(ScarpetRuntime.captureNativeFunction(owner -> {
                    if (other == owner) return CompletableFuture.completedFuture(true);
                    return owner == null ? base(self, other) : considers(owner, other);
                }));
            }));
        }
        if (self instanceof net.minecraft.world.entity.animal.allay.Allay allay && other instanceof net.minecraft.world.entity.player.Player) {
            return ScarpetNativeDeathActors.entity(other, other::getUUID).thenCompose(ScarpetRuntime.captureNativeFunction(id ->
                            ScarpetNativeDeathActors.entity(allay, () -> allay.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LIKED_PLAYER).map(id::equals).orElse(false))))
                    .thenCompose(ScarpetRuntime.captureNativeFunction(liked -> liked ? CompletableFuture.completedFuture(true) : base(self, other)));
        }
        if (self instanceof net.minecraft.world.entity.monster.illager.Evoker) {
            if (self == other) return CompletableFuture.completedFuture(true);
            return illager(self, other).thenCompose(ScarpetRuntime.captureNativeFunction(passed -> {
                if (passed || !(other instanceof net.minecraft.world.entity.monster.Vex vex))
                    return CompletableFuture.completedFuture(passed);
                return rootOwner(vex).thenCompose(ScarpetRuntime.captureNativeFunction(owner -> owner == null ? CompletableFuture.completedFuture(false) : owner == self ? CompletableFuture.completedFuture(true) : illager(self, owner)));
            }));
        }
        if (self instanceof net.minecraft.world.entity.monster.illager.AbstractIllager) return illager(self, other);
        return base(self, other);
    }

    private static CompletableFuture<LivingEntity> owner(LivingEntity actual) {
        if (!(actual instanceof OwnableEntity ownable)) return CompletableFuture.completedFuture(null);
        return ScarpetNativeDeathActors.entity(actual, () -> ScarpetNativeDeathActors.resolve(actual, ownable.getOwnerReference(), LivingEntity.class))
                .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
    }

    private static CompletableFuture<LivingEntity> rootOwner(LivingEntity actual) {
        Set<Object> seen = new it.unimi.dsi.fastutil.objects.ObjectArraySet<>();
        seen.add(actual);
        return owner(actual).thenCompose(ScarpetRuntime.captureNativeFunction(value -> rootOwner(value, seen)));
    }

    private static CompletableFuture<LivingEntity> rootOwner(LivingEntity current, Set<Object> seen) {
        if (!(current instanceof OwnableEntity)) return CompletableFuture.completedFuture(current);
        return owner(current).thenCompose(ScarpetRuntime.captureNativeFunction(first -> {
            if (seen.contains(first)) return CompletableFuture.completedFuture(null);
            seen.add(current);
            // Native getRootOwner reads this reference twice per hop; the second actual read remains distinct.
            return owner(current).thenCompose(ScarpetRuntime.captureNativeFunction(second -> rootOwner(second, seen)));
        }));
    }
}
