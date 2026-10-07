package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.equine.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Calls production polymorphic death boundaries with real pending Native children. No live server. */
public class ScarpetNativeDeathCustomTest {
    @BeforeAll static void bootstrap(){
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        for(Item item:List.of(Items.STONE,Items.DIRT,Items.IRON_SWORD,Items.DIAMOND_AXE,Items.NETHER_STAR)){
            var components=net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,64);
            if(item==Items.IRON_SWORD)components.set(net.minecraft.core.component.DataComponents.MAX_DAMAGE,250).set(net.minecraft.core.component.DataComponents.DAMAGE,0);
            item.builtInRegistryHolder().bindComponents(components.build());
        }
    }
    static void field(Object target,String name,Object value)throws Exception{
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);return;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel world=mock(ServerLevel.class),foreign=mock(ServerLevel.class);
        final DamageSource damage=mock(DamageSource.class);
        final RandomSource random=mock(RandomSource.class),worldRandom=mock(RandomSource.class);
        final List<String> order=new ArrayList<>();final List<ItemStack> drops=new ArrayList<>();
        final Queue<CompletableFuture<Void>> children=new ArrayDeque<>();
        final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final org.mockito.MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);
        final org.mockito.MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        Fixture(){
            when(world.getServer()).thenReturn(server);when(foreign.getServer()).thenReturn(server);when(world.getRandom()).thenReturn(worldRandom);
            servers.when(MinecraftServer::getServer).thenReturn(server);ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(()->TickThread.isTickThreadFor(eq(world),any(BlockPos.class))).thenReturn(true);
            leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);return CompletableFuture.completedFuture(action.apply(null));
            });
            leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runLoadedValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);return CompletableFuture.completedFuture(action.apply(null));
            });ScarpetRuntime.of(server);
        }
        <T extends LivingEntity>T entity(Class<T> type)throws Exception{
            T value=mock(type,CALLS_REAL_METHODS);doReturn(world).when(value).level();doReturn(new BlockPos(17,70,21)).when(value).blockPosition();doReturn(new Vec3(17,70,21)).when(value).position();
            doReturn(random).when(value).getRandom();doReturn(ItemStack.EMPTY).when(value).getItemBySlot(any());
            field(value,"random",random);value.postDeathEventTasks=new ArrayList<>();
            if(value instanceof Mob mob){field(mob,"droppedEquipmentSlots",new HashSet<EquipmentSlot>());var chances=new EnumMap<EquipmentSlot,Float>(EquipmentSlot.class);for(var slot:EquipmentSlot.VALUES)chances.put(slot,0F);field(mob,"dropChances",new DropChances(chances));}
            doAnswer(call->{ItemStack actual=call.getArgument(1);drops.add(actual);order.add("drop:"+actual.getItem().toString());if(!children.isEmpty())ScarpetNativeWork.record(children.remove());return null;}).when(value).spawnAtLocation(eq(world),any(ItemStack.class));
            return value;
        }
        CompletableFuture<Void> child(){var child=new CompletableFuture<Void>();children.add(child);return child;}
        CompletableFuture<Integer> nativeBody(LivingEntity owner,Runnable body){return ScarpetNativeWork.observeNative(owner,()->{body.run();return 42;});}
        void zeroChanceExcept(Mob mob,EquipmentSlot slot,float value)throws Exception{var map=new EnumMap<EquipmentSlot,Float>(EquipmentSlot.class);for(var s:EquipmentSlot.VALUES)map.put(s,s==slot?value:0F);field(mob,"dropChances",new DropChances(map));}
        @Override public void close(){ScarpetRuntime.beginShutdown(server,()->{});leases.close();servers.close();ticks.close();}
    }
    @Test void mobEquipmentUsesRealSourceOriginalWorldRngAndStackAndWaitsDropChild()throws Exception{
        try(Fixture f=new Fixture();var ench=mockStatic(EnchantmentHelper.class,CALLS_REAL_METHODS)){
            Mob mob=f.entity(Mob.class);LivingEntity source=mock(LivingEntity.class);when(source.level()).thenReturn(f.foreign);when(f.damage.getEntity()).thenReturn(source);
            ItemStack actual=new ItemStack(Items.IRON_SWORD);doReturn(actual).when(mob).getItemBySlot(EquipmentSlot.MAINHAND);f.zeroChanceExcept(mob,EquipmentSlot.MAINHAND,.4F);
            var chance=new CompletableFuture<Float>();var spawn=f.child();
            ench.when(()->EnchantmentHelper.carpetProcessEquipmentDropChanceAsync(any(),eq(source),eq(f.damage),eq(.4F))).thenAnswer(call->{
                var admission=(ScarpetAttackEnchantments.SourceAdmission)call.getArgument(0);assertSame(f.world,admission.world());assertSame(f.worldRandom,admission.contextRandom());assertSame(mob,admission.actualCaller());f.order.add("chance");return chance;
            });
            var root=f.nativeBody(mob,()->ScarpetNativeWork.record(mob.carpetDropCustomDeathLootAsync(f.world,f.damage,true)));
            assertEquals(List.of("chance"),f.order);assertFalse(root.isDone());chance.complete(1F);
            assertEquals(1,f.drops.size());assertSame(actual,f.drops.getFirst());assertTrue(mob.postDeathEventTasks.isEmpty());assertFalse(root.isDone());spawn.complete(null);
            assertEquals(42,root.get(3,TimeUnit.SECONDS));assertEquals(1,mob.postDeathEventTasks.size());verify(f.random).nextFloat();verify(f.random,times(2)).nextInt(anyInt());
        }
    }
    @Test void genuineMobDropFailureDoesNotMarkSlotOrEnqueueClear()throws Exception{
        try(Fixture f=new Fixture()){
            Mob mob=f.entity(Mob.class);ItemStack stack=new ItemStack(Items.STONE);doReturn(stack).when(mob).getItemBySlot(EquipmentSlot.MAINHAND);f.zeroChanceExcept(mob,EquipmentSlot.MAINHAND,2F);
            var child=f.child();var problem=new IllegalStateException("real native spawn");var root=f.nativeBody(mob,()->ScarpetNativeWork.record(mob.carpetDropCustomDeathLootAsync(f.world,f.damage,false)));
            child.completeExceptionally(problem);assertSame(problem,assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause());assertTrue(mob.postDeathEventTasks.isEmpty());
        }
    }
    @Test void piglinInventoryDropsWaitEachTrueChildBeforeClearTask()throws Exception{
        try(Fixture f=new Fixture()){
            Piglin piglin=f.entity(Piglin.class);var inv=new SimpleContainer(new ItemStack(Items.STONE),new ItemStack(Items.DIRT));field(piglin,"inventory",inv);var one=f.child();var two=f.child();
            var root=f.nativeBody(piglin,()->ScarpetNativeWork.record(piglin.carpetDropCustomDeathLootAsync(f.world,f.damage,false)));
            assertEquals(1,f.drops.size());assertTrue(piglin.postDeathEventTasks.isEmpty());one.complete(null);assertEquals(2,f.drops.size());assertFalse(root.isDone());assertTrue(piglin.postDeathEventTasks.isEmpty());two.complete(null);
            assertEquals(42,root.get(3,TimeUnit.SECONDS));assertEquals(1,piglin.postDeathEventTasks.size());assertFalse(inv.isEmpty());piglin.postDeathEventTasks.getFirst().run();assertTrue(inv.isEmpty());
        }
    }
    @Test void guestOnlyPiglinChildStillCompletesAcceptedSecondDropAndRawRootFails()throws Exception{
        try(Fixture f=new Fixture()){
            Piglin piglin=f.entity(Piglin.class);field(piglin,"inventory",new SimpleContainer(new ItemStack(Items.STONE),new ItemStack(Items.DIRT)));var guest=new CompletableFuture<Void>();
            doAnswer(call->{f.drops.add(call.getArgument(1));if(f.drops.size()==1)ScarpetNativeWork.recordGuest(guest);return null;}).when(piglin).spawnAtLocation(eq(f.world),any(ItemStack.class));
            var root=f.nativeBody(piglin,()->ScarpetNativeWork.record(piglin.carpetDropCustomDeathLootAsync(f.world,f.damage,false)));assertEquals(1,f.drops.size());guest.completeExceptionally(new IllegalArgumentException("guest script"));
            var failure=assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause();assertTrue(ScarpetNativeWork.onlyGuestFailure(failure));assertEquals(2,f.drops.size());assertEquals(1,piglin.postDeathEventTasks.size());
        }
    }
    @Test void nativePiglinFailureBlocksNextDropAndClearTask()throws Exception{
        try(Fixture f=new Fixture()){
            Piglin piglin=f.entity(Piglin.class);field(piglin,"inventory",new SimpleContainer(new ItemStack(Items.STONE),new ItemStack(Items.DIRT)));var one=f.child();
            var root=f.nativeBody(piglin,()->ScarpetNativeWork.record(piglin.carpetDropCustomDeathLootAsync(f.world,f.damage,false)));var problem=new IllegalStateException("native");one.completeExceptionally(problem);
            assertSame(problem,assertThrows(ExecutionException.class,()->root.get(3,TimeUnit.SECONDS)).getCause());assertEquals(1,f.drops.size());assertTrue(piglin.postDeathEventTasks.isEmpty());
        }
    }
    @Test void witherLifetimeRunsOnActualReturnedStarAfterTrueSpawnChildren()throws Exception{
        try(Fixture f=new Fixture()){
            WitherBoss wither=f.entity(WitherBoss.class);ItemEntity star=mock(ItemEntity.class);when(star.level()).thenReturn(f.world);when(star.blockPosition()).thenReturn(BlockPos.ZERO);var child=new CompletableFuture<Void>();
            doAnswer(call->{assertSame(f.world,call.getArgument(0));assertEquals(Items.NETHER_STAR,((ItemStack)call.getArgument(1)).getItem());ScarpetNativeWork.record(child);return star;}).when(wither).spawnAtLocation(eq(f.world),any(ItemStack.class),eq(Vec3.ZERO),any(Consumer.class));
            var root=f.nativeBody(wither,()->ScarpetNativeWork.record(wither.carpetDropCustomDeathLootAsync(f.world,f.damage,false)));verify(star,never()).setExtendedLifetime();assertFalse(root.isDone());child.complete(null);
            assertEquals(42,root.get(3,TimeUnit.SECONDS));verify(star).setExtendedLifetime();
        }
    }
    @Test void endermanKeepsActualCarriedStateToolRngAndOriginalLevel()throws Exception{
        try(Fixture f=new Fixture();var ench=mockStatic(EnchantmentHelper.class,CALLS_REAL_METHODS);var loot=mockStatic(ScarpetNativeDeathLoot.class,CALLS_REAL_METHODS)){
            Enderman enderman=f.entity(Enderman.class);var state=Blocks.STONE.defaultBlockState();doReturn(state).when(enderman).getCarriedBlock();var difficulty=mock(net.minecraft.world.DifficultyInstance.class);when(f.world.getCurrentDifficultyAt(new BlockPos(17,70,21))).thenReturn(difficulty);
            var items=new CompletableFuture<List<ItemStack>>();var tool=new java.util.concurrent.atomic.AtomicReference<ItemStack>();
            var access=f.world.registryAccess();
            ench.when(()->EnchantmentHelper.enchantItemFromProvider(any(),eq(access),eq(net.minecraft.world.item.enchantment.providers.VanillaEnchantmentProviders.ENDERMAN_LOOT_DROP),eq(difficulty),eq(f.random))).thenAnswer(call->{tool.set(call.getArgument(0));return null;});
            loot.when(()->ScarpetNativeDeathLoot.carriedBlockDrops(eq(state),any())).thenAnswer(call->{var params=(net.minecraft.world.level.storage.loot.LootParams.Builder)call.getArgument(1);assertSame(f.world,params.getLevel());assertSame(tool.get(),params.getParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL));assertSame(enderman,params.getParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY));return items;});
            var child=f.child();var stack=new ItemStack(Items.STONE);var root=f.nativeBody(enderman,()->ScarpetNativeWork.record(enderman.carpetDropCustomDeathLootAsync(f.world,f.damage,false)));
            if(root.isCompletedExceptionally())root.get();assertTrue(f.drops.isEmpty());items.complete(List.of(stack));if(root.isCompletedExceptionally())root.get();assertSame(stack,f.drops.getFirst());assertFalse(root.isDone());child.complete(null);assertEquals(42,root.get(3,TimeUnit.SECONDS));
        }
    }
    @Test void allayInventoryClearRegistrationPrecedesHandAndWaitsHandClear()throws Exception{
        try(Fixture f=new Fixture()){
            Allay allay=f.entity(Allay.class);var inv=new SimpleContainer(new ItemStack(Items.STONE));field(allay,"inventory",inv);ItemStack hand=new ItemStack(Items.DIRT);doReturn(hand).when(allay).getItemBySlot(EquipmentSlot.MAINHAND);var one=f.child();var two=f.child();
            var root=f.nativeBody(allay,()->ScarpetNativeWork.record(allay.carpetDropEquipmentAsync(f.world)));assertEquals(1,f.drops.size());assertTrue(allay.postDeathEventTasks.isEmpty());one.complete(null);assertEquals(2,f.drops.size());assertSame(hand,f.drops.getLast());assertEquals(1,allay.postDeathEventTasks.size());two.complete(null);
            assertEquals(42,root.get(3,TimeUnit.SECONDS));assertEquals(2,allay.postDeathEventTasks.size());
        }
    }
    @Test void chestedHorseDropsAllActualInventoryBeforeChestAndChestTask()throws Exception{
        try(Fixture f=new Fixture()){
            AbstractChestedHorse horse=f.entity(AbstractChestedHorse.class);field(horse,"inventory",new SimpleContainer(new ItemStack(Items.STONE),new ItemStack(Items.DIRT)));doReturn(true).when(horse).hasChest();var one=f.child();var two=f.child();var chest=new CompletableFuture<Void>();
            doAnswer(call->{f.order.add("chest");ScarpetNativeWork.record(chest);return null;}).when(horse).spawnAtLocation(eq(f.world),eq(Blocks.CHEST));
            var root=f.nativeBody(horse,()->ScarpetNativeWork.record(horse.carpetDropEquipmentAsync(f.world)));assertEquals(1,f.drops.size());one.complete(null);assertEquals(2,f.drops.size());assertFalse(f.order.contains("chest"));two.complete(null);assertTrue(f.order.contains("chest"));assertTrue(horse.postDeathEventTasks.isEmpty());chest.complete(null);
            assertEquals(42,root.get(3,TimeUnit.SECONDS));assertEquals(1,horse.postDeathEventTasks.size());
        }
    }
    @Test void copperPreservedEquipmentDoesNotDuplicateCustomDeathDrops()throws Exception{
        try(Fixture f=new Fixture()){
            CopperGolem copper=f.entity(CopperGolem.class);ItemStack hand=new ItemStack(Items.STONE);doReturn(hand).when(copper).getItemBySlot(EquipmentSlot.MAINHAND);f.zeroChanceExcept(copper,EquipmentSlot.MAINHAND,2F);copper.deathDropItems=new ArrayList<>();field(copper,"droppedEquipmentSlots",new HashSet<>(Set.of(EquipmentSlot.MAINHAND)));
            var root=f.nativeBody(copper,()->ScarpetNativeWork.record(copper.carpetDropEquipmentAsync(f.world)));assertEquals(42,root.get(3,TimeUnit.SECONDS));assertTrue(f.drops.isEmpty());assertTrue(copper.postDeathEventTasks.isEmpty());
        }
    }
    @Test void actualPlayerInventoryWaitsEachAddBeforeClearingAndEquipmentBeforeFinalClear()throws Exception{
        try(Fixture f=new Fixture()){
            Player player=f.entity(Player.class);EntityEquipment equipment=new EntityEquipment();ItemStack main=new ItemStack(Items.STONE),armor=new ItemStack(Items.DIRT);equipment.set(EquipmentSlot.HEAD,armor);Inventory inv=new Inventory(player,equipment);inv.setItem(0,main);
            ItemEntity one=mock(ItemEntity.class),two=mock(ItemEntity.class);when(one.level()).thenReturn(f.world);when(two.level()).thenReturn(f.world);when(one.blockPosition()).thenReturn(BlockPos.ZERO);when(two.blockPosition()).thenReturn(BlockPos.ZERO);
            doReturn(one).when(player).createItemStackToDrop(eq(main),eq(true),eq(false));doReturn(two).when(player).createItemStackToDrop(eq(armor),eq(true),eq(false));
            var addOne=new CompletableFuture<Void>();var addTwo=new CompletableFuture<Void>();when(f.world.addFreshEntity(any(Entity.class))).thenAnswer(call->{Entity drop=call.getArgument(0);f.order.add(drop==one?"main":"armor");ScarpetNativeWork.record(drop==one?addOne:addTwo);return true;});
            var root=f.nativeBody(player,()->inv.dropAll());assertEquals(List.of("main"),f.order);assertSame(main,inv.getItem(0));addOne.complete(null);assertTrue(inv.getItem(0).isEmpty());assertEquals(List.of("main","armor"),f.order);assertSame(armor,equipment.get(EquipmentSlot.HEAD));assertFalse(root.isDone());addTwo.complete(null);
            assertEquals(42,root.get(3,TimeUnit.SECONDS));assertTrue(equipment.get(EquipmentSlot.HEAD).isEmpty());
        }
    }
}
