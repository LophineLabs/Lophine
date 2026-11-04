package fun.bm.lophine.utils.markdown;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class MarkdownTemplate {
    private final List<Node> nodes;

    MarkdownTemplate(List<Node> nodes) {
        this.nodes = List.copyOf(nodes);
    }

    public Component render() {
        return render(MarkdownArgs.empty());
    }

    public Component render(MarkdownArgs args) {
        return renderAll(nodes, args);
    }

    private static Component renderAll(List<Node> nodes, MarkdownArgs args) {
        if (nodes.isEmpty()) {
            return Component.empty();
        }
        if (nodes.size() == 1) {
            return renderNode(nodes.getFirst(), args);
        }
        TextComponent.Builder builder = Component.text();
        for (Node node : nodes) {
            builder.append(renderNode(node, args));
        }
        return builder.build();
    }

    private static Component renderNode(Node node, MarkdownArgs args) {
        return switch (node) {
            case Node.Text text -> Component.text(text.value(), text.style());
            case Node.Placeholder placeholder -> renderPlaceholder(placeholder, args);
            case Node.Span span ->
                    Component.text().style(span.style()).append(renderAll(span.children(), args)).build();
            case Node.Link link -> renderLink(link, args);
        };
    }

    private static Component renderPlaceholder(Node.Placeholder placeholder, MarkdownArgs args) {
        MarkdownArgs.Value value = args.get(placeholder.name());
        if (value == null) {
            return Component.empty();
        }
        if (value.component() != null) {
            return value.component();
        }
        Style style = placeholder.style();
        TextColor color = resolveColor(placeholder, value);
        if (color != null) {
            style = style.color(color);
        }
        return Component.text(value.display(), style);
    }

    private static @Nullable TextColor resolveColor(Node.Placeholder placeholder, MarkdownArgs.Value value) {
        if (placeholder.scale() != null && value.number() != null) {
            TextColor scaled = MarkdownColorScales.byName(placeholder.scale(), value.number().doubleValue());
            if (scaled != null) {
                return scaled;
            }
        }
        return value.color();
    }

    private static Component renderLink(Node.Link link, MarkdownArgs args) {
        Component label = renderAll(link.label(), args);
        String run = interpolate(link.run(), args);
        String suggest = interpolate(link.suggest(), args);
        String url = interpolate(link.url(), args);
        if (run != null && !run.isEmpty()) {
            label = label.clickEvent(ClickEvent.runCommand(run));
        } else if (suggest != null && !suggest.isEmpty()) {
            label = label.clickEvent(ClickEvent.suggestCommand(suggest));
        } else if (url != null && !url.isEmpty()) {
            label = label.clickEvent(ClickEvent.openUrl(url));
        }
        if (!link.hover().isEmpty()) {
            label = label.hoverEvent(HoverEvent.showText(renderAll(link.hover(), args)));
        }
        return label;
    }

    private static @Nullable String interpolate(@Nullable List<StringPart> parts, MarkdownArgs args) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (StringPart part : parts) {
            switch (part) {
                case StringPart.Lit lit -> builder.append(lit.value());
                case StringPart.Arg arg -> {
                    MarkdownArgs.Value value = args.get(arg.name());
                    if (value != null) {
                        builder.append(value.display());
                    }
                }
            }
        }
        return builder.toString();
    }

    sealed interface Node {
        record Text(String value, Style style) implements Node {
        }

        record Placeholder(String name, @Nullable String scale, Style style) implements Node {
        }

        record Span(Style style, List<Node> children) implements Node {
        }

        record Link(
                List<Node> label,
                @Nullable List<StringPart> run,
                @Nullable List<StringPart> suggest,
                @Nullable List<StringPart> url,
                List<Node> hover
        ) implements Node {
        }
    }

    sealed interface StringPart {
        record Lit(String value) implements StringPart {
        }

        record Arg(String name) implements StringPart {
        }
    }

    static Style decoration(TextDecoration decoration) {
        return Style.style(decoration);
    }
}
