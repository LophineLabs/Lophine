package fun.bm.lophine.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class I18nBidi {
    public static boolean isDefaultRightToLeft() {
        return Language.getInstance().isDefaultRightToLeft();
    }

    public static @NotNull String visual(@Nullable String logical) {
        if (logical == null || logical.isEmpty() || !isDefaultRightToLeft()) {
            return logical == null ? "" : logical;
        }
        Bidi bidi = new Bidi(logical, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
        if (bidi.isLeftToRight()) {
            return logical;
        }
        // java.text.Bidi does not expose an ICU-style writeReordered/DO_MIRRORING API,
        // so reorder per-character using the same levels that callers apply to their
        // style/codepoint arrays, then mirror any bracket whose resolved level is odd.
        int n = logical.length();
        byte[] levels = new byte[n];
        LevelChar[] cells = new LevelChar[n];
        for (int i = 0; i < n; i++) {
            levels[i] = (byte) bidi.getLevelAt(i);
            cells[i] = new LevelChar(logical.charAt(i), levels[i]);
        }
        Bidi.reorderVisually(levels, 0, cells, 0, n);
        StringBuilder sb = new StringBuilder(n);
        for (LevelChar cell : cells) {
            sb.append((cell.level() & 1) == 1 ? mirror(cell.ch()) : cell.ch());
        }
        return sb.toString();
    }

    private static char mirror(char ch) {
        return switch (ch) {
            case '(' -> ')';
            case ')' -> '(';
            case '[' -> ']';
            case ']' -> '[';
            case '{' -> '}';
            case '}' -> '{';
            case '\u00AB' -> '\u00BB'; // LEFT-POINTING DOUBLE ANGLE QUOTATION MARK
            case '\u00BB' -> '\u00AB'; // RIGHT-POINTING DOUBLE ANGLE QUOTATION MARK
            case '\u2039' -> '\u203A'; // SINGLE LEFT-POINTING ANGLE QUOTATION MARK
            case '\u203A' -> '\u2039'; // SINGLE RIGHT-POINTING ANGLE QUOTATION MARK
            case '\u2045' -> '\u2046'; // LEFT SQUARE BRACKET WITH QUILL
            case '\u2046' -> '\u2045'; // RIGHT SQUARE BRACKET WITH QUILL
            case '\u2308' -> '\u2309'; // LEFT CEILING
            case '\u2309' -> '\u2308'; // RIGHT CEILING
            case '\u230A' -> '\u230B'; // LEFT FLOOR
            case '\u230B' -> '\u230A'; // RIGHT FLOOR
            case '\u2329' -> '\u232A'; // LEFT-POINTING ANGLE BRACKET
            case '\u232A' -> '\u2329'; // RIGHT-POINTING ANGLE BRACKET
            case '\u27E8' -> '\u27E9'; // MATHEMATICAL LEFT ANGLE BRACKET
            case '\u27E9' -> '\u27E8'; // MATHEMATICAL RIGHT ANGLE BRACKET
            case '\u3008' -> '\u3009'; // CJK LEFT ANGLE BRACKET
            case '\u3009' -> '\u3008'; // CJK RIGHT ANGLE BRACKET
            case '\u300A' -> '\u300B'; // CJK LEFT DOUBLE ANGLE BRACKET
            case '\u300B' -> '\u300A'; // CJK RIGHT DOUBLE ANGLE BRACKET
            case '\u300C' -> '\u300C'; // CJK LEFT CORNER BRACKET (self-mirror)
            case '\u300D' -> '\u300D'; // CJK RIGHT CORNER BRACKET (self-mirror)
            case '\u3010' -> '\u3011'; // CJK LEFT BLACK LENTICULAR BRACKET
            case '\u3011' -> '\u3010'; // CJK RIGHT BLACK LENTICULAR BRACKET
            case '\uFD3E' -> '\uFD3F'; // ORNATE LEFT PARENTHESIS
            case '\uFD3F' -> '\uFD3E'; // ORNATE RIGHT PARENTHESIS
            case '\uFF08' -> '\uFF09'; // FULLWIDTH LEFT PARENTHESIS
            case '\uFF09' -> '\uFF08'; // FULLWIDTH RIGHT PARENTHESIS
            case '\uFF3B' -> '\uFF3D'; // FULLWIDTH LEFT SQUARE BRACKET
            case '\uFF3D' -> '\uFF3B'; // FULLWIDTH RIGHT SQUARE BRACKET
            case '\uFF5B' -> '\uFF5D'; // FULLWIDTH LEFT CURLY BRACKET
            case '\uFF5D' -> '\uFF5B'; // FULLWIDTH RIGHT CURLY BRACKET
            case '\uFF5F' -> '\uFF61'; // FULLWIDTH LEFT WHITE PARENTHESIS
            case '\uFF61' -> '\uFF5F'; // FULLWIDTH RIGHT WHITE PARENTHESIS
            default -> ch;
        };
    }

    private record LevelChar(char ch, byte level) {
    }

    public static @NotNull Component visual(@Nullable Component component, @NotNull ComponentFlattener flattener) {
        if (component == null || !isDefaultRightToLeft()) {
            return component == null ? Component.empty() : component;
        }
        List<net.kyori.adventure.text.format.Style> stack = new ArrayList<>();
        StringBuilder logical = new StringBuilder();
        List<net.kyori.adventure.text.format.Style> styles = new ArrayList<>();
        flattener.flatten(component, new FlattenerListener() {
            @Override
            public void pushStyle(final net.kyori.adventure.text.format.Style style) {
                stack.add(style);
            }

            @Override
            public void popStyle(final net.kyori.adventure.text.format.Style style) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            }

            @Override
            public void component(final String text) {
                net.kyori.adventure.text.format.Style merged = merge(stack);
                for (int i = 0; i < text.length(); i++) {
                    logical.append(text.charAt(i));
                    styles.add(merged);
                }
            }
        });
        if (styles.isEmpty()) {
            return component;
        }
        String visual = visual(logical.toString());
        if (visual.length() != styles.size()) {
            return Component.text(visual);
        }
        Bidi bidi = new Bidi(logical.toString(), Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
        net.kyori.adventure.text.format.Style[] ordered = styles.toArray(net.kyori.adventure.text.format.Style[]::new);
        if (!bidi.isLeftToRight()) {
            byte[] levels = new byte[ordered.length];
            for (int i = 0; i < ordered.length; i++) {
                levels[i] = (byte) bidi.getLevelAt(i);
            }
            Bidi.reorderVisually(levels, 0, ordered, 0, ordered.length);
        }
        TextComponent.Builder builder = Component.text();
        int index = 0;
        while (index < visual.length()) {
            net.kyori.adventure.text.format.Style style = ordered[index];
            int start = index;
            index++;
            while (index < visual.length() && ordered[index].equals(style)) {
                index++;
            }
            builder.append(Component.text(visual.substring(start, index), style));
        }
        return builder.build();
    }

    public static @NotNull FormattedCharSequence visualOrder(@NotNull FormattedText text, boolean rightToLeft) {
        if (!rightToLeft) {
            return sink -> text.visit(
                    (style, content) -> StringDecomposer.iterateFormatted(content, style, sink) ? Optional.empty() : FormattedText.STOP_ITERATION,
                    Style.EMPTY
            ).isPresent();
        }
        StringBuilder logical = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        List<Integer> codepoints = new ArrayList<>();
        text.visit((style, content) -> {
            StringDecomposer.iterateFormatted(content, style, (position, currentStyle, codepoint) -> {
                logical.appendCodePoint(codepoint);
                styles.add(currentStyle);
                codepoints.add(codepoint);
                return true;
            });
            return Optional.empty();
        }, Style.EMPTY);
        if (codepoints.isEmpty()) {
            return FormattedCharSequence.EMPTY;
        }
        String visual = visual(logical.toString());
        Style[] orderedStyles = styles.toArray(Style[]::new);
        Integer[] orderedCodepoints = codepoints.toArray(Integer[]::new);
        Bidi bidi = new Bidi(logical.toString(), Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
        if (!bidi.isLeftToRight()) {
            byte[] levels = new byte[orderedStyles.length];
            for (int i = 0; i < orderedStyles.length; i++) {
                levels[i] = (byte) bidi.getLevelAt(i);
            }
            Bidi.reorderVisually(levels, 0, orderedStyles, 0, orderedStyles.length);
            Bidi.reorderVisually(levels, 0, orderedCodepoints, 0, orderedCodepoints.length);
        }
        return sink -> {
            int position = 0;
            if (visual.length() == orderedCodepoints.length) {
                for (int i = 0; i < orderedCodepoints.length; i++) {
                    if (!sink.accept(position++, orderedStyles[i], visual.charAt(i))) {
                        return false;
                    }
                }
                return true;
            }
            for (int i = 0; i < orderedCodepoints.length; i++) {
                if (!sink.accept(position++, orderedStyles[i], orderedCodepoints[i])) {
                    return false;
                }
            }
            return true;
        };
    }

    private static net.kyori.adventure.text.format.Style merge(List<net.kyori.adventure.text.format.Style> stack) {
        net.kyori.adventure.text.format.Style style = net.kyori.adventure.text.format.Style.empty();
        for (net.kyori.adventure.text.format.Style layer : stack) {
            style = style.merge(layer);
        }
        return style;
    }
}
