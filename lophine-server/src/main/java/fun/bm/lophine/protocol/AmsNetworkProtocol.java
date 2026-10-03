// SPDX-License-Identifier: LGPL-3.0-or-later
// AMS network/v1 server wire contract from 750310179368b2569dd6121a2769b2fb1bbc7343.
package fun.bm.lophine.protocol;

import fun.bm.lophine.carpet.AmsManagementSettings;
import fun.bm.lophine.carpet.AmsTranslations;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;

@LeavesProtocol.Register(namespace = "carpetamsaddition")
public final class AmsNetworkProtocol implements LeavesProtocol {
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("carpetamsaddition", "network/v1");
    // Pinned upstream gradle.properties declares mod_version=26.3 (750310179368b2569dd6121a2769b2fb1bbc7343).
    public static final String VERSION = "26.3";
    public static final String IMPLEMENTATION = "Carpet AMS Addition";
    private static final Set<String> PACKETS = Set.of("unknown", "handshake_c2s", "handshake_s2c", "request_client_mod_version_s2c", "request_client_mod_version_c2s", "request_handshake_s2c", "sync_custom_block_hardness", "client_player_fps_c2s", "client_player_fps_s2c", "update_player_pose_s2c", "lazy_settings_s2c");
    private static final Set<String> NETWORK_RULES = Set.of("commandAmspDebug", "commandCustomBlockHardness", "commandGetClientPlayerFps", "commandSetPlayerPose");
    private static final Map<UUID, Client> CLIENTS = new ConcurrentHashMap<>();
    private static final Map<UUID, CommandSourceStack> FPS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> CLIENT_VERSIONS = new ConcurrentHashMap<>();
    private static final AtomicBoolean SERVER_SUPPORT = new AtomicBoolean(false);

    public record Client(String name, String version, Set<String> packets) {
        public Client { packets = Set.copyOf(packets); }
    }
    public record Handshake(String version, Set<String> packets) {
        public Handshake { packets = Set.copyOf(packets); }
    }

    public static Handshake readClientHandshake(FriendlyByteBuf buffer, UUID connectionUuid) {
        String version = buffer.readUtf(512);
        UUID claimed = buffer.readUUID();
        int count = buffer.readVarInt();
        if (!claimed.equals(connectionUuid) || count < 0 || count > 128) return null;
        Set<String> supported = new HashSet<>();
        for (int i = 0; i < count; ++i) supported.add(buffer.readUtf(128));
        return new Handshake(version, supported);
    }

    @Override public boolean isActive() { return true; }
    @Override public int tickerInterval(String id) { return 20; }
    public static Map<UUID, Client> supportClients() { return Map.copyOf(CLIENTS); }
    public static boolean serverSupport() { return SERVER_SUPPORT.get(); }
    public static void setServerSupport(boolean supported) { SERVER_SUPPORT.set(supported); }
    public static boolean supports(UUID player, String packet) {
        Client client = CLIENTS.get(player);
        return client != null && client.packets().contains(packet);
    }
    public static void deny(UUID player) {
        CLIENTS.remove(player);

    }
    public static void denyAll() { List.copyOf(CLIENTS.keySet()).forEach(AmsNetworkProtocol::deny); }

    public static void validateNetworkRule(String name, Object value) {
        validateNetworkRule(name,value,GeneralCompatConfig.amsNetworkProtocol);
    }
    public static void validateNetworkRule(String name,Object value,boolean enabled) {
        // The upstream observer handles this after parsing by restoring the rule default,
        // sending a warning and returning zero. The command layer performs that action.
    }
    public static boolean blocksNetworkRuleChange(String name,Object requested,Object ruleDefault,boolean enabled,boolean serverRunning) {
        return NETWORK_RULES.contains(name) && !enabled && serverRunning && !java.util.Objects.equals(requested,ruleDefault);
    }

    /** Same default reset applied by the upstream SettingsManager load observer. */
    public static void resetNetworkRuleDefaultsOnLoad() {
        if (GeneralCompatConfig.amsNetworkProtocol) return;
        GeneralCompatConfig.commandAmspDebug = "false";
        GeneralCompatConfig.commandCustomBlockHardness = "false";
        GeneralCompatConfig.commandGetClientPlayerFps = "false";
        GeneralCompatConfig.commandSetPlayerPose = "false";
    }

    @ProtocolHandler.PlayerJoin
    public static void join(ServerPlayer player) {
        if (GeneralCompatConfig.amsNetworkProtocol) requestHandshake(player);
    }

    @ProtocolHandler.PlayerLeave
    public static void leave(ServerPlayer player) { deny(player.getUUID()); }

    @ProtocolHandler.BytebufReceiver(key = "network/v1")
    public static boolean receive(ServerPlayer connectionPlayer, FriendlyByteBuf buffer) {
        if (!GeneralCompatConfig.amsNetworkProtocol) return false;
        String packet = buffer.readUtf(128);
        switch (packet) {
            case "handshake_c2s" -> {
                Handshake handshake = readClientHandshake(buffer, connectionPlayer.getUUID());
                if (handshake == null) return true;
                var actual=fun.bm.lophine.carpet.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.AmsNativeCommandEffects.owned(connectionPlayer,()->{
                    if(!GeneralCompatConfig.amsNetworkProtocol)return false;
                    CLIENTS.put(connectionPlayer.getUUID(),new Client(connectionPlayer.getGameProfile().name(),handshake.version(),handshake.packets()));return true;
                }),accepted->accepted?fun.bm.lophine.carpet.AmsNativeCommandEffects.then(handshake(connectionPlayer),ignored->
                    fun.bm.lophine.carpet.AmsNativeCommandEffects.then(syncHardness(connectionPlayer),unused->
                        fun.bm.lophine.carpet.AmsNativeCommandEffects.then(syncPoses(connectionPlayer,connectionPlayer.getUUID()),done->syncLazy(connectionPlayer)))):java.util.concurrent.CompletableFuture.completedFuture(null));
                carpet.script.external.ScarpetNativeWork.record(actual);
            }
            case "client_player_fps_c2s" -> {
                UUID claimed = buffer.readUUID();
                int fps = buffer.readInt();
                if (!claimed.equals(connectionPlayer.getUUID()) || fps < 0 || !CLIENTS.containsKey(claimed)) return true;
                var actual=fun.bm.lophine.carpet.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.AmsNativeCommandEffects.owned(connectionPlayer,()->{
                    var source=FPS.remove(claimed);return source==null||connectionPlayer instanceof org.leavesmc.leaves.bot.ServerBot?null:new FpsReply(source,connectionPlayer.getGameProfile().name(),fps);
                }),reply->reply==null?java.util.concurrent.CompletableFuture.completedFuture(null):feedback(reply.source(),net.minecraft.ChatFormatting.GREEN,"command.getClientPlayerFps.feedback",reply.name(),Integer.toString(reply.fps())));
                carpet.script.external.ScarpetNativeWork.record(actual);
            }
            case "request_client_mod_version_c2s" -> {
                String version = buffer.readUtf(512);
                UUID claimed = buffer.readUUID();
                if (!claimed.equals(connectionPlayer.getUUID()) || !CLIENTS.containsKey(claimed)) return true;
                carpet.script.external.ScarpetNativeWork.record(fun.bm.lophine.carpet.AmsNativeCommandEffects.global(connectionPlayer.carpetSpawnServer(),()->{CLIENT_VERSIONS.put(claimed,version);return (Void)null;}));
            }
            default -> buffer.skipBytes(buffer.readableBytes());
        }
        return true;
    }

    public static void requestHandshake(ServerPlayer player) {
        sendPacket(player, "request_handshake_s2c", true, buffer -> {});
    }

    private static java.util.concurrent.CompletableFuture<Void> handshake(ServerPlayer player) {
        boolean serverSupport = SERVER_SUPPORT.get();
        return sendPacket(player, "handshake_s2c", false, buffer -> {
            buffer.writeUtf(VERSION);
            buffer.writeBoolean(serverSupport);
            buffer.writeVarInt(PACKETS.size());
            PACKETS.stream().sorted().forEach(buffer::writeUtf);
        });
    }

    public static void syncHardness() { broadcast(AmsNetworkProtocol::syncHardness); }
    private static java.util.concurrent.CompletableFuture<Void> syncHardness(ServerPlayer player) {
        var map = AmsManagementSettings.hardnessSnapshot();
        return sendPacket(player, "sync_custom_block_hardness", false, buffer -> {
            buffer.writeVarInt(map.size());
            map.forEach((state, hardness) -> { buffer.writeVarInt(Block.getId(state)); buffer.writeFloat(hardness); });
        });
    }

    public static void syncPoses(UUID target) { broadcast(player -> syncPoses(player, target)); }
    private static java.util.concurrent.CompletableFuture<Void> syncPoses(ServerPlayer player, UUID target) {
        var map = AmsManagementSettings.poseSnapshot();
        return sendPacket(player, "update_player_pose_s2c", false, buffer -> {
            buffer.writeVarInt(map.size());
            map.forEach((uuid, pose) -> { buffer.writeUUID(uuid); buffer.writeUtf(pose); });
            buffer.writeUUID(target);
        });
    }

    private static java.util.concurrent.CompletableFuture<Void> syncLazy(ServerPlayer player) {
        List<String> enabled = new ArrayList<>();
        if (GeneralCompatConfig.experimentalMinecartEnabled) enabled.add("EXPERIMENTAL_MINECART_ENABLED");
        if (GeneralCompatConfig.largeShulkerBox) enabled.add("LARGE_SHULKER_BOX");
        return sendPacket(player, "lazy_settings_s2c", false, buffer -> {
            buffer.writeVarInt(enabled.size());
            enabled.forEach(buffer::writeUtf);
        });
    }

    private record FpsReply(CommandSourceStack source,String name,int fps){}
    private static java.util.concurrent.CompletableFuture<Void> feedback(CommandSourceStack source,net.minecraft.ChatFormatting color,String key,Object...args){
        return fun.bm.lophine.carpet.AmsNativeCommandEffects.source(source,()->{send(source,AmsTranslations.message(source,key,args).withStyle(color));return (Void)null;});
    }
    public static int requestFps(CommandSourceStack source,ServerPlayer target){
        // Pinned AMS keeps the latest source per target, returns one even without protocol support and has no FPS timeout.
        FPS.put(target.getUUID(),source);
        carpet.script.external.ScarpetNativeWork.record(sendPacket(target,"client_player_fps_s2c",false,buffer->buffer.writeUUID(target.getUUID())));return 1;
    }
    public static int requestVersion(CommandSourceStack source,ServerPlayer target){
        CLIENT_VERSIONS.clear();
        var actual=fun.bm.lophine.carpet.AmsNativeCommandEffects.then(sendPacket(target,"request_client_mod_version_s2c",false,buffer->buffer.writeUUID(target.getUUID())),ignored->
            fun.bm.lophine.carpet.AmsNativeCommandEffects.then(feedback(source,net.minecraft.ChatFormatting.GREEN,"command.amsp.get_client_version_waiting"),unused->versionCheck(source,target,0)));
        carpet.script.external.ScarpetNativeWork.record(actual);return 1;
    }
    private static java.util.concurrent.CompletableFuture<Void> versionCheck(CommandSourceStack source,ServerPlayer target,int retries){
        return fun.bm.lophine.carpet.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.AmsNativeCommandEffects.pause(source.getServer(),3000L),ignored->
            fun.bm.lophine.carpet.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.AmsNativeCommandEffects.global(source.getServer(),()->CLIENT_VERSIONS.remove(target.getUUID())),version->{
                if(version!=null)return fun.bm.lophine.carpet.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.AmsNativeCommandEffects.owned(target,()->target.getGameProfile().name()),name->feedback(source,net.minecraft.ChatFormatting.AQUA,"command.amsp.client_mod_version_success_feedback",name,version));
                if(retries>=5)return feedback(source,net.minecraft.ChatFormatting.RED,"command.amsp.client_mod_version_failed_feedback","5");
                return fun.bm.lophine.carpet.AmsNativeCommandEffects.then(feedback(source,net.minecraft.ChatFormatting.YELLOW,"command.amsp.request_client_version",Integer.toString(retries+1)),unused->
                    fun.bm.lophine.carpet.AmsNativeCommandEffects.then(sendPacket(target,"request_client_mod_version_s2c",false,buffer->buffer.writeUUID(target.getUUID())),done->versionCheck(source,target,retries+1)));
            }));
    }
    private static void broadcast(Consumer<ServerPlayer> operation) {
        MinecraftServer server=MinecraftServer.getServer();if(server==null)return;
        carpet.script.external.ScarpetNativeWork.record(fun.bm.lophine.carpet.AmsNativeCommandEffects.broadcast(server,operation));
    }

    private static java.util.concurrent.CompletableFuture<Void> sendPacket(ServerPlayer player,String packet,boolean force,Consumer<FriendlyByteBuf> data) {
        var actual=fun.bm.lophine.carpet.AmsNativeCommandEffects.owned(player,()->{
            ServerPlayer scheduled=player;
            if (!force && !supports(scheduled.getUUID(), packet)) return (Void)null;
            var buffer=ProtocolUtils.decorate(io.netty.buffer.Unpooled.buffer());
            try{
                buffer.writeUtf(packet);data.accept(buffer);
                var payload=new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new net.minecraft.network.protocol.common.custom.DiscardedPayload(CHANNEL,io.netty.buffer.ByteBufUtil.getBytes(buffer)));
                fun.bm.lophine.carpet.AmsNativeCommandEffects.packet(scheduled,payload);
            }finally{buffer.release();}

            return (Void)null;
        });
        carpet.script.external.ScarpetNativeWork.record(actual);return actual;
    }

    public static void send(CommandSourceStack source,Component message) {
        carpet.script.external.ScarpetNativeWork.record(sendAsync(source,message));
    }
    public static java.util.concurrent.CompletableFuture<Void> sendAsync(CommandSourceStack source,Component message) {
        var immutable=message.copy();
        return fun.bm.lophine.carpet.AmsNativeCommandEffects.source(source,()->{source.sendSuccess(()->immutable,false);return (Void)null;});
    }
}
