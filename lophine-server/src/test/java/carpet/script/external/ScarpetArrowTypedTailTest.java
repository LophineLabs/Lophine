package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.CarpetRegionLease;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.arrow.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual arrow prefix/tails and actor schedulers; only world leases and effect services are isolated. */
public class ScarpetArrowTypedTailTest {
    @BeforeAll static void bootstrap()throws Exception{
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var config=new io.papermc.paper.configuration.GlobalConfiguration();config.misc=config.new Misc();
        try(var configurations=mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)){
            configurations.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);Class.forName("net.minecraft.network.Connection");
        }
    }
    static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel sourceWorld=mock(ServerLevel.class),victimWorld=mock(ServerLevel.class),ownerWorld=mock(ServerLevel.class);
        final AbstractArrow arrow;
        final ServerPlayer victim=mock(ServerPlayer.class),owner=mock(ServerPlayer.class);
        final DamageSource damage=mock(DamageSource.class);
        final ItemStack weapon=mock(ItemStack.class),weaponSnapshot=mock(ItemStack.class),pickup=mock(ItemStack.class);
        final net.minecraft.util.RandomSource random=mock(net.minecraft.util.RandomSource.class);
        final net.minecraft.util.RandomSource worldRandom=mock(net.minecraft.util.RandomSource.class);
        final Queue<Runnable> tasks=new ArrayDeque<>();final List<String> order=new ArrayList<>();
        final AtomicReference<CompletableFuture<Boolean>> published=new AtomicReference<>();
        final CompletableFuture<Boolean> hurt=new CompletableFuture<>();
        final CompletableFuture<Void> notification=new CompletableFuture<>(),push=new CompletableFuture<>(),enchant=new CompletableFuture<>(),effect=new CompletableFuture<>(),packet=new CompletableFuture<>(),criterion=new CompletableFuture<>(),sound=new CompletableFuture<>();
        Entity current;ServerLevel currentVictimWorld=sourceWorld;
        final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final org.mockito.MockedStatic<CarpetRegionLease> leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        final org.mockito.MockedStatic<ScarpetDamageContinuations> damageBodies=mockStatic(ScarpetDamageContinuations.class,CALLS_REAL_METHODS);
        final org.mockito.MockedStatic<EnchantmentHelper> enchantments=mockStatic(EnchantmentHelper.class);
        final org.mockito.MockedStatic<ScarpetCriterionRemainingMatchers> criteria=mockStatic(ScarpetCriterionRemainingMatchers.class);
        Fixture(boolean spectral)throws Exception{this(spectral?SpectralArrow.class:Arrow.class);}
        Fixture(Class<? extends AbstractArrow> type)throws Exception{
            boolean spectral=type==SpectralArrow.class;
            arrow=mock(type,CALLS_REAL_METHODS);
            if(arrow instanceof SpectralArrow spectralArrow)spectralArrow.duration=200;
            when(sourceWorld.getServer()).thenReturn(server);when(victimWorld.getServer()).thenReturn(server);when(ownerWorld.getServer()).thenReturn(server);
            when(sourceWorld.getRandom()).thenReturn(worldRandom);
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call->call.getArgument(0)==current);
            ticks.when(()->TickThread.isTickThreadFor(any(ServerLevel.class),any(BlockPos.class))).thenReturn(true);
            leases.when(()->CarpetRegionLease.runValue(any(),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                Function<CarpetRegionLease.Lease<Object>,Object> body=call.getArgument(5);return CompletableFuture.completedFuture(body.apply(null));
            });
            actor(arrow,sourceWorld);actor(victim,sourceWorld);actor(owner,ownerWorld);
            doAnswer(call->{assertSame(victim,current);return currentVictimWorld;}).when(victim).level();
            field(Entity.class,"random").set(arrow,random);field(AbstractArrow.class,"firedFromWeapon").set(arrow,weapon);
            doReturn((byte)0).when(arrow).getPierceLevel();doReturn(new Vec3(1,0,0)).when(arrow).getDeltaMovement();doReturn(false).when(arrow).isOnFire();
            doAnswer(call->{assertSame(arrow,current);return false;}).when(arrow).isSilent();
            doReturn(owner).when(arrow).getEffectSource();doReturn(pickup).when(arrow).getPickupItemStackOrigin();
            when(weapon.copy()).thenReturn(weaponSnapshot);when(pickup.getOrDefault(eq(net.minecraft.core.component.DataComponents.POTION_DURATION_SCALE),anyFloat())).thenReturn(1F);
            if(arrow instanceof Arrow tipped)doReturn(new PotionContents(Optional.empty(),Optional.empty(),List.of(new MobEffectInstance(MobEffects.POISON,100,0)),Optional.empty())).when(tipped).getPotionContents();
            doAnswer(call->{assertSame(owner,current);order.add("notify");ScarpetNativeWork.record(notification);return null;}).when(owner).setLastHurtMob(victim);
            when(victim.getRemainingFireTicks()).thenAnswer(call->{assertSame(victim,current);return 42;});
            when(victim.hurtOrSimulate(damage,2F)).thenAnswer(call->{assertSame(victim,current);order.add("hurt");published.set(hurt);ScarpetNativeWork.record(hurt);return false;});
            damageBodies.when(()->ScarpetDamageContinuations.pendingResult(victim)).thenAnswer(call->published.get());
            when(victim.getArrowCount()).thenAnswer(call->{assertSame(victim,current);return 3;});
            doAnswer(call->{assertSame(victim,current);assertEquals(4,call.<Integer>getArgument(0).intValue());order.add("count");return null;}).when(victim).setArrowCount(anyInt());
            when(victim.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)).thenAnswer(call->{assertSame(victim,current);return 0D;});
            doAnswer(call->{assertSame(victim,current);order.add("push");ScarpetNativeWork.record(push);return null;}).when(victim).push(anyDouble(),anyDouble(),anyDouble(),eq(arrow));
            enchantments.when(()->EnchantmentHelper.carpetModifyKnockbackAsync(any(),eq(weapon),eq(victim),eq(damage),eq(0F))).thenAnswer(call->{assertSame(worldRandom,call.<ScarpetAttackEnchantments.SourceAdmission>getArgument(0).contextRandom());return CompletableFuture.completedFuture(2F);});
            enchantments.when(()->EnchantmentHelper.carpetPostAttackEffectsWithItemSourceAsync(any(),eq(victim),eq(damage),eq(weapon))).thenAnswer(call->{assertSame(arrow,current);assertSame(worldRandom,call.<ScarpetAttackEnchantments.SourceAdmission>getArgument(0).contextRandom());order.add("enchant");return enchant;});
            when(victim.addEffect(any(MobEffectInstance.class),eq(owner),eq(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ARROW))).thenAnswer(call->{
                assertSame(victim,current);assertEquals(spectral?MobEffects.GLOWING:MobEffects.POISON,call.<MobEffectInstance>getArgument(0).getEffect());order.add("effect");ScarpetNativeWork.record(effect);return true;
            });
            owner.connection=mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            doAnswer(call->{assertSame(owner,current);order.add("packet");ScarpetNativeWork.record(packet);return null;}).when(owner.connection).send(any(net.minecraft.network.protocol.Packet.class));
            when(victim.isAlive()).thenAnswer(call->{assertSame(victim,current);return false;});
            criteria.when(()->ScarpetCriterionRemainingMatchers.arrow(any(),eq(owner),anyCollection(),eq(weaponSnapshot))).thenAnswer(call->{assertSame(owner,current);order.add("criterion");return criterion;});
            doAnswer(call->{assertSame(arrow,current);order.add("sound");ScarpetNativeWork.record(sound);return null;}).when(arrow).playSound(any(),anyFloat(),anyFloat());
            doAnswer(call->{assertSame(arrow,current);order.add("discard");return null;}).when(arrow).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT);
            when(victim.projectileReceivesSideEffectsOnHit(false)).thenAnswer(call->{assertSame(victim,current);return true;});
            doAnswer(call->{assertSame(victim,current);assertEquals(42,call.<Integer>getArgument(0).intValue());order.add("fire");return null;}).when(victim).setRemainingFireTicks(anyInt());
            doAnswer(call->{assertSame(arrow,current);order.add("deflect");doReturn(Vec3.ZERO).when(arrow).getDeltaMovement();return true;}).when(arrow).deflect(eq(ProjectileDeflection.REVERSE),eq(victim),any(),eq(false),eq(0.2D));
        }
        static Field field(Class<?> type,String name)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);return field;}
        void actor(Entity actor,ServerLevel world)throws Exception{
            doAnswer(call->{assertSame(actor,current);return world;}).when(actor).level();
            doAnswer(call->{assertSame(actor,current);return BlockPos.ZERO;}).when(actor).blockPosition();
            CraftEntity bukkit=actor instanceof ServerPlayer?mock(CraftPlayer.class):mock(CraftEntity.class);doReturn(bukkit).when(actor).getBukkitEntity();
            var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);field(CraftEntity.class,"taskScheduler").set(bukkit,scheduler);
            when(scheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{java.util.function.Consumer<Entity> work=call.getArgument(0);tasks.add(()->{Entity previous=current;current=actor;try{work.accept(actor);}finally{current=previous;}});return true;});
        }
        CompletableFuture<?> start()throws Exception{
            current=arrow;
            try{return ScarpetNativeWork.observeNative(arrow,()->{
                try{var method=AbstractArrow.class.getDeclaredMethod("carpetStartArrowHitAsync",ScarpetAttackEnchantments.SourceAdmission.class,Entity.class,Entity.class,DamageSource.class,int.class);method.setAccessible(true);
                    method.invoke(arrow,new ScarpetAttackEnchantments.SourceAdmission(sourceWorld,BlockPos.ZERO,worldRandom,arrow),victim,owner,damage,2);
                }catch(Exception failure){throw new RuntimeException(failure);}return null;
            });}finally{current=null;}
        }
        void drain(){while(!tasks.isEmpty())tasks.remove().run();}
        boolean victimPaused(){Entity previous=current;current=victim;try{return ScarpetPlayerInventoryGate.paused(victim);}finally{current=previous;}}
        public void close(){criteria.close();enchantments.close();damageBodies.close();leases.close();ticks.close();}
    }
    @Test void aTippedArrowKeepsForeignOwnerNotificationBeforeHurtAndDrainsEveryCrossDimensionTail()throws Exception{success(false);}
    @Test void aSpectralArrowKeepsItsTypedEffectOnTheVictimsNewDimension()throws Exception{success(true);}
    private void success(boolean spectral)throws Exception{
        try(var f=new Fixture(spectral)){
            var actual=f.start();f.drain();assertEquals(List.of("notify"),f.order);assertFalse(actual.isDone());
            f.notification.complete(null);f.drain();assertEquals(List.of("notify","hurt"),f.order);assertFalse(actual.isDone());
            f.currentVictimWorld=f.victimWorld;f.hurt.complete(true);f.drain();assertEquals("push",f.order.getLast());assertFalse(actual.isDone());
            f.push.complete(null);f.drain();assertEquals("enchant",f.order.getLast());assertFalse(actual.isDone());
            f.enchant.complete(null);f.drain();assertEquals("effect",f.order.getLast());assertFalse(actual.isDone());
            f.effect.complete(null);f.drain();assertEquals("packet",f.order.getLast());assertFalse(actual.isDone());
            f.packet.complete(null);f.drain();assertEquals("criterion",f.order.getLast());assertFalse(actual.isDone());
            f.criterion.complete(null);f.drain();assertEquals("sound",f.order.getLast());assertFalse(actual.isDone());verify(f.arrow,never()).discard(any(org.bukkit.event.entity.EntityRemoveEvent.Cause.class));
            f.sound.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);ScarpetNativeWork.whenIdle(f.server).get(3,TimeUnit.SECONDS);
            assertEquals(List.of("notify","hurt","count","push","enchant","effect","packet","criterion","sound","discard"),f.order);
        }
    }
    @Test void anActualFalseArrowHitRestoresVictimFireBeforeDeflectingAndRemovingOnlyItsProjectile()throws Exception{
        try(var f=new Fixture(false)){
            var actual=f.start();f.drain();f.notification.complete(null);f.drain();f.currentVictimWorld=f.victimWorld;f.hurt.complete(false);f.drain();
            actual.get(3,TimeUnit.SECONDS);assertEquals(List.of("notify","hurt","fire","deflect","discard"),f.order);assertFalse(ScarpetDamageContinuations.pendingResult(f.victim).get());
        }
    }
    @Test void aFailedForeignOwnerNotificationStopsDamageAndEveryProjectileTail()throws Exception{
        try(var f=new Fixture(false)){
            var actual=f.start();f.drain();var failure=new IllegalStateException("owner notification");f.notification.completeExceptionally(failure);f.drain();
            assertSame(failure,assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS)).getCause());assertEquals(List.of("notify"),f.order);ScarpetNativeWork.whenIdle(f.server).get(3,TimeUnit.SECONDS);
        }
    }
    @Test void aFailedTypedArrowEffectDoesNotDeliverOwnerFeedbackOrRemoveTheProjectile()throws Exception{
        try(var f=new Fixture(false)){
            var actual=f.start();f.drain();f.notification.complete(null);f.drain();f.currentVictimWorld=f.victimWorld;f.hurt.complete(true);f.drain();f.push.complete(null);f.drain();f.enchant.complete(null);f.drain();
            var failure=new IllegalStateException("potion child");f.effect.completeExceptionally(failure);f.drain();assertSame(failure,assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS)).getCause());
            assertEquals(List.of("notify","hurt","count","push","enchant","effect"),f.order);ScarpetNativeWork.whenIdle(f.server).get(3,TimeUnit.SECONDS);
        }
    }
    @Test void aCombustCallbackChildFinishesBeforeItsCancellationIsReadAndDamageStarts()throws Exception{
        try(var f=new Fixture(false)){
            var eventChild=new CompletableFuture<Void>();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();doReturn(true).when(f.arrow).isOnFire();
            try(var events=mockConstruction(org.bukkit.event.entity.EntityCombustByEntityEvent.class,(event,context)->{
                when(event.callEvent()).thenAnswer(call->{assertSame(f.victim,f.current);f.order.add("combust");ScarpetNativeWork.record(eventChild);return true;});
                when(event.isCancelled()).thenAnswer(call->cancelled.get());
            })){
                var actual=f.start();f.drain();f.notification.complete(null);f.drain();assertEquals(List.of("notify","combust"),f.order);assertFalse(actual.isDone());
                cancelled.set(true);eventChild.complete(null);f.drain();assertEquals("hurt",f.order.getLast());verify(f.victim,never()).igniteForSeconds(anyFloat(),anyBoolean());
                f.hurt.complete(false);f.drain();actual.get(3,TimeUnit.SECONDS);ScarpetNativeWork.whenIdle(f.server).get(3,TimeUnit.SECONDS);
            }
        }
    }
    @Test void aNativeVictimRemovalDrainsItsCausalDamageWithoutWaitingOnTheProjectilesPublishedHit()throws Exception{
        try(var f=new Fixture(false)){
            var cleanup=new CompletableFuture<Void>();
            doAnswer(call->{
                assertSame(f.victim,f.current);f.order.add("hurt");f.published.set(f.hurt);ScarpetNativeWork.record(f.hurt);ScarpetPlayerInventoryGate.trackAccepted(f.victim,f.hurt);
                try(var accepted=ScarpetPlayerInventoryGate.acceptedScope(f.victim)){
                    var removal=ScarpetPlayerInventoryGate.whenIdleForRemoval(f.victim,()->{
                        assertSame(f.victim,f.current);f.order.add("remove");ScarpetNativeWork.record(cleanup);return cleanup.thenApply(ignored->true);
                    }).thenCompose(Function.identity());
                    ScarpetNativeWork.record(removal);removal.whenComplete((removed,failure)->{if(failure==null)f.hurt.complete(true);else f.hurt.completeExceptionally(failure);});
                }
                return false;
            }).when(f.victim).hurtOrSimulate(f.damage,2F);
            var actual=f.start();f.drain();assertNotNull(ScarpetProjectileContinuations.pendingHit(f.arrow));f.notification.complete(null);f.drain();
            assertEquals(List.of("notify","hurt","remove"),f.order);assertFalse(actual.isDone());assertTrue(f.victimPaused());
            cleanup.complete(null);f.drain();f.push.complete(null);f.drain();f.enchant.complete(null);f.drain();f.effect.complete(null);f.drain();f.packet.complete(null);f.drain();f.criterion.complete(null);f.drain();f.sound.complete(null);f.drain();
            actual.get(3,TimeUnit.SECONDS);ScarpetNativeWork.whenIdle(f.server).get(3,TimeUnit.SECONDS);assertFalse(f.victimPaused());assertEquals("discard",f.order.getLast());
        }
    }
    @Test void anOrdinaryLocalArrowWithoutNativeCallbacksFinishesItsOriginalHitImmediately()throws Exception{
        try(var f=new Fixture(false)){
            f.current=f.arrow;f.ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            doReturn(null).when(f.arrow).getOwner();doReturn(null).when(f.arrow).getWeaponItem();doReturn(false).when(f.arrow).isCritArrow();doReturn(PotionContents.EMPTY).when((Arrow)f.arrow).getPotionContents();
            doReturn(42).when(f.victim).getRemainingFireTicks();doReturn(true).when(f.victim).hurtOrSimulate(f.damage,0F);doReturn(3).when(f.victim).getArrowCount();
            doNothing().when(f.victim).setArrowCount(anyInt());doReturn(true).when(f.victim).isAlive();
            var sources=mock(net.minecraft.world.damagesource.DamageSources.class);doReturn(sources).when(f.arrow).damageSources();when(sources.arrow(f.arrow,f.arrow)).thenReturn(f.damage);
            var method=AbstractArrow.class.getDeclaredMethod("carpetOnHitEntity",net.minecraft.world.phys.EntityHitResult.class);method.setAccessible(true);
            assertFalse((Boolean)method.invoke(f.arrow,new net.minecraft.world.phys.EntityHitResult(f.victim)));
            assertTrue(f.tasks.isEmpty());assertNull(ScarpetProjectileContinuations.pendingHit(f.arrow));assertEquals(List.of("sound","discard"),f.order);
        }
    }
    @Test void aRealTridentHitKeepsChannelingEnchantThenVictimKnockbackThenSourceSoundAndDeflectionAcrossDimensions()throws Exception{
        try(var f=new Fixture(ThrownTrident.class)){
            var trident=(ThrownTrident)f.arrow;doReturn(f.owner).when(trident).getOwner();
            var sources=mock(net.minecraft.world.damagesource.DamageSources.class);doReturn(sources).when(trident).damageSources();when(sources.trident(trident,f.owner)).thenReturn(f.damage);
            f.enchantments.when(()->EnchantmentHelper.modifyDamage(f.sourceWorld,f.pickup,f.victim,f.damage,0F)).thenReturn(2F);
            doAnswer(call->{assertSame(trident,f.current);f.order.add("hurt");f.published.set(f.hurt);ScarpetNativeWork.record(f.hurt);return false;}).when(f.victim).hurtOrSimulate(f.damage,2F);
            f.enchantments.when(()->EnchantmentHelper.carpetPostAttackEffectsWithItemSourceAsync(any(),eq(f.victim),eq(f.damage),eq(f.pickup),any())).thenAnswer(call->{
                assertSame(trident,f.current);assertTrue(fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));f.order.add("trident enchant");return f.enchant;
            });
            when(f.victim.projectileReceivesSideEffectsOnHit(true)).thenAnswer(call->{assertSame(f.victim,f.current);return true;});
            doAnswer(call->{assertSame(trident,f.current);f.order.add("trident deflect");return true;}).when(trident).deflect(eq(ProjectileDeflection.REVERSE),eq(f.victim),any(),eq(false),eq(new Vec3(0.02,0.2,0.02)));
            f.current=trident;
            var actual=ScarpetNativeWork.observeNative(trident,()->{
                try{var method=ThrownTrident.class.getDeclaredMethod("onHitEntity",net.minecraft.world.phys.EntityHitResult.class);method.setAccessible(true);method.invoke(trident,new net.minecraft.world.phys.EntityHitResult(f.victim));}
                catch(Exception failure){throw new RuntimeException(failure);}return null;
            });f.current=null;assertEquals(List.of("hurt"),f.order);assertFalse(actual.isDone());
            f.currentVictimWorld=f.victimWorld;f.hurt.complete(true);f.drain();assertEquals("trident enchant",f.order.getLast());assertFalse(actual.isDone());
            f.enchant.complete(null);f.drain();assertEquals("push",f.order.getLast());assertFalse(actual.isDone());f.push.complete(null);f.drain();assertEquals("sound",f.order.getLast());assertFalse(actual.isDone());
            f.sound.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);ScarpetNativeWork.whenIdle(f.server).get(3,TimeUnit.SECONDS);
            assertEquals(List.of("hurt","trident enchant","push","sound","trident deflect"),f.order);
        }
    }
    @Test void explicitServerRegistrationIsMetadataOnlyAndRetainsTheNativeBodyToken()throws Exception{explicitRegistration(false);}
    @Test void explicitServerRegistrationPreservesTheNativeBodyFailureWithoutReadingItsVictim()throws Exception{explicitRegistration(true);}
    private void explicitRegistration(boolean fail)throws Exception{
        try(var f=new Fixture(false)){
            f.current=f.arrow;
            var actual=ScarpetNativeWork.observeNative(f.arrow,()->{ScarpetNativeWork.record(f.enchant);return true;});
            f.current=null;
            // Neither source nor victim is owned now. Registration may inspect metadata only.
            ScarpetDamageContinuations.publishExtendedResult(f.server,f.victim,actual);
            var serial=ScarpetDamageContinuations.pendingCompletion(f.victim);assertNotNull(serial);assertFalse(serial.isDone());
            assertSame(ScarpetNativeWork.knownDependencyOf(actual),ScarpetNativeWork.knownDependencyOf(serial));
            var drained=ScarpetNativeWork.whenIdle(f.server);assertFalse(drained.isDone());
            var cause=new IllegalStateException("actual native body");
            if(fail){f.enchant.completeExceptionally(cause);assertSame(cause,assertThrows(ExecutionException.class,()->serial.get(3,TimeUnit.SECONDS)).getCause());}
            else{f.enchant.complete(null);serial.get(3,TimeUnit.SECONDS);assertTrue(actual.get(3,TimeUnit.SECONDS));}
            drained.get(3,TimeUnit.SECONDS);assertNull(ScarpetDamageContinuations.pendingCompletion(f.victim));
        }
    }
}
