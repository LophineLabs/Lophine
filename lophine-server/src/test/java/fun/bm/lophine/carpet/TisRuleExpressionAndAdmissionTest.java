package fun.bm.lophine.carpet;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.network.chat.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.redstone.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class TisRuleExpressionAndAdmissionTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 static void field(Object owner,Class<?> type,String name,Object value)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
 @Test void chatStillRunsRealDescentPredicateWhenRuleIsEnabled()throws Exception{chat(true,false);}
 @Test void chatReadsTheRuleAfterTheOriginalDescentPredicate()throws Exception{chat(true,true);}
 @Test void disabledChatKeepsTheRealDescentRejection()throws Exception{chat(false,false);}
 void chat(boolean enabled,boolean toggle)throws Exception{
  boolean old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.yeetOutOfOrderChatKick;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.yeetOutOfOrderChatKick=enabled;
   var validator=new SignedMessageValidator.KeyBased(mock(net.minecraft.util.SignatureValidator.class),()->false);
   var previous=mock(PlayerChatMessage.class);var message=mock(PlayerChatMessage.class);var priorLink=mock(SignedMessageLink.class);var link=mock(SignedMessageLink.class);
   when(previous.link()).thenReturn(priorLink);when(message.link()).thenReturn(link);field(validator,SignedMessageValidator.KeyBased.class,"lastMessage",previous);
   when(link.isDescendantOf(priorLink)).thenAnswer(call->{if(toggle)fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.yeetOutOfOrderChatKick=false;return false;});
   var method=SignedMessageValidator.KeyBased.class.getDeclaredMethod("validateChain",PlayerChatMessage.class);method.setAccessible(true);
   assertEquals(enabled&&!toggle,method.invoke(validator,message));verify(link).isDescendantOf(priorLink);
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.yeetOutOfOrderChatKick=old;}
 }
 @Test void instantHeadsCancelBeforeActualStateReadAndLowLevelDispatch(){
  boolean old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate=true;var world=mock(ServerLevel.class);var updater=new InstantNeighborUpdater(world);
   try(var low=mockStatic(NeighborUpdater.class)){
    updater.neighborChanged(BlockPos.ZERO,Blocks.STONE,null);updater.neighborChanged(Blocks.STONE.defaultBlockState(),BlockPos.ZERO,Blocks.STONE,null,false);updater.shapeUpdate(Direction.UP,Blocks.STONE.defaultBlockState(),BlockPos.ZERO,BlockPos.ZERO,3,10);
    verifyNoInteractions(world);low.verifyNoInteractions();
   }
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate=old;}
 }
 @Test void collectingHeadPreservesQueueCounterAndDebugWorkOnCancellation()throws Exception{
  boolean old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate=true;var world=mock(ServerLevel.class);var updater=new CollectingNeighborUpdater(world,100);field(updater,CollectingNeighborUpdater.class,"count",42);var debug=mock(java.util.function.Consumer.class);updater.setDebugListener(debug);
   try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
    updater.neighborChanged(BlockPos.ZERO,Blocks.STONE,null);updater.neighborChanged(Blocks.STONE.defaultBlockState(),BlockPos.ZERO,Blocks.STONE,null,false);updater.shapeUpdate(Direction.UP,Blocks.STONE.defaultBlockState(),BlockPos.ZERO,BlockPos.ZERO,3,10);updater.updateNeighborsAtExceptFromFacing(BlockPos.ZERO,Blocks.STONE,null,null);
    var f=CollectingNeighborUpdater.class.getDeclaredField("count");f.setAccessible(true);assertEquals(42,f.get(updater));verifyNoInteractions(world,debug);ticks.verifyNoInteractions();
   }
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate=old;}
 }
 @Test void acceptedCollectingUpdateStillRunsWhenFlagChangesAtActualStateRead(){
  boolean old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate=false;var world=mock(ServerLevel.class);var state=Blocks.STONE.defaultBlockState();var updater=new CollectingNeighborUpdater(world,100);
   when(world.getBlockStateIfLoaded(BlockPos.ZERO)).thenAnswer(call->{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate=true;return state;});
   try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);var low=mockStatic(NeighborUpdater.class)){
    ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(world,BlockPos.ZERO,25)).thenReturn(true);updater.neighborChanged(BlockPos.ZERO,Blocks.STONE,null);
    low.verify(()->NeighborUpdater.executeUpdate(world,state,BlockPos.ZERO,Blocks.STONE,null,false));
   }
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.totallyNoBlockUpdate=old;}
 }
 @Test void angryPiglinRecordsOriginalPlayerEvenWhenSuperTargetReturnsUnchanged(){
  boolean old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.zombifiedPiglinDropLootIfAngryReintroduced;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.zombifiedPiglinDropLootIfAngryReintroduced=true;var piglin=mock(ZombifiedPiglin.class,CALLS_REAL_METHODS);var player=mock(ServerPlayer.class);
   doReturn(player).when(piglin).getTarget();doReturn(player).when(piglin).getTargetUnchecked();doNothing().when(piglin).setLastHurtByPlayer(player,100);
   assertFalse(piglin.setTarget(player,null));verify(piglin).setLastHurtByPlayer(player,100);
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.zombifiedPiglinDropLootIfAngryReintroduced=old;}
 }
 @Test void disabledPiglinDoesNotAddPlayerMemory(){
  boolean old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.zombifiedPiglinDropLootIfAngryReintroduced;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.zombifiedPiglinDropLootIfAngryReintroduced=false;var piglin=mock(ZombifiedPiglin.class,CALLS_REAL_METHODS);var player=mock(ServerPlayer.class);
   doReturn(player).when(piglin).getTarget();doReturn(player).when(piglin).getTargetUnchecked();assertFalse(piglin.setTarget(player,null));verify(piglin,never()).setLastHurtByPlayer(any(net.minecraft.world.entity.player.Player.class),anyInt());
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.zombifiedPiglinDropLootIfAngryReintroduced=old;}
 }
 @Test void wetRuleEvaluatesOriginalImmunityThenWaterEvenForAlreadyImmuneEntity()throws Exception{wet(true,true,false);}
 @Test void wetSourceIsReadBeforeTheBenefitedEntityTypeFilter()throws Exception{wet(true,false,false);}
 @Test void wetProtectedItemKeepsOriginalImmunityCall()throws Exception{wet(true,false,true);}
 @Test void disabledWetRuleDoesNotReadWater()throws Exception{wet(false,false,true);}
 void wet(boolean enabled,boolean immune,boolean item)throws Exception{
  boolean old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.wetExplosionReintroduced;
  try{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.wetExplosionReintroduced=enabled;var explosion=mock(ServerExplosion.class,CALLS_REAL_METHODS);var source=mock(Entity.class);var target=item?mock(ItemEntity.class):mock(Entity.class);field(explosion,ServerExplosion.class,"source",source);var order=new ArrayList<String>();
   when(target.ignoreExplosion(explosion)).thenAnswer(call->{order.add("immunity");return immune;});when(source.isInWater()).thenAnswer(call->{order.add("water");return true;});
   try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
    ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(source)).thenReturn(true);var method=ServerExplosion.class.getDeclaredMethod("carpetIgnoreExplosion",Entity.class);method.setAccessible(true);
    assertEquals(immune||enabled&&item,method.invoke(explosion,target));assertEquals(enabled?List.of("immunity","water"):List.of("immunity"),order);
   }
  }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.wetExplosionReintroduced=old;}
 }
}