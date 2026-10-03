package carpet.script.argument;

import carpet.script.exception.InternalExpressionException;
import carpet.script.value.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class Vector3Argument extends Argument {
    private static final ThreadLocal<java.util.Map<Entity, Vec3>> CAPTURED_POSITIONS = ThreadLocal.withInitial(java.util.HashMap::new);

    /**
     * A locator is captured before dispatch and reused by the original argument parser on its actor.
     */
    public static <T> T withCapturedEntityPosition(Entity entity, Vec3 position, java.util.function.Supplier<T> action) {
        java.util.Map<Entity, Vec3> captured = CAPTURED_POSITIONS.get();
        Vec3 previous = captured.put(entity, position);
        try {
            return action.get();
        } finally {
            if (previous == null) captured.remove(entity);
            else captured.put(entity, previous);
        }
    }

    public Vec3 vec;
    public final double yaw;
    public final double pitch;
    public boolean fromBlock = false;
    public @Nullable Entity entity = null;

    private Vector3Argument(Vec3 v, int o) {
        super(o);
        this.vec = v;
        this.yaw = 0.0D;
        this.pitch = 0.0D;
    }

    private Vector3Argument(Vec3 v, int o, double y, double p) {
        super(o);
        this.vec = v;
        this.yaw = y;
        this.pitch = p;
    }

    private Vector3Argument fromBlock() {
        fromBlock = true;
        return this;
    }

    private Vector3Argument withEntity(Entity e) {
        entity = e;
        return this;
    }

    public static Vector3Argument findIn(List<Value> params, int offset) {
        return findIn(params, offset, false, false);
    }

    public static Vector3Argument findIn(List<Value> params, int offset, boolean optionalDirection, boolean optionalEntity) {
        return findIn(params.listIterator(offset), offset, optionalDirection, optionalEntity);
    }

    public static Vector3Argument findIn(Iterator<Value> params, int offset, boolean optionalDirection, boolean optionalEntity) {
        try {
            Value v1 = params.next();
            if (v1 instanceof final BlockValue blockValue) {
                return new Vector3Argument(Vec3.atCenterOf(blockValue.getPos()), 1 + offset).fromBlock();
            }
            if (optionalEntity && v1 instanceof final EntityValue entityValue) {
                Entity e = entityValue.getEntity();
                Vec3 captured = CAPTURED_POSITIONS.get().get(e);
                return new Vector3Argument(captured == null ? carpet.script.external.ScarpetRuntime.atEntity(e, e::position) : captured, 1 + offset).withEntity(e);
            }
            if (v1 instanceof final ListValue listValue) {
                List<Value> args = listValue.getItems();
                Vec3 pos = new Vec3(
                        NumericValue.asNumber(args.get(0)).getDouble(),
                        NumericValue.asNumber(args.get(1)).getDouble(),
                        NumericValue.asNumber(args.get(2)).getDouble());
                double yaw = 0.0D;
                double pitch = 0.0D;
                if (args.size() > 3 && optionalDirection) {
                    yaw = NumericValue.asNumber(args.get(3)).getDouble();
                    pitch = NumericValue.asNumber(args.get(4)).getDouble();
                }
                return new Vector3Argument(pos, offset + 1, yaw, pitch);
            }
            Vec3 pos = new Vec3(
                    NumericValue.asNumber(v1).getDouble(),
                    NumericValue.asNumber(params.next()).getDouble(),
                    NumericValue.asNumber(params.next()).getDouble());
            double yaw = 0.0D;
            double pitch = 0.0D;
            int eatenLength = 3;
            if (params.hasNext() && optionalDirection) {
                yaw = NumericValue.asNumber(params.next()).getDouble();
                pitch = NumericValue.asNumber(params.next()).getDouble();
                eatenLength = 5;
            }

            return new Vector3Argument(pos, offset + eatenLength, yaw, pitch);
        } catch (IndexOutOfBoundsException | NoSuchElementException e) {
            throw new InternalExpressionException("Position argument should be defined either by three coordinates (a triple or by three arguments), or a positioned block value");
        }
    }
}
