// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet.org;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemPredicateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses the upstream Org script_action object names, with native item-predicate parsing on restore.
 */
public final class OrgFakePlayerActionCodec {
    private OrgFakePlayerActionCodec() {
    }

    public static JsonObject write(OrgFakePlayerActions.Action action) {
        String key = switch (action.kind()) {
            case "craft_inventory" -> "inventory_craft";
            case "craft_table" -> "crafting_table_craft";
            case "sorting" -> "categorize";
            case "empty" -> "empty_the_container";
            case "fill" -> "fill_the_container";
            default -> action.kind();
        };
        JsonObject data = new JsonObject();
        switch (action.kind()) {
            case "craft_inventory", "craft_table" -> {
                for (int i = 0; i < action.filters().size(); i++)
                    data.addProperty(String.valueOf(i), action.filters().get(i).expression());
            }
            case "sorting" -> {
                JsonArray filters = new JsonArray();
                action.filters().forEach(filter -> filters.add(filter.expression()));
                data.add("item", filters);
                data.add("thisVec", vector(action.selected()));
                data.add("otherVec", vector(action.other()));
            }
            case "empty", "fill", "rename", "stonecutting", "enchanting" ->
                    data.addProperty("item", action.filters().getFirst().expression());
        }
        if (action.kind().equals("fill")) {
            data.addProperty("dropOther", action.dropOther());
            data.addProperty("moreContainer", action.moreContainer());
        }
        if (action.kind().equals("rename")) data.addProperty("name", action.name());
        if (action.kind().equals("stonecutting")) data.addProperty("button", action.index());
        if (action.kind().equals("trade")) {
            data.addProperty("index", action.index());
            data.addProperty("void_trade", action.voidTrade());
        }
        if (action.enchantment() != null)
            data.addProperty("enchantment", action.enchantment().unwrapKey().orElseThrow().identifier().toString());
        if (action.librarian() != null) writeLibrarian(data, action.librarian());
        JsonObject result = new JsonObject();
        result.add(key, data);
        return result;
    }

    public static OrgFakePlayerActions.Action read(JsonObject record, CommandBuildContext access) throws CommandSyntaxException {
        if (record.isEmpty()) return OrgFakePlayerActions.Action.simple("stop", List.of());
        if (record.size() != 1) throw new IllegalArgumentException("An Org action has exactly one kind");
        var entry = record.entrySet().iterator().next();
        String key = entry.getKey();
        JsonObject data = entry.getValue().getAsJsonObject();
        String kind = switch (key) {
            case "inventory_craft" -> "craft_inventory";
            case "crafting_table_craft" -> "craft_table";
            case "categorize" -> "sorting";
            case "empty_the_container" -> "empty";
            case "fill_the_container" -> "fill";
            default -> key;
        };
        var filters = new ArrayList<OrgFakePlayerActions.Filter>();
        switch (kind) {
            case "craft_inventory", "craft_table" -> {
                int size = kind.equals("craft_inventory") ? 4 : 9;
                for (int i = 0; i < size; i++) filters.add(filter(data.get(String.valueOf(i)).getAsString(), access));
            }
            case "sorting" -> {
                JsonElement value = data.get("item");
                if (value.isJsonArray()) {
                    for (JsonElement element : value.getAsJsonArray())
                        filters.add(filter(element.getAsString(), access));
                } else filters.add(filter(value.getAsString(), access));
                if (filters.isEmpty()) throw new IllegalArgumentException("Sorting requires an item predicate");
            }
            case "empty", "fill", "rename", "stonecutting", "enchanting" ->
                    filters.add(filter(data.get("item").getAsString(), access));
            case "stop", "fishing", "trade", "librarian" -> {
            }
            default -> throw new IllegalArgumentException("Unknown or unregistered Org action " + kind);
        }
        Holder<Enchantment> enchantment = data.has("enchantment") ? access.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse(data.get("enchantment").getAsString()))) : null;
        OrgFakePlayerActions.Librarian librarian = kind.equals("librarian") ? readLibrarian(data) : null;
        return new OrgFakePlayerActions.Action(kind, filters, data.has("index") ? data.get("index").getAsInt() : data.has("button") ? data.get("button").getAsInt() : 0,
                data.has("name") ? data.get("name").getAsString() : "", enchantment, !data.has("dropOther") || data.get("dropOther").getAsBoolean(), data.has("moreContainer") && data.get("moreContainer").getAsBoolean(), data.has("void_trade") && data.get("void_trade").getAsBoolean(),
                kind.equals("sorting") ? vector(data.getAsJsonArray("thisVec")) : Vec3.ZERO, kind.equals("sorting") ? vector(data.getAsJsonArray("otherVec")) : Vec3.ZERO, librarian);
    }

    static void writeLibrarian(JsonObject data, OrgFakePlayerActions.Librarian spec) {
        data.addProperty("type", spec.mode());
        data.add("lectern_pos", position(spec.jobSite()));
        if (spec.priceLevel() == null) {
            data.addProperty("min_level", spec.minLevel());
            data.addProperty("max_price", spec.maxPrice());
        } else {
            data.addProperty("price", spec.priceLevel().id());
            if (spec.from() != null) {
                data.add("from", position(spec.from()));
                data.add("to", position(spec.to()));
            }
        }
        data.addProperty("start_time", spec.startTime());
        data.addProperty("refresh_count", spec.refreshCount());
    }

    static OrgFakePlayerActions.Librarian readLibrarian(JsonObject data) {
        BlockPos jobSite = position(data.getAsJsonObject(data.has("lectern_pos") ? "lectern_pos" : "block_pos"));
        String mode = data.has("type") ? data.get("type").getAsString() : "specific";
        long started = data.has("start_time") ? data.get("start_time").getAsLong() : 0;
        int refreshes = data.has("refresh_count") ? data.get("refresh_count").getAsInt() : 0;
        return switch (mode) {
            case "specific" -> new OrgFakePlayerActions.Librarian(jobSite, data.get("min_level").getAsInt(),
                    data.get("max_price").getAsInt(), null, null, null, started, refreshes);
            case "any", "missing" -> {
                OrgLibrarianSearch.PriceLevel price = OrgLibrarianSearch.PriceLevel.valueOf(data.get("price").getAsString().toUpperCase(java.util.Locale.ROOT));
                BlockPos from = mode.equals("missing") ? position(data.getAsJsonObject("from")) : null;
                BlockPos to = mode.equals("missing") ? position(data.getAsJsonObject("to")) : null;
                yield new OrgFakePlayerActions.Librarian(jobSite, -1, -1, price, from, to, started, refreshes);
            }
            default -> throw new IllegalArgumentException("Unknown librarian search mode: " + mode);
        };
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        return data;
    }

    private static BlockPos position(JsonObject pos) {
        return new BlockPos(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt());
    }

    static OrgFakePlayerActions.Filter filter(String expression, CommandBuildContext access) throws CommandSyntaxException {
        if (expression.equals("*") || expression.equals("minecraft:air") || expression.equals("air"))
            return expression.equals("*") ? OrgFakePlayerActions.ANY : OrgFakePlayerActions.EMPTY;
        StringReader reader = new StringReader(expression);
        var predicate = ItemPredicateArgument.itemPredicate(access).parse(reader);
        if (reader.canRead()) throw new IllegalArgumentException("Unexpected trailing item-predicate data");
        return new OrgFakePlayerActions.Filter(expression, predicate);
    }

    private static JsonArray vector(Vec3 value) {
        JsonArray result = new JsonArray();
        result.add(value.x);
        result.add(value.y);
        result.add(value.z);
        return result;
    }

    private static Vec3 vector(JsonArray value) {
        if (value.size() != 3) throw new IllegalArgumentException("An action vector has exactly three coordinates");
        return new Vec3(value.get(0).getAsDouble(), value.get(1).getAsDouble(), value.get(2).getAsDouble());
    }
}
