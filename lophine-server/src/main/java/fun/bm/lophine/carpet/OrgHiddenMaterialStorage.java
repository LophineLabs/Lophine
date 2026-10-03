// SPDX-License-Identifier: MIT
// BedrockAction material recycling + PlayerStorageInventory/InventoryUtils source algorithms.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

final class OrgHiddenMaterialStorage {
    private final OrgHiddenInventory inventory;
    OrgHiddenMaterialStorage(OrgHiddenInventory inventory) { this.inventory=inventory; }
    void collect(Runnable full) {
        ItemStack most=ItemStack.EMPTY;
        var player=inventory.player;var world=player.level();var position=player.blockPosition();
        for(ItemStack stack:player.getInventory().getNonEquipmentItems()){
            if(stack.isEmpty())return;
            if(stack.getItem() instanceof BlockItem item && item.getBlock().defaultBlockState().isRedstoneConductor(world,position)&&stack.getCount()>most.getCount())most=stack;
        }
        boolean dropped=false;
        for(int slot=0;slot<inventory.size();slot++){
            ItemStack stack=inventory.getItem(slot);
            if(stack!=most&&!stack.isEmpty()&&garbage(stack)){drop(slot);dropped=true;}
        }
        if(dropped||!GeneralCompatConfig.fakePlayerShulkerBoxItemHandling)return;
        collectMaterials(full);collectTools();
    }
    private void collectMaterials(Runnable full) {
        ItemStack most=most(OrgHiddenBedrock::material);
        for(int slot=0;slot<inventory.size();slot++)if(ItemStack.isSameItemSameComponents(most,inventory.getItem(slot))){drop(slot);break;}
        sort();boolean found=false;
        for(int slot=0;slot<inventory.size();slot++){
            ItemStack stack=inventory.getItem(slot);
            if(stack.isEmpty()||OrgGameplayHelper.isShulkerBox(stack))return;
            if(ItemStack.isSameItemSameComponents(stack,most)){
                found=true;if(insertWithBoxPriority(stack)){full.run();return;}
            }else if(found)return;
        }
    }
    private void collectTools() {
        sort();
        for(int slot=0;slot<inventory.size();slot++){
            ItemStack stack=inventory.getItem(slot);
            if(stack.isEmpty()||OrgGameplayHelper.isShulkerBox(stack))return;
            if(stack.has(DataComponents.TOOL)&&OrgHiddenInventory.fragile(stack)&&insertWithBoxPriority(stack))return;
        }
    }
    private static boolean garbage(ItemStack stack) {
        return !OrgHiddenBedrock.material(stack)&&!stack.is(Items.TORCH)&&!stack.has(DataComponents.FOOD)
            &&!OrgGameplayHelper.isShulkerBox(stack)&&!stack.has(DataComponents.TOOL);
    }
    private void drop(int slot){ItemStack stack=inventory.getItem(slot);inventory.setItem(slot,ItemStack.EMPTY);inventory.player.drop(stack,false,Prediction.SERVER_ONLY);}
    private ItemStack most(Predicate<ItemStack> accept) {
        var representatives=new ArrayList<ItemStack>();var counts=new ArrayList<Integer>();
        for(int slot=0;slot<inventory.size();slot++){
            ItemStack stack=inventory.getItem(slot);if(!accept.test(stack))continue;
            int index=-1;for(int i=0;i<representatives.size();i++)if(ItemStack.isSameItemSameComponents(stack,representatives.get(i))){index=i;break;}
            if(index<0){representatives.add(stack.copy());counts.add(stack.getCount());}else counts.set(index,counts.get(index)+stack.getCount());
        }
        int index=-1;for(int i=0;i<counts.size();i++)if(index<0||counts.get(i)>counts.get(index))index=i;
        return index<0?ItemStack.EMPTY:representatives.get(index);
    }
    private void sort() {
        var slots=new ArrayList<Integer>();
        for(int slot=0;slot<inventory.size();slot++){
            ItemStack held=inventory.getItem(slot);
            if(held.getCount()>held.getMaxStackSize()||opened(held))continue;
            slots.add(slot);
            if(!held.isEmpty()&&held.isStackable()&&held.getCount()<held.getMaxStackSize())for(int other=slot+1;other<inventory.size();other++){
                ItemStack stack=inventory.getItem(other);
                if(opened(stack)||stack.getCount()>stack.getMaxStackSize()||stack.isEmpty()||!ItemStack.isSameItemSameComponents(held,stack))continue;
                int moved=Math.min(stack.getCount(),held.getMaxStackSize()-held.getCount());held.grow(moved);stack.shrink(moved);
            }
        }
        var stacks=slots.stream().map(inventory::getItem).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        stacks.sort(OrgHiddenMaterialStorage::compare);
        for(int index=0;index<slots.size();index++)inventory.setItem(slots.get(index),stacks.get(index));
    }
    private boolean opened(ItemStack stack) {
        return OrgQuickShulker.isOpenedShulker(inventory.player,stack);
    }
    private static int compare(ItemStack left,ItemStack right) {
        if(ItemStack.isSameItemSameComponents(left,right))return -Integer.compare(left.getCount(),right.getCount());
        if(left.isEmpty())return 1;if(right.isEmpty())return -1;
        boolean leftBox=OrgGameplayHelper.isShulkerBox(left),rightBox=OrgGameplayHelper.isShulkerBox(right);
        if(left.is(right.getItem())){
            if(leftBox)return compareBoxes(left,right);
            int components=Integer.compare(left.getComponents().size(),right.getComponents().size());if(components!=0)return components;
            int count=-Integer.compare(left.getCount(),right.getCount());if(count!=0)return components; // Original comparator returns its component comparison here.
            return Integer.compare(ItemStack.hashItemAndComponents(left),ItemStack.hashItemAndComponents(right));
        }
        if(leftBox&&!rightBox)return 1;if(!leftBox&&rightBox)return -1;
        return BuiltInRegistries.ITEM.getKey(left.getItem()).compareTo(BuiltInRegistries.ITEM.getKey(right.getItem()));
    }
    private static int compareBoxes(ItemStack left,ItemStack right) {
        var a=contents(left).stream().filter(stack->!stack.isEmpty()).toList();var b=contents(right).stream().filter(stack->!stack.isEmpty()).toList();
        if(a.isEmpty()&&b.isEmpty())return 0;if(a.isEmpty())return 1;if(b.isEmpty())return -1;
        boolean singleA=a.stream().allMatch(stack->stack.is(a.getFirst().getItem())),singleB=b.stream().allMatch(stack->stack.is(b.getFirst().getItem()));
        if(singleA&&!singleB)return -1;if(!singleA&&singleB)return 1;
        if(singleA){int id=BuiltInRegistries.ITEM.getKey(a.getFirst().getItem()).compareTo(BuiltInRegistries.ITEM.getKey(b.getFirst().getItem()));if(id!=0)return id;}
        int count=-Integer.compare(a.stream().mapToInt(ItemStack::getCount).sum(),b.stream().mapToInt(ItemStack::getCount).sum());
        return count!=0?count:Integer.compare(ItemStack.hashItemAndComponents(left),ItemStack.hashItemAndComponents(right));
    }
    private boolean insertWithBoxPriority(ItemStack incoming) {
        var boxes=new ArrayList<Integer>();
        if(GeneralCompatConfig.fakePlayerShulkerBoxItemHandling){
            for(int slot=0;slot<inventory.size();slot++){
                ItemStack box=inventory.getItem(slot);if(!OrgGameplayHelper.isShulkerBox(box))continue;boxes.add(slot);
                var contents=contents(box).stream().filter(stack->!stack.isEmpty()).toList();
                boolean single=!contents.isEmpty()&&contents.stream().allMatch(stack->ItemStack.isSameItemSameComponents(stack,incoming));
                boolean junk=contents.size()>1&&contents.stream().anyMatch(stack->!ItemStack.isSameItemSameComponents(stack,contents.getFirst()));
                if(box.getCount()==1&&(single||junk)){deposit(box,incoming);if(incoming.isEmpty())return false;}
            }
            for(int slot:boxes){ItemStack box=inventory.getItem(slot);if(box.getCount()==1&&contents(box).stream().allMatch(ItemStack::isEmpty)){deposit(box,incoming);if(incoming.isEmpty())return false;}}
            if(!boxes.isEmpty()){
                int last=boxes.getLast();ItemStack box=inventory.getItem(last);
                if(last<inventory.size()-1&&box.getCount()>1&&inventory.getItem(last+1).isEmpty()){
                    ItemStack split=box.split(1);inventory.setItem(last+1,split);deposit(split,incoming);if(incoming.isEmpty())return false;
                }
            }
        }
        inventory.player.getInventory().add(incoming);
        if(incoming.isEmpty())return false;
        ItemStack offhand=inventory.player.getOffhandItem();
        if(offhand.isEmpty())inventory.player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,incoming.copyAndClear());
        else if(ItemStack.isSameItemSameComponents(offhand,incoming)){int moved=Math.min(incoming.getCount(),offhand.getMaxStackSize()-offhand.getCount());offhand.grow(moved);incoming.shrink(moved);}
        if(incoming.isEmpty())return false;
        inventory.player.drop(incoming.copyAndClear(),false,Prediction.SERVER_ONLY);return true;
    }
    private static List<ItemStack> contents(ItemStack box){return box.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).itemCopies().toList();}
    private static void deposit(ItemStack box,ItemStack incoming) {
        if(!incoming.getItem().canFitInsideContainerItems())return;
        var contents=new ArrayList<>(contents(box));int size=GeneralCompatConfig.largeShulkerBox||contents.size()>27?54:27;
        while(contents.size()<size)contents.add(ItemStack.EMPTY);
        for(ItemStack stack:contents)if(!stack.isEmpty()&&ItemStack.isSameItemSameComponents(stack,incoming)){
            int moved=Math.min(incoming.getCount(),stack.getMaxStackSize()-stack.getCount());stack.grow(moved);incoming.shrink(moved);
        }
        for(int index=0;index<size&&!incoming.isEmpty();index++)if(contents.get(index).isEmpty())contents.set(index,incoming.split(incoming.getMaxStackSize()));
        box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(contents));
    }
}
