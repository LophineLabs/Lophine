// SPDX-License-Identifier: LGPL-3.0-or-later
package carpet.script.external;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The original egg RNG and world stay at the source, while actual cloud predicates and radius run at its owner.
 */
public final class ScarpetRenewableDragonEgg {
    private ScarpetRenewableDragonEgg() {
    }

    private record Selection(AreaEffectCloud cloud, BlockPos target) {
    }

    public static CompletableFuture<Void> tick(BlockState state, ServerLevel original, BlockPos source, RandomSource random) {
        return ScarpetLootActors.jobNative(original, () -> ScarpetLootActors.original(original, Vec3.atCenterOf(source), () ->
                        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.renewableDragonEgg && random.nextInt(64) == 0)
                .thenCompose(ScarpetRuntime.captureNativeFunction(enabled -> {
                    if (!enabled) return CompletableFuture.<Void>completedFuture(null);
                    AABB box = AABB.encapsulatingFullBlocks(source, source.offset(1, 1, 1));
                    return ScarpetNativeAttackBodies.worldArea(original, box, null, () ->
                                    List.copyOf(original.getEntitiesOfClass(AreaEffectCloud.class, box, entity -> true)))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(all -> alive(all.iterator(), new ArrayList<>())))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(alive -> particles(alive.iterator(), new ArrayList<>())))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(clouds -> {
                                if (clouds.isEmpty()) return CompletableFuture.<Void>completedFuture(null);
                                return ScarpetLootActors.original(original, Vec3.atCenterOf(source), () -> new Selection(clouds.get(random.nextInt(clouds.size())),
                                                source.offset(random.nextInt(16) - random.nextInt(16), random.nextInt(8) - random.nextInt(8), random.nextInt(16) - random.nextInt(16))))
                                        .thenCompose(ScarpetRuntime.captureNativeFunction(selected -> ScarpetLootActors.original(original, Vec3.atCenterOf(selected.target()), () ->
                                                        original.getBlockState(selected.target()).isAir())
                                                .thenCompose(ScarpetRuntime.captureNativeFunction(air -> {
                                                    if (!air) return CompletableFuture.<Void>completedFuture(null);
                                                    return ScarpetLootActors.original(original, Vec3.atCenterOf(selected.target()), () -> {
                                                        original.setBlock(selected.target(), state, Block.UPDATE_CLIENTS);
                                                        return (Void) null;
                                                    }).thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetLootConditions.actor(selected.cloud(), () -> {
                                                        selected.cloud().setRadius(selected.cloud().getRadius() * 0.2F);
                                                        return (Void) null;
                                                    })));
                                                }))));
                            }));
                })));
    }

    private static CompletableFuture<List<AreaEffectCloud>> alive(Iterator<AreaEffectCloud> all, List<AreaEffectCloud> accepted) {
        if (!all.hasNext()) return CompletableFuture.completedFuture(accepted);
        AreaEffectCloud cloud = all.next();
        return ScarpetLootConditions.actor(cloud, cloud::isAlive).thenCompose(ScarpetRuntime.captureNativeFunction(value -> {
            if (value) accepted.add(cloud);
            return alive(all, accepted);
        }));
    }

    private static CompletableFuture<List<AreaEffectCloud>> particles(Iterator<AreaEffectCloud> all, List<AreaEffectCloud> accepted) {
        if (!all.hasNext()) return CompletableFuture.completedFuture(accepted);
        AreaEffectCloud cloud = all.next();
        return ScarpetLootConditions.actor(cloud, () -> cloud.getParticle().getType() == ParticleTypes.DRAGON_BREATH)
                .thenCompose(ScarpetRuntime.captureNativeFunction(value -> {
                    if (value) accepted.add(cloud);
                    return particles(all, accepted);
                }));
    }
}
