package carpet.script.external;

import carpet.script.CarpetContext;
import carpet.script.Context;
import carpet.script.Fluff.TriFunction;
import carpet.script.argument.BlockArgument;
import carpet.script.argument.Vector3Argument;
import carpet.script.exception.InternalExpressionException;
import carpet.script.value.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Explicit function locators. Arguments are evaluated by the VM before actor dispatch.
 */
public final class ActorFunctions {
    private ActorFunctions() {
    }

    /**
     * Detach guest containers/NBT before releasing the VM lock to dispatch native work.
     */
    public static List<Value> snapshotArguments(List<Value> args) {
        List<Value> copy = new ArrayList<>(args.size());
        for (Value argument : args) copy.add(argument.deepcopy());
        return copy;
    }

    private static Value detached(TriFunction<Context, Context.Type, List<Value>, Value> function, Context context, Context.Type type, List<Value> args) {
        Value result = function.apply(context, type, args);
        return result == null ? null : result.deepcopy();
    }

    public static TriFunction<Context, Context.Type, List<Value>, Value> global(TriFunction<Context, Context.Type, List<Value>, Value> function) {
        return (context, type, args) -> {
            List<Value> captured = snapshotArguments(args);
            return ScarpetRuntime.atGlobal(((CarpetContext) context).server(), () -> detached(function, context, type, captured));
        };
    }

    public static TriFunction<Context, Context.Type, List<Value>, Value> source(TriFunction<Context, Context.Type, List<Value>, Value> function) {
        return (context, type, args) -> {
            CarpetContext cc = (CarpetContext) context;
            List<Value> captured = snapshotArguments(args);
            Entity source = cc.source().getEntity();
            return source == null
                    ? ScarpetRuntime.atBlock(cc.level(), BlockPos.containing(cc.source().getPosition()), () -> detached(function, context, type, captured))
                    : ScarpetRuntime.atEntity(source, () -> detached(function, context, type, captured));
        };
    }

    public static TriFunction<Context, Context.Type, List<Value>, Value> block(int offset, boolean acceptString, TriFunction<Context, Context.Type, List<Value>, Value> function) {
        return (context, type, args) -> {
            CarpetContext cc = (CarpetContext) context;
            List<Value> captured = snapshotArguments(args);
            if (captured.size() <= offset) return function.apply(context, type, captured);
            BlockValue block = BlockArgument.findIn(cc, captured, offset, acceptString).block;
            if (block.getPos() == null) return function.apply(context, type, captured);
            return ScarpetRuntime.atBlock(cc.level(), block.getPos(), () -> detached(function, context, type, captured));
        };
    }

    public static TriFunction<Context, Context.Type, List<Value>, Value> vector(int offset, TriFunction<Context, Context.Type, List<Value>, Value> function) {
        return (context, type, args) -> {
            CarpetContext cc = (CarpetContext) context;
            List<Value> captured = snapshotArguments(args);
            if (captured.size() <= offset) return function.apply(context, type, captured);
            Vector3Argument vector = Vector3Argument.findIn(captured, offset, false, true);
            Supplier<Value> operation = () -> detached(function, context, type, captured);
            return ScarpetRuntime.atBlock(cc.level(), BlockPos.containing(vector.vec), () -> vector.entity == null ? operation.get() : Vector3Argument.withCapturedEntityPosition(vector.entity, vector.vec, operation));
        };
    }

    /**
     * The VM waits the true native vector operation; a region thread never waits another actor.
     */
    public static TriFunction<Context, Context.Type, List<Value>, Value> vectorAsync(int offset,
                                                                                     TriFunction<Context, Context.Type, List<Value>, java.util.concurrent.CompletableFuture<Value>> function) {
        return (context, type, args) -> {
            CarpetContext cc = (CarpetContext) context;
            List<Value> captured = snapshotArguments(args);
            if (captured.size() <= offset) return ScarpetRuntime.await(function.apply(context, type, captured));
            Vector3Argument vector = Vector3Argument.findIn(captured, offset, false, true);
            var actual = ScarpetRuntime.atBlockFuture(cc.level(), BlockPos.containing(vector.vec), () -> vector.entity == null
                            ? function.apply(context, type, captured)
                            : Vector3Argument.withCapturedEntityPosition(vector.entity, vector.vec, () -> function.apply(context, type, captured)))
                    .thenCompose(value -> value);
            return ScarpetRuntime.await(actual);
        };
    }

    public static TriFunction<Context, Context.Type, List<Value>, Value> setBlock(TriFunction<Context, Context.Type, List<Value>, Value> function) {
        return (context, type, args) -> {
            CarpetContext cc = (CarpetContext) context;
            List<Value> captured = snapshotArguments(args);
            BlockArgument target = BlockArgument.findIn(cc, captured, 0);
            BlockArgument source = BlockArgument.findIn(cc, captured, target.offset, true);
            BlockState state = source.block.getBlockState();
            var originalData = source.block.getData();
            var data = originalData == null ? null : originalData.copy();
            List<Value> normalized = new ArrayList<>(captured.subList(0, target.offset));
            normalized.add(new BlockValue(state, cc.level(), data));
            normalized.addAll(captured.subList(source.offset, captured.size()));
            return ScarpetRuntime.atBlock(cc.level(), target.block.getPos(), () -> detached(function, context, type, normalized));
        };
    }

    public static TriFunction<Context, Context.Type, List<Value>, Value> player(int offset, TriFunction<Context, Context.Type, List<Value>, Value> function) {
        return (context, type, args) -> {
            List<Value> captured = snapshotArguments(args);
            if (captured.size() <= offset) return function.apply(context, type, captured);
            ServerPlayer player = EntityValue.getPlayerByValue(((CarpetContext) context).server(), captured.get(offset));
            if (player == null) return function.apply(context, type, captured);
            captured.set(offset, new EntityValue(player));
            return ScarpetRuntime.atEntity(player, () -> detached(function, context, type, captured));
        };
    }

    public static TriFunction<Context, Context.Type, List<Value>, Value> inventory(TriFunction<Context, Context.Type, List<Value>, Value> function) {
        return (context, type, args) -> {
            CarpetContext cc = (CarpetContext) context;
            List<Value> captured = snapshotArguments(args);
            int locatorOffset = !captured.isEmpty() && captured.getFirst().isNull() ? 1 : 0;
            if (captured.size() <= locatorOffset) return function.apply(context, type, captured);
            Value first = captured.get(locatorOffset);
            if (first instanceof EntityValue value)
                return ScarpetRuntime.atEntity(value.getEntity(), () -> detached(function, context, type, captured));
            if (first instanceof ScreenValue screen)
                return ScarpetRuntime.atEntity(screen.getPlayer(), () -> detached(function, context, type, captured));
            if (first instanceof StringValue string) {
                String name = string.getString().toLowerCase(Locale.ROOT);
                Entity owner;
                if (name.equals("enderchest")) {
                    owner = EntityValue.getPlayerByValue(cc.server(), captured.get(locatorOffset + 1));
                    if (owner != null) captured.set(locatorOffset + 1, new EntityValue(owner));
                } else if (name.equals("equipment"))
                    owner = captured.get(locatorOffset + 1) instanceof EntityValue value ? value.getEntity() : null;
                else {
                    boolean ender = name.startsWith("enderchest_");
                    owner = EntityActors.player(cc.server(), ender ? name.substring(11) : name);
                    if (owner != null) {
                        captured.set(locatorOffset, new EntityValue(owner));
                        if (ender) captured.add(locatorOffset, StringValue.of("enderchest"));
                    }
                }
                if (owner == null) return function.apply(context, type, captured);
                return ScarpetRuntime.atEntity(owner, () -> detached(function, context, type, captured));
            }
            BlockPos position;
            if (first instanceof BlockValue block) position = block.getPos();
            else {
                List<Value> coordinates = first instanceof ListValue list ? list.getItems() : captured.subList(locatorOffset, captured.size());
                position = BlockPos.containing(NumericValue.asNumber(coordinates.get(0)).getDouble(), NumericValue.asNumber(coordinates.get(1)).getDouble(), NumericValue.asNumber(coordinates.get(2)).getDouble());
            }
            if (position == null)
                throw new InternalExpressionException("Inventory block must be positioned in the world");
            return ScarpetRuntime.atBlock(cc.level(), position, () -> detached(function, context, type, captured));
        };
    }
}
