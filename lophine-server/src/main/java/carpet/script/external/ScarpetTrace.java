// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.exception.InternalExpressionException;
import carpet.script.value.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.EntityCollisionContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Captures entity inputs on its owner; block and entity rays run under a complete area lease.
 */
public final class ScarpetTrace {
    public record Snapshot(Entity source, ServerLevel world, Vec3 eye, Vec3 view, AABB box, Entity rootVehicle,
                           boolean creative, EntityCollisionContext collision) {
    }

    private record Options(float reach, boolean entities, boolean liquids, boolean blocks, boolean exact) {
    }

    private ScarpetTrace() {
    }

    private static final class FrozenCollision extends EntityCollisionContext {
        private final Set<Fluid> standable;

        FrozenCollision(Entity entity) {
            super(entity.isDescending(), false, entity.getY(), entity instanceof LivingEntity living ? living.getMainHandItem().copy() : ItemStack.EMPTY, false, entity);
            standable = entity instanceof LivingEntity living ? BuiltInRegistries.FLUID.stream().filter(fluid -> living.canStandOnFluid(fluid.defaultFluidState())).collect(Collectors.toUnmodifiableSet()) : Set.of();
        }

        @Override
        public boolean canStandOnFluid(FluidState above, FluidState fluid) {
            return standable.contains(fluid.getType()) && !above.getType().isSame(fluid.getType());
        }
    }

    /**
     * Invoked while the original owner is still active, before the retirement callback is queued.
     */
    public static Snapshot capture(Entity source) {
        return new Snapshot(source, (ServerLevel) source.level(), source.getEyePosition(1), source.getViewVector(1), source.getBoundingBox(), source.getRootVehicle(),
                source instanceof ServerPlayer player && player.gameMode.isCreative(), new FrozenCollision(source));
    }

    private static Options options(Value argument, boolean creative) {
        float reach = creative && argument == null ? 5.0F : 4.5F;
        boolean entities = true, liquids = false, blocks = true, exact = false;
        if (argument instanceof ListValue list) {
            List<Value> args = list.getItems();
            if (args.isEmpty()) throw new InternalExpressionException("'trace' needs more arguments");
            reach = (float) NumericValue.asNumber(args.getFirst()).getDouble();
            if (args.size() > 1) {
                entities = false;
                blocks = false;
                for (int i = 1; i < args.size(); i++) {
                    String what = args.get(i).getString();
                    if (what.equalsIgnoreCase("entities")) entities = true;
                    else if (what.equalsIgnoreCase("blocks")) blocks = true;
                    else if (what.equalsIgnoreCase("liquids")) liquids = true;
                    else if (what.equalsIgnoreCase("exact")) exact = true;
                    else throw new InternalExpressionException("Incorrect tracing: " + what);
                }
            }
        } else if (argument != null) reach = (float) NumericValue.asNumber(argument).getDouble();
        if (!Float.isFinite(reach)) throw new InternalExpressionException("Trace reach must be finite");
        return new Options(reach, entities, liquids, blocks, exact);
    }

    public static Value live(Entity source, Value argument) {
        Value captured = argument == null ? null : argument.deepcopy();
        for (int attempt = 0; attempt < 8; attempt++) {
            Snapshot snapshot = ScarpetRuntime.atEntity(source, () -> capture(source));
            Options parsed = options(captured, snapshot.creative());
            int[] area = area(snapshot, parsed.reach());
            Value result = ScarpetRuntime.withArea(snapshot.world(), area[0], area[1], area[2], area[3], () -> {
                if (!TickThread.isTickThreadFor(source) || source.isRemoved() || source.level() != snapshot.world())
                    return null;
                Snapshot now = capture(source);
                int[] next = area(now, parsed.reach());
                if (next[0] < area[0] || next[1] < area[1] || next[2] > area[2] || next[3] > area[3]) return null;
                return evaluate(now, parsed);
            });
            if (result != null) return result;
        }
        throw new InternalExpressionException("Entity kept changing regions during trace");
    }

    public static Value retired(Snapshot source, Value argument) {
        Options parsed = options(argument, source.creative());
        int[] area = area(source, parsed.reach());
        return ScarpetRuntime.withArea(source.world(), area[0], area[1], area[2], area[3], () -> evaluate(source, parsed));
    }

    private static int[] area(Snapshot snapshot, float reach) {
        Vec3 end = snapshot.eye().add(snapshot.view().scale(reach));
        AABB query = snapshot.box().expandTowards(snapshot.view().scale(reach)).inflate(2);
        return new int[]{BlockPos.containing(Math.min(query.minX, Math.min(snapshot.eye().x, end.x) - 1), 0, Math.min(query.minZ, Math.min(snapshot.eye().z, end.z) - 1)).getX() >> 4,
                BlockPos.containing(Math.min(query.minX, Math.min(snapshot.eye().x, end.x) - 1), 0, Math.min(query.minZ, Math.min(snapshot.eye().z, end.z) - 1)).getZ() >> 4,
                BlockPos.containing(Math.max(query.maxX, Math.max(snapshot.eye().x, end.x) + 1), 0, Math.max(query.maxZ, Math.max(snapshot.eye().z, end.z) + 1)).getX() >> 4,
                BlockPos.containing(Math.max(query.maxX, Math.max(snapshot.eye().x, end.x) + 1), 0, Math.max(query.maxZ, Math.max(snapshot.eye().z, end.z) + 1)).getZ() >> 4};
    }

    static Value evaluate(Snapshot source, Value argument) {
        return evaluate(source, options(argument, source.creative()));
    }

    private static Value evaluate(Snapshot source, Options options) {
        Vec3 end = source.eye().add(source.view().scale(options.reach()));
        BlockHitResult block = options.blocks() || !options.entities() ? source.world().clip(new ClipContext(source.eye(), end, ClipContext.Block.OUTLINE,
                options.liquids() ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, source.collision())) : null;
        double maxDistance = options.reach() * options.reach();
        if (block != null && options.entities()) maxDistance = block.getLocation().distanceToSqr(source.eye());
        EntityHitResult entity = options.entities() ? entityRay(source, end, maxDistance) : null;
        HitResult hit = entity == null ? block : entity;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return Value.NULL;
        if (options.exact()) return ValueConversions.of(hit.getLocation());
        return hit instanceof EntityHitResult found ? new EntityValue(found.getEntity()) : new BlockValue(source.world(), ((BlockHitResult) hit).getBlockPos());
    }

    /**
     * Preserves the upstream root-vehicle, inside-box and equal-distance selection algorithm.
     */
    private static EntityHitResult entityRay(Snapshot source, Vec3 end, double targetDistance) {
        AABB search = source.box().expandTowards(end.subtract(source.eye())).inflate(1);
        Entity target = null;
        Vec3 targetHit = null;
        for (Entity current : source.world().getEntities(source.source(), search, entity -> !entity.isSpectator() && entity.isPickable())) {
            AABB currentBox = current.getBoundingBox().inflate(current.getPickRadius());
            Optional<Vec3> hit = currentBox.clip(source.eye(), end);
            if (currentBox.contains(source.eye())) {
                if (targetDistance >= 0) {
                    target = current;
                    targetHit = hit.orElse(source.eye());
                    targetDistance = 0;
                }
            } else if (hit.isPresent()) {
                double distance = source.eye().distanceToSqr(hit.get());
                if (distance < targetDistance || targetDistance == 0) {
                    if (current.getRootVehicle() == source.rootVehicle()) {
                        if (targetDistance == 0) {
                            target = current;
                            targetHit = hit.get();
                        }
                    } else {
                        target = current;
                        targetHit = hit.get();
                        targetDistance = distance;
                    }
                }
            }
        }
        return target == null ? null : new EntityHitResult(target, targetHit);
    }
}
