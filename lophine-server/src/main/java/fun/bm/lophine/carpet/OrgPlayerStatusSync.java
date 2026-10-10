package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Original 30 game-tick synchronization retains its real inventory, FULL reads and packet children.
 */
public final class OrgPlayerStatusSync {
    private static final ConcurrentHashMap<UUID, Request> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, Boolean> SHUTTING_DOWN = Collections.synchronizedMap(new WeakHashMap<>());

    private OrgPlayerStatusSync() {
    }

    private static final class Request {
        final ServerLevel world;
        final ServerPlayer player;
        final UUID id;
        final long gameTime;
        final CompletableFuture<Boolean> actual = new CompletableFuture<>();
        volatile boolean cancelled;

        Request(ServerPlayer player, ServerLevel world, long gameTime) {
            this.player = player;
            this.world = world;
            this.id = player.getUUID();
            this.gameTime = gameTime;
        }

        // Cancellation is metadata for not-yet-started physical phases. An admitted Native phase keeps its true receipt.
        void cancel() {
            cancelled = true;
        }

        boolean current() {
            return !cancelled && REQUESTS.get(id) == this && GeneralCompatConfig.autoSyncPlayerStatus;
        }
    }

    static List<BlockPos> positions(BlockPos center, int radius) {
        var positions = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (center.distSqr(pos) <= radius * radius) positions.add(pos.immutable());
        }
        return List.copyOf(positions);
    }

    public static void tick(ServerPlayer player) {
        TickThread.ensureTickThread(player, "Automatic player synchronization must own its player");
        UUID id = player.getUUID();
        ServerLevel world = player.level();
        if (SHUTTING_DOWN.containsKey(world.getServer()) || ScarpetNativeWork.isDraining(world.getServer())
                || !GeneralCompatConfig.autoSyncPlayerStatus) {
            removed(player);
            return;
        }
        long time = world.getGameTime();
        Request previous = REQUESTS.get(id);
        if (previous != null && (previous.world != world || previous.player != player)) {
            REQUESTS.remove(id, previous);
            previous.cancel();
            previous = null;
        }
        if (time % 30L != 0L || previous != null && previous.gameTime == time) return;
        Request request = new Request(player, world, time);
        previous = REQUESTS.put(id, request);
        if (previous != null) previous.cancel();
        ScarpetNativeWork.record(request.actual);
        ScarpetNativeWork.trackNative(world.getServer(), request.actual);
        try {
            var body = synchronize(request);
            ScarpetNativeWork.aliasDependency(request.actual, body);
            body.whenComplete((value, failure) -> {
                if (failure == null) request.actual.complete(value);
                else {
                    REQUESTS.remove(id, request);
                    request.actual.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            REQUESTS.remove(id, request);
            request.actual.completeExceptionally(failure);
        }
    }

    private record Footprint(BlockPos center, int radius, List<BlockPos> positions) {
    }

    private static CompletableFuture<Boolean> synchronize(Request request) {
        var info = OrgMenuNativeEffects.run(request.player, () -> {
            if (!request.current() || request.player.level() != request.world || request.player.hasDisconnected())
                return null;
            request.world.getServer().getPlayerList().sendAllPlayerInfo(request.player);
            BlockPos center = request.player.blockPosition().immutable();
            int radius = (int) Math.min(request.player.blockInteractionRange() + 1D, 8D);
            return new Footprint(center, radius, positions(center, radius));
        });
        return TisCommandContinuations.then(info, footprint -> footprint == null ? CompletableFuture.completedFuture(false)
                : TisCommandContinuations.then(readPackets(request, footprint), packets -> OrgMenuNativeEffects.run(request.player, () -> {
            if (!request.current() || request.player.level() != request.world || request.player.hasDisconnected())
                return false;
            for (var packet : packets) request.player.connection.send(packet);
            return true;
        })));
    }

    private static CompletableFuture<List<ClientboundBlockUpdatePacket>> readPackets(Request request, Footprint footprint) {
        Supplier<CompletableFuture<List<ClientboundBlockUpdatePacket>>> read = ScarpetRuntime.captureNativeContinuation(() -> {
            var observed = ScarpetNativeWork.observeNative(null, () -> {
                if (!request.current()) return List.<ClientboundBlockUpdatePacket>of();
                var packets = new ArrayList<ClientboundBlockUpdatePacket>(footprint.positions().size());
                for (BlockPos pos : footprint.positions())
                    packets.add(new ClientboundBlockUpdatePacket(pos, request.world.getBlockState(pos)));
                return List.copyOf(packets);
            });
            ScarpetNativeWork.trackNative(request.world.getServer(), observed);
            return ScarpetNativeWork.recoverGuestValue(observed);
        });
        BlockPos center = footprint.center();
        int radius = footprint.radius();
        var held = CarpetRegionLease.<CompletableFuture<List<ClientboundBlockUpdatePacket>>>runValue(request.world,
                (center.getX() - radius) >> 4, (center.getZ() - radius) >> 4, (center.getX() + radius) >> 4, (center.getZ() + radius) >> 4,
                lease -> read.get());
        var actual = held.thenCompose(value -> value);
        ScarpetNativeWork.record(actual);
        return actual;
    }

    /**
     * A caller view cannot cancel the actual inventory/read/delivery job.
     */
    static CompletableFuture<Boolean> completion(ServerPlayer player) {
        Request request = REQUESTS.get(player.getUUID());
        if (request == null || request.player != player) return CompletableFuture.completedFuture(false);
        var view = request.actual.copy();
        ScarpetNativeWork.aliasDependency(view, request.actual);
        return view;
    }

    public static void removed(ServerPlayer player) {
        Request request = REQUESTS.get(player.getUUID());
        if (request != null && request.player == player && REQUESTS.remove(player.getUUID(), request)) request.cancel();
    }

    public static void reset(MinecraftServer server) {
        SHUTTING_DOWN.put(server, true);
        for (var entry : REQUESTS.entrySet()) {
            var request = entry.getValue();
            if (request.world.getServer() == server && REQUESTS.remove(entry.getKey(), request)) request.cancel();
        }
    }
}
