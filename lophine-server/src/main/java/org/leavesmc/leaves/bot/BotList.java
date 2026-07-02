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

package org.leavesmc.leaves.bot;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import fun.bm.lophine.config.modules.function.FakeplayerConfig;
import fun.bm.lophine.config.modules.function.OldFeatureConfig;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.profile.MutablePropertyMap;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.scheduler.FoliaGlobalRegionScheduler;
import io.papermc.paper.util.MCUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.scores.PlayerTeam;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.event.bot.*;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class BotList {

    public static BotList INSTANCE;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final MinecraftServer server;

    public final List<ServerBot> bots = new CopyOnWriteArrayList<>();
    private final BotDataStorage manualSaveDataStorage;
    private final BotDataStorage resumeDataStorage;

    private final Map<UUID, ServerBot> botsByUUID = Maps.newHashMap();
    private final Map<String, ServerBot> botsByName = Maps.newHashMap();
    private final Map<String, Set<String>> botsNameByWorldUuid = Maps.newHashMap();
    private final Map<String, Set<String>> legacyBotsNameByWorldUuid = Maps.newHashMap();

    public boolean forceShutdown = false;

    public BotList(@NotNull MinecraftServer server) {
        this.server = server;
        this.manualSaveDataStorage = new BotDataStorage(server.storageSource, "fakeplayerdata", "fakeplayer.dat");
        this.resumeDataStorage = new BotDataStorage(server.storageSource, "resume_fakeplayerdata", "resume_fakeplayer.dat");
        INSTANCE = this;
    }

    public void saveAllResumeBots(final int interval) {
        MCUtil.ensureMain("Save Bots", () -> {
            final long now = System.currentTimeMillis() / 50;
            for (ServerBot bot : bots) {
                if (interval == -1 || now - bot.lastSave >= interval) {
                    this.resumeDataStorage.save(bot);
                    bot.lastSave = now;
                }
            }
            return null;
        });
    }

    public void saveAllResumeBots() {
        if (!FakeplayerConfig.checkEnabled() || !FakePlayerCompatConfig.fakePlayerResident) {
            return;
        }
        for (ServerBot bot : this.bots) {
            this.resumeDataStorage.save(bot);
        }
    }

    public ServerBot createNewBot(@NotNull BotCreateState state) {
        BotCreateEvent event = new BotCreateEvent(state.fullName(), state.skinName(), state.location(), state.createReason(), state.creator());
        event.setCancelled(!BotUtil.isCreateLegal(state.fullName()));
        this.server.server.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return null;
        }

        Location location = event.getCreateLocation();
        ServerLevel world = ((CraftWorld) location.getWorld()).getHandle();

        GameProfile profile = createBotProfile(BotUtil.getBotUUID(state), state.fullName(), state.skin());
        ServerBot bot = new ServerBot(this.server, world, profile);
        bot.createState = state;
        if (event.getCreator() instanceof org.bukkit.entity.Player player) {
            bot.createPlayer = player.getUniqueId();
        }

        return this.placeNewBot(bot, world, location, null);
    }

    public ServerBot loadNewManualSavedBot(String fullName) {
        return this.loadNewBot(fullName, this.manualSaveDataStorage);
    }

    public ServerBot loadNewResumeBot(String fullName) {
        return this.loadNewBot(fullName, this.resumeDataStorage);
    }

    public ServerBot loadNewBot(String fullName, BotDataStorage storage) {
        if (botsByName.containsKey(fullName)) {
            return null;
        }
        try {
            UUID uuid = BotUtil.getBotUUID(fullName);

            BotLoadEvent event = new BotLoadEvent(fullName, uuid);
            this.server.server.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return null;
            }

            ServerBot bot = new ServerBot(this.server, this.server.getLevel(Level.OVERWORLD), new GameProfile(uuid, fullName));
            bot.connection = new ServerBotPacketListenerImpl(this.server, bot);
            Optional<ValueInput> optional;
            try (ProblemReporter.ScopedCollector scopedCollector = new ProblemReporter.ScopedCollector(bot.problemPath(), LOGGER)) {
                optional = storage.load(bot, scopedCollector);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            if (optional.isEmpty()) {
                return null;
            }
            ValueInput nbt = optional.get();

            ResourceKey<Level> resourcekey = null;
            if (nbt.getLong("WorldUUIDMost").isPresent() && nbt.getLong("WorldUUIDLeast").isPresent()) {
                org.bukkit.World bWorld = Bukkit.getServer().getWorld(new UUID(nbt.getLong("WorldUUIDMost").orElseThrow(), nbt.getLong("WorldUUIDLeast").orElseThrow()));
                if (bWorld != null) {
                    resourcekey = ((CraftWorld) bWorld).getHandle().dimension();
                }
            }
            if (resourcekey == null) {
                return null;
            }

            ServerLevel world = this.server.getLevel(resourcekey);
            return this.placeNewBot(bot, world, bot.getLocation(), nbt);
        } catch (Exception e) {
            LOGGER.error("Failed to load bot {}", fullName, e);
            return null;
        }
    }

    public ServerBot placeNewBot(@NotNull ServerBot bot, ServerLevel world, Location location, ValueInput save) {
        Optional<ValueInput> optional = Optional.ofNullable(save);

        CommonListenerCookie cookie = CommonListenerCookie.createInitial(bot.gameProfile, false);
        bot.isRealPlayer = true; // Paper
        bot.loginTime = System.currentTimeMillis(); // Paper - Replace OfflinePlayer#getLastPlayed
        bot.connection = new ServerBotPacketListenerImpl(this.server, bot);
        if (bot.connection.connection.getPlayer() != bot) {
            throw new IllegalStateException("Bot connection is not bound to its bot player");
        }

        bot.getBukkitEntity().setMetadata("NPC", new FixedMetadataValue(MinecraftInternalPlugin.INSTANCE, true));

        BotSpawnLocationEvent event = new BotSpawnLocationEvent(bot.getBukkitEntity(), location);
        this.server.server.getPluginManager().callEvent(event);

        Connection connection = bot.connection.connection;
        NameAndId gameProfile = bot.nameAndId();
        UserNameToIdResolver profileCache = this.server.services().nameToIdCache();
        Optional<NameAndId> oldProfile = profileCache.get(gameProfile.id());
        String oldName = oldProfile.map(NameAndId::name).orElse(gameProfile.name());
        if (bot.lastKnownName != null) {
            oldName = bot.lastKnownName;
            bot.lastKnownName = null;
        } // CraftBukkit - Better rename detection
        profileCache.add(gameProfile);
        final ServerLevel[] level = {bot.level()};
        String address = connection.getLoggableAddress(this.server.logIPs());
        LevelData levelData = level[0].getLevelData();
        ServerGamePacketListenerImpl playerConnection = new ServerGamePacketListenerImpl(this.server, connection, bot, cookie);
        // Folia start - rewrite login process
        // only after setting the connection listener to game type, add the connection to this regions list
        level[0].getCurrentWorldData().connections.add(connection);
        // Folia end - rewrite login process
        if (!me.earthme.luminol.config.modules.optimizations.AsyncProtocolChangeConfig.enabled) { // Luminol - Async protocol switch // we will run async switch once these main thread logics became done
            connection.setupInboundProtocol(
                    GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(this.server.registryAccess()), playerConnection), playerConnection
            );
        } // Luminol - Async protocol switch
        playerConnection.suspendFlushing();
        GameRules gameRules = level[0].getGameRules();
        boolean immediateRespawn = gameRules.get(GameRules.IMMEDIATE_RESPAWN);
        boolean reducedDebugInfo = gameRules.get(GameRules.REDUCED_DEBUG_INFO);
        boolean doLimitedCrafting = gameRules.get(GameRules.LIMITED_CRAFTING);
        playerConnection.send(
                new ClientboundLoginPacket(
                        bot.getId(),
                        levelData.isHardcore(),
                        this.server.levelKeys(),
                        this.server.getPlayerList().getMaxPlayers(),
                        io.papermc.paper.FeatureHooks.getViewDistance(level[0]), // Paper - view distance
                        io.papermc.paper.FeatureHooks.getSimulationDistance(level[0]), // Paper - simulation distance
                        reducedDebugInfo,
                        !immediateRespawn,
                        doLimitedCrafting,
                        bot.createCommonSpawnInfo(level[0]),
                        this.server.usesAuthentication(),
                        this.server.enforceSecureProfile()
                )
        );
        bot.getBukkitEntity().sendSupportedChannels(); // CraftBukkit
        playerConnection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
        playerConnection.send(new ClientboundPlayerAbilitiesPacket(bot.getAbilities()));
        playerConnection.send(new ClientboundSetHeldSlotPacket(bot.getInventory().getSelectedSlot()));
        RecipeManager recipeManager = this.server.getRecipeManager();
        playerConnection.send(
                new ClientboundUpdateRecipesPacket(recipeManager.getSynchronizedItemProperties(), recipeManager.getSynchronizedStonecutterRecipes())
        );
        this.server.getPlayerList().sendPlayerPermissionLevel(bot);
        bot.getStats().markAllDirty();
        bot.getRecipeBook().sendInitialRecipeBook(bot);
        //this.updateEntireScoreboard(level.getScoreboard(), player); // Folia - region threading
        this.server.invalidateStatus();
        MutableComponent component;
        if (bot.getGameProfile().name().equalsIgnoreCase(oldName)) {
            component = Component.translatable("multiplayer.player.joined", bot.getDisplayName());
        } else {
            component = Component.translatable("multiplayer.player.joined.renamed", bot.getDisplayName(), oldName);
        }

        // CraftBukkit start
        component.withStyle(ChatFormatting.YELLOW);
        final Component[] joinMessage = {component}; // Paper - Adventure
        playerConnection.teleport(bot.getX(), bot.getY(), bot.getZ(), bot.getYRot(), bot.getXRot());
        ServerStatus status = this.server.getStatus();
        if (status != null && !cookie.transferred()) {
            bot.sendServerStatus(status);
        }

        // player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(this.players)); // CraftBukkit - replaced with loop below
        this.server.getPlayerList().getPlayers().add(bot);
        this.bots.add(bot);
        this.botsByName.put(bot.getScoreboardName().toLowerCase(Locale.ROOT), bot);
        this.botsByUUID.put(bot.getUUID(), bot);
        // this.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player))); // CraftBukkit - replaced with loop below
        // Paper start - Fire PlayerJoinEvent when Player is actually ready; correctly register player BEFORE PlayerJoinEvent, so the entity is valid and doesn't require tick delay hacks
        bot.suppressTrackerForLogin = true;
        this.server.getPlayerList().sendLevelInfo(bot, level[0]);
        level[0].addNewPlayer(bot);
        this.server.getCustomBossEvents().onPlayerConnect(bot); // see commented out section below serverLevel.addPlayerJoin(player);
        // Paper end - Fire PlayerJoinEvent when Player is actually ready
        bot.initInventoryMenu();
        // CraftBukkit start
        org.bukkit.craftbukkit.entity.CraftPlayer bukkitPlayer = bot.getBukkitEntity();

        // Ensure that player inventory is populated with its viewer
        bot.containerMenu.transferTo(bot.containerMenu, bukkitPlayer);

        Runnable task = () -> {
            optional.ifPresent(nbt -> {
                bot.loadAndSpawnEnderPearls(nbt);
                bot.loadAndSpawnParentVehicle(nbt);
            });
            world.getCurrentWorldData().connections.add(bot.connection.connection);
            world.addNewPlayer(bot);
            BotJoinEvent event1 = new BotJoinEvent(bot.getBukkitEntity(), PaperAdventure.asAdventure(Component.translatable("multiplayer.player.joined", bot.getDisplayName())).style(Style.style(NamedTextColor.YELLOW)));
            this.server.server.getPluginManager().callEvent(event1);

            if (!bot.connection.isAcceptingMessages()) {
                //return; // Folia - region threading - must still allow the player to connect, as we must add to chunk map before handling disconnect
            }

            org.leavesmc.leaves.protocol.core.LeavesProtocolManager.handlePlayerJoin(bot); // Leaves - protocol

            final net.kyori.adventure.text.Component jm = event1.joinMessage();

            if (jm != null && !jm.equals(net.kyori.adventure.text.Component.empty())) { // Paper - Adventure
                joinMessage[0] = io.papermc.paper.adventure.PaperAdventure.asVanilla(jm); // Paper - Adventure
                this.server.getPlayerList().broadcastSystemMessage(joinMessage[0], false); // Paper - Adventure
            }
            // CraftBukkit end

            // CraftBukkit start - sendAll above replaced with this loop
            ClientboundPlayerInfoUpdatePacket packet = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot)); // Paper - Add Listing API for Player

            final List<ServerPlayer> onlinePlayers = Lists.newArrayListWithExpectedSize(this.server.getPlayerList().getPlayers().size() - 1); // Paper - Use single player info update packet on join
            for (ServerPlayer entityplayer1 : this.server.getPlayerList().getPlayers()) { // Folia - region threading

                if (entityplayer1.getBukkitEntity().canSee(bukkitPlayer)) {
                    // Paper start - Add Listing API for Player
                    if (entityplayer1.getBukkitEntity().isListed(bukkitPlayer)) {
                        // Paper end - Add Listing API for Player
                        entityplayer1.connection.send(packet);
                        // Paper start - Add Listing API for Player
                    } else {
                        entityplayer1.connection.send(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(bot, false));
                    }
                    // Paper end - Add Listing API for Player
                }

                if (entityplayer1 == bot || !bukkitPlayer.canSee(entityplayer1.getBukkitEntity())) { // Paper - Use single player info update packet on join; Don't include joining player
                    continue;
                }

                onlinePlayers.add(entityplayer1); // Paper - Use single player info update packet on join
            }
            // Paper start - Use single player info update packet on join
            if (!onlinePlayers.isEmpty()) {
                bot.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(onlinePlayers, bot)); // Paper - Add Listing API for Player
            }
            // Paper end - Use single player info update packet on join
            bot.sentListPacket = true;
            bot.suppressTrackerForLogin = false; // Paper - Fire PlayerJoinEvent when Player is actually ready
            bot.level().getChunkSource().addEntity(bot); // Paper - Fire PlayerJoinEvent when Player is actually ready; track entity now
            // CraftBukkit end

            //player.refreshEntityData(player); // CraftBukkit - BungeeCord#2321, send complete data to self on spawn // Paper - THIS IS NOT NEEDED ANYMORE

            this.server.getPlayerList().sendLevelInfo(bot, level[0]);

            // CraftBukkit start - Only add if the player wasn't moved in the event
            if (bot.level() == level[0] && !level[0].players().contains(bot)) {
                level[0].addNewPlayer(bot);
                this.server.getCustomBossEvents().onPlayerConnect(bot);
            }

            level[0] = bot.level(); // CraftBukkit - Update in case join event changed it
            // CraftBukkit end
            this.server.getPlayerList().sendActivePlayerEffects(bot);
            // Paper - move loading pearls / parent vehicle up
            bot.initInventoryMenu();
            this.server.notificationManager().playerJoined(bot);
            playerConnection.resumeFlushing();
            // Paper start - Configurable player collision; Add to collideRule team if needed
            final net.minecraft.world.scores.Scoreboard scoreboard = this.server.getPlayerList().getServer().getLevel(Level.OVERWORLD).getScoreboard();
            final PlayerTeam collideRuleTeam = scoreboard.getPlayerTeam(this.server.getPlayerList().collideRuleTeamName);
            if (false && this.server.getPlayerList().collideRuleTeamName != null && collideRuleTeam != null && bot.getTeam() == null) { // Folia - region threading
                scoreboard.addPlayerToTeam(bot.getScoreboardName(), collideRuleTeam);
            }
            // Paper end - Configurable player collision
            // CraftBukkit start - moved down
            LOGGER.info(
                    "{}[{}] logged in with entity id {} at ([{}]{}, {}, {})", // Paper - add world identifier
                    bot.getPlainTextName(),
                    address,
                    bot.getId(),
                    level[0].dimension().identifier(), // Paper - add world identifier
                    bot.getX(),
                    bot.getY(),
                    bot.getZ()
            );
            // CraftBukkit end - moved down
            // Paper start - Send empty chunk, so players aren't stuck in the world loading screen with our chunk system not sending chunks when dead
            if (bot.isDeadOrDying()) {
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> plains = level[0].registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                        .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
                bot.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                        new net.minecraft.world.level.chunk.EmptyLevelChunk(level[0], bot.chunkPosition(), plains),
                        level[0].getLightEngine(), (java.util.BitSet) null, (java.util.BitSet) null, true) // Paper - Anti-Xray
                );
            }
            // Paper end - Send empty chunk
        };
        if (TickThread.isTickThreadFor(world, location.blockX() >> 4, location.blockZ() >> 4)) {
            task.run();
        } else {
            RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                    world, location.getBlockX() >> 4, location.blockZ() >> 4,
                    task);
        }

        return bot;
    }

    /*
     * return true if async
     */
    public boolean removeBot(@NotNull ServerBot bot, @NotNull BotRemoveEvent.RemoveReason reason, @Nullable CommandSender remover, boolean save, boolean resume, boolean async) {
        if (async && !TickThread.isTickThreadFor(bot.level(), bot.getX(), bot.getZ())) {
            bot.getBukkitEntity().taskScheduler.schedule((Entity unused) -> this.removeBot(bot, reason, remover, save, resume), null, 1L);
            return true; // async always return true
        }
        return this.removeBot(bot, remover, reason, save, resume);
    }

    public boolean removeBot(@NotNull ServerBot bot, @NotNull BotRemoveEvent.RemoveReason reason, @Nullable CommandSender remover, boolean save, boolean resume) {
        return this.removeBot(bot, reason, remover, save, resume, true);
    }

    public boolean removeBot(@NotNull ServerBot bot, @Nullable CommandSender remover, @NotNull BotRemoveEvent.RemoveReason reason, boolean save, boolean resume) {
        BotRemoveEvent event = new BotRemoveEvent(bot.getBukkitEntity(), reason, remover, PaperAdventure.asAdventure(Component.translatable("multiplayer.player.left", bot.getDisplayName())).style(Style.style(NamedTextColor.YELLOW)), save);
        this.server.server.getPluginManager().callEvent(event);

        if (event.isCancelled() && event.getReason() != BotRemoveEvent.RemoveReason.INTERNAL) {
            return false;
        }

        if (bot.removeTaskId != -1) {
            ((FoliaGlobalRegionScheduler) Bukkit.getGlobalRegionScheduler()).cancelTask(bot.removeTaskId);
            bot.removeTaskId = -1;
        }

        bot.disconnect();

        this.resumeDataStorage.removeSavedData(bot);
        if (event.shouldSave()) {
            if (resume) {
                this.resumeDataStorage.save(bot);
            } else {
                this.manualSaveDataStorage.save(bot);
            }
        } else {
            bot.dropAll(true);
            botsNameByWorldUuid.getOrDefault(bot.level().uuid.toString(), new HashSet<>()).remove(bot.getBukkitEntity().getName());
        }

        if (bot.isPassenger() && event.shouldSave()) {
            Entity entity = bot.getRootVehicle();
            if (entity.hasExactlyOnePlayerPassenger()) {
                bot.stopRiding();
                entity.getPassengersAndSelf().forEach((entity1) -> {
                    if (!OldFeatureConfig.villagerVoidTrade && entity1 instanceof AbstractVillager villager) {
                        final Player human = villager.getTradingPlayer();
                        if (human != null) {
                            villager.setTradingPlayer(null);
                        }
                    }
                    entity1.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
                });
            }
        }

        bot.unRide();
        for (ThrownEnderpearl thrownEnderpearl : bot.getEnderPearls()) {
            // Lophine start - restore vanilla ender pearl loading on Folia
            thrownEnderpearl.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity pearl) -> {
                if (!pearl.isRemoved()) {
                    pearl.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER, EntityRemoveEvent.Cause.PLAYER_QUIT);
                }
            });
            // Lophine end - restore vanilla ender pearl loading on Folia
        }

        bot.level().getCurrentWorldData().connections.remove(bot.connection.connection);
        bot.level().removePlayerImmediately(bot, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        bot.retireScheduler();

        this.bots.remove(bot);
        this.botsByName.remove(bot.getScoreboardName().toLowerCase(Locale.ROOT));

        UUID uuid = bot.getUUID();
        ServerBot bot1 = this.botsByUUID.get(uuid);
        if (bot1 == bot) {
            this.botsByUUID.remove(uuid);
        }

        bot.removeTab();
        ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(bot.getId());
        for (ServerPlayer player : bot.level().players()) {
            if (!(player instanceof ServerBot)) {
                player.connection.send(packet);
            }
        }

        net.kyori.adventure.text.Component removeMessage = event.removeMessage();
        if (removeMessage != null && !removeMessage.equals(net.kyori.adventure.text.Component.empty())) {
            this.server.getPlayerList().broadcastSystemMessage(PaperAdventure.asVanilla(removeMessage), false);
        }
        return true;
    }

    public void removeAllIn(String worldUuid) {
        for (String fullName : this.botsNameByWorldUuid.getOrDefault(worldUuid, new HashSet<>())) {
            ServerBot bot = this.getBotByName(fullName);
            if (bot != null) {
                this.removeBot(bot, BotRemoveEvent.RemoveReason.INTERNAL, null, FakePlayerCompatConfig.fakePlayerResident, FakePlayerCompatConfig.fakePlayerResident);
            }
        }
    }

    public boolean removeAll() {
        boolean finished = true;
        AtomicInteger check = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        for (ServerBot bot : this.bots) {
            bot.resume = FakePlayerCompatConfig.fakePlayerResident;
            if (TickThread.isTickThreadFor(bot.level(), bot.getX(), bot.getZ())) {
                this.removeBot(bot, BotRemoveEvent.RemoveReason.INTERNAL, null, FakePlayerCompatConfig.fakePlayerResident, FakePlayerCompatConfig.fakePlayerResident);
            } else {
                finished = false;
                check.getAndIncrement();
                this.removeBot(bot, check, received, new AtomicInteger());
            }
        }
        return finished;
    }

    private void removeBot(ServerBot bot, AtomicInteger check, AtomicInteger received, AtomicInteger counter) {
        bot.getBukkitEntity().taskScheduler.schedule((Entity unused) -> {
            if (counter.get() >= 20) {
                BotList.LOGGER.info("Try to remove bot {} located in [{}]{},{},{} too many times!", bot.getName().getString(), bot.level().serverLevelData.getLevelName(), bot.getX(), bot.getY(), bot.getZ());
            }
            counter.getAndIncrement();
            try {
                this.removeBot(bot, BotRemoveEvent.RemoveReason.INTERNAL, null, FakePlayerCompatConfig.fakePlayerResident, FakePlayerCompatConfig.fakePlayerResident);
                received.getAndIncrement();
            } catch (Exception e) {
                this.removeBot(bot, check, received, counter);
            }
            if (received.get() >= check.get()) {
                this.forceShutdown = true;
                MinecraftServer.getServer().stopServer();
            }
        }, null, 1L);
    }

    public void loadResumeBotInfo() {
        if (!FakeplayerConfig.checkEnabled() || !FakePlayerCompatConfig.fakePlayerResident) {
            return;
        }
        CompoundTag savedBotList = this.getResumeBotList().copy();
        for (String fullName : savedBotList.keySet()) {
            UUID levelUuid = BotUtil.getBotLevel(fullName, this.resumeDataStorage);
            if (levelUuid == null) {
                LOGGER.warn("Bot {} has no world UUID, skipping loading.", fullName);
                continue;
            }
            this.botsNameByWorldUuid
                    .computeIfAbsent(levelUuid.toString(), (k) -> new HashSet<>())
                    .add(fullName);
        }
        loadLegacyResumeBotInfo();
    }

    private void loadLegacyResumeBotInfo() {
        CompoundTag savedBotList = this.getManualSavedBotList().copy();
        for (String fullName : savedBotList.keySet()) {
            CompoundTag nbt = savedBotList.getCompound(fullName).orElseThrow();
            if (!nbt.getBoolean("resume").orElse(false)) {
                continue;
            }
            UUID levelUuid = BotUtil.getBotLevel(fullName, this.manualSaveDataStorage);
            if (levelUuid == null) {
                LOGGER.warn("Bot {} has no world UUID, skipping loading.", fullName);
                continue;
            }
            this.legacyBotsNameByWorldUuid
                    .computeIfAbsent(levelUuid.toString(), (k) -> new HashSet<>())
                    .add(fullName);
        }
    }

    public void loadResume(String worldUuid) {
        if (!FakeplayerConfig.checkEnabled() || !FakePlayerCompatConfig.fakePlayerResident) {
            return;
        }
        new HashSet<>(this.botsNameByWorldUuid.getOrDefault(worldUuid, new HashSet<>())).forEach(this::loadNewResumeBot);
        new HashSet<>(this.legacyBotsNameByWorldUuid.getOrDefault(worldUuid, new HashSet<>())).forEach(this::loadNewManualSavedBot);
    }

    public void updateBotLevel(@NotNull ServerBot bot, @NotNull ServerLevel level) {
        String prevUuid = bot.level().uuid.toString();
        String newUuid = level.uuid.toString();
        this.botsNameByWorldUuid
                .computeIfAbsent(newUuid, (k) -> new HashSet<>())
                .add(bot.getBukkitEntity().getName());
        this.botsNameByWorldUuid
                .computeIfAbsent(prevUuid, (k) -> new HashSet<>())
                .remove(bot.getBukkitEntity().getName());
    }

    public void networkTick() {
        this.bots.forEach(ServerBot::networkTick);
    }

    @Nullable
    public ServerBot getBot(@NotNull UUID uuid) {
        return this.botsByUUID.get(uuid);
    }

    @Nullable
    public ServerBot getBotByName(@NotNull String name) {
        return this.botsByName.get(name.toLowerCase(Locale.ROOT));
    }

    public CompoundTag getManualSavedBotList() {
        return this.getSavedBotList(this.manualSaveDataStorage);
    }

    public CompoundTag getResumeBotList() {
        return this.getSavedBotList(this.resumeDataStorage);
    }

    public CompoundTag getSavedBotList(@NotNull BotDataStorage storage) {
        return storage.getSavedBotList();
    }

    @Contract("_, _, _ -> new")
    public static @NotNull GameProfile createBotProfile(UUID uuid, String name, String[] skin) {
        GameProfile profile = new GameProfile(uuid, name, new MutablePropertyMap());
        profile.properties().put("is_bot", new Property("is_bot", "true"));
        if (skin != null) {
            profile.properties().put("textures", new Property("textures", skin[0], skin[1]));
        }
        return profile;
    }
}
