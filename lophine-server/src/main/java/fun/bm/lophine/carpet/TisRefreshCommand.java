/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Adapted from Carpet TIS Addition, Fallen_Breath and contributors.
 * Upstream revision: 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
 */
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TisRefreshCommand {
    private static final Set<UUID> REFRESHING = ConcurrentHashMap.newKeySet();

    private TisRefreshCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("refresh")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandRefresh))
                .then(Commands.literal("inventory")
                        .executes(context -> refreshInventories(context.getSource(), List.of(context.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> refreshInventories(context.getSource(), EntityArgument.getPlayers(context, "players")))))
                .then(Commands.literal("chunk")
                        .executes(context -> refresh(context.getSource(), new Selection(true, null, null)))
                        .then(Commands.literal("current").executes(context -> refresh(context.getSource(), new Selection(true, null, null))))
                        .then(Commands.literal("all").executes(context -> refresh(context.getSource(), new Selection(false, null, null))))
                        .then(Commands.literal("inrange").then(Commands.argument("chebyshevDistance", IntegerArgumentType.integer())
                                .executes(context -> {
                                    int distance = IntegerArgumentType.getInteger(context, "chebyshevDistance");
                                    return refresh(context.getSource(), new Selection(false, null, distance));
                                })))
                        .then(Commands.literal("at").then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("chunkZ", IntegerArgumentType.integer()).executes(context -> refresh(context.getSource(),
                                        new Selection(false, new ChunkPos(IntegerArgumentType.getInteger(context, "chunkX"), IntegerArgumentType.getInteger(context, "chunkZ")), null))))))));
    }

    private static int refreshInventories(final CommandSourceStack source, final Collection<ServerPlayer> players) {
        return TisCommandContinuations.complete(source, players.size(), () -> {
            List<CompletableFuture<Integer>> actuals = new ArrayList<>();
            for (ServerPlayer player : players)
                actuals.add(TisCommandContinuations.entity(source, player, () ->
                        TisCommandContinuations.then(TisCommandContinuations.phase(player, () -> {
                            if (player.isRemoved() || player.hasDisconnected())
                                throw new IllegalStateException("Inventory refresh target retired");
                            source.getServer().getPlayerList().sendAllPlayerInfo(player);
                            return null;
                        }), ignored -> TisCommandContinuations.owned(player, () -> {
                            player.sendSystemMessage(TisTranslations.text("command.refresh.inventory.done"));
                            return 1;
                        }))));
            return TisCommandContinuations.total(actuals);
        }, null, () -> {
        });
    }

    private record Selection(boolean current, ChunkPos selected, Integer distance) {
    }

    private record View(ServerLevel world, List<ChunkPos> chunks, boolean modify, UUID id) {
    }

    private record Prepared(LevelChunk chunk, ClientboundLevelChunkWithLightPacket packet) {
    }

    private static int refresh(final CommandSourceStack source, final Selection selection) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return TisCommandContinuations.complete(source, 1, () -> TisCommandContinuations.entity(source, player, () -> {
            UUID id = player.getUUID();
            if (!REFRESHING.add(id)) {
                source.sendFailure(TisTranslations.message(source, "command.refresh.chunk.overloaded"));
                return CompletableFuture.completedFuture(0);
            }
            try {
                View view = capture(source, player, selection, id);
                if (view == null) {
                    REFRESHING.remove(id);
                    return CompletableFuture.completedFuture(0);
                }
                List<CompletableFuture<Integer>> actuals = new ArrayList<>();
                for (ChunkPos pos : view.chunks()) actuals.add(refreshChunk(source, player, view, pos, 8));
                var chunks = TisCommandContinuations.total(actuals);
                var actual = TisCommandContinuations.then(chunks, count -> report(player, count));
                actual.whenComplete((count, failure) -> REFRESHING.remove(id));
                return actual;
            } catch (Throwable failure) {
                REFRESHING.remove(id);
                return CompletableFuture.failedFuture(failure);
            }
        }), null, () -> {
        });
    }

    /**
     * Capture the player's sent view, anti-xray permission and watched center only on its real owner.
     */
    private static View capture(CommandSourceStack source, ServerPlayer player, Selection selection, UUID id) {
        if (player.isRemoved() || player.hasDisconnected())
            throw new IllegalStateException("Chunk refresh target retired");
        var loader = player.moonrise$getChunkLoader();
        if (loader == null) return null;
        ServerLevel world = player.level();
        ChunkPos selected = selection.current() ? player.chunkPosition() : selection.selected();
        List<ChunkPos> chunks = new ArrayList<>();
        if (selected != null) {
            if (!loader.getSentChunksRaw().contains(selected.pack())) {
                source.sendFailure(TisTranslations.message(source, "command.refresh.chunk.too_far"));
                return null;
            }
            chunks.add(selected);
        } else {
            var watched = player.getLastSectionPos();
            var iterator = loader.getSentChunksRaw().iterator();
            while (iterator.hasNext()) {
                ChunkPos pos = ChunkPos.unpack(iterator.nextLong());
                if (selection.distance() == null || ChunkTrackingView.isWithinDistance(pos.x(), pos.z(), selection.distance(),
                        watched.x(), watched.z(), true)) chunks.add(pos);
            }
        }
        // Both controller implementations inspect only the player bypass permission for null chunk.
        return new View(world, List.copyOf(chunks), world.chunkPacketBlockController.shouldModify(player, null), id);
    }

    private static CompletableFuture<Integer> refreshChunk(CommandSourceStack source, ServerPlayer player, View view, ChunkPos pos, int attempts) {
        if (attempts == 0)
            return CompletableFuture.failedFuture(new IllegalStateException("Refresh target kept changing owner"));
        return TisCommandContinuations.then(TisCommandContinuations.owned(player, () -> player.level() == view.world()
                && !player.hasDisconnected() && !player.isRemoved() ? player.chunkPosition() : null), center -> {
            if (center == null) return CompletableFuture.completedFuture(0);
            // Merge the packet chunk with the current player actor without loading missing chunks.
            return TisCommandContinuations.loadedArea(source, view.world(), Math.min(center.x(), pos.x()), Math.min(center.z(), pos.z()),
                    Math.max(center.x(), pos.x()), Math.max(center.z(), pos.z()), () -> TisCommandContinuations.then(
                            TisCommandContinuations.owned(view.world(), new net.minecraft.core.BlockPos(pos.x() << 4, 0, pos.z() << 4), () -> {
                                LevelChunk chunk = view.world().getChunkIfLoaded(pos.x(), pos.z());
                                return chunk == null ? null : new Prepared(chunk, new ClientboundLevelChunkWithLightPacket(chunk,
                                        view.world().getLightEngine(), null, null, view.modify()));
                            }), prepared -> {
                                if (prepared == null) return CompletableFuture.completedFuture(0);
                                return TisCommandContinuations.then(TisCommandContinuations.owned(player, () -> {
                                    if (player.level() != view.world() || player.hasDisconnected() || player.isRemoved()
                                            || player.moonrise$getChunkLoader() == null
                                            || !player.moonrise$getChunkLoader().getSentChunksRaw().contains(pos.pack()))
                                        return 0;
                                    if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(view.world(), pos.x(), pos.z()))
                                        return -1;
                                    AmsNativeCommandEffects.packet(player, prepared.packet());
                                    if (io.papermc.paper.event.packet.PlayerChunkLoadEvent.getHandlerList().getRegisteredListeners().length > 0)
                                        new io.papermc.paper.event.packet.PlayerChunkLoadEvent(new org.bukkit.craftbukkit.CraftChunk(prepared.chunk()),
                                                player.getBukkitEntity()).callEvent();
                                    return 1;
                                }), result -> result < 0 ? refreshChunk(source, player, view, pos, attempts - 1) : CompletableFuture.completedFuture(result));
                            }));
        });
    }

    private static CompletableFuture<Integer> report(ServerPlayer player, int count) {
        return TisCommandContinuations.owned(player, () -> {
            if (player.hasDisconnected() || player.isRemoved()) return count;
            Component message = TisTranslations.translate(TisTranslations.text("command.refresh.chunk.done", count), player);
            AmsNativeCommandEffects.packet(player, new ClientboundSystemChatPacket(message, false));
            return count;
        });
    }
}
