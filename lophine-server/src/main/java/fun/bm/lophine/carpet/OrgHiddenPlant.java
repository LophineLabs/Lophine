// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1 PlantAction.
package fun.bm.lophine.carpet;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class OrgHiddenPlant extends OrgHiddenPlayerActions.Engine {
    private BlockPos crop;
    OrgHiddenPlant(ServerPlayer player) { super(player); }
    String name() { return "plant"; }
    AABB footprint() { return new AABB(player.blockPosition()).inflate(Math.min(player.blockInteractionRange(), 10)); }
    CompletableFuture<Void> tick() {
        excavator.tick();
        var initial = crop == null ? yes() : harvest(crop);
        return initial.thenCompose(done -> ownerFuture(() -> {
            if (!done) return CompletableFuture.completedFuture(null);
            ItemStack held = player.getOffhandItem();
            Kind kind = Kind.of(held);
            if (kind == Kind.NONE) return CompletableFuture.completedFuture(null);
            // WorldTraverser uses truncation toward zero at both bounds and X/Y/Z iteration order.
            AABB box = footprint();
            return next(new Positions((int)box.minX, (int)box.minY, (int)box.minZ,
                (int)box.maxX, (int)box.maxY, (int)box.maxZ), kind, held);
        }));
    }
    private CompletableFuture<Void> next(Iterator<BlockPos> positions, Kind kind, ItemStack held) {
        while (positions.hasNext() && !cancelled) {
            BlockPos block = positions.next();
            if (!player.isWithinBlockInteractionRange(block, 0)) continue;
            CompletableFuture<Boolean> operation = switch (kind) {
                case CROPS -> crops(block, held);
                case WART -> wart(block);
                case MELON -> melon(block, held);
                case BAMBOO -> bamboo(block);
                case NONE -> CompletableFuture.completedFuture(false);
            };
            if (operation.isDone() && !operation.isCompletedExceptionally()) {
                if (!operation.getNow(false)) break;
            } else return operation.thenCompose(proceed -> ownerFuture(() -> proceed ? next(positions, kind, held) : CompletableFuture.completedFuture(null)));
        }
        return CompletableFuture.completedFuture(null);
    }
    private CompletableFuture<Boolean> crops(BlockPos floor, ItemStack held) {
        if (!world.getBlockState(floor).is(BlockTags.SUPPORTS_CROPS)) return yes();
        BlockPos above = floor.above(); BlockState state = world.getBlockState(above);
        CompletableFuture<Void> placed = (player.isCreative() || inventory.replenish(InteractionHand.OFF_HAND, 1)) && state.isAir()
            ? plant(floor, above) : CompletableFuture.completedFuture(null);
        return placed.thenCompose(ignored -> ownerFuture(() -> {
            Block block = state.getBlock();
            if (block instanceof CropBlock cropBlock)
                return cropBlock.isMaxAge(state) && !(cropBlock instanceof TorchflowerCropBlock) ? harvest(above) : fertilize(above);
            if (block instanceof PitcherCropBlock pitcher)
                return pitcher.isValidBonemealTarget(world, above, state, BonemealSource.INTERACTION) ? fertilize(above) : harvest(above);
            return block == Blocks.TORCHFLOWER ? harvest(above) : yes();
        }));
    }
    private CompletableFuture<Boolean> wart(BlockPos floor) {
        if (!world.getBlockState(floor).is(BlockTags.SUPPORTS_NETHER_WART)) return yes();
        BlockPos above = floor.above();
        CompletableFuture<Void> placed = (player.isCreative() || inventory.replenish(InteractionHand.OFF_HAND, 1)) && world.getBlockState(above).isAir()
            ? plant(floor, above) : CompletableFuture.completedFuture(null);
        return placed.thenCompose(ignored -> ownerFuture(() -> {
            var state = world.getBlockState(above);
            return state.is(Blocks.NETHER_WART) && state.getValue(NetherWartBlock.AGE) == 3 ? harvest(above) : yes();
        }));
    }
    private CompletableFuture<Boolean> melon(BlockPos floor, ItemStack seed) {
        if (!world.getBlockState(floor).is(BlockTags.SUPPORTS_STEM_CROPS)) return yes();
        BlockPos stem = floor.above(); var state = world.getBlockState(stem);
        Block melon, growing, attached;
        if (seed.is(Items.MELON_SEEDS)) { melon = Blocks.MELON; growing = Blocks.MELON_STEM; attached = Blocks.ATTACHED_MELON_STEM; }
        else if (seed.is(Items.PUMPKIN_SEEDS)) { melon = Blocks.PUMPKIN; growing = Blocks.PUMPKIN_STEM; attached = Blocks.ATTACHED_PUMPKIN_STEM; }
        else return yes();
        if (state.is(attached)) {
            BlockPos fruit = stem.relative(state.getValue(AttachedStemBlock.FACING));
            if (world.getBlockState(fruit).is(melon)) { inventory.switchToAppropriateTool(world, fruit); return harvest(fruit); }
        } else if (state.is(growing) && state.getValue(StemBlock.AGE) < StemBlock.MAX_AGE) return fertilize(stem);
        return yes();
    }
    private CompletableFuture<Boolean> bamboo(BlockPos floor) {
        var support = world.getBlockState(floor);
        if (!support.is(BlockTags.SUPPORTS_BAMBOO) || support.is(Blocks.BAMBOO) || support.is(Blocks.BAMBOO_SAPLING)) return yes();
        BlockPos root = floor.above(); var state = world.getBlockState(root);
        if (state.isAir()) return yes(); // The source harvests existing bamboo, without planting new bamboo.
        if (state.getBlock() instanceof BambooSaplingBlock && world.getBlockState(root.above()).isAir()) return fertilize(root);
        if (!(state.getBlock() instanceof BambooStalkBlock stalk)) return yes();
        if (!stalk.isValidBonemealTarget(world, root, state, BonemealSource.INTERACTION)) {
            inventory.switchToAppropriateTool(world, root.above()); return harvest(root.above());
        }
        int air = 0; boolean reachedAir = false;
        for (int height = 2; height <= 16; height++) {
            var upper = world.getBlockState(floor.above(height));
            if (upper.isAir()) { reachedAir = true; air++; }
            else if (!upper.is(Blocks.BAMBOO)) break;
            if (reachedAir) {
                if (air >= 3) return fertilize(root);
                if (upper.is(Blocks.BAMBOO)) break;
            }
            if (height == 16) return fertilize(root);
        }
        return yes();
    }
    private CompletableFuture<Void> plant(BlockPos floor, BlockPos above) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(above));
        return click(InteractionHand.OFF_HAND, new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, above, false))
            .thenCompose(result -> owner(() -> { OrgHiddenNative.swing(player, InteractionHand.OFF_HAND); return null; }));
    }
    private CompletableFuture<Boolean> fertilize(BlockPos target) {
        if (!inventory.replenish(stack -> stack.is(Items.BONE_MEAL)) || !player.isCreative() && !inventory.replenish(1)) return yes();
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(target));
        return click(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(target), Direction.DOWN, target, true))
            .thenCompose(result -> owner(() -> { OrgHiddenNative.swing(player, InteractionHand.MAIN_HAND); return true; }));
    }
    private CompletableFuture<Boolean> harvest(BlockPos target) {
        return excavator.mine(target, Direction.DOWN, !player.isCreative()).thenCompose(done -> owner(() -> {
            crop = done ? null : target.immutable(); return done;
        }));
    }
    private static CompletableFuture<Boolean> yes() { return CompletableFuture.completedFuture(true); }
    private enum Kind {
        CROPS, WART, MELON, BAMBOO, NONE;
        static Kind of(ItemStack stack) {
            if (stack.is(Items.WHEAT_SEEDS) || stack.is(Items.POTATO) || stack.is(Items.CARROT) || stack.is(Items.BEETROOT_SEEDS)
                || stack.is(Items.TORCHFLOWER_SEEDS) || stack.is(Items.PITCHER_POD)) return CROPS;
            if (stack.is(Items.NETHER_WART)) return WART;
            if (stack.is(Items.MELON_SEEDS) || stack.is(Items.PUMPKIN_SEEDS)) return MELON;
            return stack.is(Items.BAMBOO) ? BAMBOO : NONE;
        }
    }
    static final class Positions implements Iterator<BlockPos> {
        final int minX, minY, maxX, maxY, maxZ; int x, y, z;
        Positions(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = x = Math.max(minX, -net.minecraft.world.level.Level.MAX_LEVEL_SIZE);
            this.minY = y = Math.max(minY, net.minecraft.world.level.Level.MIN_ENTITY_SPAWN_Y);
            z = Math.max(minZ, -net.minecraft.world.level.Level.MAX_LEVEL_SIZE);
            this.maxX = Math.min(maxX, net.minecraft.world.level.Level.MAX_LEVEL_SIZE);
            this.maxY = Math.min(maxY, net.minecraft.world.level.Level.MAX_ENTITY_SPAWN_Y);
            this.maxZ = Math.min(maxZ, net.minecraft.world.level.Level.MAX_LEVEL_SIZE);
        }
        public boolean hasNext() { return minX <= maxX && minY <= maxY && z <= maxZ; }
        public BlockPos next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            var block = new BlockPos(x++, y, z);
            if (x > maxX) { x = minX; if (++y > maxY) { y = minY; z++; } }
            return block;
        }
    }
}
