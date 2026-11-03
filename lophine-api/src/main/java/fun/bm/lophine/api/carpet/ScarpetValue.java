package fun.bm.lophine.api.carpet;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable data exchanged with Scarpet. Maps preserve typed keys, including numbers. */
public sealed interface ScarpetValue permits ScarpetValue.Null, ScarpetValue.Bool, ScarpetValue.Integer,
        ScarpetValue.Decimal, ScarpetValue.Text, ScarpetValue.Sequence, ScarpetValue.Mapping {
    /** Explicit script null; Java null is never a script value. */
    enum Null implements ScarpetValue {
        /** The script null singleton. */
        INSTANCE
    }

    /**
     * An immutable script data value.
     *
     * @param value boolean value
     */
    record Bool(boolean value) implements ScarpetValue { }

    /**
     * An immutable script data value.
     *
     * @param value signed 64-bit integer, preserving integers beyond double precision
     */
    record Integer(long value) implements ScarpetValue { }

    /**
     * An immutable script data value.
     *
     * @param value floating-point value
     */
    record Decimal(double value) implements ScarpetValue { }

    /**
     * An immutable script data value.
     *
     * @param value string value
     */
    record Text(String value) implements ScarpetValue {
        /**
         * Creates a non-null string value.
         *
         * @param value string contents
         */
        public Text {
            Objects.requireNonNull(value);
        }
    }

    /**
     * An immutable script data value.
     *
     * @param values list contents, copied on construction
     */
    record Sequence(List<ScarpetValue> values) implements ScarpetValue {
        /**
         * Copies the sequence contents.
         *
         * @param values sequence contents
         */
        public Sequence {
            values = List.copyOf(values);
        }
    }

    /**
     * An immutable script data value.
     *
     * @param values map contents, copied on construction
     */
    record Mapping(Map<ScarpetValue, ScarpetValue> values) implements ScarpetValue {
        /**
         * Copies the map contents.
         *
         * @param values map contents
         */
        public Mapping {
            values = Map.copyOf(values);
        }
    }
}
