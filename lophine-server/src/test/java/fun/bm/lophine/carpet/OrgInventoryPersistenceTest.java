package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/** Actual inventory actors with serial ownership and successful writes whose selected readbacks fail. */
class OrgInventoryPersistenceTest {
    @TempDir Path directory;
    private static final NamespacedKey SOURCE = new NamespacedKey("lophine", "carpet_org_inventory_source");
    private static final NamespacedKey TARGET = new NamespacedKey("lophine", "carpet_org_inventory_target");
    private static final NamespacedKey HOLD = new NamespacedKey("lophine", "carpet_org_inventory_hold");

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        for (var item : List.of(Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT)) {
            try { item.builtInRegistryHolder().components(); }
            catch (NullPointerException unbound) { item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build()); }
        }
    }

    @Test void unknownTargetCreditKeepsBothSidesInputDetachedUntilHeldCustodyIsReadable() throws Exception {
        try (Fixture fixture = new Fixture(directory, false, true)) {
            fixture.unreadable.addAll(List.of(3, 4));
            fixture.start(); fixture.process(fixture.target);
            assertTrue(fixture.target.inventory.getItem(0).isEmpty());
            assertTrue(fixture.viewer.inventory.getItem(0).isEmpty());
            assertNotNull(fixture.target.pdc.get(HOLD));
            assertTrue(((String) fixture.target.pdc.get(TARGET)).contains(":target_hold:"));
            fixture.process(fixture.target);
            assertTrue(fixture.target.inventory.getItem(0).isEmpty());
            fixture.process(fixture.target); fixture.process(fixture.viewer);
            assertEquals(10, fixture.target.inventory.getItem(0).getCount());
            assertTrue(fixture.target.inventory.getItem(0).is(Items.DIAMOND));
            assertEquals(20, fixture.viewer.inventory.getItem(0).getCount());
            assertTrue(fixture.viewer.inventory.getItem(0).is(Items.EMERALD));
            assertNull(fixture.target.pdc.get(HOLD));
        }
    }

    @Test void unknownViewerCreditCannotRestorePaidInputOrOverwriteLaterBusinessItems() throws Exception {
        try (Fixture fixture = new Fixture(directory, false, true)) {
            fixture.start(); fixture.process(fixture.target);
            // Viewer reservation=1, target reservation=2, credit=3, commit marker=4, viewer credit=5.
            fixture.unreadable.add(5); fixture.process(fixture.viewer);
            assertTrue(fixture.viewer.inventory.getItem(0).isEmpty());
            assertNotNull(fixture.viewer.pdc.get(HOLD));
            assertTrue(((String) fixture.viewer.pdc.get(SOURCE)).contains(":source_hold:"));
            fixture.viewer.inventory.setItem(0, new ItemStack(Items.GOLD_INGOT, 3));
            fixture.process(fixture.viewer);
            assertTrue(fixture.viewer.inventory.getItem(0).is(Items.GOLD_INGOT));
            assertEquals(3, fixture.viewer.inventory.getItem(0).getCount());
            assertTrue(fixture.viewer.inventory.getItem(1).is(Items.EMERALD));
            assertEquals(20, fixture.viewer.inventory.getItem(1).getCount());
            assertNull(fixture.viewer.pdc.get(HOLD));
        }
    }

    @Test void aMergedPreexistingStackIsHeldWholeAndNeverBecomesUnknownCompensation() throws Exception {
        try (Fixture fixture = new Fixture(directory, false, true)) {
            fixture.start(); fixture.process(fixture.target);
            fixture.viewer.inventory.setItem(0, new ItemStack(Items.EMERALD, 20)); // A later legitimate pickup.
            fixture.unreadable.add(5); fixture.process(fixture.viewer);
            assertTrue(fixture.viewer.inventory.getItem(0).isEmpty()); // Existing 20 and incoming 20 are held together.
            assertNotNull(fixture.viewer.pdc.get(HOLD));
            fixture.process(fixture.viewer);
            assertEquals(40, fixture.viewer.inventory.getItem(0).getCount());
            assertTrue(fixture.viewer.inventory.getItem(0).is(Items.EMERALD));
        }
    }

    record Actor(UUID id, ServerPlayer player, Inventory inventory, Map<NamespacedKey, Object> pdc) {}

    static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final PlayerDataStorage storage = mock(PlayerDataStorage.class);
        final RegistryAccess.Frozen lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final Map<UUID, Actor> actors = new HashMap<>();
        record Scheduled(java.util.function.Consumer<Entity> work, java.util.function.Consumer<Entity> retired, long due) {}
        final Map<UUID, java.util.concurrent.ConcurrentLinkedQueue<Scheduled>> scheduled = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<UUID, java.util.concurrent.atomic.AtomicLong> clocks = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<UUID, CompoundTag> saved = new HashMap<>();
        final Set<Integer> unreadable = new HashSet<>();
        final AtomicReference<Entity> owner = new AtomicReference<>();
        final MockedStatic<TickThread> ticks;
        final Actor viewer, target;
        final Object coordinator;
        final Method process;
        final boolean serialCustody;
        int reads;

        Fixture(Path directory) throws Exception { this(directory, false); }
        Fixture(Path directory, boolean fakeTarget) throws Exception { this(directory, fakeTarget, false); }
        Fixture(Path directory, boolean fakeTarget, boolean serialCustody) throws Exception {
            this.serialCustody = serialCustody;
            PlayerList players = mock(PlayerList.class);
            var storageField = PlayerList.class.getField("playerIo"); storageField.setAccessible(true); storageField.set(players, storage);
            when(server.getPlayerList()).thenReturn(players); when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory);
            when(server.registryAccess()).thenReturn(lookup);
            viewer = actor(); target = actor(fakeTarget ? org.leavesmc.leaves.bot.ServerBot.class : ServerPlayer.class); when(players.getPlayers()).thenReturn(List.of(viewer.player, target.player));
            when(players.getPlayer(any(UUID.class))).thenAnswer(call -> { Actor actor = actors.get(call.getArgument(0)); return actor == null ? null : actor.player; });
            viewer.inventory.setItem(0, new ItemStack(Items.DIAMOND, 10)); target.inventory.setItem(0, new ItemStack(Items.EMERALD, 20));
            doAnswer(call -> {
                ServerPlayer player = call.getArgument(0);
                CompoundTag tag = OrgInventoryTransfers.inventoryTag(player), values = new CompoundTag();
                actors.get(player.getUUID()).pdc.forEach((key, value) -> { if (value instanceof byte[] bytes) values.putByteArray(key.toString(), bytes.clone()); else values.putString(key.toString(), (String) value); });
                tag.put("BukkitValues", values); saved.put(player.getUUID(), tag); return null;
            }).when(storage).save(any(Player.class));
            when(storage.load(any(NameAndId.class))).thenAnswer(call -> { reads++; NameAndId identity = call.getArgument(0); return unreadable.contains(reads) ? Optional.empty() : Optional.ofNullable(saved.get(identity.id())).map(CompoundTag::copy); });
            if (fakeTarget) {
                org.leavesmc.leaves.bot.BotList bots = mock(org.leavesmc.leaves.bot.BotList.class); when(server.getBotList()).thenReturn(bots);
                when(bots.saveCarpetBotState(any(org.leavesmc.leaves.bot.ServerBot.class))).thenAnswer(call -> {
                    ServerPlayer bot = call.getArgument(0); storage.save(bot); return storage.load(bot.nameAndId());
                });
                when(players.getPlayers()).thenReturn(List.of(viewer.player)); // The legacy bot is intentionally absent from the human/native-Carpet roster.
            }
            Method coordinatorFactory = OrgInventoryTransfers.class.getDeclaredMethod("coordinator", MinecraftServer.class); coordinatorFactory.setAccessible(true);
            coordinator = coordinatorFactory.invoke(null, server);
            process = coordinator.getClass().getDeclaredMethod("process", ServerPlayer.class); process.setAccessible(true);
            // Register only after all fallible fixture construction has completed.
            ticks = mockStatic(TickThread.class);
            try {
                ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> call.getArgument(0) == owner.get());
            } catch (RuntimeException | Error failure) {
                ticks.close();
                throw failure;
            }
        }

        Actor actor() throws Exception { return actor(ServerPlayer.class); }
        Actor actor(Class<? extends ServerPlayer> type) throws Exception {
            UUID id = UUID.randomUUID(); ServerPlayer player = mock(type);
            CraftPlayer bukkit = org.leavesmc.leaves.bot.ServerBot.class.isAssignableFrom(type)
                ? mock(org.leavesmc.leaves.entity.bot.CraftBot.class) : mock(CraftPlayer.class);
            CraftPersistentDataContainer pdc = mock(CraftPersistentDataContainer.class); Map<NamespacedKey, Object> data = new HashMap<>();
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> data.get(call.getArgument(0)));
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.BYTE_ARRAY))).thenAnswer(call -> data.get(call.getArgument(0)));
            when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> data.get(call.getArgument(0)) instanceof String);
            when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE_ARRAY))).thenAnswer(call -> data.get(call.getArgument(0)) instanceof byte[]);
            doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
            doAnswer(call -> { byte[] bytes = call.getArgument(2); data.put(call.getArgument(0), bytes.clone()); return null; }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE_ARRAY), any(byte[].class));
            doAnswer(call -> { data.remove(call.getArgument(0)); return null; }).when(pdc).remove(any(NamespacedKey.class));
            when(bukkit.getPersistentDataContainer()).thenReturn(pdc); when(player.getBukkitEntity()).thenReturn(bukkit);
            when(player.getUUID()).thenReturn(id); when(player.nameAndId()).thenReturn(new NameAndId(id, "inventory_test")); when(player.getDisplayName()).thenReturn(net.minecraft.network.chat.Component.literal("inventory_test")); when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(id,"inventory_test")); when(player.registryAccess()).thenReturn(lookup);
            ServerLevel level = mock(ServerLevel.class); when(level.getServer()).thenReturn(server); when(player.level()).thenReturn(level); when(player.carpetSpawnServer()).thenReturn(server);
            Inventory inventory = new Inventory(player, new EntityEquipment()); when(player.getInventory()).thenReturn(inventory);
            player.enderChestSlotCount = -1; PlayerEnderChestContainer ender = new PlayerEnderChestContainer(player); when(player.getEnderChestInventory()).thenReturn(ender);
            var menuField = Player.class.getField("inventoryMenu"); menuField.setAccessible(true); menuField.set(player, mock(InventoryMenu.class)); player.containerMenu = mock(AbstractContainerMenu.class);
            AtomicReference<ItemStack> cursor = new AtomicReference<>(ItemStack.EMPTY);
            when(player.containerMenu.getCarried()).thenAnswer(call -> cursor.get()); doAnswer(call -> { cursor.set(call.getArgument(0)); return null; }).when(player.containerMenu).setCarried(any(ItemStack.class));
            io.papermc.paper.threadedregions.EntityScheduler scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var schedulerField = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler"); schedulerField.setAccessible(true); schedulerField.set(bukkit, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                long now = clocks.computeIfAbsent(id, ignored -> new java.util.concurrent.atomic.AtomicLong()).get();
                scheduled.computeIfAbsent(id, ignored -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(new Scheduled(call.getArgument(0), call.getArgument(1), now + Math.max(1L, call.<Long>getArgument(2)))); return true;
            });
            Actor result = new Actor(id, player, inventory, data); actors.put(id, result); return result;
        }

        void start() { start(false); }
        void start(boolean merge) {
            owner.set(viewer.player);
            List<ItemStack> viewerBefore = OrgInventoryTransfers.viewerState(viewer.player), viewerAfter = OrgInventoryTransfers.copies(viewerBefore);
            owner.set(target.player); List<ItemStack> targetBefore = OrgInventoryTransfers.targetState(target.player, false), targetAfter = OrgInventoryTransfers.copies(targetBefore);
            viewerAfter.set(0, ItemStack.EMPTY); viewerAfter.set(merge ? 1 : 0, new ItemStack(Items.EMERALD, merge ? 40 : 20)); targetAfter.set(0, new ItemStack(Items.DIAMOND, 10));
            owner.set(viewer.player);
            assertTrue(OrgInventoryTransfers.submit(viewer.player, target.id, false, targetBefore, targetAfter, viewerBefore, viewerAfter, List.of(), () -> {}));
            owner.set(null);
            try { process(viewer); } catch (Exception failure) { throw new AssertionError(failure); }
        }
        void process(Actor actor) throws Exception {
            if (serialCustody) {
                // Financial fault tests stop on each real owner/custody boundary. An
                // inline snapshot must not let a broad two-tick drain consume the next
                // credit/readback/retry before that test has installed its fault.
                for (int turn=0;turn<32&&preparing();turn++) {
                    drainOne(viewer);
                    if (!preparing()) break;
                    drainOne(target);
                }
                owner.set(actor.player);
                try {
                    boolean inventoryParticipant = OrgInventoryTransfers.participantBlocked(actor.player);
                    process.invoke(coordinator,actor.player);
                    // XP/file actor fixtures share these queues but have no inventory
                    // participant; advance one actual callback, never its newly queued retry.
                    if (!inventoryParticipant) drainOne(actor);
                } finally { owner.set(null); }
                return;
            }
            // PREPARING only schedules both actors' quiescence acknowledgements. Drain
            // those real owner queues before advancing the single requested custody
            // phase, preserving the tests' exact debit/readback fault boundaries.
            var transactionsField=coordinator.getClass().getDeclaredField("transactions");transactionsField.setAccessible(true);
            var transactions=(Map<?,?>)transactionsField.get(coordinator);
            for(int tick=0;tick<8;tick++){
                boolean preparing=false;for(Object transaction:transactions.values()){var phase=transaction.getClass().getDeclaredField("phase");phase.setAccessible(true);if(phase.get(transaction).equals("preparing")){preparing=true;break;}}
                if(!preparing)break;
                drain(viewer);drain(target);
            }
            owner.set(actor.player);try{process.invoke(coordinator,actor.player);drain(actor);}finally{owner.set(null);}
        }
        private boolean preparing() throws Exception {
            var field=coordinator.getClass().getDeclaredField("transactions");field.setAccessible(true);
            for(Object transaction:((Map<?,?>)field.get(coordinator)).values()){
                var phase=transaction.getClass().getDeclaredField("phase");phase.setAccessible(true);
                if(phase.get(transaction).equals("preparing"))return true;
            }
            return false;
        }
        private void drainOne(Actor actor) {
            owner.set(actor.player);
            var queue=scheduled.get(actor.id);if(queue==null)return;
            long now=clocks.computeIfAbsent(actor.id, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
            int count=queue.size();
            for(int index=0;index<count;index++){
                Scheduled task=queue.poll();if(task==null)return;
                if(task.due<=now){task.work.accept(actor.player);return;}
                queue.add(task);
            }
        }
        void drain(Actor actor) {
            owner.set(actor.player); var queue=scheduled.get(actor.id); if(queue==null)return;
            // Two owner ticks cover dispatch -> inventory gate. A deferred alias retry queued
            // by this pass belongs to a later tick, so it cannot starve the other owner here.
            var clock=clocks.computeIfAbsent(actor.id, ignored -> new java.util.concurrent.atomic.AtomicLong());
            for(int pass=0;pass<2;pass++){
                long now=clock.incrementAndGet(); int count=queue.size();
                for(int index=0;index<count;index++){Scheduled task=queue.poll();if(task==null)break;if(task.due<=now)task.work.accept(actor.player);else queue.add(task);}
            }
        }
        @Override public void close() { try { actors.values().forEach(actor -> {var queue=scheduled.get(actor.id);if(queue==null)return;Scheduled task;while((task=queue.poll())!=null)if(task.retired!=null)task.retired.accept(actor.player);}); } finally { owner.set(null); ticks.close(); } }
    }
}
