// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Old-world collision data is captured by its owner; native shapes read the original entity on its current owner.
 */
public final class ScarpetExplosionDensity {
    public record Entry(BlockState state, VoxelShape shape) {
    }

    private record Prepared(Snapshot snapshot, Float cached) {
    }

    private static final class Missing extends RuntimeException {
        final BlockPos position;

        Missing(BlockPos position) {
            super(null, null, false, false);
            this.position = position.immutable();
        }
    }

    private static final class Snapshot implements BlockGetter {
        final Map<Long, Entry> blocks = new HashMap<>();
        final Map<Long, VoxelShape> shapeUpdates = new HashMap<>();
        final Set<BlockPos> required = new LinkedHashSet<>();
        final int height, minY;
        boolean collecting;

        Snapshot(int height, int minY) {
            this.height = height;
            this.minY = minY;
        }

        Entry entry(BlockPos position) {
            if (collecting) {
                required.add(position.immutable());
                return new Entry(null, null);
            }
            Entry found = blocks.get(position.asLong());
            if (found == null) throw new Missing(position);
            return found;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            BlockState state = entry(position).state();
            return state == null ? Blocks.VOID_AIR.defaultBlockState() : state;
        }

        @Override
        public BlockState getBlockStateIfLoaded(BlockPos position) {
            return getBlockState(position);
        }

        @Override
        public FluidState getFluidIfLoaded(BlockPos position) {
            return getFluidState(position);
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return getBlockState(position).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            throw new IllegalStateException("A contextual explosion shape requested a foreign mutable block entity");
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public int getMinY() {
            return minY;
        }
    }

    private ScarpetExplosionDensity() {
    }

    public static CompletableFuture<Float> compute(ServerExplosion explosion, Entity target) {
        return ScarpetExplosionActors.entity(target, target::getBoundingBox).thenCompose(bounds ->
                ScarpetExplosionActors.world(explosion.level(), BlockPos.containing(explosion.center()), () -> {
                    Float cached = explosion.carpetCachedDensity(bounds);
                    Snapshot snapshot = new Snapshot(explosion.level().getHeight(), explosion.level().getMinY());
                    return new Prepared(snapshot, cached);
                }).thenCompose(prepared -> {
                    if (prepared.cached() != null) return CompletableFuture.completedFuture(prepared.cached());
                    Snapshot snapshot = prepared.snapshot();
                    return ScarpetExplosionActors.entity(target, () -> {
                        snapshot.collecting = true;
                        try {
                            getSeenFraction(explosion.center(), target, snapshot, new BlockPos.MutableBlockPos());
                        } finally {
                            snapshot.collecting = false;
                        }
                        return null;
                    }).thenCompose(ignored -> fill(explosion, snapshot, snapshot.required)).thenCompose(ignored -> evaluate(explosion, target, bounds, snapshot));
                }));
    }

    private static CompletableFuture<Void> fill(ServerExplosion explosion, Snapshot snapshot, Collection<BlockPos> positions) {
        List<BlockPos> captured = List.copyOf(positions);
        BlockPos center = BlockPos.containing(explosion.center());
        int minX = center.getX() >> 4, maxX = minX, minZ = center.getZ() >> 4, maxZ = minZ;
        for (BlockPos position : captured) {
            minX = Math.min(minX, position.getX() >> 4);
            maxX = Math.max(maxX, position.getX() >> 4);
            minZ = Math.min(minZ, position.getZ() >> 4);
            maxZ = Math.max(maxZ, position.getZ() >> 4);
        }
        Supplier<Void> owned = ScarpetRuntime.captureNativeContinuation(() -> {
            for (BlockPos position : captured)
                snapshot.blocks.put(position.asLong(), explosion.carpetCollisionEntry(position));
            return null;
        });
        CompletableFuture<Void> done = fun.bm.lophine.carpet.CarpetRegionLease.runValue(explosion.level(), minX, minZ, maxX, maxZ, lease -> owned.get());
        ScarpetNativeWork.record(done);
        return done;
    }

    private static CompletableFuture<Float> evaluate(ServerExplosion explosion, Entity target, AABB bounds, Snapshot snapshot) {
        return ScarpetExplosionActors.entity(target, () -> {
            if (!target.getBoundingBox().equals(bounds)) return new Object[]{null, null};
            try {
                return new Object[]{Float.valueOf(getSeenFraction(explosion.center(), target, snapshot, new BlockPos.MutableBlockPos())), null};
            } catch (Missing missing) {
                return new Object[]{null, missing.position};
            }
        }).thenCompose(result -> {
            if (result[0] != null)
                return ScarpetExplosionActors.world(explosion.level(), BlockPos.containing(explosion.center()), () -> {
                    explosion.carpetStoreDensity(bounds, (Float) result[0], snapshot.shapeUpdates);
                    return (Float) result[0];
                });
            if (result[1] == null) return compute(explosion, target);
            return fill(explosion, snapshot, List.of((BlockPos) result[1])).thenCompose(ignored -> evaluate(explosion, target, bounds, snapshot));
        });
    }

    private static boolean clipsAnything(final Vec3 from, final Vec3 to,
                                         final ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext context,
                                         final Snapshot snapshot, final BlockPos.MutableBlockPos currPos) {
        // assume that context.delegated = false
        final double adjX = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.x - to.x);
        final double adjY = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.y - to.y);
        final double adjZ = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.z - to.z);

        if (adjX == 0.0 && adjY == 0.0 && adjZ == 0.0) {
            return false;
        }

        final double toXAdj = to.x - adjX;
        final double toYAdj = to.y - adjY;
        final double toZAdj = to.z - adjZ;
        final double fromXAdj = from.x + adjX;
        final double fromYAdj = from.y + adjY;
        final double fromZAdj = from.z + adjZ;

        int currX = Mth.floor(fromXAdj);
        int currY = Mth.floor(fromYAdj);
        int currZ = Mth.floor(fromZAdj);

        final double diffX = toXAdj - fromXAdj;
        final double diffY = toYAdj - fromYAdj;
        final double diffZ = toZAdj - fromZAdj;

        final double dxDouble = Math.signum(diffX);
        final double dyDouble = Math.signum(diffY);
        final double dzDouble = Math.signum(diffZ);

        final int dx = (int) dxDouble;
        final int dy = (int) dyDouble;
        final int dz = (int) dzDouble;

        final double normalizedDiffX = diffX == 0.0 ? Double.MAX_VALUE : dxDouble / diffX;
        final double normalizedDiffY = diffY == 0.0 ? Double.MAX_VALUE : dyDouble / diffY;
        final double normalizedDiffZ = diffZ == 0.0 ? Double.MAX_VALUE : dzDouble / diffZ;

        double normalizedCurrX = normalizedDiffX * (diffX > 0.0 ? (1.0 - Mth.frac(fromXAdj)) : Mth.frac(fromXAdj));
        double normalizedCurrY = normalizedDiffY * (diffY > 0.0 ? (1.0 - Mth.frac(fromYAdj)) : Mth.frac(fromYAdj));
        double normalizedCurrZ = normalizedDiffZ * (diffZ > 0.0 ? (1.0 - Mth.frac(fromZAdj)) : Mth.frac(fromZAdj));

        for (; ; ) {
            currPos.set(currX, currY, currZ);

            // ClipContext.Block.COLLIDER -> BlockBehaviour.BlockStateBase::getCollisionShape
            // ClipContext.Fluid.NONE -> ignore fluids

            // read block from cache
            final long key = BlockPos.asLong(currX, currY, currZ);

            Entry entry = snapshot.entry(currPos);
            BlockState blockState = entry.state();
            if (!snapshot.collecting && blockState != null && !((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState) blockState).moonrise$emptyContextCollisionShape()) {
                VoxelShape collision = entry.shape();
                if (collision == null) {
                    collision = blockState.getCollisionShape(snapshot, currPos, context);
                    if (!context.isDelegated()) snapshot.shapeUpdates.put(key, collision);
                }
                if (!collision.isEmpty() && collision.clip(from, to, currPos) != null) return true;
            }

            if (normalizedCurrX > 1.0 && normalizedCurrY > 1.0 && normalizedCurrZ > 1.0) {
                return false;
            }

            // inc the smallest normalized coordinate

            if (normalizedCurrX < normalizedCurrY) {
                if (normalizedCurrX < normalizedCurrZ) {
                    currX += dx;
                    normalizedCurrX += normalizedDiffX;
                } else {
                    // x < y && x >= z <--> z < y && z <= x
                    currZ += dz;
                    normalizedCurrZ += normalizedDiffZ;
                }
            } else if (normalizedCurrY < normalizedCurrZ) {
                // y <= x && y < z
                currY += dy;
                normalizedCurrY += normalizedDiffY;
            } else {
                // y <= x && z <= y <--> z <= y && z <= x
                currZ += dz;
                normalizedCurrZ += normalizedDiffZ;
            }
        }
    }

    private static float getSeenFraction(final Vec3 source, final Entity target,
                                         final Snapshot snapshot, final BlockPos.MutableBlockPos blockPos) {
        final AABB boundingBox = target.getBoundingBox();
        final double diffX = boundingBox.maxX - boundingBox.minX;
        final double diffY = boundingBox.maxY - boundingBox.minY;
        final double diffZ = boundingBox.maxZ - boundingBox.minZ;

        final double incX = 1.0 / (diffX * 2.0 + 1.0);
        final double incY = 1.0 / (diffY * 2.0 + 1.0);
        final double incZ = 1.0 / (diffZ * 2.0 + 1.0);

        if (incX < 0.0 || incY < 0.0 || incZ < 0.0) {
            return 0.0f;
        }

        final double offX = (1.0 - Math.floor(1.0 / incX) * incX) * 0.5 + boundingBox.minX;
        final double offY = boundingBox.minY;
        final double offZ = (1.0 - Math.floor(1.0 / incZ) * incZ) * 0.5 + boundingBox.minZ;

        final ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext context = new ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext(target);

        int totalRays = 0;
        int missedRays = 0;

        for (double dx = 0.0; dx <= 1.0; dx += incX) {
            final double fromX = Math.fma(dx, diffX, offX);
            for (double dy = 0.0; dy <= 1.0; dy += incY) {
                final double fromY = Math.fma(dy, diffY, offY);
                for (double dz = 0.0; dz <= 1.0; dz += incZ) {
                    ++totalRays;

                    final Vec3 from = new Vec3(
                            fromX,
                            fromY,
                            Math.fma(dz, diffZ, offZ)
                    );

                    if (!clipsAnything(from, source, context, snapshot, blockPos)) {
                        ++missedRays;
                    }
                }
            }
        }

        return (float) missedRays / (float) totalRays;
    }
}
