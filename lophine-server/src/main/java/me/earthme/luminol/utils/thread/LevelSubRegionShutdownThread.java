package me.earthme.luminol.utils.thread;

import ca.spottedleaf.concurrentutil.completable.CallbackCompletable;
import ca.spottedleaf.moonrise.common.util.WorldUtil;
import io.papermc.paper.threadedregions.*;
import me.earthme.luminol.utils.TeleportRecord;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class LevelSubRegionShutdownThread extends RegionShutdownThread {
    private final ServerLevel toUnload;
    private final CallbackCompletable<ServerLevel> unloadCallback = new CallbackCompletable<>();

    @NotNull
    private Duration schedulerHaltTimeout = Duration.ZERO;
    private boolean doSaving = true;
    private boolean started = false;

    public LevelSubRegionShutdownThread(String name, ServerLevel toUnload) {
        super(name);
        this.toUnload = toUnload;
    }

    public void applySettings(
            Duration schedulerHaltTimeout,
            boolean doSaving
    ) {
        Objects.requireNonNull(schedulerHaltTimeout);

        RegionizedServer.ensureGlobalTickThread("Apply world shutdown settings off global region!");

        this.schedulerHaltTimeout = schedulerHaltTimeout;
        this.doSaving = doSaving;
    }

    public CallbackCompletable<ServerLevel> getUnloadCallback() {
        return this.unloadCallback;
    }

    public boolean startUnloading() {
        if (this.started) {
            return false;
        }

        this.started = true;
        this.start();
        return true;
    }

    @Override
    public void run() {
        LOGGER.info("Beginning unload task of level {}.", this.toUnload.dimension());

        this.toUnload.levelUnloadStateLock.blockReadingReferencing(); // stop accepting any new reading reference prely

        final boolean shutdownWriteLockHeld = this.toUnload.levelUnloadStateLock.acquireWrite();
        if (!shutdownWriteLockHeld) {
            // already unreachable
            LOGGER.info("Level {} is already unloaded during global shutdown! Quitting.", this.toUnload.dimension());
            return;
        }

        final CompletableFuture<Void> haltCallbackTask = new CompletableFuture<>();

        if (!this.toUnload.levelScheduler.addHaltCallback(() -> haltCallbackTask.complete(null))) {
            haltCallbackTask.complete(null);
        }

        this.toUnload.levelScheduler.halt();

        // wait full scheduler exit
        try {
            if (this.schedulerHaltTimeout != Duration.ZERO) {
                try {
                    haltCallbackTask.get(this.schedulerHaltTimeout.toNanos(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    LOGGER.error("Shutdown thread was interrupted by another threads! An incorrect nms call?", e);
                } catch (TimeoutException e) {
                    LOGGER.warn("Timed out for waiting scheduler exit of level {}! Forcing unloading.", this.toUnload.dimension());
                } catch (ExecutionException e) {
                    LOGGER.error("Exception caught while waiting scheduler exit!", e);
                }
            } else {
                haltCallbackTask.join();
            }
        } catch (Throwable ex) {
            LOGGER.error("Exception caught while await sub scheduler termination of level !", ex);
        }

        LOGGER.info("Halted sub scheduler of level {}.", this.toUnload.dimension());

        try {
            this.handleOnHalted();
        } catch (Throwable ex) {
            LOGGER.error("Exception caught while processing unload logics!", ex);
        } finally {
            this.doRemoveLevel();
            this.finalizeLock();
            this.retireCallback();
        }
    }

    private void doRemoveLevel() {
        LOGGER.info("Removing level...");
        RegionizedServer.getInstance().addTask(() -> {
            MinecraftServer.getServer().removeLevel(this.toUnload);
            RegionizedServer.getInstance().removeWorld(this.toUnload);

            LOGGER.info("Removed level");
        });
    }

    public void waitForComplete() {
        try {
            this.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void retireCallback() {
        this.unloadCallback.complete(this.toUnload);
    }

    private void finalizeLock() {
        LOGGER.info("Leaving finalization safe point area of level {}", this.toUnload.dimension());
        this.toUnload.levelUnloadStateLock.acquireUnreachable();
    }

    private void migrateAllPlayersOnThisRegion(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        ChunkPos center = null;
        try {
            this.shuttingDown = region;
            center = region.getCenterChunk();

            final RegionizedWorldData worldData = region.regioniser.world.worldRegionData.get();

            for (final ServerPlayer player : worldData.getLocalPlayers()) {
                try {
                    final TeleportRecord lastTeleportPos = player.lastTeleportRecord;

                    ServerLevel toMigrateLevel = MinecraftServer.getServer().overworld();

                    if (lastTeleportPos != null) {
                        final ServerLevel lastLevel = lastTeleportPos.lastLevel().get();

                        if (lastLevel != null && lastLevel != this.toUnload) {
                            toMigrateLevel = lastLevel;
                        }
                    }

                    Vec3 destinationPosition = lastTeleportPos == null ? Vec3.ZERO : lastTeleportPos.lastPos();

                    // we can acquire read lock multiple times so we don't care about these re-entering problems
                    final boolean teleported = player.teleportAsync(
                            toMigrateLevel,
                            destinationPosition,
                            lastTeleportPos == null ? 0.0F : lastTeleportPos.yaw(),
                            lastTeleportPos == null ? 0.0F : lastTeleportPos.pitch(),
                            player.getDeltaMovement(),
                            PlayerTeleportEvent.TeleportCause.UNKNOWN,
                            Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS | Entity.TELEPORT_FLAG_LOAD_CHUNK,
                            null
                    );

                    if (!teleported) {
                        LOGGER.error("Failed to migrate player {}! Force kicking.", player.getScoreboardName());

                        // don't care that the player is not removed from the world
                        // as the players would be collected with their unloaded level by GC after disconnection
                        // and the logic of mc will help us correct it to an unloaded level
                        player.connection.connection.disconnect((DisconnectionDetails) null);
                    }
                } catch (final Throwable thr) {
                    LOGGER.error("Failed to migrate out player: " + player, thr);
                }
            }
        } catch (final Throwable thr) {
            LOGGER.error("Failed to migrate player out for region around chunk " + center + " in world '" + region.regioniser.world.getWorld().getName() + "'", thr);
        } finally {
            this.shuttingDown = null;
        }
    }

    private void closeOtherMiscResources() {
        this.toUnload.noSave = false;
        this.toUnload.getChunkSource().getDataStorage().close();

        io.papermc.paper.FeatureHooks.closeEntityManager(this.toUnload, this.doSaving);

        MinecraftServer.getServer().saveGlobalData(true);
    }

    private void handleOnHalted() {
        this.toUnload.moonrise$getChunkTaskScheduler().halt(false, 0L);
        this.haltChunkSystem(this.toUnload);

        LOGGER.info("Halted chunk systems");

        final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions = new ArrayList<>();

        this.toUnload.regioniser.computeForAllRegions(regions::add); // COW

        LOGGER.info("Finishing pending teleports...");
        for (var region : regions) {
            this.finishTeleportations(region, this.toUnload);
        }
        LOGGER.info("Finished pending teleports");

        LOGGER.info("Saving world data for world '" + WorldUtil.getWorldName(this.toUnload) + "'");

        LOGGER.info("Closing player inventories...");
        for (var region : regions) {
            this.closePlayerInventories(region);
        }
        LOGGER.info("Closed player inventories");

        LOGGER.info("Migrating out players");
        for (var region : regions) {
            this.migrateAllPlayersOnThisRegion(region);
        }
        LOGGER.info("Migrated out players");

        if (this.doSaving) {
            LOGGER.info("Saving chunks...");
            for (int i = 0, len = regions.size(); i < len; ++i) {
                this.saveRegionChunks(regions.get(i), (i + 1) == len);
            }
            LOGGER.info("Saved chunks");
        } else {
            LOGGER.info("Do saving is not required for level {}. Skipping saving chunks.", this.toUnload.dimension());
        }

        LOGGER.info("Saving level data...");
        this.saveLevelData(this.toUnload);
        LOGGER.info("Saved level data");

        LOGGER.info("Closing other misc resources...");
        this.closeOtherMiscResources();
        LOGGER.info("Closed other misc resources");
    }
}
