// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Resumable form of Moonrise's exposure sampling; no entity or world access is performed here.
 */
final class ScarpetExplosionSampler {
    @FunctionalInterface
    interface Probe {
        boolean clips(Vec3 from, Vec3 to, BlockPos position);
    }

    private final AABB bounds;
    private final Vec3 source;
    private final double incX, incY, incZ, offX, offZ;
    private double x, y, z;
    private long total, missed;
    private Ray ray;
    private boolean done;

    ScarpetExplosionSampler(AABB bounds, Vec3 source) {
        this.bounds = bounds;
        this.source = source;
        coordinate(bounds.minX);
        coordinate(bounds.minY);
        coordinate(bounds.minZ);
        coordinate(bounds.maxX);
        coordinate(bounds.maxY);
        coordinate(bounds.maxZ);
        coordinate(source.x);
        coordinate(source.y);
        coordinate(source.z);
        this.incX = increment(bounds.maxX - bounds.minX);
        this.incY = increment(bounds.maxY - bounds.minY);
        this.incZ = increment(bounds.maxZ - bounds.minZ);
        this.offX = (1.0 - Math.floor(1.0 / this.incX) * this.incX) * 0.5 + bounds.minX;
        this.offZ = (1.0 - Math.floor(1.0 / this.incZ) * this.incZ) * 0.5 + bounds.minZ;
    }

    private static void coordinate(double value) {
        // Mth.floor saturates outside int range, which makes a DDA cursor lose its endpoint.
        if (!Double.isFinite(value) || value <= Integer.MIN_VALUE + 2D || value >= Integer.MAX_VALUE - 2D) {
            throw new IllegalArgumentException("Explosion exposure requires finite block coordinates within integer range");
        }
    }

    private static double increment(double extent) {
        double result = 1.0 / (extent * 2.0 + 1.0);
        if (extent < 0.0 || !Double.isFinite(result) || result <= 0.0 || 1.0 + result == 1.0) {
            throw new IllegalArgumentException("Explosion exposure bounds do not allow sampling progress");
        }
        return result;
    }

    /**
     * A throwing probe leaves the current voxel and all completed rays intact.
     */
    boolean advance(BooleanSupplier workAvailable, Probe probe) {
        while (!this.done && workAvailable.getAsBoolean()) {
            if (this.ray == null) {
                this.ray = new Ray(new Vec3(Math.fma(this.x, this.bounds.maxX - this.bounds.minX, this.offX),
                        Math.fma(this.y, this.bounds.maxY - this.bounds.minY, this.bounds.minY),
                        Math.fma(this.z, this.bounds.maxZ - this.bounds.minZ, this.offZ)), this.source);
            }
            Boolean blocked = this.ray.step(probe);
            if (blocked == null) continue;
            ++this.total;
            if (!blocked) ++this.missed;
            this.ray = null;
            this.z += this.incZ;
            if (this.z > 1.0) {
                this.z = 0.0;
                this.y += this.incY;
                if (this.y > 1.0) {
                    this.y = 0.0;
                    this.x += this.incX;
                    this.done = this.x > 1.0;
                }
            }
        }
        return this.done;
    }

    float result() {
        if (!this.done) throw new IllegalStateException("Exposure sampling is not complete");
        return (float) this.missed / (float) this.total;
    }

    /**
     * Bounded geometric lookahead avoids one owner round trip per missing air block.
     */
    List<BlockPos> lookahead(int limit) {
        List<BlockPos> positions = new ArrayList<>();
        if (this.ray == null) return positions;
        Ray copy = new Ray(this.ray);
        while (positions.size() < limit) {
            Boolean done = copy.step((from, to, position) -> {
                positions.add(position.immutable());
                return false;
            });
            if (done != null) break;
        }
        return positions;
    }

    private static final class Ray {
        final Vec3 from, to;
        final double stepX, stepY, stepZ;
        final int dx, dy, dz;
        final long maxVisits;
        final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        double nextX, nextY, nextZ;
        int x, y, z;
        long visited;
        boolean empty;

        Ray(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
            double epsilon = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON;
            double adjX = epsilon * (from.x - to.x), adjY = epsilon * (from.y - to.y), adjZ = epsilon * (from.z - to.z);
            this.empty = adjX == 0.0 && adjY == 0.0 && adjZ == 0.0;
            double fromX = from.x + adjX, fromY = from.y + adjY, fromZ = from.z + adjZ;
            double toX = to.x - adjX, toY = to.y - adjY, toZ = to.z - adjZ;
            coordinate(fromX);
            coordinate(fromY);
            coordinate(fromZ);
            coordinate(toX);
            coordinate(toY);
            coordinate(toZ);
            this.x = Mth.floor(fromX);
            this.y = Mth.floor(fromY);
            this.z = Mth.floor(fromZ);
            double diffX = toX - fromX, diffY = toY - fromY, diffZ = toZ - fromZ;
            this.dx = (int) Math.signum(diffX);
            this.dy = (int) Math.signum(diffY);
            this.dz = (int) Math.signum(diffZ);
            this.stepX = diffX == 0.0 ? Double.MAX_VALUE : Math.signum(diffX) / diffX;
            this.stepY = diffY == 0.0 ? Double.MAX_VALUE : Math.signum(diffY) / diffY;
            this.stepZ = diffZ == 0.0 ? Double.MAX_VALUE : Math.signum(diffZ) / diffZ;
            this.nextX = this.stepX * (diffX > 0.0 ? 1.0 - Mth.frac(fromX) : Mth.frac(fromX));
            this.nextY = this.stepY * (diffY > 0.0 ? 1.0 - Mth.frac(fromY) : Mth.frac(fromY));
            this.nextZ = this.stepZ * (diffZ > 0.0 ? 1.0 - Mth.frac(fromZ) : Mth.frac(fromZ));
            this.maxVisits = Math.abs((long) Mth.floor(toX) - this.x) + Math.abs((long) Mth.floor(toY) - this.y)
                    + Math.abs((long) Mth.floor(toZ) - this.z) + 8L;
        }

        Ray(Ray original) {
            this.from = original.from;
            this.to = original.to;
            this.stepX = original.stepX;
            this.stepY = original.stepY;
            this.stepZ = original.stepZ;
            this.dx = original.dx;
            this.dy = original.dy;
            this.dz = original.dz;
            this.maxVisits = original.maxVisits;
            this.nextX = original.nextX;
            this.nextY = original.nextY;
            this.nextZ = original.nextZ;
            this.x = original.x;
            this.y = original.y;
            this.z = original.z;
            this.visited = original.visited;
            this.empty = original.empty;
        }

        /**
         * null means the ray continues; false means it reached its end without clipping.
         */
        Boolean step(Probe probe) {
            if (this.empty) return false;
            this.position.set(this.x, this.y, this.z);
            boolean blocked = probe.clips(this.from, this.to, this.position);
            if (++this.visited > this.maxVisits)
                throw new IllegalStateException("Explosion exposure ray failed to make progress");
            if (blocked) return true;
            if (this.nextX > 1.0 && this.nextY > 1.0 && this.nextZ > 1.0) return false;
            if (this.nextX < this.nextY) {
                if (this.nextX < this.nextZ) {
                    this.x += this.dx;
                    this.nextX += this.stepX;
                } else {
                    this.z += this.dz;
                    this.nextZ += this.stepZ;
                }
            } else if (this.nextY < this.nextZ) {
                this.y += this.dy;
                this.nextY += this.stepY;
            } else {
                this.z += this.dz;
                this.nextZ += this.stepZ;
            }
            return null;
        }
    }
}
