package org.leavesmc.leaves.protocol;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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

import java.util.*;
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
    private static final int MAX_CLIENT_COMMAND_RESPONSE_LINES = 12;
    private static final int MAX_CLIENT_COMMAND_RESPONSE_LINE_CODE_POINTS = 512;
    private static final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
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
                if (onlinePlayer == null) {
                    return;
                }

                LOGGER.info("Player {} joined with carpet {}", onlinePlayer.getScoreboardName(), carpetVersion);
                sendServerData(onlinePlayer);
                activePlayers.add(playerId);
            }, null, 1L);
            return;
        }

        CompoundTag clientCommand = payload.nbt.getCompound("clientCommand").orElse(null);
        if (clientCommand == null || !activePlayers.contains(player.getUUID())) {
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
            if (onlinePlayer == null || !activePlayers.contains(playerId)) {
                return;
            }

            List<Component> output = new ArrayList<>();
            Component[] error = {null};
            int[] returnValue = {0};
            MinecraftServer server = onlinePlayer.level().getServer();
            if (server == null) {
                error[0] = Component.literal("No Server");
            } else {
                try {
                    CommandSource outputSink = new CommandSource() {
                        @Override
                        public void sendSystemMessage(Component message) {
                            output.add(message);
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
                    ).withCallback((success, resultValue) -> returnValue[0] = resultValue);
                    server.getCommands().performPrefixedCommand(commandSource, command);
                } catch (RuntimeException exception) {
                    error[0] = Component.literal(exception.getMessage() == null
                            ? "Command failed"
                            : exception.getMessage());
                }
            }

            CompoundTag result = new CompoundTag();
            result.putString("id", commandId);
            if (error[0] != null) {
                result.putString("error", limitCodePoints(error[0].getString(), MAX_CLIENT_COMMAND_RESPONSE_LINE_CODE_POINTS));
            }
            result.putInt("return", returnValue[0]);
            if (!output.isEmpty()) {
                ListTag outputTag = new ListTag();
                for (int i = 0; i < Math.min(output.size(), MAX_CLIENT_COMMAND_RESPONSE_LINES); i++) {
                    Component line = output.get(i);
                    outputTag.add(StringTag.valueOf(limitCodePoints(
                            line.getString(), MAX_CLIENT_COMMAND_RESPONSE_LINE_CODE_POINTS
                    )));
                }
                result.put("output", outputTag);
            }

            CompoundTag response = new CompoundTag();
            response.put("clientCommand", result);
            ProtocolUtils.sendPayloadPacket(onlinePlayer, new CarpetPayload(response));
        }, null, 1L);
    }

    private static String limitCodePoints(String value, int maxCodePoints) {
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    @ProtocolHandler.PlayerLeave
    public static void onPlayerLeave(ServerPlayer player) {
        activePlayers.remove(player.getUUID());
    }

    @Override
    public boolean isActive() {
        return CarpetRules.hasRules();
    }

    private static void sendServerData(ServerPlayer player) {
        sendServerData(player.getUUID());
    }

    private static void sendServerData(UUID playerId) {
        ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) {
            activePlayers.remove(playerId);
            return;
        }

        CompoundTag data = new CompoundTag();
        CarpetRules.write(data);
        player.getBukkitEntity().getScheduler().execute(MinecraftInternalPlugin.INSTANCE, () -> {
            ServerPlayer onlinePlayer = MinecraftServer.getServer().getPlayerList().getPlayer(playerId);
            if (onlinePlayer != null) {
                ProtocolUtils.sendPayloadPacket(onlinePlayer, new CarpetPayload(data));
            }
        }, null, 1L);
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
                activePlayers.forEach(CarpetServerProtocol::sendServerData);
                rulesDirty = false;
            }
        }

        public static void write(@NotNull CompoundTag tag) {
            CompoundTag rulesNbt = new CompoundTag();
            rules.values().forEach(rule -> rule.writeNBT(rulesNbt));

            tag.put("Rules", rulesNbt);
        }

        public static void register(CarpetRule rule) {
            rules.put(rule.name, rule);
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
                activePlayers.forEach(CarpetServerProtocol::sendServerData);
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
}
