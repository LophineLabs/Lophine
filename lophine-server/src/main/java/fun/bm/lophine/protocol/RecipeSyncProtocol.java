package fun.bm.lophine.protocol;

import com.mojang.logging.LogUtils;
import fun.bm.lophine.config.modules.function.protocol.RecipeSyncProtocolConfig;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.DecoderException;
import io.netty.util.AttributeKey;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;
import org.leavesmc.leaves.protocol.core.*;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@LeavesProtocol.Register(namespace = RecipeSyncProtocol.PROTOCOL_ID)
public final class RecipeSyncProtocol implements LeavesProtocol {
    public static final String PROTOCOL_ID = "recipe-sync";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PAYLOAD_SIZE = 1024 * 1024;
    private static final int MAX_CONFIGURATION_PAYLOAD_SIZE = 32767;
    private static final int MAX_SUPPORTED_SERIALIZERS = 256;
    private static final int MAX_CACHED_FABRIC_VARIANTS = 16;
    private static final Identifier FABRIC_RECIPE_SYNC = Identifier.fromNamespaceAndPath("fabric", "recipe_sync");
    private static final Identifier NEOFORGE_RECIPE_CONTENT = Identifier.fromNamespaceAndPath("neoforge", "recipe_content");
    private static final List<RecipeType<?>> NEOFORGE_RECIPE_TYPES = List.of(
            RecipeType.CRAFTING,
            RecipeType.STONECUTTING,
            RecipeType.SMELTING,
            RecipeType.SMOKING,
            RecipeType.BLASTING,
            RecipeType.CAMPFIRE_COOKING,
            RecipeType.SMITHING
    );
    private static final Set<RecipeType<?>> NEOFORGE_RECIPE_TYPE_SET = Set.copyOf(NEOFORGE_RECIPE_TYPES);
    private static final AttributeKey<Set<Identifier>> FABRIC_SUPPORTED_SERIALIZERS =
            AttributeKey.valueOf("lophine:recipe_sync_supported_serializers");
    private static final AttributeKey<Boolean> CONNECTION_ACTIVE =
            AttributeKey.valueOf("lophine:recipe_sync_connection_active");
    private static final AttributeKey<Boolean> FABRIC_CHANNEL_AVAILABLE =
            AttributeKey.valueOf("lophine:recipe_sync_fabric_channel");
    private static final AttributeKey<Boolean> NEOFORGE_CHANNEL_AVAILABLE =
            AttributeKey.valueOf("lophine:recipe_sync_neoforge_channel");
    private static final AttributeKey<Long> FABRIC_SENT_GENERATION =
            AttributeKey.valueOf("lophine:recipe_sync_fabric_generation");
    private static final AttributeKey<Long> NEOFORGE_SENT_GENERATION =
            AttributeKey.valueOf("lophine:recipe_sync_neoforge_generation");
    private static final AttributeKey<Boolean> VERSION_WARNING_SENT =
            AttributeKey.valueOf("lophine:recipe_sync_version_warning");
    private static final Set<UUID> PENDING_SYNCS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicBoolean SYNC_ALL_WHEN_READY = new AtomicBoolean();
    private static final AtomicBoolean GLOBAL_WORK_SCHEDULED = new AtomicBoolean();

    private static volatile RecipePayloadCache payloadCache;

    @Override
    public boolean isActive() {
        return RecipeSyncProtocolConfig.enabled;
    }

    public static void onConfigModify(boolean enabled) {
        GENERATION.incrementAndGet();
        payloadCache = null;
        PENDING_SYNCS.clear();
        SYNC_ALL_WHEN_READY.set(enabled);
        if (enabled) {
            scheduleGlobalWork();
        }
    }

    @ProtocolHandler.PayloadReceiver(
            payload = FabricSupportedSerializersPayload.class,
            stage = ProtocolHandler.Stage.CONFIGURATION
    )
    public static void handleFabricSupportedSerializers(
            Context context,
            FabricSupportedSerializersPayload payload
    ) {
        if (payload.serializers().size() > MAX_SUPPORTED_SERIALIZERS) {
            LOGGER.warn(
                    "Ignoring {} Fabric recipe serializers from {} because the limit is {}",
                    payload.serializers().size(),
                    context.profile().name(),
                    MAX_SUPPORTED_SERIALIZERS
            );
            return;
        }

        Set<Identifier> supported = new HashSet<>();
        for (Identifier id : payload.serializers()) {
            if (id.getNamespace().equals("minecraft")
                    && BuiltInRegistries.RECIPE_SERIALIZER.getOptional(id).isPresent()) {
                supported.add(id);
            }
        }

        context.connection().channel.attr(FABRIC_SUPPORTED_SERIALIZERS).set(Set.copyOf(supported));
        markChannelAvailable(context, FABRIC_CHANNEL_AVAILABLE);
    }

    @ProtocolHandler.MinecraftRegister(key = "fabric:recipe_sync")
    public static void handleFabricChannel(Context context, Identifier ignored) {
        markChannelAvailable(context, FABRIC_CHANNEL_AVAILABLE);
    }

    @ProtocolHandler.MinecraftRegister(key = "neoforge:recipe_content")
    public static void handleNeoForgeChannel(Context context, Identifier ignored) {
        markChannelAvailable(context, NEOFORGE_CHANNEL_AVAILABLE);
    }

    @ProtocolHandler.PlayerRecipeSync
    public static void handleInitialRecipeSync(ServerPlayer player) {
        Channel channel = channel(player);
        if (channel == null) {
            return;
        }
        channel.attr(CONNECTION_ACTIVE).set(true);
        syncAvailableChannels(player, true);
    }

    @ProtocolHandler.PlayerJoin
    public static void handlePlayerJoin(ServerPlayer player) {
        Channel channel = channel(player);
        if (channel != null) {
            channel.attr(CONNECTION_ACTIVE).set(true);
        }
        queueSync(player.getUUID());
    }

    @ProtocolHandler.PlayerLeave
    public static void handlePlayerLeave(ServerPlayer player) {
        Channel channel = channel(player);
        if (channel != null) {
            channel.attr(CONNECTION_ACTIVE).set(false);
        }
        PENDING_SYNCS.remove(player.getUUID());
    }

    @ProtocolHandler.ReloadDataPack
    public static void handleDataPackReload() {
        GENERATION.incrementAndGet();
        payloadCache = null;
        SYNC_ALL_WHEN_READY.set(true);
        scheduleGlobalWork();
    }

    private static void runGlobalWork() {
        if (!RecipeSyncProtocolConfig.enabled) {
            return;
        }
        ensurePayloadCache();
        if (SYNC_ALL_WHEN_READY.compareAndSet(true, false)) {
            queueAllOnlinePlayers();
        }
        drainPendingSyncs();
    }

    private static void scheduleGlobalWork() {
        if (!RecipeSyncProtocolConfig.enabled || !GLOBAL_WORK_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().run(MinecraftInternalPlugin.INSTANCE, ignored -> {
                try {
                    runGlobalWork();
                } finally {
                    GLOBAL_WORK_SCHEDULED.set(false);
                    if (hasPendingGlobalWork()) {
                        scheduleGlobalWork();
                    }
                }
            });
        } catch (RuntimeException exception) {
            GLOBAL_WORK_SCHEDULED.set(false);
            LOGGER.warn("Could not schedule recipe synchronization work", exception);
        }
    }

    private static boolean hasPendingGlobalWork() {
        return RecipeSyncProtocolConfig.enabled
                && (payloadCache == null || SYNC_ALL_WHEN_READY.get() || !PENDING_SYNCS.isEmpty());
    }

    private static void queueAllOnlinePlayers() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PENDING_SYNCS.add(player.getUUID());
        }
    }

    private static void markChannelAvailable(Context context, AttributeKey<Boolean> capability) {
        Channel channel = context.connection().channel;
        if (channel != null) {
            channel.attr(capability).set(true);
        }
        queueSync(context.profile().id());
    }

    private static void queueSync(UUID playerId) {
        if (!RecipeSyncProtocolConfig.enabled) {
            return;
        }
        PENDING_SYNCS.add(playerId);
        scheduleGlobalWork();
    }

    private static void drainPendingSyncs() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || payloadCache == null || PENDING_SYNCS.isEmpty()) {
            return;
        }
        UUID[] pending = PENDING_SYNCS.toArray(UUID[]::new);
        for (UUID playerId : pending) {
            if (!PENDING_SYNCS.remove(playerId)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && isConnectionActive(player)) {
                syncAvailableChannels(player);
            }
        }
    }

    private static void syncAvailableChannels(ServerPlayer player) {
        syncAvailableChannels(player, false);
    }

    private static void syncAvailableChannels(ServerPlayer player, boolean beforeVanillaRecipeUpdate) {
        Channel channel = channel(player);
        if (channel == null || !Boolean.TRUE.equals(channel.attr(CONNECTION_ACTIVE).get())) {
            return;
        }
        if (Boolean.TRUE.equals(channel.attr(FABRIC_CHANNEL_AVAILABLE).get())) {
            syncFabric(player, beforeVanillaRecipeUpdate);
        }
        if (Boolean.TRUE.equals(channel.attr(NEOFORGE_CHANNEL_AVAILABLE).get())) {
            syncNeoForge(player, beforeVanillaRecipeUpdate);
        }
    }

    private static void syncFabric(ServerPlayer player, boolean beforeVanillaRecipeUpdate) {
        Channel channel = channel(player);
        if (channel == null) {
            return;
        }
        Set<Identifier> supported = channel.attr(FABRIC_SUPPORTED_SERIALIZERS).get();
        if (supported == null || !canSendVersionedPayload(player)) {
            return;
        }

        long generation = GENERATION.get();
        RecipePayloadCache cache = payloadCache;
        if (cache == null) {
            queueSync(player.getUUID());
            return;
        }
        EncodedPayload payload = beforeVanillaRecipeUpdate
                ? cache.cachedFabricPayload(supported)
                : cache.fabricPayload(supported);
        if (payload == null) {
            // Variant payloads are assembled by the global fallback instead of a Folia player region.
            queueSync(player.getUUID());
            return;
        }
        if (!payload.sendable()) {
            return;
        }

        if (GENERATION.get() != generation || payloadCache != cache) {
            queueSync(player.getUUID());
            return;
        }
        Long sentGeneration = channel.attr(FABRIC_SENT_GENERATION).get();
        if (sentGeneration != null && sentGeneration == generation) {
            return;
        }
        if (!canSendToPlayer(player, beforeVanillaRecipeUpdate)) {
            return;
        }

        try {
            ProtocolUtils.sendRawPayloadPacket(player, FABRIC_RECIPE_SYNC, payload.data());

            if (!beforeVanillaRecipeUpdate) {
                // JEI stores the synchronized RecipeMap first, then reloads it when vanilla's
                // update-recipes packet is handled. Reversing this order leaves JEI on fallback recipes.
                RecipeManager recipeManager = MinecraftServer.getServer().getRecipeManager();
                player.connection.send(new ClientboundUpdateRecipesPacket(
                        recipeManager.getSynchronizedItemProperties(),
                        recipeManager.getSynchronizedStonecutterRecipes()
                ));
            }
            channel.attr(FABRIC_SENT_GENERATION).set(generation);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to synchronize Fabric recipes to {}", player.getScoreboardName(), exception);
        }
    }

    private static void syncNeoForge(ServerPlayer player, boolean beforeVanillaRecipeUpdate) {
        Channel channel = channel(player);
        if (channel == null) {
            return;
        }
        if (!canSendVersionedPayload(player)) {
            return;
        }

        long generation = GENERATION.get();
        RecipePayloadCache cache = payloadCache;
        if (cache == null) {
            queueSync(player.getUUID());
            return;
        }
        EncodedPayload payload = cache.neoForgePayload();
        if (!payload.sendable()) {
            return;
        }

        if (GENERATION.get() != generation || payloadCache != cache) {
            queueSync(player.getUUID());
            return;
        }
        Long sentGeneration = channel.attr(NEOFORGE_SENT_GENERATION).get();
        if (sentGeneration != null && sentGeneration == generation) {
            return;
        }
        if (!canSendToPlayer(player, beforeVanillaRecipeUpdate)) {
            return;
        }

        try {
            // Configuration and PlayerList#reloadTagData send tags before this hook. This order is
            // required because recipe ingredients may refer to named item tags.
            ProtocolUtils.sendRawPayloadPacket(player, NEOFORGE_RECIPE_CONTENT, payload.data());
            channel.attr(NEOFORGE_SENT_GENERATION).set(generation);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to synchronize NeoForge recipes to {}", player.getScoreboardName(), exception);
        }
    }

    private static boolean canSendVersionedPayload(ServerPlayer player) {
        if (ViaVersionGate.isNativeProtocol(player)) {
            return true;
        }
        Channel channel = channel(player);
        if (channel != null && channel.attr(VERSION_WARNING_SENT).compareAndSet(null, true)) {
            LOGGER.warn(
                    "Not synchronizing recipes to {} because the client protocol does not match the server protocol",
                    player.getScoreboardName()
            );
        }
        return false;
    }

    private static Channel channel(ServerPlayer player) {
        return player.connection.connection.channel;
    }

    private static boolean isConnectionActive(ServerPlayer player) {
        Channel channel = channel(player);
        return channel != null && Boolean.TRUE.equals(channel.attr(CONNECTION_ACTIVE).get());
    }

    private static boolean isCurrentPlayer(ServerPlayer player) {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null
                && isConnectionActive(player)
                && server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private static boolean canSendToPlayer(ServerPlayer player, boolean beforeVanillaRecipeUpdate) {
        if (!isConnectionActive(player) || !player.connection.isAcceptingMessages()) {
            return false;
        }
        return beforeVanillaRecipeUpdate || isCurrentPlayer(player);
    }

    private static RecipePayloadCache ensurePayloadCache() {
        RecipePayloadCache cache = payloadCache;
        if (cache != null) {
            return cache;
        }
        synchronized (RecipeSyncProtocol.class) {
            cache = payloadCache;
            if (cache == null) {
                long generation = GENERATION.get();
                RecipePayloadCache rebuilt = buildPayloadCache();
                if (GENERATION.get() != generation) {
                    SYNC_ALL_WHEN_READY.set(true);
                    return RecipePayloadCache.EMPTY;
                }
                payloadCache = rebuilt;
                cache = rebuilt;
            }
        }
        return cache;
    }

    private static RecipePayloadCache buildPayloadCache() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return RecipePayloadCache.EMPTY;
        }

        try {
            List<RecipeHolder<?>> recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
            recipes.sort(Comparator.comparing(holder -> holder.id().identifier().toString()));

            Map<Identifier, SerializerRecipes> recipesBySerializer = new TreeMap<>(Comparator.comparing(Identifier::toString));
            int skippedRecipes = 0;
            for (RecipeHolder<?> holder : recipes) {
                RecipeSerializer<?> serializer = holder.value().getSerializer();
                Identifier serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
                if (serializerId == null || !serializerId.getNamespace().equals("minecraft")) {
                    skippedRecipes++;
                    continue;
                }
                recipesBySerializer
                        .computeIfAbsent(serializerId, ignored -> new SerializerRecipes(serializer))
                        .recipes()
                        .add(holder);
            }

            Map<Identifier, EncodedGroup> fabricGroups = new LinkedHashMap<>();
            for (Map.Entry<Identifier, SerializerRecipes> entry : recipesBySerializer.entrySet()) {
                try {
                    fabricGroups.put(entry.getKey(), encodeFabricGroup(
                            server,
                            entry.getKey(),
                            entry.getValue().serializer(),
                            entry.getValue().recipes()
                    ));
                } catch (RuntimeException exception) {
                    fabricGroups.put(entry.getKey(), EncodedGroup.failed(entry.getValue().recipes().size()));
                    LOGGER.error("Recipe serializer {} could not be encoded", entry.getKey(), exception);
                }
            }

            EncodedPayload neoForgePayload;
            try {
                neoForgePayload = encodeNeoForgePayload(server, recipes);
            } catch (RuntimeException exception) {
                neoForgePayload = EncodedPayload.EMPTY;
                LOGGER.error("The NeoForge recipe payload could not be encoded", exception);
            }
            if (skippedRecipes > 0) {
                LOGGER.warn(
                        "Recipe synchronization skipped {} recipe(s) whose serializer is not safe for vanilla clients",
                        skippedRecipes
                );
            }
            RecipePayloadCache cache = new RecipePayloadCache(Map.copyOf(fabricGroups), neoForgePayload);
            cache.warmFabricPayload();
            return cache;
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to build recipe synchronization payloads", exception);
            return RecipePayloadCache.EMPTY;
        }
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    private static EncodedGroup encodeFabricGroup(
            MinecraftServer server,
            Identifier serializerId,
            RecipeSerializer<?> serializer,
            List<RecipeHolder<?>> recipes
    ) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(256, MAX_PAYLOAD_SIZE),
                server.registryAccess()
        );
        try {
            buf.writeIdentifier(serializerId);
            buf.writeVarInt(recipes.size());
            StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> codec =
                    (StreamCodec<RegistryFriendlyByteBuf, Recipe<?>>) serializer.streamCodec();
            for (RecipeHolder<?> holder : recipes) {
                buf.writeResourceKey(holder.id());
                codec.encode(buf, holder.value());
            }
            return new EncodedGroup(ByteBufUtil.getBytes(buf), recipes.size(), true);
        } catch (IndexOutOfBoundsException exception) {
            LOGGER.error(
                    "Fabric recipe serializer {} exceeds the {} byte custom-payload limit",
                    serializerId,
                    MAX_PAYLOAD_SIZE
            );
            return EncodedGroup.failed(recipes.size());
        } finally {
            buf.release();
        }
    }

    private static EncodedPayload encodeNeoForgePayload(
            MinecraftServer server,
            List<RecipeHolder<?>> recipes
    ) {
        List<RecipeHolder<?>> included = new ArrayList<>();
        for (RecipeHolder<?> holder : recipes) {
            Identifier serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
            if (serializerId != null
                    && serializerId.getNamespace().equals("minecraft")
                    && NEOFORGE_RECIPE_TYPE_SET.contains(holder.value().getType())) {
                included.add(holder);
            }
        }

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(256, MAX_PAYLOAD_SIZE),
                server.registryAccess()
        );
        try {
            buf.writeVarInt(NEOFORGE_RECIPE_TYPES.size());
            for (RecipeType<?> recipeType : NEOFORGE_RECIPE_TYPES) {
                buf.writeVarInt(BuiltInRegistries.RECIPE_TYPE.getId(recipeType));
            }
            buf.writeVarInt(included.size());
            for (RecipeHolder<?> holder : included) {
                RecipeHolder.STREAM_CODEC.encode(buf, holder);
            }
            return checkedPayload("NeoForge", ByteBufUtil.getBytes(buf), included.size(), NEOFORGE_RECIPE_TYPES.size());
        } catch (IndexOutOfBoundsException exception) {
            LOGGER.error("The NeoForge recipe payload exceeds the {} byte custom-payload limit", MAX_PAYLOAD_SIZE);
            return EncodedPayload.EMPTY;
        } finally {
            buf.release();
        }
    }

    private static EncodedPayload checkedPayload(String loader, byte[] data, int recipes, int groups) {
        if (data.length > MAX_PAYLOAD_SIZE) {
            LOGGER.error(
                    "The {} recipe payload is {} bytes, above the {} byte custom-payload limit; "
                            + "the complete payload will not be sent",
                    loader,
                    data.length,
                    MAX_PAYLOAD_SIZE
            );
            return EncodedPayload.EMPTY;
        }
        LOGGER.info("Prepared {} recipe payload: {} recipes in {} groups ({} bytes)", loader, recipes, groups, data.length);
        return new EncodedPayload(data, recipes, groups, true);
    }

    private record SerializerRecipes(RecipeSerializer<?> serializer, List<RecipeHolder<?>> recipes) {
        private SerializerRecipes(RecipeSerializer<?> serializer) {
            this(serializer, new ArrayList<>());
        }
    }

    private record EncodedGroup(byte[] data, int recipes, boolean sendable) {
        private static EncodedGroup failed(int recipes) {
            return new EncodedGroup(new byte[0], recipes, false);
        }
    }

    private record EncodedPayload(byte[] data, int recipes, int groups, boolean sendable) {
        private static final EncodedPayload EMPTY = new EncodedPayload(new byte[0], 0, 0, false);
    }

    private static final class RecipePayloadCache {
        private static final RecipePayloadCache EMPTY = new RecipePayloadCache(Map.of(), EncodedPayload.EMPTY);

        private final Map<Identifier, EncodedGroup> fabricGroups;
        private final EncodedPayload neoForgePayload;
        private final Map<Set<Identifier>, EncodedPayload> fabricPayloads =
                new LinkedHashMap<>(MAX_CACHED_FABRIC_VARIANTS, 0.75F, true);

        private RecipePayloadCache(
                Map<Identifier, EncodedGroup> fabricGroups,
                EncodedPayload neoForgePayload
        ) {
            this.fabricGroups = fabricGroups;
            this.neoForgePayload = neoForgePayload;
        }

        private synchronized EncodedPayload fabricPayload(Set<Identifier> supportedSerializers) {
            if (this == EMPTY) {
                return EncodedPayload.EMPTY;
            }
            Set<Identifier> key = this.selectFabricSerializers(supportedSerializers);
            EncodedPayload cached = this.fabricPayloads.get(key);
            if (cached != null) {
                return cached;
            }
            EncodedPayload encoded = this.encodeFabricPayload(key);
            if (this.fabricPayloads.size() >= MAX_CACHED_FABRIC_VARIANTS) {
                var iterator = this.fabricPayloads.entrySet().iterator();
                iterator.next();
                iterator.remove();
            }
            this.fabricPayloads.put(key, encoded);
            return encoded;
        }

        private synchronized @Nullable EncodedPayload cachedFabricPayload(Set<Identifier> supportedSerializers) {
            if (this == EMPTY) {
                return EncodedPayload.EMPTY;
            }
            return this.fabricPayloads.get(this.selectFabricSerializers(supportedSerializers));
        }

        private Set<Identifier> selectFabricSerializers(Set<Identifier> supportedSerializers) {
            Set<Identifier> selected = new HashSet<>();
            for (Identifier serializer : supportedSerializers) {
                if (this.fabricGroups.containsKey(serializer)) {
                    selected.add(serializer);
                }
            }
            return Set.copyOf(selected);
        }

        private void warmFabricPayload() {
            this.fabricPayload(this.fabricGroups.keySet());
        }

        private EncodedPayload encodeFabricPayload(Set<Identifier> selectedSerializers) {
            RegistryFriendlyByteBuf buf = ProtocolUtils.decorate(Unpooled.buffer(256, MAX_PAYLOAD_SIZE));
            try {
                List<Identifier> selected = selectedSerializers.stream()
                        .sorted(Comparator.comparing(Identifier::toString))
                        .toList();
                int recipes = 0;
                buf.writeVarInt(selected.size());
                for (Identifier serializer : selected) {
                    EncodedGroup group = this.fabricGroups.get(serializer);
                    if (!group.sendable()) {
                        LOGGER.error(
                                "The Fabric recipe payload cannot be completed because serializer {} failed to encode",
                                serializer
                        );
                        return EncodedPayload.EMPTY;
                    }
                    buf.writeBytes(group.data());
                    recipes += group.recipes();
                }
                return checkedPayload("Fabric", ByteBufUtil.getBytes(buf), recipes, selected.size());
            } catch (IndexOutOfBoundsException exception) {
                LOGGER.error("The Fabric recipe payload exceeds the {} byte custom-payload limit", MAX_PAYLOAD_SIZE);
                return EncodedPayload.EMPTY;
            } finally {
                buf.release();
            }
        }

        private EncodedPayload neoForgePayload() {
            return this.neoForgePayload;
        }
    }

    public record FabricSupportedSerializersPayload(Set<Identifier> serializers) implements LeavesCustomPayload {
        @LeavesCustomPayload.ID
        public static final Identifier ID = Identifier.fromNamespaceAndPath(
                "fabric",
                "recipe_sync/supported_serializers"
        );

        @LeavesCustomPayload.Codec
        public static final StreamCodec<RegistryFriendlyByteBuf, FabricSupportedSerializersPayload> CODEC =
                StreamCodec.ofMember(
                        FabricSupportedSerializersPayload::write,
                        FabricSupportedSerializersPayload::read
                );

        private static FabricSupportedSerializersPayload read(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > MAX_CONFIGURATION_PAYLOAD_SIZE) {
                throw new DecoderException("Fabric recipe serializer payload is too large");
            }
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_SUPPORTED_SERIALIZERS) {
                throw new DecoderException("Invalid Fabric recipe serializer count: " + size);
            }
            Set<Identifier> serializers = new HashSet<>();
            for (int i = 0; i < size; i++) {
                serializers.add(buf.readIdentifier());
            }
            return new FabricSupportedSerializersPayload(Set.copyOf(serializers));
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(this.serializers.size());
            for (Identifier serializer : this.serializers) {
                buf.writeIdentifier(serializer);
            }
        }
    }

    private static final class ViaVersionGate {
        private static final AtomicBoolean WARNED = new AtomicBoolean();
        private static volatile ViaAccess access;

        private ViaVersionGate() {
        }

        private static boolean isNativeProtocol(ServerPlayer player) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("ViaVersion");
            if (plugin == null || !plugin.isEnabled()) {
                return true;
            }

            try {
                ViaAccess via = access;
                if (via == null) {
                    via = resolve(plugin.getClass().getClassLoader());
                    access = via;
                }
                int clientProtocol = via.playerProtocol(player.getUUID());
                return clientProtocol >= 0 && clientProtocol == SharedConstants.getProtocolVersion();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                if (WARNED.compareAndSet(false, true)) {
                    LOGGER.warn(
                            "ViaVersion is installed but its client protocol could not be read; "
                                    + "recipe synchronization will fail closed",
                            exception
                    );
                }
                return false;
            }
        }

        private static ViaAccess resolve(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via", true, loader);
            Class<?> viaApiClass = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, loader);
            return new ViaAccess(
                    viaClass.getMethod("getAPI"),
                    viaApiClass.getMethod("getPlayerVersion", UUID.class)
            );
        }

        private record ViaAccess(Method getApi, Method getPlayerVersion) {
            private int playerProtocol(UUID playerId) throws ReflectiveOperationException {
                Object api = this.getApi.invoke(null);
                return (int) this.getPlayerVersion.invoke(api, playerId);
            }
        }
    }
}
