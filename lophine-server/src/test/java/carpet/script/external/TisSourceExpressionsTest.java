package carpet.script.external;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.navigation.*;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TisSourceExpressionsTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private final Map<Field,Object> previous=new HashMap<>();
    @BeforeEach void setup()throws Exception{for(String name:List.of("dustTrapdoorReintroduced","redstoneDustRepeaterComparatorIgnoreUpwardsStateUpdate","simpleUpdateSkipper","entityInstantDeathRemoval","entityPathNavigationStuckDetectionUseRealTimeReintroduced","entityPlacementIgnoreCollision","minecartPlaceableOnGround")){Field f=GeneralCompatConfig.class.getField(name);previous.put(f,f.get(null));f.set(null,false);}}
    @AfterEach void restore()throws Exception{for(var entry:previous.entrySet())entry.getKey().set(null,entry.getValue());}
    private static void field(Object object,Class<?> type,String name,Object value)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static BlockState update(Block block,LevelReader world,BlockState below)throws Exception{
        Method method=block.getClass().getDeclaredMethod("updateShape",BlockState.class,LevelReader.class,ScheduledTickAccess.class,BlockPos.class,Direction.class,BlockPos.class,BlockState.class,RandomSource.class);method.setAccessible(true);
        return(BlockState)method.invoke(block,block.defaultBlockState(),world,mock(ScheduledTickAccess.class),BlockPos.ZERO,Direction.DOWN,BlockPos.ZERO.below(),below,mock(RandomSource.class));
    }
    @Test void dustTrapdoorRuleRunsWireSupportReadAndDoesNotEnableOtherComponents()throws Exception{
        GeneralCompatConfig.dustTrapdoorReintroduced=true;LevelReader world=mock(LevelReader.class);when(world.isClientSide()).thenReturn(true);BlockState below=mock(BlockState.class);
        assertSame(Blocks.REDSTONE_WIRE.defaultBlockState(),update(Blocks.REDSTONE_WIRE,world,below));verify(below).isFaceSturdy(world,BlockPos.ZERO.below(),Direction.UP);
        assertSame(Blocks.AIR.defaultBlockState(),update(Blocks.REPEATER,world,below));assertSame(Blocks.AIR.defaultBlockState(),update(Blocks.COMPARATOR,world,below));
        verify(below,times(2)).isFaceSturdy(world,BlockPos.ZERO.below(),Direction.UP,SupportType.RIGID);
    }
    @Test void redstoneIgnoreReadsSupportAndRunsOriginalShapeTail()throws Exception{
        GeneralCompatConfig.redstoneDustRepeaterComparatorIgnoreUpwardsStateUpdate=true;LevelReader world=mock(LevelReader.class);when(world.isClientSide()).thenReturn(true);BlockState below=mock(BlockState.class);
        assertSame(Blocks.REPEATER.defaultBlockState(),update(Blocks.REPEATER,world,below));assertSame(Blocks.COMPARATOR.defaultBlockState(),update(Blocks.COMPARATOR,world,below));
        verify(below,times(2)).isFaceSturdy(world,BlockPos.ZERO.below(),Direction.UP,SupportType.RIGID);
    }
    @Test void instantRemovalUsesCurrentDeathCounterIncludingNegativeNativeValues()throws Exception{
        GeneralCompatConfig.entityInstantDeathRemoval=true;LivingEntity entity=mock(LivingEntity.class,CALLS_REAL_METHODS);ServerLevel world=mock(ServerLevel.class);when(entity.level()).thenReturn(world);when(entity.isRemoved()).thenReturn(false);
        doNothing().when(entity).remove(any(Entity.RemovalReason.class),any(org.bukkit.event.entity.EntityRemoveEvent.Cause.class));entity.deathTime=-5;
        Method tick=LivingEntity.class.getDeclaredMethod("tickDeath");tick.setAccessible(true);tick.invoke(entity);
        assertEquals(-4,entity.deathTime);verify(world).broadcastEntityEvent(entity,net.minecraft.world.entity.EntityEvent.POOF);
        verify(entity).remove(Entity.RemovalReason.KILLED,org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH);
    }
    @Test void realtimeNavigationEvaluatesGameTimeBeforeReplacingItsValue()throws Exception{
        GeneralCompatConfig.entityPathNavigationStuckDetectionUseRealTimeReintroduced=true;GroundPathNavigation navigation=mock(GroundPathNavigation.class,CALLS_REAL_METHODS);Level world=mock(Level.class);Mob mob=mock(Mob.class);Path path=mock(Path.class);
        field(navigation,PathNavigation.class,"level",world);field(navigation,PathNavigation.class,"mob",mob);field(navigation,PathNavigation.class,"path",path);
        field(navigation,PathNavigation.class,"timeoutCachedNode",new Vec3i(2,3,4));when(path.getNextNodePos()).thenReturn(new BlockPos(2,3,4));when(world.getGameTime()).thenReturn(77L);
        Method check=PathNavigation.class.getDeclaredMethod("doStuckDetection",Vec3.class);check.setAccessible(true);
        try(var clock=mockStatic(net.minecraft.util.Util.class)){clock.when(net.minecraft.util.Util::getMillis).thenReturn(123L);check.invoke(navigation,Vec3.ZERO);verify(world).getGameTime();Field timer=PathNavigation.class.getDeclaredField("lastTimeoutCheck");timer.setAccessible(true);assertEquals(123L,timer.getLong(navigation));}
    }
    @Test void endCrystalReadsInvalidPositionAndClearsOriginalEntityList()throws Exception{
        GeneralCompatConfig.entityPlacementIgnoreCollision=true;Level world=mock(Level.class);UseOnContext context=mock(UseOnContext.class);ItemStack item=mock(ItemStack.class);
        when(context.getLevel()).thenReturn(world);when(context.getClickedPos()).thenReturn(BlockPos.ZERO);when(context.getItemInHand()).thenReturn(item);
        when(world.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.OBSIDIAN.defaultBlockState());when(world.isEmptyBlock(new BlockPos(0,-1024,0))).thenReturn(true);
        List<Entity> original=new ArrayList<>(List.of(mock(Entity.class)));when(world.getEntities(isNull(),any(AABB.class))).thenReturn(original);
        EndCrystalItem crystal=mock(EndCrystalItem.class,CALLS_REAL_METHODS);assertSame(InteractionResult.SUCCESS,crystal.useOn(context));
        verify(world).isEmptyBlock(new BlockPos(0,-1024,0));verify(world,never()).isEmptyBlock(BlockPos.ZERO.above());verify(world).getEntities(isNull(),any(AABB.class));assertTrue(original.isEmpty());verify(item).shrink(1);
    }
    @Test void groundMinecartRetainsOriginalFalseAddCostAndServerSwingResult()throws Exception{
        GeneralCompatConfig.minecartPlaceableOnGround=true;GeneralCompatConfig.entityPlacementIgnoreCollision=true;ServerLevel world=mock(ServerLevel.class);UseOnContext context=mock(UseOnContext.class);ItemStack item=mock(ItemStack.class);AbstractMinecart cart=mock(AbstractMinecart.class);
        when(context.getLevel()).thenReturn(world);when(context.getClickedPos()).thenReturn(BlockPos.ZERO);when(context.getItemInHand()).thenReturn(item);when(context.getClickLocation()).thenReturn(new Vec3(0.1,0.2,0.3));when(world.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.STONE.defaultBlockState());when(cart.getBoundingBox()).thenReturn(new AABB(0,0,0,1,1,1));
        var placeEvent=mock(org.bukkit.event.entity.EntityPlaceEvent.class);var factory=org.bukkit.craftbukkit.event.CraftEventFactory.class;
        try(var carts=mockStatic(AbstractMinecart.class);var events=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class);var lifetime=mockStatic(fun.bm.lophine.carpet.TisLifetimeTracker.class)){
            carts.when(()->AbstractMinecart.createMinecart(eq(world),eq(0.1),eq(0.2),eq(0.3),isNull(),eq(EntitySpawnReason.DISPENSER),same(item),isNull())).thenReturn(cart);
            events.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPlaceEvent(context,cart)).thenReturn(placeEvent);
            MinecartItem minecart=mock(MinecartItem.class,CALLS_REAL_METHODS);assertSame(InteractionResult.SUCCESS_SERVER,minecart.useOn(context));
            AABB bounds=cart.getBoundingBox();verify(world).noCollision(same(cart),same(bounds));verify(world).addFreshEntity(cart);verify(item).shrink(1);verify(world,never()).getBlockState(BlockPos.ZERO.below());
        }
    }
}
