package org.leavesmc.leaves.protocol;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@LeavesProtocol.Register(namespace = "carpet")
public class CarpetServerProtocol implements LeavesProtocol {
    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final String PROTOCOL_ID = "carpet";
    public static final String VERSION = ProtocolUtils.buildProtocolVersion(PROTOCOL_ID);

    private static final String HI = "69";
    private static final String HELLO = "420";
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
        }
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
