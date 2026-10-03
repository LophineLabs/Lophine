// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.carpet.config.modules.WoolHopperCounterConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.util.HopperCounter;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public final class CarpetWoolTool {
    private static final Map<Block, DyeColor> COLORS = Arrays.stream(DyeColor.values()).collect(Collectors.toUnmodifiableMap(Blocks.WOOL::pick, color -> color));

    private CarpetWoolTool() {
    }

    public static void placed(DyeColor color, ServerPlayer player, BlockPos pos, ServerLevel level) {
        placedAsync(color, player, pos, level);
    }

    public static java.util.concurrent.CompletableFuture<Void> placedAsync(DyeColor color, ServerPlayer player, BlockPos pos, ServerLevel level) {
        if (!GeneralCompatConfig.carpets) return done();
        BlockPos original = pos.immutable();
        return OrgMenuNativeEffects.admit(level.getServer(), () -> switch (color) {
            case PINK -> "false".equals(GeneralCompatConfig.commandSpawn) ? done()
                    : TisCommandContinuations.then(CarpetSpawnCommand.reportAsync(level, original), report -> CarpetMessenger.sendAsync(player, report));
            case GRAY -> "false".equals(GeneralCompatConfig.commandInfo) ? done()
                    : TisCommandContinuations.then(CarpetInfoCommand.reportAsync(level, original.below()), report -> CarpetMessenger.sendAsync(player, report));
            case BROWN ->
                    "false".equals(GeneralCompatConfig.commandDistance) ? done() : TisCommandContinuations.owned(player, () -> {
                        var source = player.createCommandSourceStack();
                        if (player.isShiftKeyDown() || !CarpetDistanceCalculator.hasStartingPoint(source))
                            CarpetDistanceCalculator.setStart(source, Vec3.atLowerCornerOf(original));
                        else CarpetDistanceCalculator.setEnd(source, Vec3.atLowerCornerOf(original));
                        return null;
                    });
            case BLACK -> "false".equals(GeneralCompatConfig.commandSpawn) ? done()
                    : TisCommandContinuations.then(TisCommandContinuations.owned(level, original.below(), () -> COLORS.get(level.getBlockState(original.below()).getBlock())), under -> {
                MobCategory category = under == null ? null : switch (under) {
                    case RED -> MobCategory.MONSTER;
                    case GREEN -> MobCategory.CREATURE;
                    case BLUE -> MobCategory.WATER_CREATURE;
                    case BROWN -> MobCategory.AMBIENT;
                    case CYAN -> MobCategory.WATER_AMBIENT;
                    default -> null;
                };
                return TisCommandContinuations.then(OrgCommandNativeEffects.global(level.getServer(), () -> {
                    if (category == null)
                        return CarpetSpawnReporter.tracking() ? CarpetSpawnReporter.report() : CarpetSpawnCommand.mobcaps(level);
                    return CarpetSpawnReporter.tracking() ? CarpetSpawnReporter.recent(level, category) : null;
                }), report -> report == null
                        ? TisCommandContinuations.then(CarpetSpawnCommand.entityReportAsync(level, category, true), rows -> CarpetMessenger.sendAsync(player, rows))
                        : CarpetMessenger.sendAsync(player, report));
            });
            case GREEN, RED -> !WoolHopperCounterConfig.hopperCounters ? done()
                    : TisCommandContinuations.then(TisCommandContinuations.owned(level, original.below(), () -> COLORS.get(level.getBlockState(original.below()).getBlock())), under -> {
                if (under == null) return done();
                var counter = HopperCounter.getCounter(under);
                return TisCommandContinuations.then(OrgCommandNativeEffects.global(level.getServer(), () -> {
                    if (color == DyeColor.GREEN)
                        return counter.format(level.getServer(), false).stream().map(io.papermc.paper.adventure.PaperAdventure::asVanilla).toList();
                    counter.reset(level.getServer());
                    return java.util.List.of(CarpetMessenger.c("w " + under.getName() + " counter reset"));
                }), report -> CarpetMessenger.sendAsync(player, report));
            });
            default -> done();
        });
    }

    private static java.util.concurrent.CompletableFuture<Void> done() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
