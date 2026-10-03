// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import org.leavesmc.leaves.bot.ServerBot;

/** Recipe-setting screens use real viewer items, return them natively, then send an immutable recipe to the fake owner. */
public final class OrgFakePlayerRecipeMenus {
    private OrgFakePlayerRecipeMenus() {}
    public static boolean interact(ServerPlayer viewer, Entity target, InteractionHand hand) {
        return interactAsync(viewer,target,hand)!=null;
    }
    public static java.util.concurrent.@org.jspecify.annotations.Nullable CompletableFuture<Boolean> interactAsync(ServerPlayer viewer,Entity target,InteractionHand hand){
        if (!(target instanceof ServerBot) || viewer.isSpectator() || !TickThread.isTickThreadFor(viewer)) return null;
        String mode = GeneralCompatConfig.quickSettingFakePlayerCraft;
        if (mode.equals("false") || mode.equals("sneaking") && !viewer.isShiftKeyDown()
            || !OrgUtilityCommands.permitted(viewer.createCommandSourceStack(), GeneralCompatConfig.commandPlayerAction)) return null;
        var held = viewer.getItemInHand(hand);
        if (!held.is(Items.CRAFTING_TABLE) && !held.is(Items.STONECUTTER)) return null;
        return openAsync(viewer,target.getUUID(),held.is(Items.STONECUTTER));
    }
    public static void open(ServerPlayer viewer, UUID target, boolean stone) {
        openAsync(viewer,target,stone);
    }
    public static java.util.concurrent.CompletableFuture<Boolean> openAsync(ServerPlayer viewer,UUID target,boolean stone){
        return OrgMenuNativeEffects.run(viewer,()->{
            if(!OrgUtilityCommands.permitted(viewer.createCommandSourceStack(),GeneralCompatConfig.commandPlayerAction))return false;
            ContainerLevelAccess access = ContainerLevelAccess.create(viewer.level(), viewer.blockPosition().immutable());
            return viewer.openMenu(new SimpleMenuProvider((id, inventory, player) -> stone ? new StoneMenu(id, inventory, access, target) : new CraftMenu(id, inventory, access, target), Component.literal(stone ? "Fake player stonecutting recipe" : "Fake player craft recipe"))).isPresent();
        });
    }
    static OrgFakePlayerActions.Filter item(Item item) {
        return item == Items.AIR ? OrgFakePlayerActions.EMPTY : new OrgFakePlayerActions.Filter(BuiltInRegistries.ITEM.getKey(item).toString(), stack -> !stack.isEmpty() && stack.is(item));
    }
    static OrgFakePlayerActions.Action craft(List<Item> recipe) {
        int[][] outside = {{0,1,2,5,8}, {0,3,6,7,8}, {2,5,6,7,8}, {0,1,2,3,6}};
        int[][] inside = {{3,4,6,7}, {1,2,4,5}, {0,1,3,4}, {4,5,7,8}};
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            boolean fits = true; for (int index : outside[quadrant]) if (recipe.get(index) != Items.AIR) { fits = false; break; }
            if (fits) { var filters = new ArrayList<OrgFakePlayerActions.Filter>(); for (int index : inside[quadrant]) filters.add(item(recipe.get(index))); return OrgFakePlayerActions.Action.simple("craft_inventory", filters); }
        }
        return OrgFakePlayerActions.Action.simple("craft_table", recipe.stream().map(OrgFakePlayerRecipeMenus::item).toList());
    }
    private static void set(Player viewer, UUID target, OrgFakePlayerActions.Action action) {
        if (!(viewer instanceof ServerPlayer server)) return;
        ServerPlayer selected = server.level().getServer().getPlayerList().getPlayer(target);
        if (!(selected instanceof ServerBot)) return;
        OrgMenuNativeEffects.run(selected,()->{if(!selected.isDeadOrDying())OrgFakePlayerActions.set(selected,action);return null;});
    }
    private static final class CraftMenu extends CraftingMenu {
        final UUID target;
        CraftMenu(int id, Inventory inventory, ContainerLevelAccess access, UUID target) { super(id, inventory, access); this.target = target; }
        @Override public void clicked(int slot, int button, ContainerInput input, Player player) { if (slot != 0) super.clicked(slot, button, input, player); }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public void removed(Player player) {
            List<Item> recipe = new ArrayList<>(); boolean any = false;
            for (int index = 0; index < 9; index++) { var stack = this.craftSlots.getItem(index); recipe.add(stack.getItem()); any |= !stack.isEmpty(); }
            // Always return actual recipe-setting materials, including an empty recipe.
            super.removed(player);
            if (any) set(player, this.target, craft(recipe));
        }
    }
    private static final class StoneMenu extends StonecutterMenu {
        final UUID target;
        StoneMenu(int id, Inventory inventory, ContainerLevelAccess access, UUID target) { super(id, inventory, access); this.target = target; }
        @Override public void clicked(int slot, int button, ContainerInput input, Player player) { if (slot != 1) super.clicked(slot, button, input, player); }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public void removed(Player player) {
            var input = this.container.getItem(0); Item item = input.getItem(); int selected = this.getSelectedRecipeIndex(); boolean any = !input.isEmpty();
            super.removed(player);
            if (any && selected >= 0) set(player, this.target, new OrgFakePlayerActions.Action("stonecutting", List.of(item(item)), selected, "", null, false, false, false, net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec3.ZERO));
        }
    }
}
