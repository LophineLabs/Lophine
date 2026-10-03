package carpet.script.external;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Shulker entity hit -> Projectile LAND -> discard -> damage event, through real damage receipts. */
public class ScarpetNativeShulkerHitTest {
    public abstract static class Bullet extends ShulkerBullet {
        public Bullet(EntityType<? extends ShulkerBullet> type,Level level){super(type,level);}
        public void hit(HitResult hit){super.onHit(hit);}
    }
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    boolean oldImmune,oldLevitation;
    @BeforeEach void settings(){oldImmune=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.immuneShulkerBullet;oldLevitation=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.shulkerHitLevitationDisabled;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.immuneShulkerBullet=false;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.shulkerHitLevitationDisabled=false;}
    @AfterEach void restore(){fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.immuneShulkerBullet=oldImmune;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.shulkerHitLevitationDisabled=oldLevitation;}
    static final class H implements AutoCloseable {
        final ScarpetNativeDeathCustomTest.Fixture f=new ScarpetNativeDeathCustomTest.Fixture();
        final Bullet bullet=mock(Bullet.class,CALLS_REAL_METHODS);final LivingEntity target,owner;final ItemStack weapon=mock(ItemStack.class);
        final CompletableFuture<Boolean> hurt=new CompletableFuture<>();final CompletableFuture<Void> post=new CompletableFuture<>();
        CompletableFuture<Void> levitation,land,discard,damage,guest,visual,block,particles,sound;
        final org.mockito.MockedStatic<EnchantmentHelper> ench=mockStatic(EnchantmentHelper.class);
        final org.mockito.MockedStatic<fun.bm.lophine.carpet.TisProjectileVisualizer> tracer=mockStatic(fun.bm.lophine.carpet.TisProjectileVisualizer.class);
        H()throws Exception {
            target=f.entity(LivingEntity.class);owner=f.entity(LivingEntity.class);doReturn(f.world).when(bullet).level();doReturn(BlockPos.ZERO).when(bullet).blockPosition();doReturn(Vec3.ZERO).when(bullet).position();doReturn(owner).when(bullet).getOwner();
            doReturn(0D).when(bullet).getX();doReturn(0D).when(bullet).getY();doReturn(0D).when(bullet).getZ();
            doReturn(weapon).when(owner).getWeaponItem();doReturn(ItemStack.EMPTY).when(owner).getMainHandItem();when(weapon.copy()).thenReturn(weapon);doReturn(net.minecraft.network.chat.Component.literal("owner")).when(owner).getDisplayName();doReturn("owner").when(owner).getScoreboardName();doReturn(false).when(owner).isSilent();
            var sources=mock(net.minecraft.world.damagesource.DamageSources.class);doReturn(sources).when(bullet).damageSources();when(sources.mobProjectile(bullet,owner)).thenReturn(f.damage);when(f.damage.getEntity()).thenReturn(owner);doReturn(false).when(target).is(net.minecraft.tags.EntityTypeTags.REDIRECTABLE_PROJECTILE);
            tracer.when(()->fun.bm.lophine.carpet.TisProjectileVisualizer.hit(eq(bullet),any())).thenAnswer(call->{f.order.add("visual");if(visual!=null)ScarpetNativeWork.record(visual);return null;});
            doAnswer(call->{assertSame(f.world,call.getArgument(0));assertEquals(4F,(Float)call.getArgument(2));f.order.add("hurt");ScarpetDamageContinuations.publishBodyResult(target,hurt);if(guest!=null)ScarpetNativeWork.recordGuest(guest);return false;}).when(target).hurtServer(eq(f.world),eq(f.damage),eq(4F));
            ench.when(()->EnchantmentHelper.carpetPostAttackEffectsWithItemSourceAsync(any(),eq(target),eq(f.damage),eq(weapon))).thenAnswer(call->{var admission=(ScarpetAttackEnchantments.SourceAdmission)call.getArgument(0);assertSame(bullet,admission.actualCaller());assertSame(f.world,admission.world());f.order.add("post");return post;});
            doAnswer(call->{var effect=(net.minecraft.world.effect.MobEffectInstance)call.getArgument(0);assertSame(net.minecraft.world.effect.MobEffects.LEVITATION,effect.getEffect());assertEquals(fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.shulkerHitLevitationDisabled?0:200,effect.getDuration());assertSame(owner,call.getArgument(1));f.order.add("levitation");if(levitation!=null)ScarpetNativeWork.record(levitation);return false;}).when(target).addEffect(any(),eq(owner),eq(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ATTACK));
            doAnswer(call->{assertEquals(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT,call.getArgument(0));f.order.add("discard");if(discard!=null)ScarpetNativeWork.record(discard);return null;}).when(bullet).discard(any(org.bukkit.event.entity.EntityRemoveEvent.Cause.class));
            doAnswer(call->{Object event=call.getArgument(0);if(event==GameEvent.PROJECTILE_LAND){f.order.add("land");if(land!=null)ScarpetNativeWork.record(land);}else if(event==GameEvent.ENTITY_DAMAGE){f.order.add("damage-event");if(damage!=null)ScarpetNativeWork.record(damage);}else fail("unexpected event "+event);return null;}).when(f.world).gameEvent(any(Holder.class),any(Vec3.class),any(GameEvent.Context.class));
            doAnswer(call->{f.order.add("land");if(land!=null)ScarpetNativeWork.record(land);return null;}).when(f.world).gameEvent(eq(GameEvent.PROJECTILE_LAND),any(BlockPos.class),any(GameEvent.Context.class));
            var state=mock(net.minecraft.world.level.block.state.BlockState.class);when(f.world.getBlockState(any(BlockPos.class))).thenReturn(state);
            doAnswer(call->{f.order.add("block");if(block!=null)ScarpetNativeWork.record(block);return null;}).when(state).onProjectileHit(eq(f.world),eq(state),any(BlockHitResult.class),eq(bullet));
            doAnswer(call->{f.order.add("particles");if(particles!=null)ScarpetNativeWork.record(particles);return 0;}).when(f.world).sendParticles(eq(net.minecraft.core.particles.ParticleTypes.EXPLOSION),anyDouble(),anyDouble(),anyDouble(),eq(2),anyDouble(),anyDouble(),anyDouble(),eq(0D));
            doAnswer(call->{f.order.add("sound");if(sound!=null)ScarpetNativeWork.record(sound);return null;}).when(bullet).playSound(eq(net.minecraft.sounds.SoundEvents.SHULKER_BULLET_HIT),eq(1F),eq(1F));
        }
        CompletableFuture<Integer> hit(){return ScarpetNativeWork.observeNative(bullet,()->{bullet.hit(new EntityHitResult(target));return 7;});}
        @Override public void close(){tracer.close();ench.close();f.close();}
    }
    @Test void trueHitWaitsHurtPostFalseEffectLandDiscardAndDamageChildren()throws Exception{
        try(H h=new H()){h.levitation=new CompletableFuture<>();h.land=new CompletableFuture<>();h.discard=new CompletableFuture<>();h.damage=new CompletableFuture<>();var root=h.hit();assertEquals(List.of("visual","hurt"),h.f.order);assertFalse(root.isDone());h.hurt.complete(true);assertEquals("post",h.f.order.getLast());h.post.complete(null);assertEquals("levitation",h.f.order.getLast());assertFalse(root.isDone());h.levitation.complete(null);assertEquals("land",h.f.order.getLast());assertFalse(root.isDone());h.land.complete(null);assertEquals("discard",h.f.order.getLast());assertFalse(root.isDone());h.discard.complete(null);assertEquals("damage-event",h.f.order.getLast());assertFalse(root.isDone());h.damage.complete(null);assertEquals(7,root.get(3,TimeUnit.SECONDS));assertEquals(List.of("visual","hurt","post","levitation","land","discard","damage-event"),h.f.order);assertTrue(ScarpetProjectileContinuations.pendingHit(h.bullet).isDone());}
    }
    @Test void actualFalseDamageSkipsPostEffectButStillRunsParentLandAndDestroy()throws Exception{
        try(H h=new H()){var root=h.hit();h.hurt.complete(false);assertEquals(7,root.get(3,TimeUnit.SECONDS));assertEquals(List.of("visual","hurt","land","discard","damage-event"),h.f.order);}
    }
    @Test void nativePostFailureStopsEffectParentLandAndDestroy()throws Exception{
        try(H h=new H()){var root=h.hit();h.hurt.complete(true);var problem=new IllegalStateException("native post");h.post.completeExceptionally(problem);assertSame(problem,assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause());assertEquals(List.of("visual","hurt","post"),h.f.order);}
    }
    @Test void guestDamageFailureRetainsRawFailureAndRunsFalseNativeParentTail()throws Exception{
        try(H h=new H()){h.guest=new CompletableFuture<>();var root=h.hit();h.hurt.complete(false);h.guest.completeExceptionally(new IllegalArgumentException("guest"));assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause()));assertEquals(List.of("visual","hurt","land","discard","damage-event"),h.f.order);}
    }
    @Test void immuneHeadSkipsAllDamageButOriginalParentStillLandsAndDestroys()throws Exception{
        try(H h=new H()){fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.immuneShulkerBullet=true;assertEquals(7,h.hit().get(3,TimeUnit.SECONDS));assertEquals(List.of("visual","land","discard","damage-event"),h.f.order);verify(h.target,never()).hurtServer(any(),any(),anyFloat());}
    }
    @Test void sourceLevitationDisabledStillCallsZeroDurationEffectBeforeParent()throws Exception{
        try(H h=new H()){fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.shulkerHitLevitationDisabled=true;var root=h.hit();h.hurt.complete(true);h.post.complete(null);assertEquals(7,root.get(3,TimeUnit.SECONDS));assertEquals(List.of("visual","hurt","post","levitation","land","discard","damage-event"),h.f.order);}
    }
    @Test void originalVisualizerNativeChildFencesDamageAndWholeCaller()throws Exception{
        try(H h=new H()){h.visual=new CompletableFuture<>();var root=h.hit();assertEquals(List.of("visual"),h.f.order);assertFalse(root.isDone());h.visual.complete(null);assertEquals(List.of("visual","hurt"),h.f.order);h.hurt.complete(false);assertEquals(7,root.get(3,TimeUnit.SECONDS));}
    }
    @Test void blockHitWaitsBlockParticlesSoundLandAndDiscardInOriginalOrder()throws Exception{
        try(H h=new H()){h.block=new CompletableFuture<>();h.particles=new CompletableFuture<>();h.sound=new CompletableFuture<>();var root=ScarpetNativeWork.observeNative(h.bullet,()->{h.bullet.hit(new BlockHitResult(Vec3.ZERO,Direction.UP,BlockPos.ZERO,false));return 7;});assertEquals(List.of("visual","block"),h.f.order);assertFalse(root.isDone());h.block.complete(null);assertEquals("particles",h.f.order.getLast());assertFalse(root.isDone());h.particles.complete(null);assertEquals("sound",h.f.order.getLast());assertFalse(root.isDone());h.sound.complete(null);assertEquals(7,root.get(3,TimeUnit.SECONDS));assertEquals(List.of("visual","block","particles","sound","land","discard","damage-event"),h.f.order);}
    }
    @Test void cancelledBlockStillRunsOriginalShulkerParticlesSoundAndParentTail()throws Exception{
        try(H h=new H()){ScarpetNativeDeathCustomTest.field(h.bullet,"hitCancelled",true);var root=ScarpetNativeWork.observeNative(h.bullet,()->{h.bullet.hit(new BlockHitResult(Vec3.ZERO,Direction.UP,BlockPos.ZERO,false));return 7;});assertEquals(7,root.get(3,TimeUnit.SECONDS));assertEquals(List.of("visual","particles","sound","land","discard","damage-event"),h.f.order);}
    }
}
