package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Scoped server-side adaptations of Carpet Org Addition gameplay rules.
 */
public final class OrgGameplayHelper {
    private static final ScopedValue<Boolean> TOOL_NO_BREAK = ScopedValue.newInstance();
    private static final ScopedValue<ServerPlayer> BLOCK_BREAKER = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> SHULKER_STACKING = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> ORG_ACTION = ScopedValue.newInstance();

    private OrgGameplayHelper() {
    }

    /**
     * Native rule flags plus the original physical breaker reference; the reference is metadata until its owner runs.
     */
    public record NativeRuleScopes(boolean toolNoBreak, ServerPlayer breaker, boolean shulkerStacking,
                                   boolean channelingTrident, boolean orgAction) {
        public NativeRuleScopes(boolean toolNoBreak, ServerPlayer breaker, boolean shulkerStacking, boolean channelingTrident) {
            this(toolNoBreak, breaker, shulkerStacking, channelingTrident, false);
        }

        public <T> T call(Supplier<T> operation) {
            return ScopedValue.where(TOOL_NO_BREAK, toolNoBreak).where(BLOCK_BREAKER, breaker)
                    .where(SHULKER_STACKING, shulkerStacking).where(GeneralCompatConfig.CHANNELING_TRIDENT, channelingTrident)
                    .where(ORG_ACTION, orgAction).call(operation::get);
        }

        /**
         * An external delayed command keeps rule flags and has no physical routing owner from the callback it outlives.
         */
        public NativeRuleScopes withoutPhysicalRouting() {
            return new NativeRuleScopes(toolNoBreak, null, shulkerStacking, channelingTrident, false);
        }
    }

    public static NativeRuleScopes captureNativeRuleScopes() {
        return new NativeRuleScopes(TOOL_NO_BREAK.orElse(false), (BLOCK_BREAKER.isBound() ? BLOCK_BREAKER.get() : null), SHULKER_STACKING.orElse(true), GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false), ORG_ACTION.orElse(false));
    }

    public static boolean insideOrgAction() {
        return ORG_ACTION.orElse(false);
    }

    public static <T> T withOrgAction(Supplier<T> operation) {
        return ScopedValue.where(ORG_ACTION, true).call(operation::get);
    }

    public static void withOrgAction(Runnable operation) {
        ScopedValue.where(ORG_ACTION, true).run(operation);
    }

    public static <T> T withoutPhysicalRouting(Supplier<T> operation) {
        return captureNativeRuleScopes().withoutPhysicalRouting().call(operation);
    }

    public static boolean isToolNoBreak(ItemStack stack, Player player) {
        return GeneralCompatConfig.noToolBreak && (TOOL_NO_BREAK.orElse(false)
                || fragileMendingItem(stack, player));
    }

    public static boolean suppressToolAttributes(ItemStack stack, Player player) {
        return GeneralCompatConfig.noToolBreak && fragileMendingItem(stack, player);
    }

    private static boolean fragileMendingItem(ItemStack stack, Player player) {
        return !stack.isEmpty() && stack.isDamageableItem()
                && stack.getMaxDamage() - stack.getDamageValue() <= 1
                && (player == null || !player.hasInfiniteMaterials())
                && EnchantmentHelper.has(stack, EnchantmentEffectComponents.REPAIR_WITH_XP);
    }

    public static boolean toolNoBreakActive() {
        return TOOL_NO_BREAK.orElse(false);
    }

    public static <T> T withTool(Player player, ItemStack stack, Supplier<T> action) {
        return ScopedValue.where(TOOL_NO_BREAK, isToolNoBreak(stack, player)).call(action::get);
    }

    public static void withTool(Player player, ItemStack stack, Runnable action) {
        ScopedValue.where(TOOL_NO_BREAK, isToolNoBreak(stack, player)).run(action);
    }

    public static <T> T withBlockBreaker(ServerPlayer player, Supplier<T> action) {
        return ScopedValue.where(BLOCK_BREAKER, player).call(action::get);
    }

    public static ServerPlayer blockBreaker() {
        return (BLOCK_BREAKER.isBound() ? BLOCK_BREAKER.get() : null);
    }

    public static boolean allowShulkerStacking() {
        return SHULKER_STACKING.orElse(true);
    }

    public static <T> T withoutShulkerStacking(Supplier<T> action) {
        return ScopedValue.where(SHULKER_STACKING, false).call(action::get);
    }

    public static void withoutShulkerStacking(Runnable action) {
        ScopedValue.where(SHULKER_STACKING, false).run(action);
    }

    public static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock;
    }

    public static boolean stackableShulker(ItemStack stack) {
        return GeneralCompatConfig.shulkerBoxStackable && isShulkerBox(stack)
                && stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().findAny().isEmpty();
    }

    /**
     * Consumption is deferred until Bukkit's resurrection event succeeds.
     */
    public record InventoryDeathProtection(ItemStack stack, Runnable consume) {
    }

    public static InventoryDeathProtection findInventoryDeathProtection(Player player) {
        String mode = GeneralCompatConfig.betterTotemOfUndying;
        if ("vanilla".equals(mode)) return null;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.has(DataComponents.DEATH_PROTECTION)) {
                return new InventoryDeathProtection(stack, () -> {
                    stack.shrink(1);
                    player.getInventory().setChanged();
                });
            }
        }
        if (!"inventory_with_shulker_box".equals(mode)) return null;
        // Upstream searches all single boxes before stacked boxes.
        for (int pass = 0; pass < 2; pass++) {
            for (ItemStack shulker : player.getInventory().getNonEquipmentItems()) {
                if (shulker.isEmpty() || !isShulkerBox(shulker)
                        || (pass == 0) != (shulker.getCount() == 1)
                        || (pass == 1 && player.getInventory().getFreeSlot() < 0)) continue;
                ItemContainerContents contents = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                ArrayList<ItemStack> items = new ArrayList<>(contents.itemCopies().toList());
                for (ItemStack item : items) {
                    if (item.isEmpty() || !item.has(DataComponents.DEATH_PROTECTION)) continue;
                    return new InventoryDeathProtection(item, () -> {
                        item.shrink(1);
                        ItemStack edited = shulker.getCount() == 1 ? shulker : shulker.split(1);
                        edited.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
                        if (edited != shulker) {
                            player.getInventory().add(edited);
                            if (!edited.isEmpty())
                                player.drop(edited, false, net.minecraft.util.Prediction.SERVER_ONLY);
                        }
                        player.getInventory().setChanged();
                    });
                }
            }
        }
        return null;
    }
}
