// SPDX-License-Identifier: MIT
package carpet.script.external;

import com.google.common.collect.ImmutableList;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The original vehicle virtual method receives the actual rider and owner-captured immutable rider inputs. */
public final class ScarpetDismountView {
    public record Result(Vec3 position,Pose pose) {}
    public static final class View implements CollisionContext {
        final LivingEntity rider;
        final float width,yRot;
        final HumanoidArm arm;
        final ImmutableList<Pose> poses;
        final Map<Pose,EntityDimensions> dimensions;
        final Map<Pose,AABB> bounds;
        final boolean descending,powderWalkable;
        final double bottom,fallDistance;
        final ItemStack held;
        final Set<FluidState> stableFluids;
        final VoxelShape liquidShape;
        Pose selected;
        private View(LivingEntity rider) {
            this.rider=rider;this.width=rider.getBbWidth();this.yRot=rider.getYRot();this.arm=rider.getMainArm();
            this.poses=ImmutableList.copyOf(rider.getDismountPoses());
            Map<Pose,EntityDimensions> sizes=new EnumMap<>(Pose.class);Map<Pose,AABB> boxes=new EnumMap<>(Pose.class);
            for(Pose pose:Pose.values()){sizes.put(pose,rider.getDimensions(pose));boxes.put(pose,rider.getLocalBoundsForPose(pose));}
            this.dimensions=Map.copyOf(sizes);this.bounds=Map.copyOf(boxes);
            this.descending=rider.isDescending();this.bottom=rider.getY();this.fallDistance=rider.fallDistance;
            this.held=rider.getMainHandItem().copy();
            this.powderWalkable=rider.is(net.minecraft.tags.EntityTypeTags.POWDER_SNOW_WALKABLE_MOBS)||rider.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS);
            Set<FluidState> fluids=Collections.newSetFromMap(new IdentityHashMap<>());
            for(var fluid:BuiltInRegistries.FLUID)for(FluidState state:fluid.getStateDefinition().getPossibleStates())if(rider.canStandOnFluid(state))fluids.add(state);
            this.stableFluids=Collections.unmodifiableSet(fluids);this.liquidShape=rider.getLiquidCollisionShape();
        }
        @Override public boolean isDescending(){return descending;}
        @Override public boolean isAbove(VoxelShape shape,BlockPos pos,boolean fallback){return bottom>pos.getY()+shape.max(Direction.Axis.Y)-1.0E-5F;}
        @Override public boolean isHoldingItem(Item item){return held.is(item);}
        @Override public boolean alwaysCollideWithFluid(){return false;}
        @Override public boolean canStandOnFluid(FluidState above,FluidState fluid){return stableFluids.contains(fluid)&&!above.getType().isSame(fluid.getType());}
        @Override public VoxelShape getCollisionShape(BlockState state,CollisionGetter level,BlockPos pos) {
            if(state.is(Blocks.POWDER_SNOW)) {
                if(fallDistance>2.5)return Shapes.box(0,0,0,1,0.9F,1);
                return powderWalkable&&isAbove(Shapes.block(),pos,false)&&!descending?state.getShape(level,pos):Shapes.empty();
            }
            if(state.getBlock() instanceof LiquidBlock) {
                return state.getValue(LiquidBlock.LEVEL)==0&&isAbove(liquidShape,pos,true)&&canStandOnFluid(level.getFluidState(pos.above()),state.getFluidState())?liquidShape:Shapes.empty();
            }
            return state.getCollisionShape(level,pos,this);
        }
    }
    private static final ThreadLocal<View> CURRENT=new ThreadLocal<>();
    private ScarpetDismountView() {}
    public static View capture(LivingEntity rider){return new View(rider);}
    public static Result calculate(View view,Supplier<Vec3> originalVehicleVirtual) {
        View previous=CURRENT.get();CURRENT.set(view);
        try{return new Result(originalVehicleVirtual.get(),view.selected);}
        finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    private static View view(LivingEntity rider){View value=CURRENT.get();return value!=null&&value.rider==rider?value:null;}
    public static float width(LivingEntity rider){View v=view(rider);return v==null?rider.getBbWidth():v.width;}
    public static float yRot(LivingEntity rider){View v=view(rider);return v==null?rider.getYRot():v.yRot;}
    public static HumanoidArm arm(LivingEntity rider){View v=view(rider);return v==null?rider.getMainArm():v.arm;}
    public static ImmutableList<Pose> poses(LivingEntity rider){View v=view(rider);return v==null?rider.getDismountPoses():v.poses;}
    public static EntityDimensions dimensions(LivingEntity rider,Pose pose){View v=view(rider);return v==null?rider.getDimensions(pose):v.dimensions.get(pose);}
    public static AABB bounds(LivingEntity rider,Pose pose){View v=view(rider);return v==null?rider.getLocalBoundsForPose(pose):v.bounds.get(pose);}
    public static void setPose(LivingEntity rider,Pose pose){View v=view(rider);if(v==null)rider.setPose(pose);else v.selected=pose;}
    public static Iterable<VoxelShape> collisions(CollisionGetter level,LivingEntity rider,AABB box){View v=view(rider);return v==null?level.getBlockCollisions(rider,box):level.getBlockCollisionsFromContext(v,box);}
}
