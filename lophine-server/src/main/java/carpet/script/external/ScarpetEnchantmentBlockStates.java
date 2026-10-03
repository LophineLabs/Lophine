// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.CombiningPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.NotPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.VolumeMatchPredicate;
import net.minecraft.world.level.levelgen.feature.stateproviders.*;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Spatial provider branches use the original world; random branches use the original affected entity's actual RNG.
 */
public final class ScarpetEnchantmentBlockStates {
    private ScarpetEnchantmentBlockStates() {
    }

    public static CompletableFuture<BlockState> state(BlockStateProvider provider, ServerLevel world, Entity randomOwner, BlockPos position, boolean optional) {
        if (provider instanceof RuleBasedStateProvider rule) return rules(rule, world, randomOwner, position, 0)
                .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value != null || optional ? CompletableFuture.completedFuture(value)
                        : ScarpetEnchantmentEntityEffects.world(world, position, 16, () -> world.getBlockState(position))));
        if (provider instanceof CopyPropertiesProvider copy)
            return state(copy.source().value(), world, randomOwner, position, false)
                    .thenCompose(ScarpetRuntime.captureNativeFunction(value -> ScarpetEnchantmentEntityEffects.world(world, position, 16, () -> value.withPropertiesOf(world.getBlockState(position)))));
        if (provider instanceof RandomizedIntStateProvider randomized)
            return state(randomized.carpetSource(), world, randomOwner, position, false)
                    .thenCompose(ScarpetRuntime.captureNativeFunction(value -> ScarpetLootConditions.actor(randomOwner, () -> randomized.carpetApplyValue(value, randomOwner.getRandom()))));
        if (provider instanceof RandomBlockProvider random)
            return ScarpetLootConditions.actor(randomOwner, () -> random.getOptionalState(world, randomOwner.getRandom(), position))
                    .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value != null || optional ? CompletableFuture.completedFuture(value)
                            : ScarpetEnchantmentEntityEffects.world(world, position, 16, () -> world.getBlockState(position))));
        // Other registered providers are immutable state/noise/weighted/rotated expressions, with no original-world block reads.
        return ScarpetLootConditions.actor(randomOwner, () -> optional ? provider.getOptionalState(world, randomOwner.getRandom(), position)
                : provider.getState(world, randomOwner.getRandom(), position));
    }

    private static CompletableFuture<BlockState> rules(RuleBasedStateProvider provider, ServerLevel world, Entity randomOwner, BlockPos position, int index) {
        if (index == provider.rules().size())
            return provider.fallback() == null ? CompletableFuture.completedFuture(null)
                    : state(provider.fallback().value(), world, randomOwner, position, true);
        var rule = provider.rules().get(index);
        Supplier<CompletableFuture<BlockState>> next = ScarpetRuntime.captureNativeContinuation(() -> rules(provider, world, randomOwner, position, index + 1));
        return predicate(rule.ifTrue(), world, position).thenCompose(ScarpetRuntime.captureNativeFunction(allowed -> !allowed ? next.get()
                : state(rule.then().value(), world, randomOwner, position, true).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value == null ? next.get() : CompletableFuture.completedFuture(value)))));
    }

    public static CompletableFuture<Boolean> predicate(BlockPredicate predicate, ServerLevel world, BlockPos position) {
        return ScarpetEnchantmentEntityEffects.world(world, position, padding(predicate), () -> predicate.test(world, position));
    }

    private static int padding(BlockPredicate predicate) {
        if (predicate instanceof VolumeMatchPredicate volume) {
            int radius = Math.max(Math.max(Math.abs(volume.min().getX()), Math.abs(volume.max().getX())), Math.max(Math.abs(volume.min().getZ()), Math.abs(volume.max().getZ())));
            return Math.addExact(radius, padding(volume.match()));
        }
        if (predicate instanceof CombiningPredicate combined) {
            int radius = 32;
            for (var child : combined.carpetPredicates()) radius = Math.max(radius, padding(child));
            return radius;
        }
        if (predicate instanceof NotPredicate inverse) return padding(inverse.carpetPredicate());
        return 32; // All native leaf offsets are bounded at 16; this includes support/biome/collision neighbor probes.
    }
}
