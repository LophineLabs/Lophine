package org.leavesmc.leaves.protocol;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@LeavesProtocol.Register(namespace = "carpet")
public class CarpetServerProtocol implements LeavesProtocol {
    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final String PROTOCOL_ID = "carpet";
    public static final String VERSION = ProtocolUtils.buildProtocolVersion(PROTOCOL_ID);

    private static final String HI = "69";
    private static final String HELLO = "420";
    private static final int MAX_CLIENT_COMMAND_LENGTH = 16_384;
    private static final int MAX_CLIENT_COMMAND_ID_LENGTH = 1_024;

    private record ClientSession(ServerPlayer player, String version) {
    }

    private static final Map<UUID, ClientSession> clients = new ConcurrentHashMap<>();
    private static boolean batchingRules = false;
    private static boolean rulesDirty = false;

    @Contract("_ -> new")
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PROTOCOL_ID, path);
    }

    @ProtocolHandler.PlayerJoin
    public static void onPlayerJoin(ServerPlayer player) {
        CompoundTag data = new CompoundTag();
        data.putString(HI, VERSION);
        ProtocolUtils.sendPayloadPacket(player, new CarpetPayload(data));
    }

    @ProtocolHandler.PayloadReceiver(payload = CarpetPayload.class)
    private static void handleHello(@NotNull ServerPlayer player, @NotNull CarpetServerProtocol.CarpetPayload payload) {
        if (payload.nbt.contains(HELLO)) {
            UUID playerId = player.getUUID();
            String carpetVersion = payload.nbt.getString(HELLO).orElse("Unknown");
            player.getBukkitEntity().getScheduler().execute(MinecraftInternalPlugin.INSTANCE, () -> {
                ServerPlayer onlinePlayer = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
                if (onlinePlayer != player || player.hasDisconnected()) {
                    return;
                }

                LOGGER.info("Player {} joined with carpet {}", onlinePlayer.getScoreboardName(), carpetVersion);
                clients.put(playerId, new ClientSession(player, carpetVersion));
                sendServerData(onlinePlayer);
            }, null, 1L);
            return;
        }

        CompoundTag clientCommand = payload.nbt.getCompound("clientCommand").orElse(null);
        if (clientCommand == null || !hasClient(player)) {
            return;
        }

        String command = clientCommand.getString("command").orElse("");
        String commandId = clientCommand.getString("id").orElse("");
        if (command.isEmpty()
                || command.length() > MAX_CLIENT_COMMAND_LENGTH
                || commandId.isEmpty()
                || commandId.length() > MAX_CLIENT_COMMAND_ID_LENGTH) {
            return;
        }

        handleClientCommand(player, commandId, command);
    }

    private static void handleClientCommand(ServerPlayer player, String commandId, String command) {
        UUID playerId = player.getUUID();
        player.getBukkitEntity().getScheduler().execute(MinecraftInternalPlugin.INSTANCE, () -> {
            ServerPlayer onlinePlayer = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
            if (onlinePlayer != player || player.hasDisconnected() || !hasClient(player)) {
                return;
            }

            MinecraftServer server = onlinePlayer.level().getServer();
            var actual = fun.bm.lophine.carpet.CarpetClientCommandResult.execute(commandId, output -> {
                if (server == null) throw new IllegalStateException("No Server");
                CommandSource outputSink = new CommandSource() {
                    @Override
                    public void sendSystemMessage(Component message) {
                        output.message(message);
                    }

                    @Override
                    public boolean acceptsSuccess() {
                        return true;
                    }

                    @Override
                    public boolean acceptsFailure() {
                        return true;
                    }

                    @Override
                    public boolean shouldInformAdmins() {
                        return false;
                    }

                    @Override
                    public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack stack) {
                        return onlinePlayer.getBukkitEntity();
                    }
                };
                PermissionSet permissions = server.getProfilePermissions(onlinePlayer.nameAndId());
                ServerLevel level = onlinePlayer.level() instanceof ServerLevel serverLevel ? serverLevel : null;
                CommandSourceStack commandSource = new CommandSourceStack(
                        outputSink,
                        onlinePlayer.position(),
                        onlinePlayer.getRotationVector(),
                        level,
                        permissions,
                        server,
                        onlinePlayer
                ).withCallback(output::returned);
                server.getCommands().performPrefixedCommand(commandSource, command);
            });
            fun.bm.lophine.carpet.AmsNativeCommandEffects.then(actual, result ->
                    fun.bm.lophine.carpet.AmsNativeCommandEffects.owned(player, () -> {
                        if (player.hasDisconnected() || player.isRemoved() || !hasClient(player)) return null;
                        CompoundTag response = new CompoundTag();
                        response.put("clientCommand", result);
                        fun.bm.lophine.carpet.AmsNativeCommandEffects.packet(player,
                                new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new CarpetPayload(response)));
                        return (Void) null;
                    }));
        }, null, 1L);
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLeave(ServerPlayer player) {
        clients.computeIfPresent(player.getUUID(), (id, session) -> session.player() == player ? null : session);
    }

    @Override
    public boolean isActive() {
        return CarpetRules.hasRules();
    }

    private static void sendServerData(ServerPlayer player) {
        sendServerData(player.getUUID());
    }

    private static void broadcastServerData(UUID playerId) {
        if (!fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.superSecretSetting) sendServerData(playerId);
    }

    private static void sendServerData(UUID playerId) {
        var server = MinecraftServer.getServer();
        if (server == null) return;
        ClientSession session = clients.get(playerId);
        if (session == null) return;
        fun.bm.lophine.carpet.AmsNativeCommandEffects.then(fun.bm.lophine.carpet.AmsNativeCommandEffects.global(server, () -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != session.player()) {
                clients.remove(playerId, session);
                return null;
            }
            CompoundTag data = new CompoundTag();
            CarpetRules.write(data);
            return new ServerData(player, data);
        }), snapshot -> snapshot == null ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : fun.bm.lophine.carpet.AmsNativeCommandEffects.owned(snapshot.player(), () -> {
            if (clients.get(playerId) == session && !snapshot.player().isRemoved() && !snapshot.player().hasDisconnected())
                fun.bm.lophine.carpet.AmsNativeCommandEffects.packet(snapshot.player(), new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new CarpetPayload(snapshot.data())));
            return (Void) null;
        }));
    }

    private record ServerData(ServerPlayer player, CompoundTag data) {
    }

    public static class CarpetRules {

        private static final Map<String, CarpetRule> rules = new ConcurrentHashMap<>();

        public static void beginBatch() {
            batchingRules = true;
            rulesDirty = false;
        }

        public static void endBatch() {
            batchingRules = false;
            if (rulesDirty) {
                clients.keySet().forEach(CarpetServerProtocol::broadcastServerData);
                rulesDirty = false;
            }
        }

        public static void write(@NotNull CompoundTag tag) {
            CompoundTag rulesNbt = new CompoundTag();
            rules.values().forEach(rule -> rule.writeNBT(rulesNbt));

            tag.put("Rules", rulesNbt);
        }

        public static void register(CarpetRule rule) {
            rules.put(rule.identifier + ":" + rule.name, rule);
            markDirty();
        }

        public static void clear() {
            rules.clear();
            markDirty();
        }

        public static boolean hasRules() {
            return !rules.isEmpty();
        }

        private static void markDirty() {
            if (batchingRules) {
                rulesDirty = true;
            } else {
                clients.keySet().forEach(CarpetServerProtocol::broadcastServerData);
            }
        }
    }

    public record CarpetRule(String identifier, String name, String value) {

        @NotNull
        @Contract("_, _, _ -> new")
        public static CarpetRule of(String identifier, String name, Enum<?> value) {
            return new CarpetRule(identifier, name, value.name().toLowerCase(Locale.ROOT));
        }

        @NotNull
        @Contract("_, _, _ -> new")
        public static CarpetRule of(String identifier, String name, boolean value) {
            return new CarpetRule(identifier, name, Boolean.toString(value));
        }

        @NotNull
        @Contract("_, _, _ -> new")
        public static CarpetRule of(String identifier, String name, int value) {
            return new CarpetRule(identifier, name, Integer.toString(value));
        }

        @NotNull
        @Contract("_, _, _ -> new")
        public static CarpetRule of(String identifier, String name, long value) {
            return new CarpetRule(identifier, name, Long.toString(value));
        }

        @NotNull
        @Contract("_, _, _ -> new")
        public static CarpetRule of(String identifier, String name, float value) {
            return new CarpetRule(identifier, name, Float.toString(value));
        }

        @NotNull
        @Contract("_, _, _ -> new")
        public static CarpetRule of(String identifier, String name, double value) {
            return new CarpetRule(identifier, name, Double.toString(value));
        }

        @NotNull
        @Contract("_, _, _ -> new")
        public static CarpetRule of(String identifier, String name, String value) {
            return new CarpetRule(identifier, name, value);
        }

        public void writeNBT(@NotNull CompoundTag rules) {
            CompoundTag rule = new CompoundTag();
            String key = name;

            while (rules.contains(key)) {
                key = key + "2";
            }

            rule.putString("Value", value);
            rule.putString("Manager", identifier);
            rule.putString("Rule", name);
            rules.put(key, rule);
        }
    }

    public record CarpetPayload(CompoundTag nbt) implements LeavesCustomPayload {
        @ID
        private static final Identifier HELLO_ID = CarpetServerProtocol.id("hello");

        @Codec
        private static final StreamCodec<FriendlyByteBuf, CarpetPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.COMPOUND_TAG, CarpetPayload::nbt, CarpetPayload::new
        );
    }

    // Lophine - original Carpet server payload endpoints
    public static boolean isValidCarpetPlayer(ServerPlayer player) {
        return !fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.superSecretSetting && hasClient(player);
    }

    private static boolean hasClient(ServerPlayer player) {
        ClientSession session = clients.get(player.getUUID());
        return session != null && session.player() == player;
    }

    public static String getPlayerStatus(ServerPlayer player) {
        ClientSession session = clients.get(player.getUUID());
        return !fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.superSecretSetting && session != null && session.player() == player
                ? "carpet " + session.version() : "vanilla";
    }

    public static void sendCustomCommand(ServerPlayer player, String key, net.minecraft.nbt.Tag tag) {
        if (!isValidCarpetPlayer(player)) return;
        CompoundTag payload = new CompoundTag();
        payload.put(key, tag.copy());
        Runnable delivery = () -> {
            if (isValidCarpetPlayer(player) && !player.isRemoved())
                ProtocolUtils.sendPayloadPacket(player, new CarpetPayload(payload));
        };
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)) delivery.run();
        else player.getBukkitEntity().taskScheduler.schedule(owned -> delivery.run(), null, 1L);
    }
}
