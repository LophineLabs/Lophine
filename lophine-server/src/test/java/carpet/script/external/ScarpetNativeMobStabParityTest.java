package carpet.script.external;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes actual Mob/base Living stab entries and the production damage receipt adapter. */
public class ScarpetNativeMobStabParityTest {
    public abstract static class SoundMob extends Mob {
        protected SoundMob(EntityType<? extends Mob> type, Level level){super(type,level);}
        @Override public void playAttackSound(){}
    }
    public abstract static class SoundLiving extends LivingEntity {
        protected SoundLiving(EntityType<? extends LivingEntity> type, Level level){super(type,level);}
        @Override public void playAttackSound(){}
    }
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    static final class H implements AutoCloseable {
        final ScarpetNativeDeathCustomTest.Fixture f=new ScarpetNativeDeathCustomTest.Fixture();
        final LivingEntity source,target;final ItemStack stack=mock(ItemStack.class),fresh=mock(ItemStack.class);final Item item=mock(Item.class);
        final CompletableFuture<Boolean> hurt=new CompletableFuture<>(),itemDone=new CompletableFuture<>();
        final CompletableFuture<Void> post=new CompletableFuture<>();
        final org.mockito.MockedStatic<EnchantmentHelper> ench=mockStatic(EnchantmentHelper.class);
        CompletableFuture<Float> modified=CompletableFuture.completedFuture(8F),bonus=CompletableFuture.completedFuture(2F),knock=CompletableFuture.completedFuture(4F);
        CompletableFuture<Void> knockChild,guest,soundChild,pierceChild;
        H(boolean mob)throws Exception {
            source=mob?f.entity(SoundMob.class):f.entity(SoundLiving.class);target=f.entity(SoundLiving.class);
            doReturn(2D).when(source).getAttributeValue(Attributes.ATTACK_DAMAGE);doReturn(4D).when(source).getAttributeValue(Attributes.ATTACK_KNOCKBACK);
            doReturn(stack).when(source).getWeaponItem();doReturn(stack).when(source).getMainHandItem();doReturn(stack).when(source).getItemBySlot(EquipmentSlot.MAINHAND);
            doReturn(Vec3.ZERO).when(source).getDeltaMovement();doReturn(Vec3.ZERO).when(target).getDeltaMovement();
            doReturn(net.minecraft.network.chat.Component.literal("source")).when(source).getDisplayName();doReturn("source").when(source).getScoreboardName();doReturn(false).when(source).isSilent();
            doReturn(false).when(target).isPassenger();doReturn(false).when(target).is(net.minecraft.tags.EntityTypeTags.CANNOT_BE_DISMOUNTED_BY_ITEM_USAGE);
            when(stack.copy()).thenReturn(stack);when(stack.getDamageSource(source)).thenReturn(f.damage);when(stack.getItem()).thenReturn(item);when(f.damage.getEntity()).thenReturn(source);
            when(item.carpetGetAttackDamageBonusAsync(target,8F,f.damage)).thenAnswer(call->{f.order.add("bonus");return bonus;});
            ench.when(()->EnchantmentHelper.carpetModifyDamageAsync(any(),eq(stack),eq(target),eq(f.damage),anyFloat())).thenAnswer(call->{
                var admission=(ScarpetAttackEnchantments.SourceAdmission)call.getArgument(0);assertSame(f.world,admission.world());assertSame(f.worldRandom,admission.contextRandom());assertSame(source,admission.actualCaller());f.order.add("damage");return modified;
            });
            ench.when(()->EnchantmentHelper.carpetModifyKnockbackAsync(any(),eq(stack),eq(target),eq(f.damage),eq(4F))).thenAnswer(call->{f.order.add("knock-value");return knock;});
            doAnswer(call->{assertSame(f.world,call.getArgument(0));f.order.add("hurt:"+call.getArgument(2));ScarpetDamageContinuations.publishBodyResult(target,hurt);if(guest!=null)ScarpetNativeWork.recordGuest(guest);return false;}).when(target).hurtServer(eq(f.world),eq(f.damage),anyFloat());
            doAnswer(call->{f.order.add("push:"+call.getArgument(0)+":"+call.getArgument(5));if(knockChild!=null){ScarpetNativeWork.record(knockChild);knockChild=null;}return null;}).when(target).knockback(anyDouble(),anyDouble(),anyDouble(),eq(f.damage),anyFloat(),anyBoolean(),eq(source),any());
            doAnswer(call->{f.order.add("slow");return null;}).when(source).setDeltaMovement(any(Vec3.class));
            when(stack.carpetHurtEnemyAsync(target,source)).thenAnswer(call->{f.order.add("item");return itemDone;});
            ench.when(()->EnchantmentHelper.carpetPostAttackEffectsWithItemSourceAsync(any(),eq(target),eq(f.damage),any())).thenAnswer(call->{assertSame(fresh,call.getArgument(3));f.order.add("post");return post;});
            doAnswer(call->{f.order.add("last");return null;}).when(source).setLastHurtMob(target);
            if(source instanceof SoundMob sound)doAnswer(call->{f.order.add("sound");if(soundChild!=null)ScarpetNativeWork.record(soundChild);return null;}).when(sound).playAttackSound();
            else doAnswer(call->{f.order.add("sound");if(soundChild!=null)ScarpetNativeWork.record(soundChild);return null;}).when((SoundLiving)source).playAttackSound();
            ench.when(()->EnchantmentHelper.doPostPiercingAttackEffects(f.world,source)).thenAnswer(call->{f.order.add("pierce");if(pierceChild!=null)ScarpetNativeWork.record(pierceChild);return null;});
        }
        CompletableFuture<Boolean> mob(){return ScarpetNativeWork.observeNative(source,()->((Mob)source).doHurtTarget(f.world,target));}
        CompletableFuture<Boolean> stab(boolean damage,boolean knockback){return ScarpetNativeWork.observeNative(source,()->source.stabAttack(EquipmentSlot.MAINHAND,target,2F,damage,knockback,false));}
        @Override public void close(){ench.close();f.close();}
    }
    @Test void mobFalseWaitsActualDamageAndRunsOnlyOriginalPiercingTail()throws Exception{
        try(H h=new H(true)){var root=h.mob();assertEquals(List.of("damage","bonus","hurt:10.0"),h.f.order);assertFalse(root.isDone());var result=ScarpetAttackContinuations.pendingHitResult(h.source);assertFalse(result.isDone());h.hurt.complete(false);assertFalse(result.get(3,TimeUnit.SECONDS));root.get(3,TimeUnit.SECONDS);assertEquals(List.of("damage","bonus","hurt:10.0","pierce"),h.f.order);}
    }
    @Test void mobTrueWaitsKnockItemFalseFreshPostSoundAndPiercingChildren()throws Exception{
        try(H h=new H(true)){h.knockChild=new CompletableFuture<>();var push=h.knockChild;h.soundChild=new CompletableFuture<>();h.pierceChild=new CompletableFuture<>();var root=h.mob();h.hurt.complete(true);
            assertEquals("push:2.0:true",h.f.order.getLast());assertFalse(root.isDone());push.complete(null);assertEquals("item",h.f.order.getLast());assertFalse(root.isDone());doReturn(h.fresh).when(h.source).getWeaponItem();h.itemDone.complete(false);assertEquals("post",h.f.order.getLast());assertFalse(root.isDone());h.post.complete(null);assertEquals("sound",h.f.order.getLast());assertFalse(root.isDone());h.soundChild.complete(null);assertEquals("pierce",h.f.order.getLast());assertFalse(root.isDone());h.pierceChild.complete(null);root.get(3,TimeUnit.SECONDS);assertTrue(ScarpetAttackContinuations.pendingHitResult(h.source).get());
            assertEquals(List.of("damage","bonus","hurt:10.0","knock-value","push:2.0:true","slow","item","post","last","sound","pierce"),h.f.order);
        }
    }
    @Test void nativeDamageFailureSuppressesAllMobTailEffects()throws Exception{
        try(H h=new H(true)){var root=h.mob();var failure=new IllegalStateException("actual damage");h.hurt.completeExceptionally(failure);assertSame(failure,assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause());assertEquals(List.of("damage","bonus","hurt:10.0"),h.f.order);}
    }
    @Test void nativeKnockFailureSuppressesItemAndPiercing()throws Exception{
        try(H h=new H(true)){h.knock=new CompletableFuture<>();var root=h.mob();h.hurt.complete(true);var failure=new IllegalStateException("native knock");h.knock.completeExceptionally(failure);assertSame(failure,assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause());assertEquals("knock-value",h.f.order.getLast());}
    }
    @Test void guestDamageFailureRetainsRawFailureAndStillFinishesFalseNativeTail()throws Exception{
        try(H h=new H(true)){h.guest=new CompletableFuture<>();var root=h.mob();h.hurt.complete(false);assertFalse(root.isDone());h.guest.completeExceptionally(new IllegalArgumentException("guest"));assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause()));assertEquals("pierce",h.f.order.getLast());assertFalse(ScarpetAttackContinuations.pendingHitResult(h.source).get());}
    }
    @Test void stabNoAffectedStillWaitsOriginalItemFalseWithoutPostOrSound()throws Exception{
        try(H h=new H(false)){var root=h.stab(true,false);assertEquals(List.of("damage","hurt:8.0"),h.f.order);h.hurt.complete(false);assertEquals("item",h.f.order.getLast());assertFalse(root.isDone());h.itemDone.complete(false);root.get(3,TimeUnit.SECONDS);assertFalse(ScarpetAttackContinuations.pendingStabResult(h.source).get());assertEquals(List.of("damage","hurt:8.0","item"),h.f.order);}
    }
    @Test void stabWithoutDamageRunsTwoOriginalKnocksAndItemBeforeSound()throws Exception{
        try(H h=new H(false)){var root=h.stab(false,true);assertEquals(List.of("damage","push:0.4000000059604645:false","slow","knock-value","push:2.0:true","slow","item"),h.f.order);assertFalse(root.isDone());h.itemDone.complete(false);root.get(3,TimeUnit.SECONDS);assertTrue(ScarpetAttackContinuations.pendingStabResult(h.source).get());assertEquals(List.of("last","sound"),h.f.order.subList(h.f.order.size()-2,h.f.order.size()));verify(h.target,never()).hurtServer(any(),any(),anyFloat());}
    }
    @Test void stabTrueRereadsOwnerWeaponAfterItemAndWaitsRealPost()throws Exception{
        try(H h=new H(false)){var root=h.stab(true,false);h.hurt.complete(true);assertEquals("item",h.f.order.getLast());doReturn(h.fresh).when(h.source).getWeaponItem();h.itemDone.complete(false);assertEquals("post",h.f.order.getLast());assertFalse(root.isDone());h.post.complete(null);root.get(3,TimeUnit.SECONDS);assertTrue(ScarpetAttackContinuations.pendingStabResult(h.source).get());assertEquals(List.of("damage","hurt:8.0","item","post","last","sound"),h.f.order);}
    }
    @Test void plainMobCallerPublishesActualFalseReceiptAndFinishesPiercing()throws Exception{
        try(H h=new H(true)){
            h.ench.when(()->EnchantmentHelper.modifyDamage(h.f.world,h.stack,h.target,h.f.damage,2F)).thenReturn(8F);
            when(h.item.getAttackDamageBonus(h.target,8F,h.f.damage)).thenReturn(2F);
            assertNull(ScarpetAttackContinuations.pendingHitResult(h.source));assertTrue(((Mob)h.source).doHurtTarget(h.f.world,h.target));
            var result=ScarpetAttackContinuations.pendingHitResult(h.source);assertNotNull(result);assertFalse(result.isDone());h.hurt.complete(false);assertFalse(result.get(3,TimeUnit.SECONDS));assertSame(result,ScarpetAttackContinuations.pendingHitResult(h.source));assertEquals(List.of("hurt:10.0","pierce"),h.f.order);
        }
    }
    @Test void plainStabCallerKeepsFalseReceiptUntilMandatoryItemChildFinishes()throws Exception{
        try(H h=new H(false)){
            h.ench.when(()->EnchantmentHelper.modifyDamage(h.f.world,h.stack,h.target,h.f.damage,2F)).thenReturn(8F);
            assertTrue(h.source.stabAttack(EquipmentSlot.MAINHAND,h.target,2F,true,false,false));var result=ScarpetAttackContinuations.pendingStabResult(h.source);
            assertNotNull(result);h.hurt.complete(false);assertFalse(result.isDone());assertEquals(List.of("hurt:8.0","item"),h.f.order);h.itemDone.complete(false);assertFalse(result.get(3,TimeUnit.SECONDS));assertSame(result,ScarpetAttackContinuations.pendingStabResult(h.source));
        }
    }
    @Test void completedActualFalseStabStillPublishesNewBeforeAfterCarrier()throws Exception{
        try(H h=new H(false)){
            h.itemDone.complete(false);var before=ScarpetAttackContinuations.pendingStabResult(h.source);
            var root=h.stab(false,false);assertFalse(root.get(3,TimeUnit.SECONDS));var after=ScarpetAttackContinuations.pendingStabResult(h.source);assertNotNull(after);assertNotSame(before,after);assertFalse(after.get());assertEquals(List.of("damage","item"),h.f.order);
        }
    }
}
