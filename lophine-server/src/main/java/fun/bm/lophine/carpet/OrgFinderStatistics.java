// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Owned item reads become immutable query results before any actor or file lease is released. */
final class OrgFinderStatistics {
    record Items(long total,Map<Item,Long> counts,Set<Item> nested) {
        Items { counts=Map.copyOf(counts);nested=Set.copyOf(nested); }
    }
    static Items count(Container inventory,Predicate<ItemStack> predicate) {
        var counts=new LinkedHashMap<Item,Long>();var nested=new HashSet<Item>();
        for(int index=0;index<inventory.getContainerSize();index++)tally(inventory.getItem(index),predicate,counts,nested,false,1,0);
        return new Items(counts.values().stream().mapToLong(Long::longValue).sum(),counts,nested);
    }
    private static void tally(ItemStack stack,Predicate<ItemStack> predicate,Map<Item,Long> counts,Set<Item> nested,
            boolean inside,int multiplier,int depth) {
        if(depth>512)throw new IllegalStateException("Finder item nesting is too deep");
        if(predicate.test(stack)){
            long count=(long)stack.getCount()*multiplier;counts.merge(stack.getItem(),count,Math::addExact);
            if(inside)nested.add(stack.getItem());
        }
        // The pinned source counts a container's contents with that container stack's own count.
        var container=stack.get(DataComponents.CONTAINER);
        if(container!=null){container.nonEmptyItemCopyStream().forEach(item->tally(item,predicate,counts,nested,true,stack.getCount(),depth+1));return;}
        var bundle=stack.get(DataComponents.BUNDLE_CONTENTS);
        if(bundle!=null)bundle.itemCopies().forEach(item->tally(item,predicate,counts,nested,true,stack.getCount(),depth+1));
    }
    record Group(Block block,Set<BlockPos> positions,BlockPos center) {
        Group { positions=Set.copyOf(positions); }
    }
    static List<Group> groups(Map<BlockPos,Block> matches,BlockPos origin) {
        var remaining=new HashMap<>(matches);var result=new ArrayList<Group>();
        while(!remaining.isEmpty()){
            BlockPos first=remaining.keySet().iterator().next();var frontier=new ArrayDeque<BlockPos>();frontier.add(first);
            var component=new LinkedHashMap<Block,Set<BlockPos>>();
            while(!frontier.isEmpty()){
                BlockPos position=frontier.removeFirst();Block block=remaining.remove(position);if(block==null)continue;
                component.computeIfAbsent(block,ignored->new HashSet<>()).add(position);
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
                    BlockPos next=position.offset(dx,dy,dz);if(remaining.containsKey(next))frontier.addLast(next);
                }
            }
            for(var entry:component.entrySet()){
                Set<BlockPos> positions=entry.getValue();BlockPos center=positions.iterator().next();
                if(positions.size()>2){
                    int minX=center.getX(),minY=center.getY(),minZ=center.getZ(),maxX=minX,maxY=minY,maxZ=minZ;
                    for(BlockPos pos:positions){minX=Math.min(minX,pos.getX());minY=Math.min(minY,pos.getY());minZ=Math.min(minZ,pos.getZ());maxX=Math.max(maxX,pos.getX());maxY=Math.max(maxY,pos.getY());maxZ=Math.max(maxZ,pos.getZ());}
                    BlockPos middle=new BlockPos((minX+maxX)/2,(minY+maxY)/2,(minZ+maxZ)/2);
                    center=positions.stream().min(Comparator.comparingDouble(pos->pos.distSqr(middle))).orElseThrow();
                }
                result.add(new Group(entry.getKey(),positions,center));
            }
        }
        result.sort(Comparator.<Group>comparingInt(group->group.positions.size()).reversed().thenComparingDouble(group->group.center.distSqr(origin)));
        return List.copyOf(result);
    }
}
