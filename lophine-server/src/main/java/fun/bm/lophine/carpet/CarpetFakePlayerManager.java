/*
 * This file is part of Lophine.
 * Portions derived from Leaves (https://github.com/LeavesMC/Leaves).
 * This file is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This file is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this file. If not, see <https://www.gnu.org/licenses/>.
 */

package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import fun.bm.lophine.config.modules.function.OldFeatureConfig;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.profile.MutablePropertyMap;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.storage.ValueInput;
import org.bukkit.command.CommandSender;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CarpetFakePlayerManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final MinecraftServer server;
    public final List<CarpetFakePlayer> players = new CopyOnWriteArrayList<>();
    private final CarpetBotRegistrations<CarpetFakePlayer> carpetRegistrations = new CarpetBotRegistrations<>();
    private final Map<UUID, CarpetFakePlayer> botsByUUID = carpetRegistrations.byUuid();
    private final Map<String, CarpetFakePlayer> botsByLowerName = carpetRegistrations.byName();
    public volatile boolean forceShutdown;
    private volatile java.util.concurrent.CompletableFuture<Void> shutdown;
    private final Map<UUID, java.util.concurrent.CompletableFuture<Boolean>> removing = new java.util.concurrent.ConcurrentHashMap<>();

    public CarpetFakePlayerManager(MinecraftServer server) { this.server = server; }
    public enum RemoveReason { COMMAND, DEATH, INTERNAL, KICK }
    public record Removal(CarpetFakePlayer player, RemoveReason reason, boolean save, net.kyori.adventure.text.Component message) {
        public boolean shouldSave() { return save; }
        public net.kyori.adventure.text.Component removeMessage() { return message; }
    }
    public static GameProfile createProfile(UUID uuid, String name, String[] skin) {
        GameProfile profile = new GameProfile(uuid, name, new MutablePropertyMap());
        if (skin != null) profile.properties().put("textures", new Property("textures", skin[0], skin[1]));
        return profile;
    }
    public java.util.Optional<CompoundTag> saveState(CarpetFakePlayer bot) {
        TickThread.ensureTickThread(bot, "Saving Carpet player off its owning region");
        server.getPlayerList().carpetSaveFakePlayer(bot);
        return server.getPlayerList().loadPlayerData(bot.nameAndId());
    }
    public java.nio.file.Path statePath(CarpetFakePlayer bot) {
        return server.getPlayerList().playerIo.getPlayerDir().toPath().resolve(bot.getStringUUID() + ".dat");
    }

    public java.util.concurrent.CompletableFuture<CarpetFakePlayer> placementCompletion(CarpetFakePlayer bot) {
        var actual = bot.carpetActualPlacementFuture;
        var view = actual.copy();
        carpet.script.external.ScarpetNativeWork.aliasDependency(view, actual);
        return view;
    }

    public CarpetFakePlayer place(@NotNull CarpetFakePlayer bot, ServerLevel world, net.minecraft.world.phys.Vec3 location, net.minecraft.world.phys.Vec2 rotation, ValueInput save) {
        if (this.shutdown != null || this.forceShutdown) throw new IllegalStateException("Carpet players are stopping");
        UUID identity = bot.getUUID();
        String name = bot.getScoreboardName();
        try (var reservation = this.carpetRegistrations.reserve(identity, name)) {
            if (this.server.getPlayerList().getPlayer(identity) != null || this.server.getPlayerList().getPlayerByName(name) != null)
                throw new IllegalStateException("Player identity is already logged in");
            return carpetPlaceNewBot(bot, world, location, rotation, save, reservation);
        } catch (Throwable failure) {
            carpetRegistrations.remove(identity, name, bot);
            this.players.removeIf(value -> value == bot);
            bot.carpetActualPlacementFuture.completeExceptionally(failure);
            bot.carpetPlacementFuture.completeExceptionally(failure);
            throw failure;
        }
    }

    private CarpetFakePlayer carpetPlaceNewBot(@NotNull CarpetFakePlayer bot, ServerLevel world, net.minecraft.world.phys.Vec3 location, net.minecraft.world.phys.Vec2 rotation, ValueInput save,
                                        CarpetBotRegistrations<CarpetFakePlayer>.Reservation reservation) {
        Optional<ValueInput> optional = Optional.ofNullable(save);

        bot.isRealPlayer = true;
        bot.loginTime = System.currentTimeMillis();
        bot.connection = new CarpetFakePlayerPacketListener(this.server, bot);
        if (bot.connection.connection.getPlayer() != bot) {
            throw new IllegalStateException("Bot connection is not bound to its bot player");
        }
        bot.connection.markClientLoaded();
        // Preserve the established NPC metadata contract with an independent Carpet owner.
        bot.getBukkitEntity().setMetadata("NPC", new org.bukkit.metadata.FixedMetadataValue(CarpetInternalPlugin.INSTANCE, true));
        bot.setServerLevel(world);

        net.minecraft.world.phys.Vec3 spawnLocation = location;
        ServerLevel targetWorld = world;

        fun.bm.lophine.carpet.CarpetPlayerBirths.admitPlayer(bot, bot.carpetActualPlacementFuture);
        synchronized (this.server.getPlayerList()) {
            if (this.server.getPlayerList().getPlayer(bot.getUUID()) != null || this.server.getPlayerList().getPlayerByName(bot.getScoreboardName()) != null
                    || this.server.getPlayerList().hasPendingConnection(bot.getUUID(), bot.getScoreboardName()))
                throw new IllegalStateException("Player identity is already logged in");
            reservation.publish(bot);
            this.players.add(bot);
        }

        bot.suppressTrackerForLogin = true;

        var carpetPlacement = bot.carpetActualPlacementFuture;
        int carpetSpawnX = net.minecraft.util.Mth.floor(spawnLocation.x), carpetSpawnZ = net.minecraft.util.Mth.floor(spawnLocation.z);
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
                                            bot.setPosRaw(spawnLocation.x, spawnLocation.y, spawnLocation.z);
                                            bot.setRot(rotation.y, rotation.x);
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
                                                bot.initInventoryMenu();
                                                this.server.getCustomBossEvents().onPlayerConnect(bot);
                                                this.carpetPublishJoinMessage(bot, orgSilence);

                                                bot.renderInfo();
                                                bot.suppressTrackerForLogin = false;

                                                bot.level().getChunkSource().chunkMap.addEntity(bot);
                                                fun.bm.lophine.carpet.AmsFakePlayers.added(bot);
                                                if (!orgSilence)
                                                    LOGGER.info("{}[Local] logged in with entity id {} at ([{}]{}, {}, {})", bot.getName().getString(), bot.getId(), targetWorld.getWorld().getName(), location.x, location.y, location.z);
                                                bot.carpetPlacementInitializer.run();
                                                bot.carpetPlacementInitializer = () -> {
                                                };
                                                bot.carpetPlacementReady = true;
                                                carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(bot,
                                                        carpet.script.external.ScarpetNativeWork.completionOf(carpet.script.external.ScarpetNativeWork.capture()));
                                                try (var joined = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(bot)) {
                                                    carpet.script.external.ScarpetNativeWork.record(fun.bm.lophine.carpet.AmsPlayerJoin.join(bot, true));
                                                    if (!bot.carpetShadow)
                                                        fun.bm.lophine.carpet.CarpetFakePlayerPersistence.recordOnline(bot);
                                                }

                                            })));
                                    var finished = joinedPhase.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored, failure) -> {
                                        if (failure != null && !carpet.script.external.ScarpetNativeWork.onlyGuestFailure(failure)) {
                                            var cleanup = fun.bm.lophine.carpet.CarpetPlayerBirthPhase.run(bot, () -> {
                                                bot.carpetPlacementInitializer = () -> {
                                                };
                                                bot.disconnect();
                                                var currentWorld = bot.level();
                                                currentWorld.removePlayerImmediately(bot, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
                                                Runnable retired = () -> {
                                                    currentWorld.getCurrentWorldData().connections.remove(bot.connection.connection);
                                                    this.carpetClearPlayerServices(bot);
                                                    bot.retireScheduler();
                                                    this.carpetRegistrations.remove(bot.getUUID(), bot.getScoreboardName(), bot);
                                                    this.players.removeIf(value -> value == bot);
                                                    bot.removeTab();
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
        if (TickThread.isTickThreadFor(targetWorld, carpetSpawnX >> 4, carpetSpawnZ >> 4)) {
            task.run();
        } else {
            RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                    targetWorld, carpetSpawnX >> 4, carpetSpawnZ >> 4,
                    task);
        }

        return bot;
    }

    private static java.util.concurrent.CompletableFuture<Void> carpetBirthAfterGuest(java.util.concurrent.CompletableFuture<Void> actual) {
        return actual.handle((ignored, failure) -> {
            if (failure != null && !carpet.script.external.ScarpetNativeWork.onlyGuestFailure(failure))
                throw new java.util.concurrent.CompletionException(failure);
            return null;
        });
    }

    public java.util.concurrent.CompletableFuture<Boolean> removeAsync(CarpetFakePlayer bot, RemoveReason reason, @Nullable CommandSender remover, boolean save, boolean resume) {
        return removeAsync(bot, reason, remover, save, resume, PaperAdventure.asAdventure(Component.translatable("multiplayer.player.left", bot.getDisplayName())).color(NamedTextColor.YELLOW));
    }

    public java.util.concurrent.CompletableFuture<Boolean> removeAsync(CarpetFakePlayer bot, RemoveReason reason, @Nullable CommandSender remover, boolean save, boolean resume, net.kyori.adventure.text.@Nullable Component message) {
        carpet.script.external.ScarpetRetiredActors.capture(bot);
        var captured = carpet.script.external.ScarpetRuntime.captureOwnerOperation(() -> {
            try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(bot)) {
                return carpet.script.external.ScarpetNativeWork.<java.util.concurrent.CompletableFuture<Boolean>>observeNative(bot, () -> {
                    var completed = this.carpetPrepareBotRemoval(bot, remover, reason, save, resume, message);
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

    private boolean carpetRemoveBotNative(CarpetFakePlayer bot, Removal event, boolean resume) {
        bot.disconnect();

        if (!bot.containerMenu.getCarried().isEmpty()) {
            var carried = bot.containerMenu.getCarried();
            bot.containerMenu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);
            bot.drop(carried, false, net.minecraft.util.Prediction.PREDICTED);
        }

        if (!event.shouldSave()) {
            bot.dropExperience(bot.level(), null);
            bot.dropAll(true);
            bot.experienceLevel = 0;
            bot.totalExperience = 0;
            bot.experienceProgress = 0.0F;

        }

        this.server.getPlayerList().carpetSaveFakePlayer(bot);
        if (resume && !bot.carpetShadow) fun.bm.lophine.carpet.CarpetFakePlayerPersistence.recordOnline(bot);
        else fun.bm.lophine.carpet.CarpetFakePlayerPersistence.forget(bot);

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

    private boolean carpetFinishBotRemoval(CarpetFakePlayer bot, Removal event) {
        bot.level().getCurrentWorldData().connections.remove(bot.connection.connection);
        fun.bm.lophine.carpet.AmsFakePlayers.removed(bot);
        fun.bm.lophine.carpet.OrgPlayerManager.retired(bot);
        fun.bm.lophine.carpet.OrgMailService.retired(bot);
        fun.bm.lophine.carpet.OrgPlayerInventoryMenus.retired(bot);
        fun.bm.lophine.carpet.OrgHiddenPlayerActions.onRetired(bot);
        this.carpetClearPlayerServices(bot);
        bot.retireScheduler();

        this.players.removeIf(value -> value == bot);
        this.carpetRegistrations.remove(bot.getUUID(), bot.getScoreboardName(), bot);

        if (!fun.bm.lophine.carpet.OrgNativePlayerMessages.keepTab(event)) bot.removeTab();
        ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(bot.getId());
        for (ServerPlayer player : bot.level().players()) {
            if (!(player instanceof CarpetFakePlayer)) {
                player.connection.send(packet);
            }
        }

        this.carpetPublishLeaveMessage(bot, event);
        return true;
    }

    private void carpetClearPlayerServices(CarpetFakePlayer bot) {
        OrgNavigation.disconnected(bot);
        bot.getAdvancements().clearTriggers();
        bot.getBukkitEntity().packetProcessor.close();
        this.server.getCustomBossEvents().onPlayerDisconnect(bot);
        this.server.server.getScoreboardManager().removePlayer(bot.getBukkitEntity());
    }

    public CarpetFakePlayer getBot(@NotNull UUID uuid) {
        return this.botsByUUID.get(uuid);
    }

    public CarpetFakePlayer getBotByName(@NotNull String name) {
        return this.botsByLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    public void sendPlayerInfo(ServerPlayer viewer) {
        for (CarpetFakePlayer player : this.players) {
            if (player.carpetPlacementReady) player.sendPlayerInfo(viewer);
        }
    }

    private void carpetPublishJoinMessage(CarpetFakePlayer bot, boolean silence) {
        var message = silence ? null : PaperAdventure.asAdventure(Component.translatable("multiplayer.player.joined", bot.getDisplayName())).color(NamedTextColor.YELLOW);
        var event = new org.bukkit.event.player.PlayerJoinEvent(bot.getBukkitEntity(), message);
        this.server.server.getPluginManager().callEvent(event);
        if (!silence && event.joinMessage() != null) this.server.getPlayerList().broadcastSystemMessage(PaperAdventure.asVanilla(event.joinMessage()), false);
    }
    private void carpetPublishLeaveMessage(CarpetFakePlayer bot, Removal event) {
        boolean silence = OrgNativePlayerMessages.consumeLeave(event);
        if (!silence && event.removeMessage() != null) this.server.getPlayerList().broadcastSystemMessage(PaperAdventure.asVanilla(event.removeMessage()), false);
    }
    private java.util.concurrent.CompletableFuture<Boolean> carpetPrepareBotRemoval(CarpetFakePlayer bot, CommandSender remover, RemoveReason reason, boolean save, boolean resume, net.kyori.adventure.text.@Nullable Component message) {
        var actual = new java.util.concurrent.CompletableFuture<Boolean>();
        var existing = removing.putIfAbsent(bot.getUUID(), actual);
        if (existing != null) {
            var view = existing.copy();
            carpet.script.external.ScarpetNativeWork.aliasDependency(view, existing);
            return view;
        }
        try {
            if (!this.players.contains(bot)) {
                removing.remove(bot.getUUID(), actual);
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            if (bot.containerMenu != bot.inventoryMenu) bot.closeContainer(org.bukkit.event.inventory.InventoryCloseEvent.Reason.DISCONNECT);
            var quit = new org.bukkit.event.player.PlayerQuitEvent(bot.getBukkitEntity(), message, bot.quitReason);
            this.server.server.getPluginManager().callEvent(quit);
            var event = new Removal(bot, reason, save, quit.quitMessage());
            OrgNativePlayerMessages.prepared(bot, event);
            var left = carpet.script.external.ScarpetRuntime.onLeaveFuture(bot, Component.literal(reason.name().toLowerCase(Locale.ROOT)));
            var removal = left.handle((ignored, failure) -> null).thenCompose(ignored -> carpet.script.external.ScarpetNativeRemovals.onOwnerFuture(bot, () -> {
                try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(bot)) {
                    bot.resume = resume;
                    boolean result = this.carpetRemoveBotNative(bot, event, resume);
                    return carpet.script.external.ScarpetNativeRemovals.completion(bot).thenApply(finished -> result);
                }
            })).thenCompose(next -> next);
            carpet.script.external.ScarpetNativeWork.aliasDependency(actual, removal);
            removal.whenComplete((removed, failure) -> {
                removing.remove(bot.getUUID(), actual);
                if (failure == null) actual.complete(removed); else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) {
            removing.remove(bot.getUUID(), actual);
            actual.completeExceptionally(failure);
        }
        var view = actual.copy();
        carpet.script.external.ScarpetNativeWork.aliasDependency(view, actual);
        return view;
    }
    public synchronized boolean removeAll() {
        if (shutdown != null) return forceShutdown;
        var removals = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Boolean>>();
        for (CarpetFakePlayer bot : List.copyOf(players)) {
            var actual = new java.util.concurrent.CompletableFuture<Boolean>();
            removals.add(actual);
            Runnable task = () -> {
                try {
                    removeAsync(bot, RemoveReason.INTERNAL, null, true, true).whenComplete((removed, failure) -> {
                        if (failure == null) actual.complete(removed); else actual.completeExceptionally(failure);
                    });
                } catch (Throwable failure) { actual.completeExceptionally(failure); }
            };
            if (TickThread.isTickThreadFor(bot) || TickThread.isShutdownThread()) task.run();
            else if (!bot.getBukkitEntity().getScheduler().execute(CarpetInternalPlugin.INSTANCE, task, () -> actual.complete(false), 1L)) actual.complete(false);
        }
        shutdown = java.util.concurrent.CompletableFuture.allOf(removals.toArray(java.util.concurrent.CompletableFuture[]::new)).thenRun(() -> {
            for (var removal : removals) if (!Boolean.TRUE.equals(removal.join())) throw new IllegalStateException("Carpet player removal failed during shutdown");
        });
        if (shutdown.isDone()) { shutdown.join(); forceShutdown = true; return true; }
        shutdown.whenComplete((ignored, failure) -> {
            if (failure != null) LOGGER.error("Cannot finish Carpet player shutdown", failure);
            else { forceShutdown = true; server.stopServer(); }
        });
        return false;
    }
}
