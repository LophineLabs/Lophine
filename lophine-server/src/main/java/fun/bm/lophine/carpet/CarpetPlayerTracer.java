// SPDX-License-Identifier: LGPL-3.0-or-later
// Adapted from Fabric Carpet revision f358000b175ddbcf1dd0bc59641c715fb0545664.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.*;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

final class CarpetPlayerTracer {
    private CarpetPlayerTracer() {
    }

    static List<Entity> nearby(final ServerPlayer player, final AABB box, final Predicate<Entity> predicate) {
        TickThread.ensureTickThread(player, "Carpet action query must own its player");
        if (!CarpetPlayerTargetArea.Bounds.query(box).loaded(player.level()))
            throw new CarpetPlayerTargetArea.Pending();
        return player.level().getEntities(player, box, entity -> {
            if (!TickThread.isTickThreadFor(entity)) throw new CarpetPlayerTargetArea.Pending();
            return predicate.test(entity);
        });
    }

    static HitResult rayTrace(final ServerPlayer player, final float partialTicks, final double reach, final boolean fluids) {
        final Vec3 start = player.getEyePosition(partialTicks);
        final Vec3 end = start.add(player.getViewVector(partialTicks).scale(reach));
        final ServerLevel level = player.level();
        TickThread.ensureTickThread(player, "Player ray trace must own its player");
        final BlockGetter local = new BlockGetter() {
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                this.getBlockStateIfLoaded(pos);
                return level.getBlockEntity(pos);
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return this.getBlockStateIfLoaded(pos);
            }

            @Override
            public BlockState getBlockStateIfLoaded(BlockPos pos) {
                if (!TickThread.isTickThreadFor(level, pos)) throw new CarpetPlayerTargetArea.Pending();
                final BlockState loaded = level.getBlockStateIfLoaded(pos);
                if (loaded == null) throw new CarpetPlayerTargetArea.Pending();
                return loaded;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return this.getBlockState(pos).getFluidState();
            }

            @Override
            public FluidState getFluidIfLoaded(BlockPos pos) {
                return this.getFluidState(pos);
            }

            @Override
            public int getHeight() {
                return level.getHeight();
            }

            @Override
            public int getMinY() {
                return level.getMinY();
            }
        };
        BlockHitResult blockHit = local.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE,
                fluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, player));
        double targetDistance = start.distanceToSqr(blockHit.getLocation());
        Entity target = null;
        Vec3 targetHit = null;
        for (Entity current : nearby(player, player.getBoundingBox().expandTowards(end.subtract(start)).inflate(1), e -> !e.isSpectator() && e.isPickable())) {
            final AABB box = current.getBoundingBox().inflate(current.getPickRadius());
            final Optional<Vec3> hit = box.clip(start, end);
            if (box.contains(start) && targetDistance >= 0) {
                target = current;
                targetHit = hit.orElse(start);
                targetDistance = 0;
            } else if (hit.isPresent()) {
                final double distance = start.distanceToSqr(hit.get());
                if (distance < targetDistance || targetDistance == 0) {
                    if (current.getRootVehicle() != player.getRootVehicle() || targetDistance == 0) {
                        target = current;
                        targetHit = hit.get();
                        targetDistance = distance;
                    }
                }
            }
        }
        if (target != null) return new EntityHitResult(target, targetHit);
        return blockHit;
    }
}
