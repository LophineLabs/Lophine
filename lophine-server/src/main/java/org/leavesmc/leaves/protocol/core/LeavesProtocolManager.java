/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.protocol.core;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import me.earthme.luminol.utils.ClassLoadUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.leavesmc.leaves.protocol.core.invoker.*;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class LeavesProtocolManager {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final LeavesCustomPayload INVALID_PAYLOAD = new InvalidPayload();

    private static final Map<Class<? extends LeavesCustomPayload>, PayloadReceiverInvokerHolder> PAYLOAD_RECEIVERS = new HashMap<>();
    private static final Map<Class<? extends LeavesCustomPayload>, Identifier> IDS = new HashMap<>();
    private static final Map<Class<? extends LeavesCustomPayload>, StreamCodec<? super RegistryFriendlyByteBuf, LeavesCustomPayload>> CODECS = new HashMap<>();
    private static final Map<Identifier, StreamCodec<? super RegistryFriendlyByteBuf, LeavesCustomPayload>> ID2CODEC = new HashMap<>();

    private static final Map<String, BytebufReceiverInvokerHolder> STRICT_BYTEBUF_RECEIVERS = new HashMap<>();
    private static final Map<String, BytebufReceiverInvokerHolder> NAMESPACED_BYTEBUF_RECEIVERS = new HashMap<>();
    private static final List<BytebufReceiverInvokerHolder> GENERIC_BYTEBUF_RECEIVERS = new ArrayList<>();

    private static final Map<String, MinecraftRegisterInvokerHolder> STRICT_MINECRAFT_REGISTER = new HashMap<>();
    private static final Map<String, MinecraftRegisterInvokerHolder> NAMESPACED_MINECRAFT_REGISTER = new HashMap<>();
    private static final List<MinecraftRegisterInvokerHolder> WILD_MINECRAFT_REGISTER = new ArrayList<>();

    private static final List<EmptyInvokerHolder<ProtocolHandler.Ticker>> TICKERS = new ArrayList<>();

    private static final List<PlayerInvokerHolder<ProtocolHandler.PlayerJoin>> PLAYER_JOIN = new ArrayList<>();
    private static final List<PlayerInvokerHolder<ProtocolHandler.PlayerLeave>> PLAYER_LEAVE = new ArrayList<>();
    private static final List<EmptyInvokerHolder<ProtocolHandler.ReloadServer>> RELOAD_SERVER = new ArrayList<>();
    private static final List<EmptyInvokerHolder<ProtocolHandler.ReloadDataPack>> RELOAD_DATAPACK = new ArrayList<>();

    private static long lastAcceptTime = 0;

    @SuppressWarnings("unchecked")
    public static void init() {
        String[] packages = {
                "org.leavesmc.leaves.protocol",
                "fun.bm.lophine.protocol"
        };
        for (String pkg : packages) {
            Collection<Class<?>> classes = ClassLoadUtil.getClasses(pkg, MinecraftServer.class.getClassLoader());
            for (Class<?> clazz : classes) {
                if (LeavesCustomPayload.class.isAssignableFrom(clazz) && !clazz.equals(LeavesCustomPayload.class)) {
                    for (Field field : clazz.getDeclaredFields()) {
                        field.setAccessible(true);
                        if (!Modifier.isStatic(field.getModifiers())) {
                            continue;
                        }
                        try {
                            final LeavesCustomPayload.ID id = field.getAnnotation(LeavesCustomPayload.ID.class);
                            if (id != null && field.getType().equals(Identifier.class)) {
                                IDS.put((Class<? extends LeavesCustomPayload>) clazz, (Identifier) field.get(null));
                            }
                            final LeavesCustomPayload.Codec codec = field.getAnnotation(LeavesCustomPayload.Codec.class);
                            if (codec != null && field.getType().equals(StreamCodec.class)) {
                                CODECS.put((Class<? extends LeavesCustomPayload>) clazz, (StreamCodec<? super RegistryFriendlyByteBuf, LeavesCustomPayload>) field.get(null));
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    continue;
                }

                final LeavesProtocol.Register register = clazz.getAnnotation(LeavesProtocol.Register.class);
                if (register == null) {
                    continue;
                }
                LeavesProtocol protocol;
                try {
                    Constructor<?> constructor = clazz.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    protocol = (LeavesProtocol) constructor.newInstance();
                } catch (Throwable throwable) {
                    LOGGER.error("Failed to load class {}. {}", clazz.getName(), throwable);
                    return;
                }

                for (final Method method : clazz.getDeclaredMethods()) {
                    if (method.isBridge() || method.isSynthetic()) {
                        continue;
                    }
                    method.setAccessible(true);

                    final ProtocolHandler.Init init = method.getAnnotation(ProtocolHandler.Init.class);
                    if (init != null) {
                        InitInvokerHolder holder = new InitInvokerHolder(protocol, method, init);
                        try {
                            holder.invoke();
                        } catch (RuntimeException exception) {
                            LOGGER.error("Failed to invoke init method {} in {}, {}: {}", method.getName(), clazz.getName(), exception.getCause(), exception.getMessage());
                        }
                        continue;
                    }

                    final ProtocolHandler.PayloadReceiver payloadReceiver = method.getAnnotation(ProtocolHandler.PayloadReceiver.class);
                    if (payloadReceiver != null) {
                        PAYLOAD_RECEIVERS.put(payloadReceiver.payload(), new PayloadReceiverInvokerHolder(protocol, method, payloadReceiver));
                        continue;
                    }

                    final ProtocolHandler.BytebufReceiver bytebufReceiver = method.getAnnotation(ProtocolHandler.BytebufReceiver.class);
                    if (bytebufReceiver != null) {
                        String key = bytebufReceiver.key();
                        BytebufReceiverInvokerHolder holder = new BytebufReceiverInvokerHolder(protocol, method, bytebufReceiver);
                        if (bytebufReceiver.onlyNamespace()) {
                            NAMESPACED_BYTEBUF_RECEIVERS.put(key.isEmpty() ? register.namespace() : key, holder);
                        } else {
                            if (key.isEmpty()) {
                                GENERIC_BYTEBUF_RECEIVERS.add(holder);
                            } else {
                                if (key.contains(":")) {
                                    STRICT_BYTEBUF_RECEIVERS.put(key, holder);
                                } else {
                                    STRICT_BYTEBUF_RECEIVERS.put(register.namespace() + ":" + key, holder);
                                }
                            }
                        }
                        continue;
                    }

                    final ProtocolHandler.Ticker ticker = method.getAnnotation(ProtocolHandler.Ticker.class);
                    if (ticker != null) {
                        TICKERS.add(new EmptyInvokerHolder<>(protocol, method, ticker));
                        continue;
                    }

                    final ProtocolHandler.PlayerJoin playerJoin = method.getAnnotation(ProtocolHandler.PlayerJoin.class);
                    if (playerJoin != null) {
                        PLAYER_JOIN.add(new PlayerInvokerHolder<>(protocol, method, playerJoin));
                        continue;
                    }

                    final ProtocolHandler.PlayerLeave playerLeave = method.getAnnotation(ProtocolHandler.PlayerLeave.class);
                    if (playerLeave != null) {
                        PLAYER_LEAVE.add(new PlayerInvokerHolder<>(protocol, method, playerLeave));
                        continue;
                    }

                    final ProtocolHandler.ReloadServer reloadServer = method.getAnnotation(ProtocolHandler.ReloadServer.class);
                    if (reloadServer != null) {
                        RELOAD_SERVER.add(new EmptyInvokerHolder<>(protocol, method, reloadServer));
                        continue;
                    }

                    final ProtocolHandler.ReloadDataPack reloadDataPack = method.getAnnotation(ProtocolHandler.ReloadDataPack.class);
                    if (reloadDataPack != null) {
                        RELOAD_DATAPACK.add(new EmptyInvokerHolder<>(protocol, method, reloadDataPack));
                        continue;
                    }

                    final ProtocolHandler.MinecraftRegister minecraftRegister = method.getAnnotation(ProtocolHandler.MinecraftRegister.class);
                    if (minecraftRegister != null) {
                        String key = minecraftRegister.key();
                        MinecraftRegisterInvokerHolder holder = new MinecraftRegisterInvokerHolder(protocol, method, minecraftRegister);
                        if (minecraftRegister.onlyNamespace()) {
                            NAMESPACED_MINECRAFT_REGISTER.put(key.isEmpty() ? register.namespace() : key, holder);
                        } else {
                            if (key.isEmpty()) {
                                WILD_MINECRAFT_REGISTER.add(holder);
                            } else {
                                if (key.contains(":")) {
                                    STRICT_MINECRAFT_REGISTER.put(key, holder);
                                } else {
                                    STRICT_MINECRAFT_REGISTER.put(register.namespace() + ":" + key, holder);
                                }
                            }
                        }
                    }
                }
            }
        }
        for (var idInfo : IDS.entrySet()) {
            var codec = CODECS.get(idInfo.getKey());
            if (codec == null) {
                throw new IllegalArgumentException("Payload " + idInfo.getKey() + " is not configured correctly");
            }
            ID2CODEC.put(idInfo.getValue(), codec);
        }
    }

    public static LeavesCustomPayload decode(Identifier location, FriendlyByteBuf buf) {
        var codec = ID2CODEC.get(location);
        if (codec == null) {
            return null;
        }
        try {
            return codec.decode(ProtocolUtils.decorate(buf));
        } catch (Exception e) {
            LOGGER.warn("Rejected malformed Leaves payload {}", location, e);
            return INVALID_PAYLOAD;
        }
    }

    public static void encode(FriendlyByteBuf buf, LeavesCustomPayload payload) {
        var location = IDS.get(payload.getClass());
        var codec = CODECS.get(payload.getClass());
        if (location == null || codec == null) {
            throw new IllegalArgumentException("Payload " + payload.getClass() + " is not configured correctly " + location + " " + codec);
        }
        try {
            buf.writeIdentifier(location);
            codec.encode(ProtocolUtils.decorate(buf), payload);
        } catch (Exception e) {
            LOGGER.error("Failed to encode payload {}", location, e);
            throw e;
        }
    }

    public static void handlePayload(IdentifierSelector selector, LeavesCustomPayload payload) {
        if (payload == INVALID_PAYLOAD) {
            return;
        }
        PayloadReceiverInvokerHolder holder;
        if ((holder = PAYLOAD_RECEIVERS.get(payload.getClass())) != null) {
            try {
                holder.invoke(selector, payload);
            } catch (RuntimeException exception) {
                LOGGER.warn("Rejected malformed Leaves payload {} from {}", payload.getClass().getName(), describeSelector(selector), exception);
            }
        }
    }

    public static boolean handleBytebuf(IdentifierSelector selector, Identifier location, ByteBuf buf) {
        RegistryFriendlyByteBuf buf1 = ProtocolUtils.decorate(buf);
        BytebufReceiverInvokerHolder holder;
        if ((holder = STRICT_BYTEBUF_RECEIVERS.get(location.toString())) != null) {
            safeInvokeBytebuf(holder, selector, location, buf1);
            return true;
        }
        if ((holder = NAMESPACED_BYTEBUF_RECEIVERS.get(location.getNamespace())) != null) {
            if (safeInvokeBytebuf(holder, selector, location, buf1)) {
                return true;
            }
        }
        for (var holder1 : GENERIC_BYTEBUF_RECEIVERS) {
            if (safeInvokeBytebuf(holder1, selector, location, buf1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean safeInvokeBytebuf(BytebufReceiverInvokerHolder holder, IdentifierSelector selector, Identifier location, RegistryFriendlyByteBuf buf) {
        try {
            return holder.invoke(selector, buf);
        } catch (RuntimeException exception) {
            LOGGER.warn("Rejected malformed bytebuf payload {} from {}", location, describeSelector(selector), exception);
            return true;
        }
    }

    private static String describeSelector(IdentifierSelector selector) {
        if (selector.player() != null) {
            return selector.player().getScoreboardName();
        }
        if (selector.context() != null) {
            return selector.context().profile().name();
        }
        return "unknown";
    }

    private record InvalidPayload() implements LeavesCustomPayload {
    }

    public static void handleTick() {
        float interval = MinecraftServer.getServer().tickRateManager().millisecondsPerTick();
        long currentTime = interval >= 1 ? System.currentTimeMillis() / (long) MinecraftServer.getServer().tickRateManager().millisecondsPerTick() : (long) (System.currentTimeMillis() / MinecraftServer.getServer().tickRateManager().millisecondsPerTick());
        if (currentTime == lastAcceptTime) return;
        lastAcceptTime = currentTime;
        for (var tickerInfo : TICKERS) {
            if (currentTime % tickerInfo.owner().tickerInterval(tickerInfo.handler().tickerId()) == 0) {
                tickerInfo.invoke();
            }
        }
    }

    public static void handlePlayerJoin(ServerPlayer player) {
        sendKnownId(player, ProtocolHandler.Stage.GAME);
        for (var join : PLAYER_JOIN) {
            join.invoke(player);
        }
    }

    public static void handleConfigurationStart(ServerCommonPacketListenerImpl listener) {
        sendKnownId(new Context(listener.profile, listener.connection), ProtocolHandler.Stage.CONFIGURATION);
    }

    public static void handlePlayerLeave(ServerPlayer player) {
        for (var leave : PLAYER_LEAVE) {
            leave.invoke(player);
        }
    }

    public static void handleServerReload() {
        for (var reload : RELOAD_SERVER) {
            reload.invoke();
        }
    }

    public static void handleDataPackReload() {
        for (var reload : RELOAD_DATAPACK) {
            reload.invoke();
        }
    }

    public static void handleMinecraftRegister(String channelId, IdentifierSelector selector) {
        Identifier location = Identifier.tryParse(channelId);
        if (location == null) {
            return;
        }

        for (var wildHolder : WILD_MINECRAFT_REGISTER) {
            wildHolder.invoke(selector, location);
        }

        MinecraftRegisterInvokerHolder holder;
        if ((holder = STRICT_MINECRAFT_REGISTER.get(location.toString())) != null) {
            holder.invoke(selector, location);
        }
        if ((holder = NAMESPACED_MINECRAFT_REGISTER.get(location.getNamespace())) != null) {
            holder.invoke(selector, location);
        }
    }

    private static Set<String> collectKnownIds(ProtocolHandler.Stage stage) {
        Set<String> set = new TreeSet<>();
        PAYLOAD_RECEIVERS.forEach((clazz, holder) -> {
            if (holder.owner().isActive() && holder.handler().stage() == stage) {
                Identifier id = IDS.get(clazz);
                if (id != null) {
                    set.add(id.toString());
                }
            }
        });
        STRICT_BYTEBUF_RECEIVERS.forEach((key, holder) -> {
            if (holder.owner().isActive() && holder.handler().stage() == stage) {
                set.add(key);
            }
        });
        return set;
    }

    private static void sendKnownId(ServerPlayer player, ProtocolHandler.Stage stage) {
        Set<String> set = collectKnownIds(stage);
        if (set.isEmpty()) {
            return;
        }
        ProtocolUtils.sendBytebufPacket(player, Identifier.fromNamespaceAndPath("minecraft", "register"), buf -> {
            writeKnownIds(buf, set);
        });
    }

    private static void sendKnownId(Context context, ProtocolHandler.Stage stage) {
        Set<String> set = collectKnownIds(stage);
        if (set.isEmpty()) {
            return;
        }
        ProtocolUtils.sendBytebufPacket(context, Identifier.fromNamespaceAndPath("minecraft", "register"), buf -> {
            writeKnownIds(buf, set);
        });
    }

    private static void writeKnownIds(FriendlyByteBuf buf, Set<String> ids) {
        for (String channel : ids) {
            buf.writeBytes(channel.getBytes(StandardCharsets.US_ASCII));
            buf.writeByte(0);
        }
        buf.writerIndex(buf.writerIndex() - 1);
    }
}
