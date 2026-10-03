// SPDX-License-Identifier: MIT
// Source FakePlayerSerializer and PlayerManagerCommand c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.*;

/**
 * Original server Components are built from immutable saved profile values, never a live fake actor.
 */
final class OrgPlayerProfileMessages {
    record Output(List<Component> headers, List<Component> rows, boolean pages, int count) {
    }

    private OrgPlayerProfileMessages() {
    }

    private static MutableComponent text(String suffix, String fallback, Object... args) {
        String key = "carpet-org-addition." + suffix;
        return Component.translatableWithFallback(key, OrgRuleTranslations.text(key, fallback), args);
    }

    private static MutableComponent manager(String suffix, String fallback, Object... args) {
        return text("command.playerManager." + suffix, fallback, args);
    }

    private static Component toggle(boolean value) {
        return text("literal." + value, value ? "True" : "False");
    }

    private static Component dimension(String value) {
        return switch (value) {
            case "overworld", "minecraft:overworld" -> text("dimension.overworld", "Overworld");
            case "the_nether", "minecraft:the_nether" -> text("dimension.the_nether", "Nether");
            case "the_end", "minecraft:the_end" -> text("dimension.the_end", "End");
            default -> Component.literal(value);
        };
    }

    private static String number(JsonElement value) {
        return new java.text.DecimalFormat("#.##").format(value.getAsDouble());
    }

    static Component info(String name, JsonObject profile) {
        List<Component> rows = new ArrayList<>();
        rows.add(manager("info.name", "Name: %s", name));
        var position = profile.getAsJsonObject("pos");
        rows.add(manager("info.pos", "Position: [%s, %s, %s]", number(position.get("x")), number(position.get("y")), number(position.get("z"))));
        var direction = profile.getAsJsonObject("direction");
        rows.add(manager("info.direction", "Facing: [%s, %s]", number(direction.get("yaw")), number(direction.get("pitch"))));
        rows.add(manager("info.dimension", "Dimension: %s", dimension(profile.get("dimension").getAsString())));
        rows.add(manager("info.gamemode", "Mode: %s", net.minecraft.world.level.GameType.byName(profile.get("gamemode").getAsString()).getLongDisplayName()));
        for (String key : List.of("flying", "sneaking", "autologin"))
            rows.add(manager("info." + key, key + ": %s", toggle(profile.has(key) && profile.get(key).getAsBoolean())));
        List<String> groups = groups(profile);
        if (!groups.isEmpty())
            rows.add(manager("info.group", "Group: %s", groups.size() == 1 ? groups.getFirst() : "[" + String.join(", ", groups) + "]"));
        JsonObject simple = profile.has("simple_action") ? profile.getAsJsonObject("simple_action") : new JsonObject(), script = profile.has("script_action") ? profile.getAsJsonObject("script_action") : new JsonObject();
        Component scriptName = OrgPlayerActionPresentation.profileName(script);
        boolean hasScript = !(scriptName.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translated && translated.getKey().equals("carpet-org-addition.command.playerAction.stop"));
        if (!simple.isEmpty() || hasScript) {
            rows.add(manager("info.action", "Action:"));
            for (var entry : simple.entrySet()) {
                JsonObject action = entry.getValue().getAsJsonObject();
                rows.add(indent(1, manager("info." + entry.getKey(), entry.getKey())));
                rows.add(indent(2, action.has("continuous") && action.get("continuous").getAsBoolean() ? manager("info.continuous", "Continuous") : manager("info.interval", "Interval %s game tick", action.has("interval") ? action.get("interval").getAsInt() : 1)));
            }
            if (hasScript) rows.add(indent(1, scriptName));
        }
        if (profile.has("startup_action") && !profile.getAsJsonArray("startup_action").isEmpty()) {
            rows.add(manager("info.startup", "On login:"));
            var startup = new ArrayList<JsonObject>();
            for (var value : profile.getAsJsonArray("startup_action")) startup.add(value.getAsJsonObject());
            startup.sort(Comparator.comparingInt(entry -> entry.getAsJsonObject("function").get("type").getAsString().equals("simple") ? 0 : 1));
            for (var entry : startup) {
                var function = entry.getAsJsonObject("function");
                String value = function.get("value").getAsString();
                rows.add(indent(1, function.get("type").getAsString().equals("command") ? manager("info.startup.run", "Execute command: %s", value) : manager("info.startup." + value, switch (value) {
                    case "use" -> "Right-click";
                    case "attack" -> "Left-click";
                    default -> "Disconnect";
                })));
                int delay = entry.has("delay") ? entry.get("delay").getAsInt() : 1;
                if (delay > 1) rows.add(indent(2, manager("info.startup.delay", "Delay: %s ticks", delay)));
            }
        }
        if (profile.has("annotation") && !profile.get("annotation").getAsString().isEmpty())
            rows.add(manager("info.comment", "Comment: %s", profile.get("annotation").getAsString()));
        return join(rows, "\n");
    }

    private static Component indent(int level, Component text) {
        return Component.literal("    ".repeat(level)).append(text);
    }

    private static Component join(List<Component> parts, String delimiter) {
        var result = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i != 0) result.append(delimiter);
            result.append(parts.get(i));
        }
        return result;
    }

    private static Component button(String label, String command, String hover, ChatFormatting color) {
        var result = Component.literal(label).withStyle(color).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(text("button." + hover, hover))));
        if (command != null) result.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)));
        return result;
    }

    static Component line(String name, JsonObject profile) {
        var result = Component.empty().append(button("[↑]", "/playerManager spawn " + com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(name), "login", ChatFormatting.GREEN)).append(" ").append(button("[↓]", "/player " + name + " kill", "logout", ChatFormatting.RED)).append(" ").append(Component.literal("[?]").withStyle(ChatFormatting.GRAY).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(info(name, profile))))).append(" ").append(name);
        String comment = profile.has("annotation") ? profile.get("annotation").getAsString() : "";
        if (!comment.isEmpty())
            result.append(Component.literal("    // " + comment).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        return result;
    }

    private static List<String> groups(JsonObject profile) {
        return profile.has("group") ? profile.getAsJsonArray("group").asList().stream().map(JsonElement::getAsString).distinct().toList() : List.of();
    }

    private static Component groupButton(Component title, String command, int size) {
        return title.copy().withStyle(style -> style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(manager("group.player", "This group contains %s players", size))));
    }

    static Output list(Map<String, JsonObject> saved, String group, String filter, boolean ungrouped, boolean overview) {
        var profiles = new TreeMap<String, JsonObject>(String.CASE_INSENSITIVE_ORDER);
        profiles.putAll(saved);
        var rows = new ArrayList<Component>();
        var headers = new ArrayList<Component>();
        if (overview) {
            if (profiles.isEmpty())
                return new Output(List.of(manager("list.no_player", "No players being saved")), List.of(), false, 0);
            var memberships = new TreeMap<String, Integer>();
            int outside = 0;
            for (var profile : profiles.values()) {
                var groups = groups(profile);
                if (groups.isEmpty()) outside++;
                else for (String member : groups) memberships.merge(member, 1, Integer::sum);
            }
            if (memberships.size() + (outside > 0 ? 1 : 0) > 1) {
                headers.add(manager("list.expand", "Click group name to expand player list:"));
                for (var entry : memberships.entrySet())
                    rows.add(groupButton(Component.literal("[" + entry.getKey() + "]"), "/playerManager group list group " + com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(entry.getKey()), entry.getValue()));
                if (outside > 0)
                    rows.add(groupButton(manager("group.name.ungrouped", "[Ungrouped]"), "/playerManager group list ungrouped", outside));
                rows.add(groupButton(manager("group.name.all", "[All]"), "/playerManager group list all", profiles.size()));
                return new Output(headers, List.of(join(rows, " ")), false, rows.size());
            }
        }
        for (var entry : profiles.entrySet()) {
            String name = entry.getKey();
            var profile = entry.getValue();
            String comment = profile.has("annotation") ? profile.get("annotation").getAsString() : "";
            if (group != null && !groups(profile).contains(group) || ungrouped && !groups(profile).isEmpty() || filter != null && !name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)) && !comment.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
                continue;
            rows.add(line(name, profile));
        }
        if (rows.isEmpty() && (group != null || ungrouped))
            throw new IllegalArgumentException(manager("group.non_existent", "The player group %s does not exist", group == null ? manager("group.name.ungrouped", "[Ungrouped]") : group).getString());
        if (rows.isEmpty() && filter != null)
            return new Output(List.of(manager("list.no_player", "No players being saved")), List.of(), false, 0);
        headers.add(Component.empty());
        headers.add(group != null ? manager("group.list", "Players in group [%s] (%s %s):", group, button("[↑]", "/playerManager group spawn " + com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(group), "login", ChatFormatting.GREEN), button("[↓]", "/playerManager group kill " + com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(group), "logout", ChatFormatting.RED)) : ungrouped ? manager("group.list.ungrouped", "Ungrouped players:") : filter != null ? manager("list.filter", "All players matching \"%s\":", filter) : manager("group.list.all", "All players:"));
        return new Output(headers, rows, true, rows.size());
    }
}
