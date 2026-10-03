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
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import fun.bm.lophine.config.modules.function.FakeplayerConfig;
import fun.bm.lophine.config.modules.function.OldFeatureConfig;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.profile.MutablePropertyMap;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.util.MCUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
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

    /**
     * A fresh caller view cannot cancel the actual placement and its join callbacks.
     */
    public java.util.concurrent.CompletableFuture<ServerBot> carpetPlacementCompletion(ServerBot bot) {
        var actual = bot.carpetActualPlacementFuture;
        var view = actual.copy();
        carpet.script.external.ScarpetNativeWork.aliasDependency(view, actual);
        return view;
    }

    private final BotDataStorage manualSaveDataStorage;
    private final BotDataStorage resumeDataStorage;

    private final fun.bm.lophine.carpet.CarpetBotRegistrations<ServerBot> carpetRegistrations = new fun.bm.lophine.carpet.CarpetBotRegistrations<>();
    private final Map<UUID, ServerBot> botsByUUID = carpetRegistrations.byUuid();
    private final Map<String, ServerBot> botsByLowerName = carpetRegistrations.byName();
    private final Map<String, Set<String>> botsNameByWorldUuid = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Set<String>> legacyBotsNameByWorldUuid = new java.util.concurrent.ConcurrentHashMap<>();

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
        if (!fun.bm.lophine.carpet.AmsFakePlayers.canSpawn(this.server, state.fullName(), state.creator())) {
            return null;
        }
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

    public Optional<CompoundTag> saveCarpetBotState(ServerBot bot) {
        TickThread.ensureTickThread(bot, "Saving Carpet bot transaction off its owning thread");
        BotDataStorage storage = bot.resume ? this.resumeDataStorage : this.manualSaveDataStorage;
        synchronized (storage) {
            storage.save(bot);
            return storage.readCarpetSavedState(bot);
        }
    }

    public java.nio.file.Path getCarpetBotStatePath(ServerBot bot) {
        return (bot.resume ? this.resumeDataStorage : this.manualSaveDataStorage).statePath(bot.getUUID());
    }

    public ServerBot loadNewManualSavedBot(String fullName) {
        return this.loadNewBot(fullName, this.manualSaveDataStorage);
    }

    public ServerBot loadNewResumeBot(String fullName) {
        return this.loadNewBot(fullName, this.resumeDataStorage);
    }

    public ServerBot loadNewBot(String inputName, BotDataStorage storage) {
        String lowerName = inputName.toLowerCase(Locale.ROOT);
        if (botsByLowerName.containsKey(lowerName)) {
            return null;
        }
        try {
            if (!storage.getSavedBotList().contains(lowerName)) {
                return null;
            }
            String name = storage.getNameFromLower(lowerName);
            UUID uuid = storage.getUUIDFromLower(lowerName);
            BotLoadEvent event = new BotLoadEvent(name, uuid);
            this.server.server.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return null;
            }

            ServerBot bot = new ServerBot(this.server, this.server.getLevel(Level.OVERWORLD), new GameProfile(uuid, name));
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
            LOGGER.error("Failed to load bot {}", inputName, e);
            return null;
        }
    }

    public ServerBot placeNewBot(@NotNull ServerBot bot, ServerLevel world, Location location, ValueInput save) {
        UUID identity = bot.getUUID();
        String name = bot.getScoreboardName();
        try (var reservation = this.carpetRegistrations.reserve(identity, name)) {
            if (this.server.getPlayerList().getPlayer(identity) != null || this.server.getPlayerList().getPlayerByName(name) != null)
                throw new IllegalStateException("Player identity is already logged in");
            return carpetPlaceNewBot(bot, world, location, save, reservation);
        } catch (Throwable failure) {
            carpetRegistrations.remove(identity, name, bot);
            this.bots.removeIf(value -> value == bot);
            bot.carpetActualPlacementFuture.completeExceptionally(failure);
            bot.carpetPlacementFuture.completeExceptionally(failure);
            throw failure;
        }
    }

    private ServerBot carpetPlaceNewBot(@NotNull ServerBot bot, ServerLevel world, Location location, ValueInput save,
                                        fun.bm.lophine.carpet.CarpetBotRegistrations<ServerBot>.Reservation reservation) {
        Optional<ValueInput> optional = Optional.ofNullable(save);

        bot.isRealPlayer = true;
        bot.loginTime = System.currentTimeMillis();
        bot.connection = new ServerBotPacketListenerImpl(this.server, bot);
        if (bot.connection.connection.getPlayer() != bot) {
            throw new IllegalStateException("Bot connection is not bound to its bot player");
        }
        bot.connection.markClientLoaded();
        bot.getBukkitEntity().setMetadata("NPC", new FixedMetadataValue(MinecraftInternalPlugin.INSTANCE, true));
        bot.setServerLevel(world);

        BotSpawnLocationEvent event = new BotSpawnLocationEvent(bot.getBukkitEntity(), location);
        this.server.server.getPluginManager().callEvent(event);
        Location spawnLocation = event.getSpawnLocation().clone();
        ServerLevel targetWorld = ((CraftWorld) spawnLocation.getWorld()).getHandle();

        fun.bm.lophine.carpet.CarpetPlayerBirths.admitPlayer(bot, bot.carpetActualPlacementFuture);
        reservation.publish(bot);
        this.bots.add(bot);

        bot.suppressTrackerForLogin = true;

        var carpetPlacement = bot.carpetActualPlacementFuture;
        int carpetSpawnX = spawnLocation.getBlockX(), carpetSpawnZ = spawnLocation.getBlockZ();
        var carpetPlace = carpet.script.external.ScarpetRuntime.captureNativeContinuation(() ->
                fun.bm.lophine.carpet.CarpetRegionLease.<java.util.concurrent.CompletableFuture<Void>>runValue(targetWorld,
                        (carpetSpawnX - 32) >> 4, (carpetSpawnZ - 32) >> 4, (carpetSpawnX + 32) >> 4, (carpetSpawnZ + 32) >> 4, lease ->
                                carpet.script.external.ScarpetNativeWork.<Void>observeNative(bot, () -> {
                                    carpet.script.external.ScarpetNativeWork.aliasDependency(carpetPlacement,
                                            carpet.script.external.ScarpetNativeWork.completionOf(carpet.script.external.ScarpetNativeWork.capture()));
                                    var prefix = carpet.script.external.ScarpetNativeWork.<Void>observeNative(bot, () -> {
                                        try (var prefixAccepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(bot)) {
                                            bot.setServerLevel(targetWorld);
                                            bot.gameMode.setLevel(targetWorld);
                                            bot.setPosRaw(spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ());
                                            bot.setRot(spawnLocation.getYaw(), spawnLocation.getPitch());
                                            targetWorld.getCurrentWorldData().connections.add(bot.connection.connection);
                                            bot.connection.teleport(bot.getX(), bot.getY(), bot.getZ(), bot.getYRot(), bot.getXRot());
                                            targetWorld.addNewPlayer(bot);
                                            if (fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerSpawnNoKnockback) {
                                                bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                                                bot.setRemainingFireTicks(0);
                                                bot.fallDistance = 0.0F;
                                                for (var effect : java.util.List.copyOf(bot.getActiveEffects())) {
                                                    if (effect.getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
                                                        bot.removeEffect(effect.getEffect());
                                                    }
                                                }
                                            }
                                            carpet.script.external.ScarpetRetiredActors.capture(bot);
                                            return null;
                                        }
                                    });
                                    var restores = carpetBirthAfterGuest(prefix).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(ignored -> {
                                        if (optional.isEmpty())
                                            return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                                        var actual = fun.bm.lophine.carpet.CarpetPlayerSpawnContinuations.extras(bot, optional.get());
                                        carpet.script.external.ScarpetNativeWork.record(actual);
                                        return carpetBirthAfterGuest(actual);
                                    }));
                                    var joinedPhase = restores.thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(ignored ->
                                            fun.bm.lophine.carpet.CarpetPlayerBirthPhase.run(bot, () -> {
                                                boolean orgSilence = fun.bm.lophine.carpet.OrgNativePlayerMessages.consumeJoin(bot);
                                                this.carpetPublishJoinMessage(bot, orgSilence);

                                                bot.renderInfo();
                                                bot.suppressTrackerForLogin = false;

                                                bot.level().getChunkSource().chunkMap.addEntity(bot);
                                                bot.renderData();
                                                bot.initInventoryMenu();
                                                fun.bm.lophine.carpet.AmsFakePlayers.added(bot);
                                                botsNameByWorldUuid
                                                        .computeIfAbsent(bot.level().uuid.toString(), (k) -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                                                        .add(bot.getBukkitEntity().getName());
                                                if (!orgSilence)
                                                    BotList.LOGGER.info("{}[{}] logged in with entity id {} at ([{}]{}, {}, {})", bot.getName().getString(), "Local", bot.getId(), bot.level().serverLevelData.getLevelName(), bot.getX(), bot.getY(), bot.getZ());
                                                bot.carpetPlacementInitializer.run();
                                                bot.carpetPlacementInitializer = () -> {
                                                };
                                                bot.carpetPlacementReady = true;
                                                if (bot.carpetNativePlayer) {
                                                    carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(bot,
                                                            carpet.script.external.ScarpetNativeWork.completionOf(carpet.script.external.ScarpetNativeWork.capture()));
                                                    try (var joined = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(bot)) {
                                                        carpet.script.external.ScarpetNativeWork.record(fun.bm.lophine.carpet.AmsPlayerJoin.join(bot, true));
                                                    }
                                                } else
                                                    carpet.script.external.ScarpetNativeWork.record(fun.bm.lophine.carpet.AmsPlayerJoin.join(bot, false));
                                            })));
                                    var finished = joinedPhase.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored, failure) -> {
                                        if (failure != null && !carpet.script.external.ScarpetNativeWork.onlyGuestFailure(failure)) {
                                            var cleanup = fun.bm.lophine.carpet.CarpetPlayerBirthPhase.run(bot, () -> {
                                                this.carpetRegistrations.remove(bot.getUUID(), bot.getScoreboardName(), bot);
                                                this.bots.removeIf(value -> value == bot);
                                                bot.carpetPlacementInitializer = () -> {
                                                };
                                                var currentWorld = bot.level();
                                                currentWorld.removePlayerImmediately(bot, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
                                                Runnable retired = () -> {
                                                    currentWorld.getCurrentWorldData().connections.remove(bot.connection.connection);
                                                    bot.retireScheduler();
                                                };
                                                if (!carpet.script.external.ScarpetNativeRemovals.thenOwner(bot, retired))
                                                    retired.run();
                                            });
                                            carpet.script.external.ScarpetNativeWork.record(cleanup);
                                            LOGGER.error("Failed to finish fake player placement", failure);
                                        }
                                    }));
                                    carpet.script.external.ScarpetNativeWork.record(finished);
                                    return null;
                                })).thenCompose(actual -> actual));
        carpet.script.external.ScarpetNativeWork.record(carpetPlacement);
        carpet.script.external.ScarpetNativeWork.trackNative(this.server, carpetPlacement);
        Runnable task = () -> {
            java.util.concurrent.CompletableFuture<Void> actual;
            try {
                actual = carpetPlace.get();
            } catch (Throwable failure) {
                actual = java.util.concurrent.CompletableFuture.failedFuture(failure);
            }
            carpet.script.external.ScarpetNativeWork.aliasDependency(carpetPlacement, actual);
            actual.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    carpetPlacement.complete(bot);
                    bot.carpetPlacementFuture.complete(bot);
                } else {
                    carpetPlacement.completeExceptionally(failure);
                    bot.carpetPlacementFuture.completeExceptionally(failure);
                }
            });
        };
        if (TickThread.isTickThreadFor(targetWorld, spawnLocation.blockX() >> 4, spawnLocation.blockZ() >> 4)) {
            task.run();
        } else {
            RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                    targetWorld, spawnLocation.getBlockX() >> 4, spawnLocation.blockZ() >> 4,
                    task);
        }

        return bot;
    }

    /**
     * Keep the real observer registered in its outer scope; only completed Guest failures allow the next native phase.
     */
    private static java.util.concurrent.CompletableFuture<Void> carpetBirthAfterGuest(java.util.concurrent.CompletableFuture<Void> actual) {
        return actual.handle((ignored, failure) -> {
            if (failure != null && !carpet.script.external.ScarpetNativeWork.onlyGuestFailure(failure))
                throw new java.util.concurrent.CompletionException(failure);
            return null;
        });
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
        if (!bot.carpetNativePlayer) return this.carpetRemoveBotLegacy(bot, remover, reason, save, resume);
        var completed = this.carpetRemoveBotAsync(bot, reason, remover, save, resume);
        return completed.isDone() ? completed.join() : true;
    }

    public java.util.concurrent.CompletableFuture<Boolean> carpetRemoveBotAsync(ServerBot bot, BotRemoveEvent.RemoveReason reason, @Nullable CommandSender remover, boolean save, boolean resume) {
        carpet.script.external.ScarpetRetiredActors.capture(bot);
        var captured = carpet.script.external.ScarpetRuntime.captureOwnerOperation(() -> {
            try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(bot)) {
                return carpet.script.external.ScarpetNativeWork.<java.util.concurrent.CompletableFuture<Boolean>>observeNative(bot, () -> {
                    var completed = this.carpetPrepareBotRemoval(bot, remover, reason, save, resume);
                    carpet.script.external.ScarpetNativeWork.record(completed);
                    return completed;
                }).thenCompose(next -> next);
            }
        });
        var completed = TickThread.isShutdownThread() ? captured.get()
                : carpet.script.external.ScarpetPlayerInventoryGate.whenIdleForRemoval(bot, captured).thenCompose(next -> next);
        carpet.script.external.ScarpetNativeWork.record(completed);
        return carpet.script.external.ScarpetNativeRemovals.trackCaller(this.server, completed);
    }

    private void carpetPublishJoinMessage(ServerBot bot, boolean silence) {
        BotJoinEvent event = new BotJoinEvent(bot.getBukkitEntity(), silence ? null : PaperAdventure.asAdventure(Component.translatable("multiplayer.player.joined", bot.getDisplayName())).style(Style.style(NamedTextColor.YELLOW)));
        this.server.server.getPluginManager().callEvent(event);
        var message = event.joinMessage();
        if (!silence && message != null && !message.equals(net.kyori.adventure.text.Component.empty()))
            this.server.getPlayerList().broadcastSystemMessage(PaperAdventure.asVanilla(message), false);
    }

    private void carpetPublishLeaveMessage(ServerBot bot, BotRemoveEvent event) {
        boolean silence = fun.bm.lophine.carpet.OrgNativePlayerMessages.consumeLeave(event);
        var message = event.removeMessage();
        if (!silence && message != null && !message.equals(net.kyori.adventure.text.Component.empty()))
            this.server.getPlayerList().broadcastSystemMessage(PaperAdventure.asVanilla(message), false);
    }

    private java.util.concurrent.CompletableFuture<Boolean> carpetPrepareBotRemoval(ServerBot bot, @Nullable CommandSender remover, BotRemoveEvent.RemoveReason reason, boolean save, boolean resume) {
        BotRemoveEvent event = new BotRemoveEvent(bot.getBukkitEntity(), reason, remover, PaperAdventure.asAdventure(Component.translatable("multiplayer.player.left", bot.getDisplayName())).style(Style.style(NamedTextColor.YELLOW)), save);
        fun.bm.lophine.carpet.OrgNativePlayerMessages.prepared(bot, event);
        this.server.server.getPluginManager().callEvent(event);

        if (event.isCancelled() && event.getReason() != BotRemoveEvent.RemoveReason.INTERNAL) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }


        var left = bot.carpetNativePlayer ? carpet.script.external.ScarpetRuntime.onLeaveFuture(bot, Component.literal(reason.name().toLowerCase(java.util.Locale.ROOT))) : java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
        return left.handle((ignored, failure) -> null).thenCompose(ignored -> carpet.script.external.ScarpetNativeRemovals.onOwnerFuture(bot, () -> {
            try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(bot)) {
                boolean result = this.carpetRemoveBotNative(bot, event, resume);
                return carpet.script.external.ScarpetNativeRemovals.completion(bot).thenApply(finished -> result);
            }
        })).thenCompose(next -> next);
    }

    private boolean carpetRemoveBotLegacy(ServerBot bot, @Nullable CommandSender remover, BotRemoveEvent.RemoveReason reason, boolean save, boolean resume) {
        BotRemoveEvent event = new BotRemoveEvent(bot.getBukkitEntity(), reason, remover, PaperAdventure.asAdventure(Component.translatable("multiplayer.player.left", bot.getDisplayName())).style(Style.style(NamedTextColor.YELLOW)), save);
        fun.bm.lophine.carpet.OrgNativePlayerMessages.prepared(bot, event);
        this.server.server.getPluginManager().callEvent(event);

        if (event.isCancelled() && event.getReason() != BotRemoveEvent.RemoveReason.INTERNAL) {
            return false;
        }

        if (bot.removeTaskId != -1) {
            Bukkit.getGlobalRegionScheduler().cancelTask(bot.removeTaskId);
            bot.removeTaskId = -1;
        }

        if (bot.carpetNativePlayer)
            carpet.script.external.ScarpetRuntime.onLeave(bot, Component.literal(reason.name().toLowerCase(java.util.Locale.ROOT)));
        bot.disconnect();

        this.resumeDataStorage.removeSavedData(bot.nameAndId().name());
        if (event.shouldSave()) {
            if (resume) {
                this.resumeDataStorage.save(bot);
            } else {
                this.manualSaveDataStorage.save(bot);
            }
        } else {
            bot.dropExperience(bot.level(), null);
            bot.dropAll(true);
            if (bot.carpetNativePlayer) {
                bot.experienceLevel = 0;
                bot.totalExperience = 0;
                bot.experienceProgress = 0.0F;
            }
            botsNameByWorldUuid.getOrDefault(bot.level().uuid.toString(), new HashSet<>()).remove(bot.getBukkitEntity().getName());
        }

        if (bot.carpetNativePlayer) {
            this.server.getPlayerList().carpetSaveFakePlayer(bot);
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
            if (!thrownEnderpearl.level().paperConfig().misc.legacyEnderPearlBehavior) {
                thrownEnderpearl.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER, EntityRemoveEvent.Cause.PLAYER_QUIT);
            } else {
                thrownEnderpearl.setOwner(null);
            }
        }

        bot.level().removePlayerImmediately(bot, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        if (carpet.script.external.ScarpetNativeRemovals.thenOwner(bot, () -> this.carpetFinishBotRemoval(bot, event)))
            return true;
        return this.carpetFinishBotRemoval(bot, event);
    }

    private boolean carpetRemoveBotNative(ServerBot bot, BotRemoveEvent event, boolean resume) {
        if (bot.removeTaskId != -1) {
            Bukkit.getGlobalRegionScheduler().cancelTask(bot.removeTaskId);
            bot.removeTaskId = -1;
        }

        bot.disconnect();

        this.resumeDataStorage.removeSavedData(bot.nameAndId().name());
        if (event.shouldSave()) {
            if (resume) {
                this.resumeDataStorage.save(bot);
            } else {
                this.manualSaveDataStorage.save(bot);
            }
        } else {
            bot.dropExperience(bot.level(), null);
            bot.dropAll(true);
            if (bot.carpetNativePlayer) {
                bot.experienceLevel = 0;
                bot.totalExperience = 0;
                bot.experienceProgress = 0.0F;
            }
            botsNameByWorldUuid.getOrDefault(bot.level().uuid.toString(), new HashSet<>()).remove(bot.getBukkitEntity().getName());
        }

        if (bot.carpetNativePlayer) {
            this.server.getPlayerList().carpetSaveFakePlayer(bot);
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
            if (!thrownEnderpearl.level().paperConfig().misc.legacyEnderPearlBehavior) {
                thrownEnderpearl.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER, EntityRemoveEvent.Cause.PLAYER_QUIT);
            } else {
                thrownEnderpearl.setOwner(null);
            }
        }

        bot.level().removePlayerImmediately(bot, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        if (carpet.script.external.ScarpetNativeRemovals.thenOwner(bot, () -> this.carpetFinishBotRemoval(bot, event)))
            return true;
        return this.carpetFinishBotRemoval(bot, event);
    }

    private boolean carpetFinishBotRemoval(ServerBot bot, BotRemoveEvent event) {
        bot.level().getCurrentWorldData().connections.remove(bot.connection.connection);
        fun.bm.lophine.carpet.AmsFakePlayers.removed(bot);
        fun.bm.lophine.carpet.OrgPlayerManager.retired(bot);
        fun.bm.lophine.carpet.OrgMailService.retired(bot);
        fun.bm.lophine.carpet.OrgPlayerInventoryMenus.retired(bot);
        fun.bm.lophine.carpet.OrgHiddenPlayerActions.onRetired(bot);
        bot.retireScheduler();

        this.bots.removeIf(value -> value == bot);
        this.carpetRegistrations.remove(bot.getUUID(), bot.getScoreboardName(), bot);

        if (!fun.bm.lophine.carpet.OrgNativePlayerMessages.keepTab(event)) bot.removeTab();
        ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(bot.getId());
        for (ServerPlayer player : bot.level().players()) {
            if (!(player instanceof ServerBot)) {
                player.connection.send(packet);
            }
        }

        this.carpetPublishLeaveMessage(bot, event);
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
        var removals = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Boolean>>();
        for (ServerBot bot : java.util.List.copyOf(this.bots)) {
            if (bot.carpetNativePlayer) {
                if (TickThread.isTickThreadFor(bot) || TickThread.isShutdownThread()) {
                    bot.resume = FakePlayerCompatConfig.fakePlayerResident;
                    var actual = this.carpetRemoveBotAsync(bot, BotRemoveEvent.RemoveReason.INTERNAL, null,
                            FakePlayerCompatConfig.fakePlayerResident, FakePlayerCompatConfig.fakePlayerResident);
                    removals.add(actual);
                    finished &= actual.isDone();
                } else {
                    finished = false;
                    var actual = new java.util.concurrent.CompletableFuture<Boolean>();
                    removals.add(actual);
                    boolean scheduled = bot.getBukkitEntity().taskScheduler.schedule(owner -> {
                        ServerBot owned = (ServerBot) owner;
                        owned.resume = FakePlayerCompatConfig.fakePlayerResident;
                        try {
                            this.carpetRemoveBotAsync(owned, BotRemoveEvent.RemoveReason.INTERNAL, null,
                                            FakePlayerCompatConfig.fakePlayerResident, FakePlayerCompatConfig.fakePlayerResident)
                                    .whenComplete((removed, failure) -> {
                                        if (failure == null) actual.complete(removed);
                                        else actual.completeExceptionally(failure);
                                    });
                        } catch (Throwable failure) {
                            actual.completeExceptionally(failure);
                        }
                    }, retired -> actual.complete(false), 1L);
                    if (!scheduled) actual.complete(false);
                }
                continue;
            }
            if (TickThread.isTickThreadFor(bot.level(), bot.getX(), bot.getZ())) {
                bot.resume = FakePlayerCompatConfig.fakePlayerResident;
                removals.add(java.util.concurrent.CompletableFuture.completedFuture(this.removeBot(bot,
                        BotRemoveEvent.RemoveReason.INTERNAL, null, FakePlayerCompatConfig.fakePlayerResident, FakePlayerCompatConfig.fakePlayerResident)));
            } else {
                finished = false;
                var actual = new java.util.concurrent.CompletableFuture<Boolean>();
                removals.add(actual);
                this.removeBot(bot, actual, new AtomicInteger());
            }
        }
        // Establish the complete cohort before any terminal callback can advance shutdown.
        var completed = java.util.concurrent.CompletableFuture.allOf(removals.toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenRun(() -> {
                    for (var removal : removals) {
                        if (!Boolean.TRUE.equals(removal.join()))
                            throw new IllegalStateException("A player shutdown removal did not complete");
                    }
                });
        if (finished) {
            completed.join();
        } else {
            completed.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    BotList.LOGGER.error("Cannot finish player removals during shutdown", failure);
                    return;
                }
                this.forceShutdown = true;
                MinecraftServer.getServer().stopServer();
            });
        }
        return finished;
    }

    private void removeBot(ServerBot bot, java.util.concurrent.CompletableFuture<Boolean> actual, AtomicInteger counter) {
        boolean scheduled = bot.getBukkitEntity().taskScheduler.schedule((Entity unused) -> {
            if (counter.get() >= 20) {
                BotList.LOGGER.info("Try to remove bot {} located in [{}]{},{},{} too many times!", bot.getName().getString(), bot.level().serverLevelData.getLevelName(), bot.getX(), bot.getY(), bot.getZ());
            }
            counter.getAndIncrement();
            try {
                bot.resume = FakePlayerCompatConfig.fakePlayerResident;
                actual.complete(this.removeBot(bot, BotRemoveEvent.RemoveReason.INTERNAL, null,
                        FakePlayerCompatConfig.fakePlayerResident, FakePlayerCompatConfig.fakePlayerResident));
            } catch (Exception e) {
                this.removeBot(bot, actual, counter);
            }
        }, retired -> actual.complete(false), 1L);
        if (!scheduled) actual.complete(false);
    }

    public void loadResumeBotInfo() {
        if (!FakeplayerConfig.checkEnabled() || !FakePlayerCompatConfig.fakePlayerResident) {
            return;
        }
        CompoundTag savedBotList = this.getResumeBotList().copy();
        for (Map.Entry<String, Tag> entry : savedBotList.entrySet()) {
            String lowerName = entry.getKey();
            String fullName = ((CompoundTag) entry.getValue()).getStringOr("name", lowerName);
            UUID levelUuid = BotUtil.getBotLevel(fullName, this.resumeDataStorage);
            if (levelUuid == null) {
                LOGGER.warn("Bot {} has no world UUID, skipping loading.", fullName);
                continue;
            }
            this.botsNameByWorldUuid
                    .computeIfAbsent(levelUuid.toString(), (k) -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                    .add(fullName);
        }
        loadLegacyResumeBotInfo();
    }

    private void loadLegacyResumeBotInfo() {
        CompoundTag savedBotList = this.getManualSavedBotList().copy();
        for (String fullName : savedBotList.keySet()) {
            // Legacy format saved fullName as the key
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
                    .computeIfAbsent(levelUuid.toString(), (k) -> java.util.concurrent.ConcurrentHashMap.newKeySet())
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
                .computeIfAbsent(newUuid, (k) -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(bot.getBukkitEntity().getName());
        this.botsNameByWorldUuid
                .computeIfAbsent(prevUuid, (k) -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .remove(bot.getBukkitEntity().getName());
    }

    public void networkTick() {
        this.bots.forEach(bot -> {
            if (TickThread.isTickThreadFor(bot)) bot.networkTick();
        });
    }

    @Nullable
    public ServerBot getBot(@NotNull UUID uuid) {
        return this.botsByUUID.get(uuid);
    }

    @Nullable
    public ServerBot getBotByName(@NotNull String name) {
        return this.botsByLowerName.get(name.toLowerCase(Locale.ROOT));
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
