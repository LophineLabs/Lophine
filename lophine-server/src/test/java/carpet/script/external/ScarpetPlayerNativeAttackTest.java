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
public class ScarpetPlayerNativeAttackTest {
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
    @SuppressWarnings("unchecked") private static <T> CompletableFuture<T> invoke(Player player,String name,Class<?>[] signature,Object... values) throws Exception {
        var method=Player.class.getDeclaredMethod(name,signature);method.setAccessible(true);return (CompletableFuture<T>)method.invoke(player,values);
    }
    @Test void actualPlayerAfterHurtOwnsForeignKnockbackBeforeSourceSlowItemAndFreshHealthStats() throws Exception {
        try(Fixture f=new Fixture()){
            var child=new CompletableFuture<Void>();var source=mock(net.minecraft.world.damagesource.DamageSource.class);when(source.getEntity()).thenReturn(f.attacker);
            doAnswer(call->{assertSame(f.victim,f.current);assertEquals(1D,(Double)call.getArgument(0));f.order.add("knock");ScarpetNativeWork.record(child);return null;})
                .when(f.victim).knockback(anyDouble(),anyDouble(),anyDouble(),eq(source),anyFloat(),anyBoolean(),eq(f.attacker),any());
            when(f.victim.getHealth()).thenAnswer(call->{assertSame(f.victim,f.current);f.order.add("health");return 7F;});
            var actual=invoke(f.attacker,"carpetAttackAfterHurtAsync",new Class<?>[]{Entity.class,ItemStack.class,net.minecraft.world.damagesource.DamageSource.class,Vec3.class,float.class,float.class,float.class,float.class,float.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class},
                f.victim,f.stack,source,Vec3.ZERO,3F,3F,1F,0F,10F,false,false,false,true,true);
            f.drain();assertEquals(List.of("knock"),f.order);assertFalse(actual.isDone());child.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);
            assertEquals(List.of("knock","slow","health","stat:30","food","post"),f.order);
        }
    }
    @Test void actualNativeKnockbackFailureCannotRunTheSourceRemainingAttack() throws Exception {
        try(Fixture f=new Fixture()){
            var child=new CompletableFuture<Void>();var problem=new IllegalStateException("actual native knock");
            doAnswer(call->{ScarpetNativeWork.record(child);throw problem;}).when(f.victim).knockback(anyDouble(),anyDouble(),anyDouble(),any(),anyFloat(),anyBoolean(),eq(f.attacker),any());
            var actual=f.attacker.carpetCauseExtraKnockbackAsync(f.victim,1F,Vec3.ZERO,mock(net.minecraft.world.damagesource.DamageSource.class),3F,true);
            f.drain();assertFalse(actual.isDone());child.complete(null);f.drain();assertSame(problem,assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS)).getCause());assertTrue(f.order.isEmpty());
        }
    }
    @Test void realMaceUsesFreshVictimOwnedShapeAfterItsActualEventChildrenThenSourceWorldBody() throws Exception {
        var eventChild=new CompletableFuture<Void>();
        try(Fixture f=new Fixture();var events=mockConstruction(io.papermc.paper.event.entity.EntityAttemptSmashAttackEvent.class,(event,context)->{
            when(event.callEvent()).thenAnswer(call->{f.order.add("event");ScarpetNativeWork.record(eventChild);return true;});when(event.getResult()).thenReturn(org.bukkit.event.Event.Result.ALLOW);
        })){ 
            when(f.victim.onGround()).thenAnswer(call->{assertSame(f.victim,f.current);f.order.add("ground");return true;});
            doReturn(false).when(f.attacker).isIgnoringFallDamageFromCurrentImpulse();doNothing().when(f.attacker).setIgnoreFallDamageFromCurrentImpulse(anyBoolean(),any());
            when(f.from.getEntitiesOfClass(eq(LivingEntity.class),any(AABB.class))).thenAnswer(call->{f.order.add("query");return List.of();});
            var mace=mock(MaceItem.class,CALLS_REAL_METHODS);var actual=mace.carpetHurtEnemyAsync(f.stack,f.victim,f.attacker);
            assertEquals(List.of("event"),f.order);f.drain();assertFalse(actual.isDone());eventChild.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);assertEquals(List.of("event","slow","ground","query"),f.order);
            assertNull(ScarpetNativeAttackBodies.currentMace(f.victim));
        }
    }
    private static ServerPlayer nearby(Fixture f,String name,double x) throws Exception {
        var near=mock(ServerPlayer.class);when(near.level()).thenReturn(f.from);when(near.blockPosition()).thenReturn(BlockPos.ZERO);
        when(near.position()).thenReturn(new Vec3(x,0,0));when(near.getDeltaMovement()).thenReturn(Vec3.ZERO);
        near.connection=mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        f.attach(near,mock(org.bukkit.craftbukkit.entity.CraftPlayer.class));doReturn(null).when(f.attacker).getTeam();
        doAnswer(call->{assertSame(near,f.current);f.order.add(name+" packet");return null;}).when(near.connection).send(any(net.minecraft.network.protocol.Packet.class));
        return near;
    }
    @Test void realMaceFiltersAllRealOwnersThenWaitsEachPushChildrenBeforeItsPacketAndNextPush() throws Exception {
        try(Fixture f=new Fixture();var events=mockConstruction(io.papermc.paper.event.entity.EntityAttemptSmashAttackEvent.class,(event,context)->{
            when(event.callEvent()).thenReturn(true);when(event.getResult()).thenReturn(org.bukkit.event.Event.Result.ALLOW);
        })){
            var first=nearby(f,"first",1D);var second=nearby(f,"second",2D);var pushChild=new CompletableFuture<Void>();var packetChild=new CompletableFuture<Void>();
            when(first.isSpectator()).thenAnswer(call->{assertSame(first,f.current);f.order.add("first filter");return false;});
            when(second.isSpectator()).thenAnswer(call->{assertSame(second,f.current);f.order.add("second filter");return false;});
            doReturn(false).when(f.attacker).isIgnoringFallDamageFromCurrentImpulse();doNothing().when(f.attacker).setIgnoreFallDamageFromCurrentImpulse(anyBoolean(),any());
            when(f.from.getEntitiesOfClass(eq(LivingEntity.class),any(AABB.class))).thenReturn(List.of(first,second));
            doAnswer(call->{assertSame(first,f.current);f.order.add("first push");ScarpetNativeWork.record(pushChild);return null;}).when(first).push(anyDouble(),anyDouble(),anyDouble(),eq(f.attacker));
            doAnswer(call->{assertSame(second,f.current);f.order.add("second push");return null;}).when(second).push(anyDouble(),anyDouble(),anyDouble(),eq(f.attacker));
            doAnswer(call->{assertSame(first,f.current);f.order.add("first packet");ScarpetNativeWork.record(packetChild);return null;}).when(first.connection).send(any(net.minecraft.network.protocol.Packet.class));
            var actual=mock(MaceItem.class,CALLS_REAL_METHODS).carpetHurtEnemyAsync(f.stack,f.victim,f.attacker);f.drain();
            assertEquals(List.of("slow","first filter","second filter","first push"),f.order);assertFalse(actual.isDone());
            pushChild.complete(null);f.drain();assertEquals("first packet",f.order.getLast());assertFalse(actual.isDone());
            packetChild.complete(null);f.drain();actual.get(3,TimeUnit.SECONDS);
            assertEquals(List.of("slow","first filter","second filter","first push","first packet","second push","second packet"),f.order);
        }
    }
    @Test void realMaceNativePushFailureWaitsItsChildrenAndSuppressesPacketAndLaterPush() throws Exception {
        try(Fixture f=new Fixture();var events=mockConstruction(io.papermc.paper.event.entity.EntityAttemptSmashAttackEvent.class,(event,context)->{
            when(event.callEvent()).thenReturn(true);when(event.getResult()).thenReturn(org.bukkit.event.Event.Result.ALLOW);
        })){
            var first=nearby(f,"first",1D);var second=nearby(f,"second",2D);var pushChild=new CompletableFuture<Void>();var problem=new IllegalStateException("native push");
            doReturn(false).when(f.attacker).isIgnoringFallDamageFromCurrentImpulse();doNothing().when(f.attacker).setIgnoreFallDamageFromCurrentImpulse(anyBoolean(),any());
            when(f.from.getEntitiesOfClass(eq(LivingEntity.class),any(AABB.class))).thenReturn(List.of(first,second));
            doAnswer(call->{f.order.add("first push");ScarpetNativeWork.record(pushChild);throw problem;}).when(first).push(anyDouble(),anyDouble(),anyDouble(),eq(f.attacker));
            doAnswer(call->{f.order.add("second push");return null;}).when(second).push(anyDouble(),anyDouble(),anyDouble(),eq(f.attacker));
            var actual=mock(MaceItem.class,CALLS_REAL_METHODS).carpetHurtEnemyAsync(f.stack,f.victim,f.attacker);f.drain();assertFalse(actual.isDone());
            pushChild.complete(null);f.drain();assertSame(problem,assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS)).getCause());
            assertEquals(List.of("slow","first push"),f.order);
        }
    }
    @Test void realMaceRetainsPrefixWorldIfSourceMovesBeforeForeignGroundAndSpatialQuery() throws Exception {
        try(Fixture f=new Fixture();var events=mockConstruction(io.papermc.paper.event.entity.EntityAttemptSmashAttackEvent.class,(event,context)->{
            when(event.callEvent()).thenReturn(true);when(event.getResult()).thenReturn(org.bukkit.event.Event.Result.ALLOW);
        })){
            doReturn(false).when(f.attacker).isIgnoringFallDamageFromCurrentImpulse();doNothing().when(f.attacker).setIgnoreFallDamageFromCurrentImpulse(anyBoolean(),any());
            doAnswer(call->{assertSame(f.attacker,f.current);f.order.add("slow");doReturn(f.to).when(f.attacker).level();return null;}).when(f.attacker).setDeltaMovement(any(Vec3.class));
            when(f.victim.onGround()).thenAnswer(call->{assertSame(f.victim,f.current);assertEquals(List.of("slow"),f.order);f.order.add("ground");return true;});
            when(f.from.getEntitiesOfClass(eq(LivingEntity.class),any(AABB.class))).thenAnswer(call->{f.order.add("original query");return List.of();});
            when(f.to.getEntitiesOfClass(eq(LivingEntity.class),any(AABB.class))).thenThrow(new AssertionError("replacement world"));
            var actual=mock(MaceItem.class,CALLS_REAL_METHODS).carpetHurtEnemyAsync(f.stack,f.victim,f.attacker);f.drain();actual.get(3,TimeUnit.SECONDS);
            assertEquals(List.of("slow","ground","original query"),f.order);
        }
    }
    @Test void actualStabReturnsFalseAfterForeignOwnedPassengerEligibilityWhenItHasNoNativeEffect() throws Exception {
        try(Fixture f=new Fixture()){
            when(f.victim.isPassenger()).thenAnswer(call->{assertSame(f.victim,f.current);f.order.add("passenger");return false;});
            var result=invoke(f.attacker,"carpetStabAfterHurtAsync",new Class<?>[]{Entity.class,ItemStack.class,net.minecraft.world.damagesource.DamageSource.class,Vec3.class,float.class,float.class,float.class,boolean.class,boolean.class,boolean.class,boolean.class},
                f.victim,f.stack,mock(net.minecraft.world.damagesource.DamageSource.class),Vec3.ZERO,0F,0F,10F,false,false,true,false);
            assertFalse(result.isDone());f.drain();assertEquals(false,result.get(3,TimeUnit.SECONDS));assertEquals(List.of("passenger"),f.order);
        }
    }
    @Test void nativeAllianceBaseReadsBothRealTeamsOnTheirActualOwners() throws Exception {
        try(Fixture f=new Fixture()){
            doAnswer(call->{assertSame(f.attacker,f.current);f.order.add("source team");return null;}).when(f.attacker).getTeam();
            doAnswer(call->{assertSame(f.victim,f.current);f.order.add("victim team");return null;}).when(f.victim).getTeam();
            doCallRealMethod().when(f.victim).isAlliedTo(nullable(net.minecraft.world.scores.Team.class));
            var result=ScarpetEntityAllies.allied(f.attacker,f.victim);assertFalse(result.isDone());f.drain();assertFalse(result.get(3,TimeUnit.SECONDS));
            assertEquals(List.of("victim team","source team","source team","victim team"),f.order);
        }
    }
}
