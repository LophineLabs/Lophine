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
public class AmsGameplayParityTest {
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
        final org.mockito.MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
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

    @AfterEach void reset(){var c=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.class;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeOneHitKill=false;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.blowUpEverything=false;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.optimizedDragonRespawn=false;}
    static void enable(Fixture f){fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeOneHitKill=true;var abilities=new net.minecraft.world.entity.player.Abilities();abilities.instabuild=true;doReturn(abilities).when(f.attacker).getAbilities();doReturn(false).when(f.attacker).isShiftKeyDown();}
    @Test void actualCreativeHeadKeepsOriginalTargetWorldAndContinuesOriginalEligibilityAfterKillAndSoundChildren()throws Exception{
        try(var f=new Fixture();var events=mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class,(event,context)->when(event.callEvent()).thenAnswer(call->{f.order.add("pre");return false;}))){
            enable(f);var kill=new CompletableFuture<Void>();var sound=new CompletableFuture<Void>();
            doAnswer(call->{assertSame(f.victim,f.current);assertSame(f.to,call.getArgument(0));f.order.add("kill");ScarpetNativeWork.record(kill);return null;}).when(f.victim).kill(any());
            when(f.victim.isAttackable()).thenAnswer(call->{assertSame(f.victim,f.current);f.order.add("eligibility");return false;});
            doAnswer(call->{assertSame(f.attacker,f.current);f.order.add("crit");ScarpetNativeWork.record(sound);return null;}).when(f.attacker).carpetPlayServerSideSound(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT);
            var actual=f.attacker.carpetAttackNativeAsync(f.victim);f.drain();assertEquals(List.of("kill"),f.order);assertFalse(actual.isDone());when(f.victim.level()).thenReturn(f.from);kill.complete(null);f.drain();assertEquals(List.of("kill","crit"),f.order);assertFalse(actual.isDone());sound.complete(null);f.drain();assertEquals(List.of("kill","crit","eligibility","pre"),f.order);actual.get(3,TimeUnit.SECONDS);
        }
    }
    @Test void actualCreativeNativeKillFailureBlocksOriginalSoundAndEligibility()throws Exception{
        try(var f=new Fixture()){
            enable(f);var child=new CompletableFuture<Void>();var failure=new IllegalStateException("actual kill");doAnswer(call->{ScarpetNativeWork.record(child);throw failure;}).when(f.victim).kill(f.to);
            var actual=f.attacker.carpetAttackNativeAsync(f.victim);f.drain();assertFalse(actual.isDone());verify(f.victim,never()).isAttackable();child.complete(null);f.drain();assertSame(failure,assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS)).getCause());verify(f.attacker,never()).carpetPlayServerSideSound(any());verify(f.victim,never()).isAttackable();
        }
    }
    @Test void guestOnlyCreativeKillFailurePreservesRawGuestAndContinuesOriginalEligibility()throws Exception{
        try(var f=new Fixture();var events=mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class,(event,context)->when(event.callEvent()).thenReturn(false))){
            enable(f);var child=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.recordGuest(child);return null;}).when(f.victim).kill(f.to);doNothing().when(f.attacker).carpetPlayServerSideSound(any());when(f.victim.isAttackable()).thenReturn(false);
            var root=ScarpetNativeWork.observeNative(f.attacker,()->{var actual=f.attacker.carpetAttackNativeAsync(f.victim);ScarpetNativeWork.record(actual);return actual;});f.drain();assertFalse(root.isDone());child.completeExceptionally(new IllegalArgumentException("guest kill"));f.drain();var failure=assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause();assertTrue(ScarpetNativeWork.onlyGuestFailure(failure));verify(f.victim).isAttackable();verify(f.attacker).carpetPlayServerSideSound(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT);
        }
    }
    @Test void actualDragonPartPreservesRepeatedOriginalTargetIdentityThenActualParent()throws Exception{
        try(var f=new Fixture()){
            enable(f);var parent=mock(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class);var target=mock(net.minecraft.world.entity.boss.enderdragon.EnderDragonPart.class);var other=mock(net.minecraft.world.entity.boss.enderdragon.EnderDragonPart.class);
            Field field=net.minecraft.world.entity.boss.enderdragon.EnderDragonPart.class.getField("parentMob");field.setAccessible(true);field.set(target,parent);when(parent.getSubEntities()).thenReturn(new net.minecraft.world.entity.boss.enderdragon.EnderDragonPart[]{target,other});when(parent.level()).thenReturn(f.to);when(target.level()).thenReturn(f.to);when(parent.blockPosition()).thenReturn(BlockPos.ZERO);when(target.blockPosition()).thenReturn(BlockPos.ZERO);f.attach(parent,mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class));f.attach(target,mock(org.bukkit.craftbukkit.entity.CraftEntity.class));
            var first=new CompletableFuture<Void>();var second=new CompletableFuture<Void>();doAnswer(call->{assertSame(target,f.current);f.order.add("target");ScarpetNativeWork.record(f.order.size()==1?first:second);return null;}).when(target).kill(f.to);doAnswer(call->{assertSame(parent,f.current);f.order.add("parent");return null;}).when(parent).kill(f.to);doAnswer(call->{f.order.add("crit");return null;}).when(f.attacker).carpetPlayServerSideSound(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT);
            var actual=ScarpetNativeAttackHeads.creativeAMS(f.attacker,target);f.drain();assertEquals(List.of("target"),f.order);first.complete(null);f.drain();assertEquals(List.of("target","target"),f.order);second.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);assertEquals(List.of("target","target","parent","crit"),f.order);verify(other,never()).kill(any());
        }
    }
    @Test void creativeSweepFinishesEachOriginalCritSoundBeforeNextVictimAndFinalSweep()throws Exception{
        try(var f=new Fixture()){
            enable(f);doReturn(true).when(f.attacker).isShiftKeyDown();var other=mock(LivingEntity.class);when(other.level()).thenReturn(f.from);when(other.blockPosition()).thenReturn(BlockPos.ZERO);f.attach(other,mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class));when(f.victim.isAttackable()).thenReturn(true);when(other.isAttackable()).thenReturn(true);when(f.from.getEntitiesOfClass(eq(Entity.class),any(AABB.class))).thenReturn(List.of(f.victim,other));
            var crit=new CompletableFuture<Void>();doAnswer(call->{f.order.add("kill1");return null;}).when(f.victim).kill(f.to);doAnswer(call->{f.order.add("kill2");return null;}).when(other).kill(f.from);doAnswer(call->{f.order.add("crit");if(f.order.size()==2)ScarpetNativeWork.record(crit);return null;}).when(f.attacker).carpetPlayServerSideSound(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT);doAnswer(call->{f.order.add("sweep");return null;}).when(f.attacker).carpetPlayServerSideSound(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP);
            var actual=ScarpetNativeAttackHeads.creativeAMS(f.attacker,f.victim);f.drain();assertEquals(List.of("kill1","crit"),f.order);assertFalse(actual.isDone());crit.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);assertEquals(List.of("kill1","crit","kill2","crit","sweep"),f.order);
        }
    }
    @Test void blowUpEverythingAffectsActualBlockAndRetainsOriginalCustomFluidResistanceTail()throws Exception{
        var state=net.minecraft.world.level.material.Fluids.WATER.defaultFluidState();fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandCustomBlockBlastResistance="true";fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.enhancedWorldEater=-1;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.blowUpEverything=true;
        Field field=fun.bm.lophine.carpet.AmsManagementSettings.class.getDeclaredField("RESISTANCE");field.setAccessible(true);var map=(Map<net.minecraft.world.level.block.state.BlockState,Float>)field.get(null);var block=net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();Float previous=map.put(block,123F);
        try{assertEquals(0,net.minecraft.world.level.block.Blocks.OBSIDIAN.getExplosionResistance());assertEquals(123F,state.getExplosionResistance());}finally{if(previous==null)map.remove(block);else map.put(block,previous);fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandCustomBlockBlastResistance="false";}
    }
    @Test void actualOptimizedRespawnUsesOriginalFightOriginAndOriginalXForPartialSearch()throws Exception{
        var fight=mock(net.minecraft.world.level.dimension.end.EnderDragonFight.class,CALLS_REAL_METHODS);var world=mock(ServerLevel.class);var pattern=mock(net.minecraft.world.level.block.state.pattern.BlockPattern.class);var chunk=mock(net.minecraft.world.level.chunk.LevelChunk.class);when(chunk.getBlockEntities()).thenReturn(Map.of());when(world.getChunk(anyInt(),anyInt())).thenReturn(chunk);BlockPos origin=new BlockPos(120,7,-80);BlockPos podium=net.minecraft.world.level.dimension.end.EnderDragonFight.getPodiumLocation(origin);when(world.getHeightmapPos(any(),eq(podium))).thenReturn(new BlockPos(0,2,0));
        for(var entry:Map.of("level",world,"exitPortalPattern",pattern,"origin",origin).entrySet()){Field field=net.minecraft.world.level.dimension.end.EnderDragonFight.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(fight,entry.getValue());}
        for(String name:List.of("cachePortalChunkIteratorX","cachePortalChunkIteratorZ")){Field field=net.minecraft.world.level.dimension.end.EnderDragonFight.class.getDeclaredField(name);field.setAccessible(true);field.setInt(fight,-8);}Field y=net.minecraft.world.level.dimension.end.EnderDragonFight.class.getDeclaredField("cachePortalOriginIteratorY");y.setAccessible(true);y.setInt(fight,-1);fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.optimizedDragonRespawn=true;
        try(var helper=mockStatic(org.leavesmc.leaves.util.BlockPatternHelper.class)){assertNull(fight.findExitPortal());verify(world).getHeightmapPos(eq(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING),eq(podium));verify(pattern).find(world,new BlockPos(podium.getX(),2,podium.getZ()));helper.verify(()->org.leavesmc.leaves.util.BlockPatternHelper.partialSearchAround(pattern,world,new BlockPos(podium.getX(),1,podium.getZ())));helper.verify(()->org.leavesmc.leaves.util.BlockPatternHelper.partialSearchAround(pattern,world,new BlockPos(podium.getX(),0,podium.getZ())));}
    }
}
