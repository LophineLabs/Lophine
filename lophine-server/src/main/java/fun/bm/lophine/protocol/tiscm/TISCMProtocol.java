package fun.bm.lophine.protocol.tiscm;

import com.mojang.logging.LogUtils;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.ServerBuildInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Contract;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@LeavesProtocol.Register(namespace = TISCMProtocol.PROTOCOL_ID)
public class TISCMProtocol implements LeavesProtocol {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String PROTOCOL_ID = "tiscm";
    private static final String PLATFORM_NAME = "Lophine";
    private static final String PLATFORM_VERSION = ServerBuildInfo.buildInfo().asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE);
    private static final Map<String, EnumSet<S2CPacket>> CLIENT_SUPPORTED_PACKETS = new ConcurrentHashMap<>();

    @Contract("_ -> new")
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PROTOCOL_ID, path);
    }

    public static void broadcastMsptSample(long tickCounter, long nanosecond) {
        if (!GeneralCompatConfig.tiscmNetworkProtocol || !GeneralCompatConfig.syncServerMsptMetricsData) {
            return;
        }

        CompoundTag nbt = new CompoundTag();
        nbt.putInt("version", 2);
        nbt.putLong("millisecond", nanosecond / 1_000_000L);
        nbt.putLong("nanosecond", nanosecond);
        nbt.putString("type", "tick_server_method");

        TISCMPayload payload = new TISCMPayload(S2CPacket.MSPT_METRICS_SAMPLE.id, nbt);
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            if (supports(player, S2CPacket.MSPT_METRICS_SAMPLE)) {
                ProtocolUtils.sendPayloadPacket(player, payload);
            }
        }
    }

    @ProtocolHandler.PayloadReceiver(payload = TISCMPayload.class)
    public static void handlePayload(ServerPlayer player, TISCMPayload payload) {
        if (!GeneralCompatConfig.tiscmNetworkProtocol) {
            return;
        }

        Optional<C2SPacket> packetType = C2SPacket.fromId(payload.packetId());
        if (packetType.isEmpty()) {
            return;
        }

        switch (packetType.get()) {
            case HI -> handleHi(player, payload.nbt());
            case SUPPORTED_S2C_PACKETS -> handleSupportedS2CPackets(player, payload.nbt());
            case SPEED_TEST_UPLOAD_PAYLOAD ->
                    fun.bm.lophine.carpet.TisSpeedTestCommand.handleUpload(player, payload.nbt());
            case SPEED_TEST_PING -> fun.bm.lophine.carpet.TisSpeedTestCommand.handlePing(player, payload.nbt());
            default -> {
            }
        }
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLeave(ServerPlayer player) {
        CLIENT_SUPPORTED_PACKETS.remove(player.getStringUUID());
        fun.bm.lophine.carpet.TisSpeedTestCommand.disconnected(player);
    }

    private static void handleHi(ServerPlayer player, CompoundTag payload) {
        String platformName = payload.getString("platform_name").orElse("Unknown");
        String platformVersion = payload.getString("platform_version").orElse("Unknown");
        LOGGER.info("Player {} connected with TISCM protocol support ({} @ {})", player.getScoreboardName(), platformName, platformVersion);

        send(player, S2CPacket.HELLO, nbt -> {
            nbt.putString("platform_name", PLATFORM_NAME);
            nbt.putString("platform_version", PLATFORM_VERSION);
        });
        send(player, S2CPacket.SUPPORTED_C2S_PACKETS, nbt -> nbt.put("supported_c2s_packets", stringList(List.of(
                C2SPacket.HI.id,
                C2SPacket.SUPPORTED_S2C_PACKETS.id,
                C2SPacket.SPEED_TEST_UPLOAD_PAYLOAD.id,
                C2SPacket.SPEED_TEST_PING.id
        ))));
    }

    private static void handleSupportedS2CPackets(ServerPlayer player, CompoundTag payload) {
        EnumSet<S2CPacket> packets = EnumSet.noneOf(S2CPacket.class);
        ListTag listTag = payload.getListOrEmpty("supported_s2c_packets");
        for (int i = 0; i < listTag.size(); i++) {
            S2CPacket.fromId(listTag.getString(i).orElse("")).ifPresent(packets::add);
        }
        CLIENT_SUPPORTED_PACKETS.put(player.getStringUUID(), packets);
    }

    public static void send(ServerPlayer player, S2CPacket packet, java.util.function.Consumer<CompoundTag> builder) {
        if (!supports(player, packet)) {
            return;
        }

        CompoundTag nbt = new CompoundTag();
        builder.accept(nbt);
        ProtocolUtils.sendPayloadPacket(player, new TISCMPayload(packet.id, nbt));
    }

    public static boolean send(ServerPlayer player, S2CPacket packet, java.util.function.Consumer<CompoundTag> builder,
                               io.netty.channel.ChannelFutureListener listener) {
        if (!GeneralCompatConfig.tiscmNetworkProtocol || !supports(player, packet)) return false;
        CompoundTag nbt = new CompoundTag();
        builder.accept(nbt);
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new TISCMPayload(packet.id, nbt)), listener);
        return true;
    }

    public static boolean supports(ServerPlayer player, S2CPacket packet) {
        if (packet.handshake) {
            return true;
        }

        EnumSet<S2CPacket> packets = CLIENT_SUPPORTED_PACKETS.get(player.getStringUUID());
        return packets != null && packets.contains(packet);
    }

    private static ListTag stringList(List<String> values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        return list;
    }

    @Override
    public boolean isActive() {
        return GeneralCompatConfig.tiscmNetworkProtocol;
    }

    private interface PayloadBuilder {
        void accept(CompoundTag nbt);
    }

    public enum C2SPacket {
        HI("hi", true),
        SUPPORTED_S2C_PACKETS("supported_s2c_packets", true),
        SPEED_TEST_UPLOAD_PAYLOAD("speed_test_upload_payload", false),
        SPEED_TEST_PING("speed_test_ping", false);

        private static final Map<String, C2SPacket> BY_ID = Arrays.stream(values()).collect(Collectors.toMap(packet -> packet.id, packet -> packet));

        private final String id;
        private final boolean handshake;

        C2SPacket(String id, boolean handshake) {
            this.id = id;
            this.handshake = handshake;
        }

        public static Optional<C2SPacket> fromId(String id) {
            return Optional.ofNullable(BY_ID.get(id));
        }
    }

    public enum S2CPacket {
        HELLO("hello", true),
        SUPPORTED_C2S_PACKETS("supported_c2s_packets", true),
        MSPT_METRICS_SAMPLE("mspt_metrics_sample", false),
        SPEED_TEST_DOWNLOAD_PAYLOAD("speed_test_download_payload", false),
        SPEED_TEST_UPLOAD_REQUEST("speed_test_upload_request", false),
        SPEED_TEST_PING("speed_test_ping", false),
        SPEED_TEST_ABORT("speed_test_abort", false);

        private static final Map<String, S2CPacket> BY_ID = Arrays.stream(values()).collect(Collectors.toMap(packet -> packet.id, packet -> packet));

        private final String id;
        private final boolean handshake;

        S2CPacket(String id, boolean handshake) {
            this.id = id;
            this.handshake = handshake;
        }

        public static Optional<S2CPacket> fromId(String id) {
            return Optional.ofNullable(BY_ID.get(id));
        }
    }

    public record TISCMPayload(String packetId, CompoundTag nbt) implements LeavesCustomPayload {
        @ID
        private static final Identifier NETWORK_ID = TISCMProtocol.id("network/v1");

        @Codec
        private static final StreamCodec<RegistryFriendlyByteBuf, TISCMPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                TISCMPayload::packetId,
                ByteBufCodecs.COMPOUND_TAG,
                TISCMPayload::nbt,
                TISCMPayload::new
        );
    }
}
