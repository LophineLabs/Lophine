// SPDX-License-Identifier: LGPL-3.0-or-later
// Container behavior adapted from Carpet AMS Addition revision 750310179368b2569dd6121a2769b2fb1bbc7343.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;

/**
 * A server container stored in the bundle's standard CONTAINER component.
 */
public final class AmsLargeBundleContainer extends SimpleContainer {
    private final ItemStack bundle;
    private final Player owner;

    public AmsLargeBundleContainer(final ItemStack bundle, final Player owner) {
        super(54, owner.getBukkitEntity());
        this.bundle = bundle;
        this.owner = owner;
        final ItemContainerContents contents = bundle.get(DataComponents.CONTAINER);
        if (contents != null) {
            final List<ItemStack> stored = contents.itemCopies().toList();
            for (int slot = 0; slot < Math.min(stored.size(), this.getContainerSize()); ++slot) {
                this.getContents().set(slot, stored.get(slot));
            }
        }
    }

    public static net.minecraft.world.MenuProvider menuProvider(final ItemStack stack) {
        return new net.minecraft.world.SimpleMenuProvider((id, inventory, owner) -> {
            final AmsLargeBundleContainer contents = new AmsLargeBundleContainer(stack, owner);
            return "9x3".equals(GeneralCompatConfig.largeBundle) ? net.minecraft.world.inventory.ChestMenu.threeRows(id, inventory, contents)
                    : net.minecraft.world.inventory.ChestMenu.sixRows(id, inventory, contents);
        }, stack.getHoverName());
    }

    private record Open(net.minecraft.core.BlockPos position, net.minecraft.world.MenuProvider provider) {
    }

    public static java.util.concurrent.CompletableFuture<Void> open(final net.minecraft.server.level.ServerLevel world,
                                                                    final net.minecraft.server.level.ServerPlayer player, final ItemStack stack) {
        return AmsNativeCommandEffects.nativeReceipt(world.getServer(), () ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(player, () -> new Open(player.blockPosition().immutable(), menuProvider(stack))), read ->
                        AmsNativeCommandEffects.then(AmsNativeCommandEffects.world(world, read.position(), () -> {
                            world.playSound(player, read.position(), net.minecraft.sounds.SoundEvents.BUNDLE_DROP_CONTENTS, net.minecraft.sounds.SoundSource.PLAYERS, 1.5F, 1.35F);
                            return (Void) null;
                        }), ignored -> AmsNativeCommandEffects.owned(player, () -> {
                            player.openMenu(read.provider());
                            return (Void) null;
                        }))));
    }

    @Override
    public void setItem(final int slot, final ItemStack stack) {
        this.getContents().set(slot, stack.copy());
        this.setChanged();
    }

    @Override
    public ItemStack removeItem(final int slot, final int amount) {
        ItemStack removed = net.minecraft.world.ContainerHelper.removeItem(this.getContents(), slot, amount);
        this.setChanged();
        return removed;
    }

    public static boolean canInsert(final ItemStack stack) {
        return !stack.isEmpty() && !(stack.getItem() instanceof BundleItem)
                && !(Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock);
    }

    @Override
    public boolean canPlaceItem(final int slot, final ItemStack stack) {
        return canInsert(stack);
    }

    @Override
    public void setChanged() {
        this.bundle.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(this.getContents()));
        this.owner.getInventory().setChanged();
        super.setChanged();
    }

    @Override
    public ItemStack removeItemNoUpdate(final int slot) {
        final ItemStack removed = super.removeItemNoUpdate(slot);
        this.setChanged();
        return removed;
    }

    @Override
    public boolean stillValid(final Player player) {
        if (player != this.owner || this.bundle.isEmpty() || "false".equals(GeneralCompatConfig.largeBundle)) {
            return false;
        }
        // Close before another packet can mutate a dropped or transferred bundle.
        for (int slot = 0; slot < player.getInventory().getContainerSize(); ++slot) {
            if (player.getInventory().getItem(slot) == this.bundle) {
                return true;
            }
        }
        return false;
    }
}
