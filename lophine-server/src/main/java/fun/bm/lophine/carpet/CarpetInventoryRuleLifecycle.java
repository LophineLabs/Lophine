package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Invalidates inventory caches on owning regions when a rule changes inventory behavior.
 */
public final class CarpetInventoryRuleLifecycle {
    private static List<Object> applied;

    private CarpetInventoryRuleLifecycle() {
    }

    public static void refresh() {
        List<Object> current = List.of(GeneralCompatConfig.largeBarrel, GeneralCompatConfig.shulkerBoxStackable,
                GeneralCompatConfig.stackableShulkerBoxes, GeneralCompatConfig.hopperSuctionDisabled, GeneralCompatConfig.hopperXpCounters);
        if (current.equals(applied)) return;
        List<Object> previous = applied;
        applied = current;
        MinecraftServer server = MinecraftServer.getServer();
        if (previous == null || server == null || !server.isReady()) return;
        AmsNativeCommandEffects.global(server, () -> {
            for (var level : server.getAllLevels()) {
                for (var holder : level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolders()) {
                    TisCommandContinuations.owned(level, new net.minecraft.core.BlockPos(holder.chunkX << 4, 0, holder.chunkZ << 4), () -> {
                        var chunk = level.getChunkSource().getChunkNow(holder.chunkX, holder.chunkZ);
                        if (chunk == null) return (Void) null;
                        for (var blockEntity : new ArrayList<>(chunk.getBlockEntities().values())) {
                            if (blockEntity instanceof HopperBlockEntity hopper && !hopper.isRemoved()) {
                                hopper.carpetInventoryRulesChanged();
                            }
                        }
                        return (Void) null;
                    });
                }
            }
            return (Void) null;
        });
    }
}
