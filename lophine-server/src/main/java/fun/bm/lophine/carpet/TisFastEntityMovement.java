// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public final class TisFastEntityMovement {
    private TisFastEntityMovement() {
    }

    public static boolean threshold(Vec3 movement) {
        return movement.lengthSqr() >= 100.0
                && Math.abs(movement.x) * Math.abs(movement.y) * Math.abs(movement.z)
                > (Math.abs(movement.x) + Math.abs(movement.y) + Math.abs(movement.z)) * 12.0;
    }

    public static boolean enabled(Vec3 movement) {
        return GeneralCompatConfig.optimizedFastEntityMovement && (threshold(movement)
                || "optimizedFastEntityMovement".equals(GeneralCompatConfig.ultraSecretSetting));
    }

    public static Vec3 collide(Entity entity, Vec3 movement, AABB original, List<AABB> entityBoxes) {
        return collide(movement, original, entityBoxes, (search, voxels, boxes) -> CollisionUtil.getCollisionsForBlocksOrWorldBorder(
                entity.level(), entity, search, voxels, boxes, CollisionUtil.COLLISION_FLAG_CHECK_BORDER, null));
    }

    public static Vec3 collideBlocks(Entity source, Level world, Vec3 movement, AABB original, List<VoxelShape> entityColliders) {
        return collide(movement, original, List.of(), (search, voxels, boxes) -> {
            voxels.addAll(entityColliders);
            CollisionUtil.getCollisionsForBlocksOrWorldBorder(world, source, search, voxels, boxes, CollisionUtil.COLLISION_FLAG_CHECK_BORDER, null);
        });
    }

    @FunctionalInterface
    interface CollisionGetter {
        void collect(AABB search, List<VoxelShape> voxels, List<AABB> boxes);
    }

    // The search enlargement retains fences/piston heads extending outside their own block.
    static Vec3 collide(Vec3 movement, AABB original, List<AABB> entityBoxes, CollisionGetter getter) {
        AABB vanilla = original.expandTowards(movement);
        AABB box = original;
        double x = movement.x, y = movement.y, z = movement.z;
        if (y != 0.0) {
            y = axis(box, vanilla, new Vec3(0.0, y, 0.0), entityBoxes, getter).y;
            if (y != 0.0) box = box.move(0.0, y, 0.0);
        }
        boolean xSmaller = Math.abs(x) < Math.abs(z);
        if (xSmaller && z != 0.0) {
            z = axis(box, vanilla, new Vec3(0.0, 0.0, z), entityBoxes, getter).z;
            if (z != 0.0) box = box.move(0.0, 0.0, z);
        }
        if (x != 0.0) {
            x = axis(box, vanilla, new Vec3(x, 0.0, 0.0), entityBoxes, getter).x;
            if (!xSmaller && x != 0.0) box = box.move(x, 0.0, 0.0);
        }
        if (!xSmaller && z != 0.0) z = axis(box, vanilla, new Vec3(0.0, 0.0, z), entityBoxes, getter).z;
        return new Vec3(x, y, z);
    }

    private static Vec3 axis(AABB box, AABB vanilla, Vec3 movement, List<AABB> entityBoxes, CollisionGetter getter) {
        AABB search = box.expandTowards(movement).inflate(1.0).intersect(vanilla);
        var voxels = new ArrayList<VoxelShape>();
        var boxes = new ArrayList<AABB>(entityBoxes);
        getter.collect(search, voxels, boxes);
        return CollisionUtil.performCollisions(movement, box, voxels, boxes);
    }
}
