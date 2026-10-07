package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes the real Native polymorphic death drop boundaries. The table completion is a controlled native receipt. */
public class ScarpetNativeDeathLootTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    static final Map<LivingEntity,Fixture> STATE=new IdentityHashMap<>();
    public abstract static class DropLiving extends LivingEntity {
        protected DropLiving(ServerLevel world){super(null,world);}
        @Override public boolean shouldDropLoot(ServerLevel world){return true;}
        @Override protected void dropCustomDeathLoot(ServerLevel world,DamageSource source,boolean killed){var state=STATE.get(this);state.order.add("custom");ScarpetNativeWork.record(state.custom);}
        @Override protected void dropEquipment(ServerLevel world){var state=STATE.get(this);state.order.add("equipment");ScarpetNativeWork.record(state.equipment);}
        public void nativeAll(ServerLevel world,DamageSource source){super.dropAllDeathLoot(world,source);}
    }
    public abstract static class DropMob extends Mob {
        protected DropMob(ServerLevel world){super(null,world);}
        public void nativeTable(ServerLevel world,DamageSource source){super.dropFromLootTable(world,source,false);}
    }
    public static class DropPlayer extends ServerPlayer {
        public DropPlayer(MinecraftServer server,ServerLevel world,com.mojang.authlib.GameProfile profile){super(server,world,profile,net.minecraft.server.level.ClientInformation.createDefault());}
        @Override public boolean shouldDropLoot(ServerLevel world){return true;}
    }
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel world=mock(ServerLevel.class);
        final DamageSource source=mock(DamageSource.class);
        final LootTable table=mock(LootTable.class);
        final ResourceKey<LootTable> key=ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,net.minecraft.resources.Identifier.parse("test:actual_death"));
        final CompletableFuture<Void> tableEnd=new CompletableFuture<>(),custom=new CompletableFuture<>(),equipment=new CompletableFuture<>();
        final AtomicReference<Consumer<ItemStack>> output=new AtomicReference<>();
        final List<String> order=new ArrayList<>();
        final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final org.mockito.MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);
        final org.mockito.MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        final org.mockito.MockedStatic<org.bukkit.craftbukkit.event.CraftEventFactory> craft=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class);
        final org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit=mockStatic(org.bukkit.Bukkit.class);
        final org.mockito.MockedStatic<io.papermc.paper.adventure.PaperAdventure> adventure=mockStatic(io.papermc.paper.adventure.PaperAdventure.class);
        Fixture(){
            when(world.getServer()).thenReturn(server);servers.when(MinecraftServer::getServer).thenReturn(server);
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(()->TickThread.isTickThreadFor(eq(world),any(BlockPos.class))).thenReturn(true);
            leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);return CompletableFuture.completedFuture(action.apply(null));
            });
            var nativeRegistries=mock(net.minecraft.server.ReloadableServerRegistries.Holder.class);when(server.reloadableRegistries()).thenReturn(nativeRegistries);when(nativeRegistries.getLootTable(key)).thenReturn(table);
            when(table.carpetGetRandomItemsNativeAsync(any(LootParams.class),anyLong(),any())).thenAnswer(call->{
                LootParams params=call.getArgument(0);assertSame(world,params.getLevel());assertEquals(new Vec3(17,70,21),params.contextMap().get(LootContextParams.ORIGIN));
                order.add("table");output.set(call.getArgument(2));return tableEnd;
            });
            var craftServer=mock(org.bukkit.craftbukkit.CraftServer.class);when(craftServer.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craftServer);
            adventure.when(()->io.papermc.paper.adventure.PaperAdventure.asAdventure(any(net.minecraft.network.chat.Component.class))).thenReturn(net.kyori.adventure.text.Component.empty());
            ScarpetRuntime.of(server);
        }
        <T extends LivingEntity> T init(T entity) throws Exception {
            doReturn(world).when(entity).level();doReturn(BlockPos.containing(17,70,21)).when(entity).blockPosition();doReturn(new Vec3(17,70,21)).when(entity).position();
            doReturn(Optional.of(key)).when(entity).getLootTable();doReturn(false).when(entity).isRemoved();doReturn(false).when(entity).isSpectator();
            var drops=LivingEntity.class.getField("postDeathEventTasks");drops.set(entity,new ArrayList<Runnable>());STATE.put(entity,this);return entity;
        }
        @Override public void close(){ScarpetRuntime.beginShutdown(server,()->{});STATE.entrySet().removeIf(e->e.getValue()==this);adventure.close();bukkit.close();craft.close();leases.close();servers.close();ticks.close();}
    }
    @Test void realLivingLootTableCustomEquipmentAndDeferredExperienceKeepTheirTrueOrder() throws Exception {
        try(Fixture f=new Fixture()){
            var target=f.init(mock(DropLiving.class,CALLS_REAL_METHODS));
            var actual=ScarpetNativeWork.observeNative(target,()->{target.nativeAll(f.world,f.source);return 42;});
            assertEquals(List.of("table"),f.order);assertFalse(actual.isDone());assertTrue(target.postDeathEventTasks.isEmpty());
            f.tableEnd.complete(null);assertEquals(List.of("table","custom"),f.order);assertFalse(actual.isDone());
            f.custom.complete(null);assertEquals(List.of("table","custom","equipment"),f.order);assertFalse(actual.isDone());
            f.equipment.complete(null);assertEquals(42,actual.get(3,TimeUnit.SECONDS));assertEquals(1,target.postDeathEventTasks.size());
        }
    }
    @Test void genuineNativeCustomDropFailureStopsEquipmentAndExperience() throws Exception {
        try(Fixture f=new Fixture()){
            var target=f.init(mock(DropLiving.class,CALLS_REAL_METHODS));var failure=new IllegalStateException("actual custom drop");
            var actual=ScarpetNativeWork.observeNative(target,()->{target.nativeAll(f.world,f.source);return 42;});f.tableEnd.complete(null);f.custom.completeExceptionally(failure);
            assertSame(failure,assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS)).getCause());assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
            assertEquals(List.of("table","custom"),f.order);assertTrue(target.postDeathEventTasks.isEmpty());
        }
    }
    @Test void actualMobLootTableOverrideAddsClearTaskAfterItsTrueTableReceipt() throws Exception {
        try(Fixture f=new Fixture()){
            var target=mock(DropMob.class,CALLS_REAL_METHODS);doReturn(f.world).when(target).level();doReturn(BlockPos.containing(17,70,21)).when(target).blockPosition();doReturn(new Vec3(17,70,21)).when(target).position();
            target.lootTable=Optional.of(f.key);target.postDeathEventTasks=new ArrayList<>();
            var actual=ScarpetNativeWork.observeNative(target,()->{target.nativeTable(f.world,f.source);return 9;});
            assertTrue(target.postDeathEventTasks.isEmpty());f.tableEnd.complete(null);assertEquals(9,actual.get(3,TimeUnit.SECONDS));
            assertEquals(1,target.postDeathEventTasks.size());assertEquals(Optional.of(f.key),target.lootTable);target.postDeathEventTasks.getFirst().run();assertTrue(target.lootTable.isEmpty());
        }
    }
    @Test void realPlayerDeathEventReceivesOnlyTheActualCompletedLootItems() throws Exception {
        try(Fixture f=new Fixture()){
            var player=f.init(mock(DropPlayer.class,CALLS_REAL_METHODS));doReturn(f.server).when(player).carpetSpawnServer();
            var rules=mock(net.minecraft.world.level.gamerules.GameRules.class);when(rules.get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY)).thenReturn(true);when(rules.get(net.minecraft.world.level.gamerules.GameRules.SHOW_DEATH_MESSAGES)).thenReturn(false);when(f.world.getGameRules()).thenReturn(rules);
            doReturn(mock(net.minecraft.world.entity.player.Inventory.class)).when(player).getInventory();
            var tracker=mock(net.minecraft.world.damagesource.CombatTracker.class);when(tracker.getDeathMessage()).thenReturn(net.minecraft.network.chat.Component.empty());doReturn(tracker).when(player).getCombatTracker();
            var event=mock(org.bukkit.event.entity.PlayerDeathEvent.class);when(event.isCancelled()).thenReturn(true);doReturn(1F).when(player).getHealth();
            f.craft.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerDeathEvent(eq(player),eq(f.source),anyList(),any(),anyBoolean(),eq(true))).thenAnswer(call->{
                List<Entity.DefaultDrop> drops=call.getArgument(2);assertEquals(1,drops.size());assertEquals(Items.STONE,drops.getFirst().item());f.order.add("Bukkit");return event;
            });
            var actual=ScarpetNativeWork.observeNative(player,()->{player.die(f.source);return 31;});assertEquals(List.of("table"),f.order);assertFalse(actual.isDone());
            var stackConstructor=ItemStack.class.getDeclaredConstructor(net.minecraft.core.Holder.class,int.class,net.minecraft.core.component.PatchedDataComponentMap.class);stackConstructor.setAccessible(true);
            var stone=stackConstructor.newInstance(Items.STONE.builtInRegistryHolder(),1,new net.minecraft.core.component.PatchedDataComponentMap(net.minecraft.core.component.DataComponentMap.EMPTY));
            f.output.get().accept(stone);assertEquals(List.of("table"),f.order);f.tableEnd.complete(null);
            assertEquals(31,actual.get(3,TimeUnit.SECONDS));assertEquals(List.of("table","Bukkit"),f.order);assertNull(player.deathDropItems);
        }
    }
}
