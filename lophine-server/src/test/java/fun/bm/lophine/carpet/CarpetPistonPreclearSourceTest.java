package fun.bm.lophine.carpet;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.*;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class CarpetPistonPreclearSourceTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void fixedMoveClearsEverySourceInReverseBeforeDestroyOrMovement()throws Exception{move(true,false,false);}
 @Test void sourceSnapshotSurvivesRuleToggleDuringResolutionAndClearing()throws Exception{move(true,true,false);}
 @Test void disabledEntryKeepsOriginalMoveEvenWhenRuleTurnsOnLater()throws Exception{move(false,true,false);}
 @Test void rejectedPistonDoesNotClearOrExtractAnything()throws Exception{move(true,false,true);}
 private void move(boolean enabled,boolean toggle,boolean cancelled)throws Exception{
  boolean before=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix;
  boolean carriedBefore=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.movableBlockEntities;
  try{
   fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix=enabled;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.movableBlockEntities=false;
   var world=mock(ServerLevel.class);var piston=BlockPos.ZERO;var first=piston.east();var second=first.east();var destroyed=second.east();
   var initial=Blocks.STONE.defaultBlockState();var fresh=Blocks.OBSERVER.defaultBlockState();var map=new HashMap<BlockPos,BlockState>();map.put(first,initial);map.put(second,initial);map.put(destroyed,Blocks.FIRE.defaultBlockState());
   when(world.getBlockState(any(BlockPos.class))).thenAnswer(call->map.getOrDefault(call.getArgument(0),Blocks.AIR.defaultBlockState()));
   var events=new ArrayList<String>();var movedStates=new ArrayList<BlockState>();
   when(world.setBlock(any(BlockPos.class),any(BlockState.class),anyInt())).thenAnswer(call->{BlockPos at=call.getArgument(0);BlockState state=call.getArgument(1);int flags=call.getArgument(2);
    if(flags==86){events.add("clear:"+at.getX());if(toggle)fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix=false;}
    if(state.is(Blocks.MOVING_PISTON))events.add("move:"+at.getX());if(at.equals(destroyed)&&state.isAir())events.add("destroy");map.put(at,state);return true;});
   var updates=new HashMap<BlockPos,Block>();doAnswer(call->{updates.putIfAbsent(call.getArgument(0),call.getArgument(1));return null;}).when(world).updateNeighborsAt(any(BlockPos.class),any(Block.class),any());
   try(var resolver=mockConstruction(PistonStructureResolver.class,(actual,context)->{
    when(actual.resolve()).thenAnswer(call->{if(toggle)fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix=!enabled;return true;});
    when(actual.getToPush()).thenReturn(List.of(first,second));when(actual.getToDestroy()).thenReturn(List.of(destroyed));when(actual.getPushDirection()).thenReturn(Direction.EAST);
   });var craft=mockStatic(org.bukkit.craftbukkit.block.CraftBlock.class);var event=mockConstruction(org.bukkit.event.block.BlockPistonExtendEvent.class,(actual,context)->{
    when(actual.callEvent()).thenAnswer(call->{map.put(first,fresh);map.put(second,fresh);return !cancelled;});
   });var moving=mockStatic(MovingPistonBlock.class);var micro=mockStatic(TisMicroTimingMarkers.class);var redstone=mockStatic(net.minecraft.world.level.redstone.ExperimentalRedstoneUtils.class);var block=mockStatic(Block.class)){
    craft.when(()->org.bukkit.craftbukkit.block.CraftBlock.at(eq(world),any(BlockPos.class))).thenReturn(mock(org.bukkit.craftbukkit.block.CraftBlock.class));
    craft.when(()->org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(any(Direction.class))).thenReturn(org.bukkit.block.BlockFace.EAST);
    moving.when(()->MovingPistonBlock.newMovingBlockEntity(any(BlockPos.class),any(BlockState.class),any(BlockState.class),any(Direction.class),anyBoolean(),anyBoolean())).thenAnswer(call->{if(!(boolean)call.getArgument(5))movedStates.add(call.getArgument(2));return mock(PistonMovingBlockEntity.class);});
    var nativeMethod=PistonBaseBlock.class.getDeclaredMethod("moveBlocks",net.minecraft.world.level.Level.class,BlockPos.class,Direction.class,boolean.class);nativeMethod.setAccessible(true);
    assertEquals(!cancelled,nativeMethod.invoke(Blocks.PISTON,world,piston,Direction.EAST,true));
    if(cancelled){assertTrue(events.isEmpty());assertTrue(movedStates.isEmpty());verify(world,never()).removeBlockEntity(any());return;}
    if(enabled){assertEquals(List.of("clear:2","clear:1"),events.subList(0,2));assertTrue(events.indexOf("destroy")>1);assertEquals(List.of(fresh,fresh),movedStates);assertSame(Blocks.OBSERVER,updates.get(first));assertSame(Blocks.OBSERVER,updates.get(second));}
    else{assertFalse(events.stream().anyMatch(value->value.startsWith("clear:")));assertEquals(List.of(initial,initial),movedStates);}
   }
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tntDupingFix=before;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.movableBlockEntities=carriedBefore;}
 }
}