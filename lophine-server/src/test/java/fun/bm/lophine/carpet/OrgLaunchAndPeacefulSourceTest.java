package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import carpet.script.external.ScarpetNativeWork;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.entity.EntityTargetEvent;
import org.junit.jupiter.api.*;

/** Actual native successful launches and target setters preserve the Org source guards, including creative/zero item cost. */
public class OrgLaunchAndPeacefulSourceTest {
    @BeforeAll static void bootstrap(){
        OrgInventoryPersistenceTest.bootstrap();
        try{Items.FIREWORK_ROCKET.builtInRegistryHolder().components();}
        catch(NullPointerException unbound){Items.FIREWORK_ROCKET.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,64).build());}
    }
    @Test void sourcePeacefulGuardCancelsBothNewTargetAndNullClearingBeforeAnyNativeBukkitTargetEvent()throws Exception{
        boolean old=GeneralCompatConfig.peacefulCreeper;GeneralCompatConfig.peacefulCreeper=true;
        try{
            var creeper=mock(Creeper.class,CALLS_REAL_METHODS);var existing=mock(LivingEntity.class);var other=mock(LivingEntity.class);
            var target=Mob.class.getDeclaredField("target");target.setAccessible(true);target.set(creeper,existing);
            assertFalse(creeper.setTarget(null,EntityTargetEvent.TargetReason.FORGOT_TARGET));assertSame(existing,creeper.getTargetRaw());
            assertFalse(creeper.setTarget(other,EntityTargetEvent.TargetReason.CLOSEST_ENTITY));assertSame(existing,creeper.getTargetRaw());
            verifyNoInteractions(other);GeneralCompatConfig.peacefulCreeper=false;assertFalse(creeper.setTarget(existing,EntityTargetEvent.TargetReason.UNKNOWN));assertSame(existing,creeper.getTargetRaw());
        }finally{GeneralCompatConfig.peacefulCreeper=old;}
    }
    private record Launch(ServerPlayer player,ItemStack stack,ItemCooldowns cooldowns,InteractionResult result,CompletableFuture<InteractionResult> nativeWork){}
    private static Launch launch(boolean flying,boolean creative,boolean consume,boolean eventAccepted,boolean spawnAccepted,boolean fake,CompletableFuture<Void> child){
        var level=mock(ServerLevel.class);ServerPlayer player=fake?mock(org.leavesmc.leaves.bot.ServerBot.class):mock(ServerPlayer.class);
        when(player.isFallFlying()).thenReturn(flying);when(player.hasInfiniteMaterials()).thenReturn(creative);
        player.containerMenu=mock(net.minecraft.world.inventory.AbstractContainerMenu.class);var stack=new ItemStack(Items.FIREWORK_ROCKET,3);when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(stack);
        var cooldowns=mock(ItemCooldowns.class);when(player.getCooldowns()).thenReturn(cooldowns);
        if(child!=null)doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(cooldowns).addCooldown(stack,5);
        var context=mock(UseOnContext.class);when(context.getLevel()).thenReturn(level);when(context.getPlayer()).thenReturn(player);when(context.getItemInHand()).thenReturn(stack);when(context.getClickLocation()).thenReturn(Vec3.ZERO);when(context.getClickedFace()).thenReturn(Direction.UP);when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        Projectile.Delayed<FireworkRocketEntity> delayed=mock(Projectile.Delayed.class);when(delayed.attemptSpawn()).thenReturn(spawnAccepted);
        var item=(FireworkRocketItem)Items.FIREWORK_ROCKET;var immediate=new java.util.concurrent.atomic.AtomicReference<InteractionResult>();
        try(var rockets=mockConstruction(FireworkRocketEntity.class);var projectiles=mockStatic(Projectile.class);
            var launchEvents=mockConstruction(com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent.class,(event,ctx)->{when(event.callEvent()).thenReturn(eventAccepted);when(event.shouldConsume()).thenReturn(consume);});
            var boostEvents=mockConstruction(com.destroystokyo.paper.event.player.PlayerElytraBoostEvent.class,(event,ctx)->{when(event.callEvent()).thenReturn(eventAccepted);when(event.shouldConsume()).thenReturn(consume);})){
            projectiles.when(()->Projectile.spawnProjectileDelayed(any(FireworkRocketEntity.class),eq(level),same(stack),any())).thenAnswer(call->{when(delayed.projectile()).thenReturn(call.getArgument(0));return delayed;});
            var actual=ScarpetNativeWork.observeNative(player,()->{var result=flying?item.use(level,player,InteractionHand.MAIN_HAND):item.useOn(context);immediate.set(result);return result;});
            return new Launch(player,stack,cooldowns,immediate.get(),actual);
        }
    }
    @Test void actualSuccessfulCreativeLaunchesSetCooldownWithNoItemConsumptionInBothNativeUsePaths(){
        boolean old=GeneralCompatConfig.fireworkRocketUseCooldown;GeneralCompatConfig.fireworkRocketUseCooldown=true;
        try{for(boolean flying:new boolean[]{false,true}){var result=launch(flying,true,true,true,true,false,null);assertSame(InteractionResult.SUCCESS,result.result());assertSame(InteractionResult.SUCCESS,result.nativeWork().join());verify(result.cooldowns()).addCooldown(result.stack(),5);assertEquals(3,result.stack().getCount());}}
        finally{GeneralCompatConfig.fireworkRocketUseCooldown=old;}
    }
    @Test void successfulPaperZeroCostLaunchStillSetsCooldownWhileFakePlayersRemainSourceExcluded(){
        boolean old=GeneralCompatConfig.fireworkRocketUseCooldown;GeneralCompatConfig.fireworkRocketUseCooldown=true;
        try{for(boolean flying:new boolean[]{false,true}){
            var result=launch(flying,false,false,true,true,false,null);assertSame(InteractionResult.SUCCESS,result.nativeWork().join());verify(result.cooldowns()).addCooldown(result.stack(),5);assertEquals(3,result.stack().getCount());
            var fake=launch(flying,false,true,true,true,true,null);assertSame(InteractionResult.SUCCESS,fake.nativeWork().join());verifyNoInteractions(fake.cooldowns());assertEquals(2,fake.stack().getCount());
        }}finally{GeneralCompatConfig.fireworkRocketUseCooldown=old;}
    }
    @Test void actualBukkitCancelledOrNativeSpawnRejectedLaunchesNeverSetOrgCooldownOrConsumeItems(){
        boolean old=GeneralCompatConfig.fireworkRocketUseCooldown;GeneralCompatConfig.fireworkRocketUseCooldown=true;
        try{for(boolean flying:new boolean[]{false,true})for(boolean event:new boolean[]{false,true}){
            var result=launch(flying,false,true,event,false,false,null);result.nativeWork().join();verifyNoInteractions(result.cooldowns());assertEquals(3,result.stack().getCount());
        }}finally{GeneralCompatConfig.fireworkRocketUseCooldown=old;}
    }
    @Test void successfulSurvivalLaunchWaitsItsActualCooldownNativeChildrenAndDisabledRulePreservesOrdinaryConsumption(){
        boolean old=GeneralCompatConfig.fireworkRocketUseCooldown;GeneralCompatConfig.fireworkRocketUseCooldown=true;
        try{
            var child=new CompletableFuture<Void>();var result=launch(true,false,true,true,true,false,child);assertFalse(result.nativeWork().isDone());assertEquals(2,result.stack().getCount());child.complete(null);assertSame(InteractionResult.SUCCESS,result.nativeWork().join());
            GeneralCompatConfig.fireworkRocketUseCooldown=false;var unchanged=launch(false,false,true,true,true,false,null);assertSame(InteractionResult.SUCCESS,unchanged.nativeWork().join());verifyNoInteractions(unchanged.cooldowns());assertEquals(2,unchanged.stack().getCount());
        }finally{GeneralCompatConfig.fireworkRocketUseCooldown=old;}
    }
}
