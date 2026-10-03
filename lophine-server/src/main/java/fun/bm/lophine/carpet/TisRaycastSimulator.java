/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Adapted from Carpet TIS Addition, Fallen_Breath and contributors.
 * Upstream revision: 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
 */
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class TisRaycastSimulator {
    private TisRaycastSimulator() {
    }

    public static void simulate(final CommandSourceStack source, final BlockPos start, final int maximumRadius) {
        ServerLevel level = source.getLevel();
        Thread.startVirtualThread(() -> {
            try {
                BlockPos center = start;
                for (int radius = 1; radius <= maximumRadius; ++radius) {
                    dispatch(level, planLayer(center, radius)).join();
                    if (radius == maximumRadius) break;
                    center = center.offset(0, 0, radius * 2 + 4);
                }
                Map<ChunkPos, List<Placement>> floor = new LinkedHashMap<>();
                for (BlockPos pos : BlockPos.betweenClosed(start.offset(-maximumRadius - 1, -1, -2), center.offset(maximumRadius + 1, -1, maximumRadius + 1))) {
                    add(floor, pos, Blocks.CONCRETE.white().defaultBlockState(), true);
                }
                dispatch(level, floor).join();
                TisRaycastCommand.feedback(source, "Endermelon raycast simulation created.");
            } catch (Throwable failure) {
                TisRaycastCommand.feedback(source, "Endermelon simulation failed: " + failure.getMessage());
            }
        });
    }

    private static Map<ChunkPos, List<Placement>> planLayer(final BlockPos center, final int radius) {
        Map<ChunkPos, List<Placement>> changes = new LinkedHashMap<>();
        add(changes, center, Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR), false);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -1, -radius), center.offset(radius, -1, radius))) {
            add(changes, pos, Blocks.SMOOTH_STONE.defaultBlockState(), false);
        }
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, 0, -radius), center.offset(radius, 0, radius))) {
            add(changes, pos, Blocks.AIR.defaultBlockState(), false);
        }
        List<BlockPos> melons = List.of(center.offset(-radius, 0, -radius), center.offset(-radius, 0, radius),
                center.offset(radius, 0, -radius), center.offset(radius, 0, radius));
        Set<BlockPos> solids = new HashSet<>(melons);
        for (BlockPos melon : melons) add(changes, melon, Blocks.MELON.defaultBlockState(), false);
        // The upstream clears this complete layer to air before placing four full-cube melons.
        // Therefore its outline ray tests depend only on the melons and candidate granite cubes,
        // which can be evaluated without accessing another region's live block storage.
        for (BlockPos mutable : BlockPos.betweenClosed(center.offset(-radius, 0, -radius), center.offset(radius, 0, radius))) {
            BlockPos pos = mutable.immutable();
            if (solids.contains(pos)) continue;
            solids.add(pos);
            add(changes, pos, Blocks.POLISHED_GRANITE.defaultBlockState(), false);
            boolean allVisible = true;
            for (BlockPos melon : melons) {
                if (!canTraceMelon(center, melon, solids, melons)) {
                    allVisible = false;
                    break;
                }
            }
            if (!allVisible) {
                solids.remove(pos);
                add(changes, pos, Blocks.AIR.defaultBlockState(), false);
            }
        }
        return changes;
    }

    private static boolean canTraceMelon(final BlockPos center, final BlockPos melon, final Set<BlockPos> solids, final List<BlockPos> melons) {
        Vec3 from = Vec3.atCenterOf(center), to = Vec3.atCenterOf(melon);
        BlockHitResult hit = BlockGetter.traverseBlocks(from, to, solids,
                (set, pos) -> set.contains(pos) ? Shapes.block().clip(from, to, pos) : null,
                set -> {
                    Vec3 delta = from.subtract(to);
                    return BlockHitResult.miss(to, Direction.getApproximateNearest(delta.x(), delta.y(), delta.z()), melon);
                });
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && melons.contains(hit.getBlockPos());
    }

    private static void add(final Map<ChunkPos, List<Placement>> changes, final BlockPos pos, final BlockState state, final boolean onlyAir) {
        BlockPos immutable = pos.immutable();
        changes.computeIfAbsent(ChunkPos.containing(immutable), ignored -> new ArrayList<>()).add(new Placement(immutable, state, onlyAir));
    }

    private static CompletableFuture<Void> dispatch(final ServerLevel level, final Map<ChunkPos, List<Placement>> changes) {
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        for (var entry : changes.entrySet()) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            completions.add(completion);
            RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(level, entry.getKey().x(), entry.getKey().z(), () -> {
                try {
                    Runnable place = () -> {
                        for (Placement update : entry.getValue()) {
                            if (!update.onlyAir() || level.getBlockState(update.pos()).isAir()) {
                                level.setBlockAndUpdate(update.pos(), update.state());
                            }
                        }
                    };
                    if (GeneralCompatConfig.fillUpdates) place.run();
                    else InteractionUpdateHelper.runWithSuppressedUpdates(place);
                    completion.complete(null);
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            });
        }
        return CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
    }

    private record Placement(BlockPos pos, BlockState state, boolean onlyAir) {
    }
}
