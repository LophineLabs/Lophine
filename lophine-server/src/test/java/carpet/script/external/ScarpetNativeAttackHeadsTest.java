package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs real Native Player/ItemStack/Mace tails with distinct actual owner schedulers. */
public class ScarpetNativeAttackHeadsTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel from=mock(ServerLevel.class),to=mock(ServerLevel.class);
        final ServerPlayer attacker=mock(ServerPlayer.class,CALLS_REAL_METHODS);
        final LivingEntity victim=mock(LivingEntity.class);
        final Queue<Runnable> tasks=new ConcurrentLinkedQueue<>();final List<String> order=new ArrayList<>();
        Entity current=attacker;final ItemStack stack;
        final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final org.mockito.MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);
        final org.mockito.MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases=mockStatic(fun.bm.lophine.carpet.CarpetRegionLease.class);
        final org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit=mockStatic(org.bukkit.Bukkit.class);
        Fixture() throws Exception {
            when(from.getServer()).thenReturn(server);when(to.getServer()).thenReturn(server);servers.when(MinecraftServer::getServer).thenReturn(server);
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call->call.getArgument(0)==current);
            ticks.when(()->TickThread.isTickThreadFor(any(ServerLevel.class),any(BlockPos.class))).thenReturn(true);
            for(boolean loaded:new boolean[]{false,true}){
                if(loaded)leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runLoadedValue(any(),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->lease(call));
                else leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(any(),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->lease(call));
            }
            doReturn(from).when(attacker).level();doReturn(server).when(attacker).carpetSpawnServer();doReturn(BlockPos.ZERO).when(attacker).blockPosition();doReturn(Vec3.ZERO).when(attacker).position();
            Field position=Entity.class.getDeclaredField("position");position.setAccessible(true);position.set(attacker,Vec3.ZERO);attacker.setId(42);
            doReturn(2D).when(attacker).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK);doReturn(Vec3.ZERO).when(attacker).getDeltaMovement();doReturn(false).when(attacker).isRemoved();
            doReturn(net.minecraft.sounds.SoundSource.PLAYERS).when(attacker).getSoundSource();
            var random=mock(net.minecraft.util.RandomSource.class);when(from.getRandom()).thenReturn(random);when(to.getRandom()).thenReturn(random);
            Field randomField=Entity.class.getDeclaredField("random");randomField.setAccessible(true);randomField.set(attacker,random);
            var config=mock(io.papermc.paper.configuration.WorldConfiguration.class);config.misc=mock(io.papermc.paper.configuration.WorldConfiguration.Misc.class);config.misc.disableSprintInterruptionOnAttack=true;when(from.paperConfig()).thenReturn(config);
            Field spigot=net.minecraft.world.level.Level.class.getDeclaredField("spigotConfig");spigot.setAccessible(true);spigot.set(from,mock(org.spigotmc.SpigotWorldConfig.class));
            when(victim.level()).thenReturn(to);when(victim.blockPosition()).thenReturn(BlockPos.ZERO);when(victim.position()).thenReturn(Vec3.ZERO);when(victim.getBoundingBox()).thenReturn(new AABB(-1,0,-1,1,2,1));when(victim.getOnPos()).thenReturn(BlockPos.ZERO);when(victim.getUUID()).thenReturn(UUID.randomUUID());
            doReturn(ItemStack.EMPTY).when(victim).getItemBySlot(any(EquipmentSlot.class));doReturn(ItemStack.EMPTY).when(attacker).getItemBySlot(any(EquipmentSlot.class));
            attach(attacker,mock(org.bukkit.craftbukkit.entity.CraftPlayer.class));attach(victim,mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class));
            attacker.connection=mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            doAnswer(call->{assertSame(attacker,current);order.add("slow");return null;}).when(attacker).setDeltaMovement(any(Vec3.class));
            doAnswer(call->{assertSame(attacker,current);order.add("food");return null;}).when(attacker).causeFoodExhaustion(anyFloat(),any());
            doAnswer(call->{assertSame(attacker,current);order.add("post");return null;}).when(attacker).postPiercingAttack();
            doAnswer(call->{assertSame(attacker,current);order.add("stat:"+call.getArgument(1));return null;}).when(attacker).awardStat(any(net.minecraft.stats.Stat.class),anyInt());
            var constructor=ItemStack.class.getDeclaredConstructor(net.minecraft.core.Holder.class,int.class,net.minecraft.core.component.PatchedDataComponentMap.class);constructor.setAccessible(true);
            stack=constructor.newInstance(Items.STICK.builtInRegistryHolder(),1,new net.minecraft.core.component.PatchedDataComponentMap(net.minecraft.core.component.DataComponentMap.EMPTY));
            doReturn(stack).when(attacker).getWeaponItem();doReturn(stack).when(attacker).getMainHandItem();
            var craft=mock(org.bukkit.craftbukkit.CraftServer.class);when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
        }
        Object lease(org.mockito.invocation.InvocationOnMock call){Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);return CompletableFuture.completedFuture(action.apply(null));}
        void attach(Entity entity,org.bukkit.craftbukkit.entity.CraftEntity wrapper) throws Exception {
            doReturn(wrapper).when(entity).getBukkitEntity();var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);Field field=org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");field.setAccessible(true);field.set(wrapper,scheduler);
            when(scheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{Consumer<Entity> action=call.getArgument(0);tasks.add(()->{var previous=current;current=entity;try{action.accept(entity);}finally{current=previous;}});return true;});
        }
        void drain(){Runnable task;while((task=tasks.poll())!=null)task.run();}
        @Override public void close(){bukkit.close();leases.close();servers.close();ticks.close();}
    }
    static void headSource(Fixture f,ItemStack actual,net.minecraft.world.damagesource.DamageSource source)throws Exception{
        doReturn(false).when(f.attacker).isCreative();doReturn(false).when(f.attacker).isSprinting();doReturn(1F).when(f.attacker).getAttackStrengthScale(.5F);
        doReturn(false).when(f.attacker).isAutoSpinAttack();doReturn(false).when(f.attacker).isUsingItem();
        doNothing().when(f.attacker).onAttack(any(Entity.class));
        doReturn(2D).when(f.attacker).getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        doReturn(actual).when(f.attacker).getWeaponItem();doReturn(actual).when(f.attacker).getMainHandItem();
        doReturn(actual).when(f.attacker).getItemBySlot(any(EquipmentSlot.class));doReturn(source).when(actual).getDamageSource(f.attacker);
        when(f.victim.isAttackable()).thenReturn(true);when(f.victim.getDeltaMovement()).thenReturn(Vec3.ZERO);when(f.victim.getHealth()).thenReturn(17F);
    }
    @Test void realAttackHeadWaitsPreEventEnchantmentOnAttackAndActualDamageNativeChildren()throws Exception{
        try(Fixture f=new Fixture();var ench=mockStatic(net.minecraft.world.item.enchantment.EnchantmentHelper.class,CALLS_REAL_METHODS)){
            ItemStack actual=spy(f.stack);var source=mock(net.minecraft.world.damagesource.DamageSource.class);headSource(f,actual,source);
            var pre=new CompletableFuture<Void>();var enchanted=new CompletableFuture<Float>();var onAttack=new CompletableFuture<Void>();var damage=new CompletableFuture<Void>();
            try(var events=mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class,(event,context)->{
                assertSame(f.victim.getBukkitEntity(),context.arguments().get(1));when(event.callEvent()).thenAnswer(call->{assertSame(f.attacker,f.current);f.order.add("pre");ScarpetNativeWork.record(pre);return true;});
            })){
                ench.when(()->net.minecraft.world.item.enchantment.EnchantmentHelper.carpetModifyDamageAsync(any(),eq(actual),eq(f.victim),eq(source),eq(2F))).thenAnswer(call->{
                    assertSame(f.attacker,f.current);var admission=(ScarpetAttackEnchantments.SourceAdmission)call.getArgument(0);assertSame(f.from,admission.world());assertSame(f.attacker,admission.actualCaller());f.order.add("enchantment");return enchanted;
                });
                doAnswer(call->{assertSame(f.attacker,f.current);f.order.add("onAttack");ScarpetNativeWork.record(onAttack);return null;}).when(f.attacker).onAttack(f.victim);
                when(f.victim.getHealth()).thenAnswer(call->{assertSame(f.victim,f.current);f.order.add("oldHealth");return 17F;});
                when(f.victim.getDeltaMovement()).thenAnswer(call->{assertSame(f.victim,f.current);f.order.add("oldMove");return Vec3.ZERO;});
                when(f.victim.hurtServer(eq(f.to),eq(source),eq(5F))).thenAnswer(call->{assertSame(f.victim,f.current);f.order.add("hurt");ScarpetNativeWork.record(damage);return false;});
                var root=ScarpetNativeWork.observeNative(f.attacker,()->{var work=f.attacker.carpetAttackNativeAsync(f.victim);ScarpetNativeWork.record(work);return work;});
                f.drain();assertEquals(List.of("pre"),f.order);pre.complete(null);f.drain();assertEquals(List.of("pre","enchantment"),f.order);assertFalse(root.isDone());
                enchanted.complete(5F);f.drain();assertEquals(List.of("pre","enchantment","onAttack"),f.order);assertFalse(root.isDone());
                onAttack.complete(null);f.drain();assertEquals(List.of("pre","enchantment","onAttack","oldHealth","oldMove","hurt"),f.order);assertFalse(root.isDone());
                damage.complete(null);f.drain();root.get(3,TimeUnit.SECONDS).get(3,TimeUnit.SECONDS);assertEquals("post",f.order.getLast());
            }
        }
    }
    @Test void realHeadNativeOnAttackFailureSuppressesVictimSnapshotAndDamage()throws Exception{
        try(Fixture f=new Fixture();var ench=mockStatic(net.minecraft.world.item.enchantment.EnchantmentHelper.class,CALLS_REAL_METHODS);
            var events=mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class,(event,context)->when(event.callEvent()).thenReturn(true))){
            ItemStack actual=spy(f.stack);var source=mock(net.minecraft.world.damagesource.DamageSource.class);headSource(f,actual,source);
            ench.when(()->net.minecraft.world.item.enchantment.EnchantmentHelper.carpetModifyDamageAsync(any(),eq(actual),eq(f.victim),eq(source),anyFloat())).thenReturn(CompletableFuture.completedFuture(4F));
            var child=new CompletableFuture<Void>();var failure=new IllegalStateException("real HEAD");doAnswer(call->{ScarpetNativeWork.record(child);throw failure;}).when(f.attacker).onAttack(f.victim);
            var actualHead=f.attacker.carpetAttackNativeAsync(f.victim);f.drain();assertFalse(actualHead.isDone());verify(f.victim,never()).getHealth();child.complete(null);f.drain();
            assertSame(failure,assertThrows(ExecutionException.class,()->actualHead.get(3,TimeUnit.SECONDS)).getCause());verify(f.victim,never()).hurtServer(any(),any(),anyFloat());
        }
    }
    @Test void guestOnlyPreAttackFailureRetainsRawFailureAndRunsAcceptedSourceTails()throws Exception{
        try(Fixture f=new Fixture();var ench=mockStatic(net.minecraft.world.item.enchantment.EnchantmentHelper.class,CALLS_REAL_METHODS)){
            ItemStack actual=spy(f.stack);var source=mock(net.minecraft.world.damagesource.DamageSource.class);headSource(f,actual,source);var guest=new CompletableFuture<Void>();
            try(var events=mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class,(event,context)->when(event.callEvent()).thenAnswer(call->{ScarpetNativeWork.recordGuest(guest);return true;}))){
                ench.when(()->net.minecraft.world.item.enchantment.EnchantmentHelper.carpetModifyDamageAsync(any(),eq(actual),eq(f.victim),eq(source),anyFloat())).thenReturn(CompletableFuture.completedFuture(0F));
                var root=ScarpetNativeWork.observeNative(f.attacker,()->{var work=f.attacker.carpetAttackNativeAsync(f.victim);ScarpetNativeWork.record(work);return work;});f.drain();assertFalse(root.isDone());
                guest.completeExceptionally(new IllegalArgumentException("guest"));f.drain();var failure=assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause();
                assertTrue(ScarpetNativeWork.onlyGuestFailure(failure));assertEquals("post",f.order.getLast());
            }
        }
    }
    @Test void offhandStabEnchantmentsKeepOriginalMainWeaponAndReturnActualFalseAfterDismountEligibility()throws Exception{
        try(Fixture f=new Fixture();var ench=mockStatic(net.minecraft.world.item.enchantment.EnchantmentHelper.class,CALLS_REAL_METHODS);
            var events=mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class,(event,context)->when(event.callEvent()).thenReturn(true))){
            ItemStack main=spy(f.stack);var source=mock(net.minecraft.world.damagesource.DamageSource.class);headSource(f,main,source);ItemStack off=mock(ItemStack.class);doReturn(off).when(f.attacker).getItemBySlot(EquipmentSlot.OFFHAND);when(off.getDamageSource(f.attacker)).thenReturn(source);
            var value=new CompletableFuture<Float>();ench.when(()->net.minecraft.world.item.enchantment.EnchantmentHelper.carpetModifyDamageAsync(any(),eq(main),eq(f.victim),eq(source),eq(7F))).thenReturn(value);
            when(f.victim.isPassenger()).thenAnswer(call->{assertSame(f.victim,f.current);return false;});
            boolean provisional=f.attacker.stabAttack(EquipmentSlot.OFFHAND,f.victim,7F,false,false,true);var actual=ScarpetAttackContinuations.pendingStabResult(f.attacker);assertTrue(provisional);assertNotNull(actual);f.drain();assertFalse(actual.isDone());value.complete(7F);f.drain();assertFalse(actual.get(3,TimeUnit.SECONDS));assertSame(actual,ScarpetAttackContinuations.pendingStabResult(f.attacker));
        }
    }
    @Test void completedStabBoolReceiptRemainsVisibleUntilNextSourceCall()throws Exception{
        try(Fixture f=new Fixture()){
            var result=CompletableFuture.completedFuture(false);ScarpetAttackContinuations.publishStab(f.attacker,result);assertSame(result,ScarpetAttackContinuations.pendingStabResult(f.attacker));
            var next=CompletableFuture.completedFuture(true);ScarpetAttackContinuations.publishStab(f.attacker,next);assertSame(next,ScarpetAttackContinuations.pendingStabResult(f.attacker));
        }
    }
    @Test void maceHeadUsesOriginalDoubleFallWorldWeaponAndPendingActualFloat()throws Exception{
        try(Fixture f=new Fixture();var ench=mockStatic(net.minecraft.world.item.enchantment.EnchantmentHelper.class,CALLS_REAL_METHODS)){
            f.attacker.fallDistance=3.3333333333333D;doReturn(false).when(f.attacker).isFallFlying();doReturn(false).when(f.attacker).isCreative();
            var source=mock(net.minecraft.world.damagesource.DamageSource.class);when(source.getDirectEntity()).thenReturn(f.attacker);var extra=new CompletableFuture<Float>();
            ench.when(()->net.minecraft.world.item.enchantment.EnchantmentHelper.carpetModifyFallBasedDamageAsync(any(),eq(f.stack),eq(f.victim),eq(source),eq(0F))).thenAnswer(call->{
                var admission=(ScarpetAttackEnchantments.SourceAdmission)call.getArgument(0);assertSame(f.from,admission.world());assertSame(f.attacker,admission.actualCaller());return extra;
            });
            var mace=mock(MaceItem.class,CALLS_REAL_METHODS);var result=mace.carpetGetAttackDamageBonusAsync(f.victim,5F,source);assertFalse(result.isDone());
            doReturn(f.to).when(f.attacker).level();extra.complete(1.1234F);assertEquals((float)(12D+2D*(3.3333333333333D-3D)+1.1234F*3.3333333333333D),result.get(3,TimeUnit.SECONDS));
        }
    }
    @Test void actualProjectileDeflectKeepsRealSourceIdentityAndOwnsSourceAimAfterEventChildren()throws Exception{
        try(Fixture f=new Fixture();var craft=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)){
            var projectile=mock(net.minecraft.world.entity.projectile.Projectile.class);when(projectile.level()).thenReturn(f.to);when(projectile.blockPosition()).thenReturn(BlockPos.ZERO);
            when(projectile.is(net.minecraft.tags.EntityTypeTags.REDIRECTABLE_PROJECTILE)).thenReturn(true);f.attach(projectile,mock(org.bukkit.craftbukkit.entity.CraftEntity.class));
            doReturn(UUID.randomUUID()).when(f.attacker).getUUID();doAnswer(call->{assertSame(f.attacker,f.current);f.order.add("aim");return new Vec3(1,2,3);}).when(f.attacker).getLookAngle();
            var source=mock(net.minecraft.world.damagesource.DamageSource.class);var event=new CompletableFuture<Void>();var actualChild=new CompletableFuture<Void>();
            craft.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(eq(projectile),eq(source),eq(4D),eq(false))).thenAnswer(call->{assertSame(projectile,f.current);f.order.add("event");ScarpetNativeWork.record(event);return false;});
            when(projectile.deflect(any(net.minecraft.world.entity.projectile.ProjectileDeflection.class),eq(f.attacker),any(),eq(true),eq(1D))).thenAnswer(call->{
                assertSame(projectile,f.current);assertSame(f.attacker,call.getArgument(1));assertNotNull(call.getArgument(2));f.order.add("deflect");
                net.minecraft.world.entity.projectile.ProjectileDeflection strategy=call.getArgument(0);strategy.deflect(projectile,f.attacker,mock(net.minecraft.util.RandomSource.class),new Vec3(1,1,1));ScarpetNativeWork.record(actualChild);return true;
            });
            doAnswer(call->{assertSame(projectile,f.current);assertEquals(new Vec3(1,2,3),call.getArgument(0));return null;}).when(projectile).setDeltaMovement(any(Vec3.class));
            var result=ScarpetNativeAttackHeads.deflect(f.attacker,projectile,source,4F);f.drain();assertEquals(List.of("event"),f.order);event.complete(null);f.drain();assertEquals(List.of("event","aim","deflect"),f.order);assertFalse(result.isDone());actualChild.complete(null);f.drain();assertTrue(result.get(3,TimeUnit.SECONDS));
        }
    }

}
