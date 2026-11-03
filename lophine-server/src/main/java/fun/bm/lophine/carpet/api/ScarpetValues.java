package fun.bm.lophine.carpet.api;

import carpet.script.exception.InternalExpressionException;
import carpet.script.value.*;
import fun.bm.lophine.api.carpet.ScarpetValue;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Bounded deep snapshots; native objects and cyclic/malicious containers never cross into plugins.
 */
final class ScarpetValues {
    private int remaining = 16384;
    private final IdentityHashMap<Value, Boolean> ancestors = new IdentityHashMap<>();

    List<ScarpetValue> arguments(List<Value> values) {
        return values.stream().map(value -> snapshot(value, 0)).toList();
    }

    private void visit(int depth) {
        if (depth > 64 || --this.remaining < 0)
            throw new InternalExpressionException("Extension value exceeds depth or size limit");
    }

    private ScarpetValue snapshot(Value value, int depth) {
        visit(depth);
        if (value instanceof NullValue) return ScarpetValue.Null.INSTANCE;
        if (value instanceof BooleanValue) return new ScarpetValue.Bool(value.getBoolean());
        if (value.getClass() == NumericValue.class) {
            // getString preserves the exact integer representation; doubles can lose low bits.
            try {
                long integer = Long.parseLong(value.getString());
                if (((NumericValue) value).getDouble() == (double) integer) return new ScarpetValue.Integer(integer);
                return new ScarpetValue.Decimal(((NumericValue) value).getDouble());
            } catch (NumberFormatException ignored) {
                return new ScarpetValue.Decimal(((NumericValue) value).getDouble());
            }
        }
        if (value.getClass() == StringValue.class) return new ScarpetValue.Text(value.getString());
        if (this.ancestors.put(value, Boolean.TRUE) != null)
            throw new InternalExpressionException("Cyclic extension argument");
        try {
            if (value.getClass() == ListValue.class) {
                return new ScarpetValue.Sequence(((ListValue) value).getItems().stream().map(item -> snapshot(item, depth + 1)).toList());
            }
            if (value.getClass() == MapValue.class) {
                var map = new LinkedHashMap<ScarpetValue, ScarpetValue>();
                ((MapValue) value).getMap().forEach((key, item) -> map.put(snapshot(key, depth + 1), snapshot(item, depth + 1)));
                return new ScarpetValue.Mapping(map);
            }
            throw new InternalExpressionException("Unsupported extension argument: " + value.getTypeString());
        } finally {
            this.ancestors.remove(value);
        }
    }

    Value nativeValue(ScarpetValue value) {
        return nativeValue(value, 0);
    }

    private Value nativeValue(ScarpetValue value, int depth) {
        visit(depth);
        return switch (value) {
            case ScarpetValue.Null ignored -> Value.NULL;
            case ScarpetValue.Bool bool -> BooleanValue.of(bool.value());
            case ScarpetValue.Integer integer -> new NumericValue(integer.value());
            case ScarpetValue.Decimal decimal -> new NumericValue(decimal.value());
            case ScarpetValue.Text text -> new StringValue(text.value());
            case ScarpetValue.Sequence sequence ->
                    new ListValue(sequence.values().stream().map(item -> nativeValue(item, depth + 1)).toList());
            case ScarpetValue.Mapping mapping -> {
                var map = new MapValue(List.of());
                mapping.values().forEach((key, item) -> {
                    Value nativeKey = nativeValue(key, depth + 1);
                    if (map.getMap().containsKey(nativeKey)) {
                        throw new InternalExpressionException("Extension map keys collide under Scarpet equality");
                    }
                    map.getMap().put(nativeKey, nativeValue(item, depth + 1));
                });
                yield map;
            }
        };
    }
}
