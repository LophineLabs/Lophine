package fun.bm.lophine.utils;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;
import fun.bm.lophine.config.modules.experiment.CommandConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.SaveAllCommand;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SaveAllUtil {
    public static final Object lock = new Object();
    public static volatile long lastSaveAllTime = 0;
    private static volatile Pair<CommandSourceStack, Boolean> currentSaveAll = null;
    private static volatile int regionCount = 0;
    private static final AtomicInteger savedRegionCount = new AtomicInteger(0);
    private static final AtomicBoolean withError = new AtomicBoolean(false);

    public static void preSaveAll(CommandSourceStack source, boolean flush) {
        synchronized (lock) {
            if (isSaving()) {
                source.sendFailure(Component.literal(
                        ServerI18nUtil.getLocalizedText("vanilla.command.save-all.refactor.already"))
                );
            } else {
                source.sendSuccess(() -> Component.translatable("commands.save.saving"), false);
                currentSaveAll = Pair.of(source, flush);
                // we need to count how many regions we need to save
                AtomicInteger count = new AtomicInteger();
                for (ServerLevel world : source.getServer().getAllLevels()) {
                    world.regioniser.computeForAllRegions(unused -> count.getAndIncrement());
                }
                regionCount = count.get();
                savedRegionCount.set(0);
                withError.set(false);
                lastSaveAllTime = System.currentTimeMillis();
            }
        }
    }

    public static void postRegionSave(io.papermc.paper.threadedregions.TickRegions.TickRegionData region) {
        Pair<CommandSourceStack, Boolean> currentSaveAll = SaveAllUtil.currentSaveAll;
        synchronized (lock) {
            if (lastSaveAllTime < region.lastSavedTime) return; // already saved
        }
        try {
            region.world.moonrise$getChunkTaskScheduler().chunkHolderManager.saveAllChunksRegionised(currentSaveAll.getSecond(), false, CommandConfig.logAllProcess, false, false, true, false);
            currentSaveAll.getFirst().getServer().getPlayerList().saveAll();
            MinecraftServer.LOGGER.info("Saved chunks in region around chunk {} in world '{}'", region.region.getCenterChunk(), region.region.regioniser.world.getWorld().getName());
        } catch (final Throwable thr) {
            CommandSyntaxException error = SaveAllCommand.getErrorFailed().create();
            currentSaveAll.getFirst().sendFailure(Component.literal(error.getMessage()));
            MinecraftServer.LOGGER.error(error.getMessage(), thr);
            withError.set(true);
        }
        region.lastSavedTime = System.currentTimeMillis();
        int saved;
        synchronized (savedRegionCount) {
            saved = savedRegionCount.incrementAndGet();
        }
        if (saved >= regionCount) {
            if (withError.get()) {
                currentSaveAll.getFirst().sendFailure(
                        Component.literal(ServerI18nUtil.getLocalizedText("vanilla.command.save-all.refactor.failure"))
                );
            } else if (saved == regionCount) {
                currentSaveAll.getFirst().sendSuccess(() -> Component.translatable("commands.save.success"), true);
                SaveAllUtil.currentSaveAll = null;
            }
        }
    }

    public static void checkTimeout() {
        Pair<CommandSourceStack, Boolean> currentSaveAll = SaveAllUtil.currentSaveAll;
        if (!isSaving()) return;
        if (System.currentTimeMillis() - lastSaveAllTime > CommandConfig.saveAllTimeout * 1000) {
            currentSaveAll.getFirst().sendFailure(Component.literal(
                    ServerI18nUtil.getLocalizedText("vanilla.command.save-all.refactor.timeout.line1")
            ));
            currentSaveAll.getFirst().sendFailure(Component.literal(
                    ServerI18nUtil.getFormatedLocalizedText("vanilla.command.save-all.refactor.timeout.line2", String.valueOf(regionCount), String.valueOf(savedRegionCount.get()))
            ));
            SaveAllUtil.currentSaveAll = null;
        }
    }

    public static boolean isSaving() {
        synchronized (lock) {
            return currentSaveAll != null;
        }
    }
}
