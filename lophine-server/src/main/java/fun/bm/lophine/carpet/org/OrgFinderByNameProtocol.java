// SPDX-License-Identifier: MIT
// Wire format from Carpet Org Addition 69264014, copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet.org;

import carpet.script.external.ScarpetNativeWork;
import com.google.gson.JsonParser;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.carpet.tis.TisCommandContinuations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class OrgFinderByNameProtocol {
    private OrgFinderByNameProtocol() {}

    record Request(String type, String name, int range, List<Identifier> ids) {
    }

    static Request decode(FriendlyByteBuf buffer) {
        try {
            if (buffer.readInt() != 1) return null;
            String type = buffer.readUtf(32);
            String field = switch (type) {
                case "item", "trade_item", "offline_player_item" -> "items";
                case "block" -> "blocks";
                default -> null;
            };
            if (field == null) return null;
            String name = buffer.readUtf(256);
            var data = JsonParser.parseString(buffer.readUtf(4096)).getAsJsonObject();
            if (buffer.isReadable()) return null;
            int range = type.equals("offline_player_item") ? 0 : data.get("range").getAsBigDecimal().intValueExact();
            var list = data.getAsJsonArray(field);
            if (range < 0 || range > 512 || list.isEmpty() || list.size() > 256) return null;
            var ids = new LinkedHashSet<Identifier>();
            for (var element : list) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
                Identifier id = Identifier.tryParse(element.getAsString());
                if (id == null) return null;
                ids.add(id);
            }
            return new Request(type, name, range, List.copyOf(ids));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public static boolean receive(ServerPlayer player, FriendlyByteBuf buffer) {
        Request request = decode(buffer);
        if (request == null) return true;
        var work = TisCommandContinuations.owned(player, () -> {
            if (player.hasDisconnected()) return null;
            var source = player.createCommandSourceStack();
            String permission = switch (request.type()) {
                case "trade_item" -> "finder.trade";
                case "offline_player_item" -> "finder.item.from.offline_player";
                default -> "finder." + request.type();
            };
            if (!OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandFinder) || !OrgServerPermissions.allowed(source, permission)) {
                source.sendFailure(OrgFinderText.localized("carpet-org-addition.operation.insufficient_permissions"));
                return null;
            }
            try {
                var label = Component.literal(request.name());
                var bounds = request.type().equals("offline_player_item") ? null
                        : OrgFinderBounds.radius(player.level(), player.blockPosition(), request.range());
                if (request.type().equals("block")) {
                    var blocks = request.ids().stream().map(BuiltInRegistries.BLOCK::getOptional).flatMap(java.util.Optional::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());
                    if (!blocks.isEmpty()) OrgFinderService.start(source, player, bounds, OrgFinderService.Kind.BLOCK,
                            block -> blocks.contains(block.getState().getBlock()), null, null, state -> blocks.contains(state.getBlock()), label, "");
                } else {
                    Set<Item> items = request.ids().stream().map(BuiltInRegistries.ITEM::getOptional).flatMap(java.util.Optional::stream)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    if (items.isEmpty()) return null;
                    java.util.function.Predicate<net.minecraft.world.item.ItemStack> predicate =
                            stack -> !stack.isEmpty() && items.contains(stack.getItem());
                    if (bounds == null) OrgFinderFiles.start(source, player, false, predicate, label, "");
                    else OrgFinderService.start(source, player, bounds,
                            request.type().equals("trade_item") ? OrgFinderService.Kind.TRADE : OrgFinderService.Kind.ITEM,
                            null, predicate, null, state -> !state.isAir(), label, "");
                }
            } catch (IllegalStateException | IllegalArgumentException failure) {
                source.sendFailure(OrgFinderService.commandFailure(failure));
            }
            return null;
        });
        ScarpetNativeWork.record(work);
        return true;
    }
}
