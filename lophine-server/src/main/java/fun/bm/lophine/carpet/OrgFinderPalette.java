// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import java.util.function.Predicate;
import carpet.script.external.Vanilla;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Palette prefilter ignores NBT; the original owned BlockInWorld predicate remains authoritative. */
final class OrgFinderPalette {
    private OrgFinderPalette() {}
    static Predicate<BlockState> of(Predicate<net.minecraft.world.level.block.state.pattern.BlockInWorld> predicate){
        Vanilla.BlockPredicatePayload payload=Vanilla.BlockPredicatePayload.of(predicate);
        return state->{
            if(state.isAir())return false;
            if(payload.state()!=null&&state.getBlock()!=payload.state().getBlock())return false;
            if(payload.tagKey()!=null&&!state.is(payload.tagKey()))return false;
            for(var entry:payload.properties().entrySet()){
                Property<?> property=state.getBlock().getStateDefinition().getProperty(entry.getKey().getString());
                if(property==null||!valueName(state,property).equals(entry.getValue().getString()))return false;
            }
            return true;
        };
    }
    private static <T extends Comparable<T>> String valueName(BlockState state,Property<T> property){return property.getName(state.getValue(property));}
}
