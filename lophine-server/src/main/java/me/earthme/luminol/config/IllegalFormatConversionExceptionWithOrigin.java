package me.earthme.luminol.config;

import java.util.IllegalFormatConversionException;

public class IllegalFormatConversionExceptionWithOrigin extends IllegalFormatConversionException {
    private final Object origin;

    /**
     * Constructs an instance of this class with the mismatched conversion and
     * the corresponding argument class.
     *
     * @param c             Inapplicable conversion
     * @param arg           Class of the mismatched argument
     * @param originalValue The original value
     */
    public IllegalFormatConversionExceptionWithOrigin(char c, Class<?> arg, Object originalValue) {
        super(c, arg);
        origin = originalValue;
    }

    public Object getOrigin() {
        return origin;
    }
}
