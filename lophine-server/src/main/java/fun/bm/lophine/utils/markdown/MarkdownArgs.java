package fun.bm.lophine.utils.markdown;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class MarkdownArgs {
    static final MarkdownArgs EMPTY = new MarkdownArgs();

    private final Map<String, Value> values = new HashMap<>();

    public static MarkdownArgs empty() {
        return EMPTY;
    }

    public static MarkdownArgs create() {
        return new MarkdownArgs();
    }

    public MarkdownArgs set(String name, Object display) {
        values.put(name, Value.ofDisplay(display));
        return this;
    }

    public MarkdownArgs set(String name, Object display, Number scaleValue) {
        values.put(name, new Value(String.valueOf(display), scaleValue, null, null));
        return this;
    }

    public MarkdownArgs set(String name, Object display, TextColor color) {
        values.put(name, new Value(String.valueOf(display), null, color, null));
        return this;
    }

    public MarkdownArgs set(String name, Component component) {
        values.put(name, new Value("", null, null, component));
        return this;
    }

    public MarkdownArgs set(String name, String display, Component component) {
        values.put(name, new Value(display, null, null, component));
        return this;
    }

    @Nullable
    Value get(String name) {
        return values.get(name);
    }

    record Value(String display, @Nullable Number number, @Nullable TextColor color, @Nullable Component component) {
        static Value ofDisplay(Object display) {
            if (display instanceof Component component) {
                return new Value("", null, null, component);
            }
            if (display instanceof Number number) {
                return new Value(String.valueOf(display), number, null, null);
            }
            return new Value(String.valueOf(display), null, null, null);
        }
    }
}
