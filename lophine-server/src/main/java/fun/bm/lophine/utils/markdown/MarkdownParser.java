package fun.bm.lophine.utils.markdown;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom markdown for Lophine command output. Parsed once when I18n loads.
 *
 * <pre>
 * **bold**  *italic*  __underline__  ~~strike~~
 * {header}text{/}  {primary+bold}text{/}  {#4fa4f0}text{/}  {red}text{/}
 * {players}  {tps|tps}  {mspt|mspt}  {util|util}  {used|mem}
 * [label](run:/cmd)  [label](suggest:/cmd|hover:hint)  [label](url:https://...)
 * \{  \}  \*  \[  \\
 * </pre>
 * <p>
 * Palette names: {@code header}, {@code primary}, {@code secondary}, {@code info}, {@code list}.
 * Placeholder scales: {@code tps}, {@code mspt}, {@code util}, {@code mem}.
 */
public final class MarkdownParser {
    private static final Map<String, TextColor> PALETTE = Map.of(
            "header", TextColor.color(79, 164, 240),
            "primary", TextColor.color(48, 145, 237),
            "secondary", TextColor.color(104, 177, 240),
            "info", TextColor.color(180, 220, 255),
            "list", TextColor.color(33, 97, 188)
    );

    private static final Map<String, TextDecoration> DECORATIONS = Map.of(
            "bold", TextDecoration.BOLD,
            "italic", TextDecoration.ITALIC,
            "underline", TextDecoration.UNDERLINED,
            "strike", TextDecoration.STRIKETHROUGH,
            "obfuscated", TextDecoration.OBFUSCATED,
            "magic", TextDecoration.OBFUSCATED
    );

    private final String source;
    private int index;

    private MarkdownParser(String source) {
        this.source = source;
    }

    public static MarkdownTemplate parse(String source) {
        if (source == null || source.isEmpty()) {
            return new MarkdownTemplate(List.of());
        }
        return new MarkdownTemplate(new MarkdownParser(source.replace("\r\n", "\n").replace('\r', '\n')).parseNodes(null));
    }

    private List<MarkdownTemplate.Node> parseNodes(@Nullable String closer) {
        List<MarkdownTemplate.Node> nodes = new ArrayList<>();
        while (!eof()) {
            if (closer != null && startsWith(closer)) {
                index += closer.length();
                return nodes;
            }
            char current = peek();
            if (current == '\\') {
                nodes.add(new MarkdownTemplate.Node.Text(String.valueOf(parseEscape()), Style.empty()));
                continue;
            }
            if (startsWith("**")) {
                index += 2;
                nodes.add(new MarkdownTemplate.Node.Span(MarkdownTemplate.decoration(TextDecoration.BOLD), parseNodes("**")));
                continue;
            }
            if (startsWith("__")) {
                index += 2;
                nodes.add(new MarkdownTemplate.Node.Span(MarkdownTemplate.decoration(TextDecoration.UNDERLINED), parseNodes("__")));
                continue;
            }
            if (startsWith("~~")) {
                index += 2;
                nodes.add(new MarkdownTemplate.Node.Span(MarkdownTemplate.decoration(TextDecoration.STRIKETHROUGH), parseNodes("~~")));
                continue;
            }
            if (current == '*' && (closer == null || !closer.equals("*"))) {
                index++;
                nodes.add(new MarkdownTemplate.Node.Span(MarkdownTemplate.decoration(TextDecoration.ITALIC), parseNodes("*")));
                continue;
            }
            if (current == '[') {
                nodes.add(parseLink());
                continue;
            }
            if (current == '{') {
                nodes.addAll(parseBrace(closer));
                continue;
            }
            nodes.add(new MarkdownTemplate.Node.Text(parseText(closer), Style.empty()));
        }
        return nodes;
    }

    private List<MarkdownTemplate.Node> parseBrace(@Nullable String closer) {
        int start = index;
        index++; // {
        if (startsWith("/}")) {
            if ("{/}".equals(closer)) {
                index = start;
                return List.of();
            }
            index += 2;
            return List.of();
        }
        String spec = readIdentifierSpec();
        if (spec.isEmpty() || eof() || peek() != '}') {
            index = start + 1;
            return List.of(new MarkdownTemplate.Node.Text("{", Style.empty()));
        }
        index++; // }
        int pipe = spec.indexOf('|');
        if (pipe >= 0) {
            String name = spec.substring(0, pipe);
            String scale = spec.substring(pipe + 1);
            if (isPlaceholderName(name) && isScaleName(scale)) {
                return List.of(new MarkdownTemplate.Node.Placeholder(name, scale, Style.empty()));
            }
            index = start + 1;
            return List.of(new MarkdownTemplate.Node.Text("{", Style.empty()));
        }
        Style style = parseStyleSpec(spec);
        if (style != null) {
            return List.of(new MarkdownTemplate.Node.Span(style, parseNodes("{/}")));
        }
        if (isPlaceholderName(spec)) {
            return List.of(new MarkdownTemplate.Node.Placeholder(spec, null, Style.empty()));
        }
        index = start + 1;
        return List.of(new MarkdownTemplate.Node.Text("{", Style.empty()));
    }

    private MarkdownTemplate.Node parseLink() {
        int start = index;
        index++; // [
        List<MarkdownTemplate.Node> label = parseNodes("]");
        if (eof() || peek() != '(') {
            index = start + 1;
            List<MarkdownTemplate.Node> fallback = new ArrayList<>();
            fallback.add(new MarkdownTemplate.Node.Text("[", Style.empty()));
            fallback.addAll(label);
            return new MarkdownTemplate.Node.Span(Style.empty(), fallback);
        }
        index++; // (
        int specStart = index;
        int braceDepth = 0;
        int parenDepth = 0;
        while (!eof()) {
            char current = peek();
            if (current == '\\') {
                index += Math.min(2, source.length() - index);
                continue;
            }
            if (current == '{') {
                braceDepth++;
            } else if (current == '}' && braceDepth > 0) {
                braceDepth--;
            } else if (current == '(' && braceDepth == 0) {
                parenDepth++;
            } else if (current == ')' && braceDepth == 0) {
                if (parenDepth == 0) {
                    break;
                }
                parenDepth--;
            }
            index++;
        }
        String spec = source.substring(specStart, index);
        if (!eof() && peek() == ')') {
            index++;
        }
        List<MarkdownTemplate.StringPart> run = null;
        List<MarkdownTemplate.StringPart> suggest = null;
        List<MarkdownTemplate.StringPart> url = null;
        List<MarkdownTemplate.Node> hover = List.of();
        for (String piece : splitSpec(spec)) {
            int colon = piece.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String kind = piece.substring(0, colon).trim();
            String value = piece.substring(colon + 1);
            switch (kind) {
                case "run" -> run = parseStringParts(value);
                case "suggest" -> suggest = parseStringParts(value);
                case "url" -> url = parseStringParts(value);
                case "hover" -> hover = new MarkdownParser(value).parseNodes(null);
                default -> {
                }
            }
        }
        return new MarkdownTemplate.Node.Link(label, run, suggest, url, hover);
    }

    private String parseText(@Nullable String closer) {
        int start = index;
        while (!eof()) {
            if (closer != null && startsWith(closer)) {
                break;
            }
            char current = peek();
            if (current == '\\' || current == '{' || current == '[') {
                break;
            }
            if (startsWith("**") || startsWith("__") || startsWith("~~")) {
                break;
            }
            if (current == '*') {
                break;
            }
            index++;
        }
        if (index == start) {
            index++;
            return source.substring(start, index);
        }
        return source.substring(start, index);
    }

    private char parseEscape() {
        index++; // \
        if (eof()) {
            return '\\';
        }
        char escaped = peek();
        index++;
        return escaped;
    }

    private String readIdentifierSpec() {
        int start = index;
        while (!eof()) {
            char current = peek();
            if (current == '}' || current == ' ' || current == '\n' || current == '\r' || current == '\t') {
                break;
            }
            index++;
        }
        return source.substring(start, index);
    }

    private boolean eof() {
        return index >= source.length();
    }

    private char peek() {
        return source.charAt(index);
    }

    private boolean startsWith(String token) {
        return source.startsWith(token, index);
    }

    private static boolean isPlaceholderName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        if (first != '_' && !Character.isLetter(first)) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char current = name.charAt(i);
            if (current != '_' && !Character.isLetterOrDigit(current)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isScaleName(String scale) {
        return MarkdownColorScales.byName(scale, 0.0) != null;
    }

    private static @Nullable Style parseStyleSpec(String spec) {
        Style style = Style.empty();
        boolean matched = false;
        for (String token : spec.split("\\+")) {
            if (token.isEmpty()) {
                return null;
            }
            TextDecoration decoration = DECORATIONS.get(token);
            if (decoration != null) {
                style = style.decorate(decoration);
                matched = true;
                continue;
            }
            TextColor color = resolveColorToken(token);
            if (color == null) {
                return null;
            }
            style = style.color(color);
            matched = true;
        }
        return matched ? style : null;
    }

    private static @Nullable TextColor resolveColorToken(String token) {
        TextColor palette = PALETTE.get(token);
        if (palette != null) {
            return palette;
        }
        if (token.startsWith("#")) {
            return TextColor.fromHexString(expandHex(token));
        }
        return NamedTextColor.NAMES.value(token.toLowerCase(Locale.ROOT));
    }

    private static String expandHex(String token) {
        if (token.length() == 4) {
            return "#" + token.charAt(1) + token.charAt(1) + token.charAt(2) + token.charAt(2) + token.charAt(3) + token.charAt(3);
        }
        return token;
    }

    private static List<String> splitSpec(String spec) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < spec.length(); i++) {
            char ch = spec.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && depth > 0) {
                depth--;
            } else if (ch == '|' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        parts.add(current.toString());
        return parts;
    }

    static List<MarkdownTemplate.StringPart> parseStringParts(String source) {
        List<MarkdownTemplate.StringPart> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '\\' && i + 1 < source.length()) {
                literal.append(source.charAt(++i));
                continue;
            }
            if (ch == '{') {
                int end = source.indexOf('}', i + 1);
                if (end > i + 1) {
                    String name = source.substring(i + 1, end);
                    if (isPlaceholderName(name)) {
                        if (!literal.isEmpty()) {
                            parts.add(new MarkdownTemplate.StringPart.Lit(literal.toString()));
                            literal.setLength(0);
                        }
                        parts.add(new MarkdownTemplate.StringPart.Arg(name));
                        i = end;
                        continue;
                    }
                }
            }
            literal.append(ch);
        }
        if (!literal.isEmpty()) {
            parts.add(new MarkdownTemplate.StringPart.Lit(literal.toString()));
        }
        return parts;
    }
}
