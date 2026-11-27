// SPDX-License-Identifier: LGPL-3.0-or-later
// AMSP v1 from 75031017 and v2 from bdb2464f / 66b8c3b0.
package fun.bm.lophine.protocol;

import fun.bm.lophine.carpet.ams.AmsManagementSettings;
import fun.bm.lophine.carpet.ams.AmsTranslations;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@LeavesProtocol.Register(namespace = "carpetamsaddition")
public final class AmsNetworkProtocol implements LeavesProtocol {
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("carpetamsaddition", "network/v1");
    public static final Identifier CHANNEL_V2 = Identifier.fromNamespaceAndPath("carpetamsaddition", "network/v2");

    enum Wire {
        V1(CHANNEL), V2(CHANNEL_V2);

        private final Identifier channel;

        Wire(Identifier channel) {
            this.channel = channel;
        }
    }

    // Pinned upstream gradle.properties declares mod_version=26.3 (750310179368b2569dd6121a2769b2fb1bbc7343).
    public static final String VERSION = "26.3";
    public static final String IMPLEMENTATION = "Carpet AMS Addition";
    private static final Set<String> PACKETS = Set.of("unknown", "handshake_c2s", "handshake_s2c", "request_client_mod_version_s2c", "request_client_mod_version_c2s", "request_handshake_s2c", "sync_custom_block_hardness", "client_player_fps_c2s", "client_player_fps_s2c", "update_player_pose_s2c", "lazy_settings_s2c");
    private static final String HARDNESS_QUERY = "block_hardness_sync_protocol_c2s";
    private static final String HARDNESS_REPLY = "block_hardness_sync_protocol_s2c";
    private static final Set<String> NETWORK_RULES = Set.of("commandAmspDebug", "commandCustomBlockHardness", "commandGetClientPlayerFps", "commandSetPlayerPose", "blockHardnessSyncProtocol");
    private static final Object CLIENT_LIFECYCLE = new Object();
    private static final Map<UUID, Client> CLIENTS = new ConcurrentHashMap<>();
    private static final Map<UUID, CommandSourceStack> FPS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> CLIENT_VERSIONS = new ConcurrentHashMap<>();
    private static final carpet.script.external.WeakIdentityMap<ServerPlayer, Boolean> WARNED = new carpet.script.external.WeakIdentityMap<>();
    private static final AtomicBoolean SERVER_SUPPORT = new AtomicBoolean(false);

    public record Client(String name, String version, Set<String> packets, Wire wire, ServerPlayer connectionPlayer) {
        public Client {
            packets = Set.copyOf(packets);
        }
    }

    public record Handshake(String version, Set<String> packets) {
        public Handshake {
            packets = Set.copyOf(packets);
        }
    }

    public static Handshake readClientHandshake(FriendlyByteBuf buffer, UUID connectionUuid) {
        return readClientHandshake(buffer, connectionUuid, Wire.V1);
    }

    static Handshake readClientHandshake(FriendlyByteBuf buffer, UUID connectionUuid, Wire wire) {
        String version = buffer.readUtf(512);
        UUID claimed = wire == Wire.V1 ? buffer.readUUID() : connectionUuid;
        int count = buffer.readVarInt();
        if (!claimed.equals(connectionUuid) || count < 0 || count > 128) return null;
        Set<String> supported = new HashSet<>();
        for (int i = 0; i < count; ++i) supported.add(buffer.readUtf(128));
        return new Handshake(version, supported);
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public int tickerInterval(String id) {
        return 20;
    }

    public static Map<UUID, Client> supportClients() {
        return Map.copyOf(CLIENTS);
    }

    public static boolean serverSupport() {
        return SERVER_SUPPORT.get();
    }

    public static void setServerSupport(boolean supported) {
        SERVER_SUPPORT.set(supported);
    }

    public static boolean supports(UUID player, String packet) {
        Client client = CLIENTS.get(player);
        return client != null && client.packets().contains(packet);
    }

    public static void deny(UUID player) {
        synchronized (CLIENT_LIFECYCLE) {
            CLIENTS.remove(player);
            FPS.remove(player);
            CLIENT_VERSIONS.remove(player);
        }
    }

    public static void denyAll() {
        synchronized (CLIENT_LIFECYCLE) {
            CLIENTS.clear();
            FPS.clear();
            CLIENT_VERSIONS.clear();
        }
    }

    public static void validateNetworkRule(String name, Object value) {
        validateNetworkRule(name, value, GeneralCompatConfig.amsNetworkProtocol);
    }

    public static void validateNetworkRule(String name, Object value, boolean enabled) {
        // The upstream observer handles this after parsing by restoring the rule default,
        // sending a warning and returning zero. The command layer performs that action.
    }

    public static boolean blocksNetworkRuleChange(String name, Object requested, Object ruleDefault, boolean enabled, boolean serverRunning) {
        return NETWORK_RULES.contains(name) && !enabled && serverRunning && !java.util.Objects.equals(requested, ruleDefault);
    }

    /**
     * Same default reset applied by the upstream SettingsManager load observer.
     */
    public static void resetNetworkRuleDefaultsOnLoad() {
        if (GeneralCompatConfig.amsNetworkProtocol) return;
        GeneralCompatConfig.commandAmspDebug = "false";
        GeneralCompatConfig.commandCustomBlockHardness = "false";
        GeneralCompatConfig.commandGetClientPlayerFps = "false";
        GeneralCompatConfig.commandSetPlayerPose = "false";
        GeneralCompatConfig.blockHardnessSyncProtocol = false;
    }

    @ProtocolHandler.PlayerJoin
    public static void join(ServerPlayer player) {
        if (GeneralCompatConfig.amsNetworkProtocol) requestHandshake(player);
    }

    @ProtocolHandler.PlayerLeave
    public static void leave(ServerPlayer player) {
        synchronized (CLIENT_LIFECYCLE) {
            Client client = CLIENTS.get(player.getUUID());
            if (client == null || client.connectionPlayer() == player) deny(player.getUUID());
        }
    }

    @ProtocolHandler.BytebufReceiver(onlyNamespace = true)
    public static boolean unsupportedChannel(ServerPlayer player, FriendlyByteBuf ignored) {
        if (!GeneralCompatConfig.amsNetworkProtocol || GeneralCompatConfig.incompatibleNetworkProtocolWarningDisabled
                || WARNED.putIfAbsent(player, Boolean.TRUE) != null) return false;
        var actual = fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.owned(player, () -> {
            if (!GeneralCompatConfig.amsNetworkProtocol || GeneralCompatConfig.incompatibleNetworkProtocolWarningDisabled || player.hasDisconnected()) {
                WARNED.remove(player);
                return (Void) null;
            }
            var source = player.createCommandSourceStack();
            send(source, AmsTranslations.message(source, "amsp.incompatible_protocol", CHANNEL.toString(), CHANNEL_V2.toString())
                    .withStyle(net.minecraft.ChatFormatting.RED));
            send(source, AmsTranslations.message(source, "amsp.incompatible_protocol_hint")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
            return (Void) null;
        });
        carpet.script.external.ScarpetNativeWork.record(actual);
        return false;
    }

    @ProtocolHandler.BytebufReceiver(key = "network/v1")
    public static boolean receive(ServerPlayer connectionPlayer, FriendlyByteBuf buffer) {
        return receive(connectionPlayer, buffer, Wire.V1);
    }

    @ProtocolHandler.BytebufReceiver(key = "network/v2")
    public static boolean receiveV2(ServerPlayer connectionPlayer, FriendlyByteBuf buffer) {
        return receive(connectionPlayer, buffer, Wire.V2);
    }

    private static boolean receive(ServerPlayer connectionPlayer, FriendlyByteBuf buffer, Wire wire) {
        if (!GeneralCompatConfig.amsNetworkProtocol) return false;
        String packet = buffer.readUtf(128);
        switch (packet) {
            case "handshake_c2s" -> {
                Handshake handshake = readClientHandshake(buffer, connectionPlayer.getUUID(), wire);
                if (handshake == null || buffer.isReadable()) return true;
                var actual = fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.owned(connectionPlayer, () -> {
                    if (!GeneralCompatConfig.amsNetworkProtocol || connectionPlayer.hasDisconnected()) return false;
                    Client client = new Client(connectionPlayer.getGameProfile().name(), handshake.version(), handshake.packets(), wire, connectionPlayer);
                    synchronized (CLIENT_LIFECYCLE) {
                        Client previous = CLIENTS.get(connectionPlayer.getUUID());
                        if (previous != null && previous.connectionPlayer() == connectionPlayer
                                && previous.wire() == Wire.V2 && wire == Wire.V1) return false;
                        CLIENTS.put(connectionPlayer.getUUID(), client);
                    }
                    return true;
                }), accepted -> accepted ? fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(handshake(connectionPlayer), ignored ->
                        fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(syncHardness(connectionPlayer), unused ->
                                fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(syncPoses(connectionPlayer, connectionPlayer.getUUID()), done -> syncLazy(connectionPlayer)))) : java.util.concurrent.CompletableFuture.completedFuture(null));
                carpet.script.external.ScarpetNativeWork.record(actual);
            }
            case "client_player_fps_c2s" -> {
                UUID claimed = readReplyPlayer(buffer, connectionPlayer.getUUID(), wire);
                int fps = buffer.readInt();
                Client admitted = connectionClient(connectionPlayer, wire);
                if (!claimed.equals(connectionPlayer.getUUID()) || fps < 0 || admitted == null || buffer.isReadable())
                    return true;
                var actual = fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.owned(connectionPlayer, () -> {
                    CommandSourceStack source;
                    synchronized (CLIENT_LIFECYCLE) {
                        if (CLIENTS.get(claimed) != admitted) return null;
                        source = FPS.remove(claimed);
                    }
                    return source == null || connectionPlayer.isFakePlayer() ? null : new FpsReply(source, connectionPlayer.getGameProfile().name(), fps);
                }), reply -> reply == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : feedback(reply.source(), net.minecraft.ChatFormatting.GREEN, "command.getClientPlayerFps.feedback", reply.name(), Integer.toString(reply.fps())));
                carpet.script.external.ScarpetNativeWork.record(actual);
            }
            case "request_client_mod_version_c2s" -> {
                String version = buffer.readUtf(512);
                UUID claimed = readReplyPlayer(buffer, connectionPlayer.getUUID(), wire);
                Client admitted = connectionClient(connectionPlayer, wire);
                if (!claimed.equals(connectionPlayer.getUUID()) || admitted == null || buffer.isReadable()) return true;
                carpet.script.external.ScarpetNativeWork.record(fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.global(connectionPlayer.carpetSpawnServer(), () -> {
                    // A queued reply from a denied/disconnected session cannot populate
                    // a replacement session that happens to use the same player UUID.
                    synchronized (CLIENT_LIFECYCLE) {
                        if (CLIENTS.get(claimed) == admitted) CLIENT_VERSIONS.put(claimed, version);
                    }
                    return (Void) null;
                }));
            }
            case HARDNESS_QUERY -> {
                if (wire == Wire.V2 && GeneralCompatConfig.blockHardnessSyncProtocol && connectionClient(connectionPlayer, wire) != null) {
                    BlockPos pos = buffer.readBlockPos();
                    float clientHardness = buffer.readFloat();
                    if (!buffer.isReadable() && Float.isFinite(clientHardness)) syncPositionHardness(connectionPlayer, pos, clientHardness);
                }
            }
            default -> buffer.skipBytes(buffer.readableBytes());
        }
        return true;
    }

    static UUID readReplyPlayer(FriendlyByteBuf buffer, UUID connectionUuid, Wire wire) {
        return wire == Wire.V1 ? buffer.readUUID() : connectionUuid;
    }

    private static Client connectionClient(ServerPlayer player, Wire wire) {
        Client client = CLIENTS.get(player.getUUID());
        return client != null && client.connectionPlayer() == player && client.wire() == wire ? client : null;
    }

    public static void requestHandshake(ServerPlayer player) {
        Client client = CLIENTS.get(player.getUUID());
        Wire wire = client != null && client.connectionPlayer() == player ? client.wire() : Wire.V2;
        sendPacket(player, "request_handshake_s2c", wire, (buffer, ignored) -> {});
        if (client == null || client.connectionPlayer() != player) {
            var fallback = fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(
                    fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.pause(player.carpetSpawnServer(), 1500L), ignored ->
                            sendPacket(player, "request_handshake_s2c", Wire.V1, (buffer, unused) -> {}));
            carpet.script.external.ScarpetNativeWork.record(fallback);
        }
    }

    private static void syncPositionHardness(ServerPlayer player, BlockPos pos, float clientHardness) {
        var actual = fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.owned(player, () -> {
            if (!GeneralCompatConfig.blockHardnessSyncProtocol || connectionClient(player, Wire.V2) == null
                    || !player.isWithinBlockInteractionRange(pos, 0.0D) || !TickThread.isTickThreadFor(player.level(), pos)) return null;
            var state = player.level().getBlockStateIfLoaded(pos);
            if (state != null) {
                float hardness = state.getDestroySpeed(player.level(), pos);
                if (Float.compare(hardness, clientHardness) != 0) {
                    sendPacket(player, HARDNESS_REPLY, null, (buffer, wire) -> {
                        buffer.writeVarInt(Block.getId(state));
                        buffer.writeFloat(hardness);
                    });
                }
            }
            return (Void) null;
        });
        carpet.script.external.ScarpetNativeWork.record(actual);
    }

    private static java.util.concurrent.CompletableFuture<Void> handshake(ServerPlayer player) {
        boolean serverSupport = SERVER_SUPPORT.get();
        return sendPacket(player, "handshake_s2c", null, (buffer, wire) -> {
            buffer.writeUtf(VERSION);
            buffer.writeBoolean(serverSupport);
            var packets = new TreeSet<>(PACKETS);
            if (wire == Wire.V2) {
                packets.add(HARDNESS_QUERY);
                packets.add(HARDNESS_REPLY);
            }
            buffer.writeVarInt(packets.size());
            packets.forEach(buffer::writeUtf);
        });
    }

    public static void syncHardness() {
        broadcast(AmsNetworkProtocol::syncHardness);
    }

    private static java.util.concurrent.CompletableFuture<Void> syncHardness(ServerPlayer player) {
        var map = AmsManagementSettings.hardnessSnapshot();
        return sendPacket(player, "sync_custom_block_hardness", null, (buffer, wire) -> {
            buffer.writeVarInt(map.size());
            map.forEach((state, hardness) -> {
                buffer.writeVarInt(Block.getId(state));
                buffer.writeFloat(hardness);
            });
        });
    }

    public static void syncPoses(UUID target) {
        broadcast(player -> syncPoses(player, target));
    }

    private static java.util.concurrent.CompletableFuture<Void> syncPoses(ServerPlayer player, UUID target) {
        var map = AmsManagementSettings.poseSnapshot();
        return sendPacket(player, "update_player_pose_s2c", null, (buffer, wire) -> {
            buffer.writeVarInt(map.size());
            map.forEach((uuid, pose) -> {
                buffer.writeUUID(uuid);
                buffer.writeUtf(pose);
            });
            buffer.writeUUID(target);
        });
    }

    private static java.util.concurrent.CompletableFuture<Void> syncLazy(ServerPlayer player) {
        List<String> enabled = new ArrayList<>();
        if (GeneralCompatConfig.experimentalMinecartEnabled) enabled.add("EXPERIMENTAL_MINECART_ENABLED");
        if (GeneralCompatConfig.largeShulkerBox) enabled.add("LARGE_SHULKER_BOX");
        return sendPacket(player, "lazy_settings_s2c", null, (buffer, wire) -> {
            if (wire == Wire.V2) writeLazySettingsV2(buffer, GeneralCompatConfig.experimentalMinecartEnabled);
            else {
                buffer.writeVarInt(enabled.size());
                enabled.forEach(buffer::writeUtf);
            }
        });
    }

    static void writeLazySettingsV2(FriendlyByteBuf buffer, boolean experimentalMinecartEnabled) {
        buffer.writeVarInt(1);
        buffer.writeUtf("experimentalMinecartEnabled");
        buffer.writeUtf(Boolean.toString(experimentalMinecartEnabled));
    }

    static void writeQueryPlayer(FriendlyByteBuf buffer, UUID target, Wire wire) {
        if (wire == Wire.V1) buffer.writeUUID(target);
    }

    private record FpsReply(CommandSourceStack source, String name, int fps) {
    }

    private static java.util.concurrent.CompletableFuture<Void> feedback(CommandSourceStack source, net.minecraft.ChatFormatting color, String key, Object... args) {
        return fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.source(source, () -> {
            send(source, AmsTranslations.message(source, key, args).withStyle(color));
            return (Void) null;
        });
    }

    public static int requestFps(CommandSourceStack source, ServerPlayer target) {
        // Pinned AMS keeps the latest source per target, returns one even without protocol support and has no FPS timeout.
        synchronized (CLIENT_LIFECYCLE) {
            FPS.put(target.getUUID(), source);
        }
        carpet.script.external.ScarpetNativeWork.record(sendPacket(target, "client_player_fps_s2c", null, (buffer, wire) -> writeQueryPlayer(buffer, target.getUUID(), wire)));
        return 1;
    }

    public static int requestVersion(CommandSourceStack source, ServerPlayer target) {
        // An unrelated player's query must not erase a reply already in flight.
        synchronized (CLIENT_LIFECYCLE) {
            CLIENT_VERSIONS.remove(target.getUUID());
        }
        var actual = fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(sendPacket(target, "request_client_mod_version_s2c", null, (buffer, wire) -> writeQueryPlayer(buffer, target.getUUID(), wire)), ignored ->
                fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(feedback(source, net.minecraft.ChatFormatting.GREEN, "command.amsp.get_client_version_waiting"), unused -> versionCheck(source, target, 0)));
        carpet.script.external.ScarpetNativeWork.record(actual);
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<Void> versionCheck(CommandSourceStack source, ServerPlayer target, int retries) {
        return fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.pause(source.getServer(), 3000L), ignored ->
                fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.global(source.getServer(), () -> CLIENT_VERSIONS.remove(target.getUUID())), version -> {
                    if (version != null)
                        return fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.owned(target, () -> target.getGameProfile().name()), name -> feedback(source, net.minecraft.ChatFormatting.AQUA, "command.amsp.client_mod_version_success_feedback", name, version));
                    if (retries >= 5)
                        return feedback(source, net.minecraft.ChatFormatting.RED, "command.amsp.client_mod_version_failed_feedback", "5");
                    return fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(feedback(source, net.minecraft.ChatFormatting.YELLOW, "command.amsp.request_client_version", Integer.toString(retries + 1)), unused ->
                            fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.then(sendPacket(target, "request_client_mod_version_s2c", null, (buffer, wire) -> writeQueryPlayer(buffer, target.getUUID(), wire)), done -> versionCheck(source, target, retries + 1)));
                }));
    }

    private static void broadcast(Consumer<ServerPlayer> operation) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        carpet.script.external.ScarpetNativeWork.record(fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.broadcast(server, operation));
    }

    private static java.util.concurrent.CompletableFuture<Void> sendPacket(ServerPlayer player, String packet, Wire handshakeWire,
                                                                            java.util.function.BiConsumer<FriendlyByteBuf, Wire> data) {
        var actual = fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.owned(player, () -> {
            ServerPlayer scheduled = player;
            if (!GeneralCompatConfig.amsNetworkProtocol || scheduled.hasDisconnected()) return (Void) null;
            Client session = CLIENTS.get(scheduled.getUUID());
            if (session != null && session.connectionPlayer() != scheduled) session = null;
            if (handshakeWire == null && (session == null || !session.packets().contains(packet))) return (Void) null;
            if (handshakeWire != null && session != null && session.wire() != handshakeWire) return (Void) null;
            Wire wire = handshakeWire == null ? session.wire() : handshakeWire;
            var buffer = ProtocolUtils.decorate(io.netty.buffer.Unpooled.buffer());
            try {
                buffer.writeUtf(packet);
                data.accept(buffer, wire);
                var payload = new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new net.minecraft.network.protocol.common.custom.DiscardedPayload(wire.channel, io.netty.buffer.ByteBufUtil.getBytes(buffer)));
                fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.packet(scheduled, payload);
            } finally {
                buffer.release();
            }

            return (Void) null;
        });
        carpet.script.external.ScarpetNativeWork.record(actual);
        return actual;
    }

    public static void send(CommandSourceStack source, Component message) {
        carpet.script.external.ScarpetNativeWork.record(sendAsync(source, message));
    }

    public static java.util.concurrent.CompletableFuture<Void> sendAsync(CommandSourceStack source, Component message) {
        var immutable = message.copy();
        return fun.bm.lophine.carpet.ams.AmsNativeCommandEffects.source(source, () -> {
            source.sendSuccess(() -> immutable, false);
            return (Void) null;
        });
    }
}
