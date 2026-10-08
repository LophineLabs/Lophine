package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.CarpetRegionLease;
import fun.bm.lophine.carpet.OrgBlockDropRouting;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real attack bodies and held stacks must finish on the current actor without artificial area admission.
 */
public class ScarpetOwnedNativeAttackTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class, CALLS_REAL_METHODS);
        final LivingEntity victim = mock(LivingEntity.class);
        final DamageSource damage = mock(DamageSource.class);
        final ItemStack held;
        final List<String> order = new ArrayList<>();
        final AtomicReference<Float> health = new AtomicReference<>(20F);
        final org.mockito.MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final org.mockito.MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
        final org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class);

        Fixture() throws Exception {
            when(world.getServer()).thenReturn(server);
            servers.when(MinecraftServer::getServer).thenReturn(server);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(TickThread::isTickThread).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
            when(world.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(mock(LevelChunk.class));
            when(world.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(1));
            var craft = mock(org.bukkit.craftbukkit.CraftServer.class);
            when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            var configuration = mock(io.papermc.paper.configuration.WorldConfiguration.class);
            configuration.misc = mock(io.papermc.paper.configuration.WorldConfiguration.Misc.class);
            configuration.entities = mock(io.papermc.paper.configuration.WorldConfiguration.Entities.class);
            configuration.entities.behavior = mock(io.papermc.paper.configuration.WorldConfiguration.Entities.Behavior.class);
            when(world.paperConfig()).thenReturn(configuration);
            Field spigot = net.minecraft.world.level.Level.class.getDeclaredField("spigotConfig");
            spigot.setAccessible(true);
            spigot.set(world, mock(org.spigotmc.SpigotWorldConfig.class));
            var constructor = ItemStack.class.getDeclaredConstructor(net.minecraft.core.Holder.class, int.class, PatchedDataComponentMap.class);
            constructor.setAccessible(true);
            held = spy(constructor.newInstance(Items.STICK.builtInRegistryHolder(), 1, new PatchedDataComponentMap(DataComponentMap.EMPTY)));
            held.set(DataComponents.WEAPON, new Weapon(1));
            held.set(DataComponents.MAX_DAMAGE, 100);
            held.set(DataComponents.DAMAGE, 0);
            prepare(player);
            prepare(victim);
            doReturn(new Abilities()).when(player).getAbilities();
            doReturn(false).when(player).isAutoSpinAttack();
            doReturn(false).when(player).isSprinting();
            doReturn(false).when(player).isCreative();
            doReturn(false).when(player).onGround();
            doReturn(1F).when(player).getAttackStrengthScale(.5F);
            doReturn(2D).when(player).getAttributeValue(Attributes.ATTACK_DAMAGE);
            doReturn(0D).when(player).getAttributeValue(Attributes.ATTACK_KNOCKBACK);
            doReturn(held).when(player).getWeaponItem();
            doReturn(held).when(player).getMainHandItem();
            doReturn(damage).when(held).getDamageSource(player);
            doReturn(server).when(player).carpetSpawnServer();
            doNothing().when(player).onAttack(any());
            doNothing().when(player).setLastHurtMob(any());
            doNothing().when(player).awardStat(any(net.minecraft.stats.Stat.class));
            doAnswer(call -> {
                order.add("stats");
                return null;
            }).when(player).awardStat(any(net.minecraft.stats.Stat.class), anyInt());
            doAnswer(call -> {
                order.add("food");
                return null;
            }).when(player).causeFoodExhaustion(anyFloat(), any());
            doAnswer(call -> {
                order.add("post");
                return null;
            }).when(player).postPiercingAttack();
            Field random = Entity.class.getDeclaredField("random");
            random.setAccessible(true);
            random.set(player, world.getRandom());
            player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            when(victim.isAttackable()).thenReturn(true);
            when(victim.getHealth()).thenAnswer(call -> health.get());
            when(victim.hurtServer(eq(world), eq(damage), eq(2F))).thenAnswer(call -> {
                order.add("damage");
                health.set(18F);
                return true;
            });
            doAnswer(call -> {
                assertSame(held, player.getMainHandItem());
                order.add("wear");
                held.setDamageValue(held.getDamageValue() + 1);
                return null;
            })
                    .when(held).postHurtEnemy(victim, player);
        }

        void prepare(LivingEntity entity) {
            doReturn(world).when(entity).level();
            doReturn(BlockPos.ZERO).when(entity).blockPosition();
            doReturn(Vec3.ZERO).when(entity).position();
            doReturn(Vec3.ZERO).when(entity).getDeltaMovement();
            doReturn(0D).when(entity).getX();
            doReturn(0D).when(entity).getY();
            doReturn(0D).when(entity).getZ();
            doReturn(Component.literal("native")).when(entity).getDisplayName();
            doReturn("native").when(entity).getScoreboardName();
            doReturn(false).when(entity).isSilent();
            doReturn(false).when(entity).isInWater();
            doReturn(ItemStack.EMPTY).when(entity).getMainHandItem();
            doReturn(ItemStack.EMPTY).when(entity).getWeaponItem();
            doReturn(ItemStack.EMPTY).when(entity).getItemBySlot(any(EquipmentSlot.class));
            var api = entity instanceof ServerPlayer ? mock(org.bukkit.craftbukkit.entity.CraftPlayer.class)
                    : mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class);
            doReturn(api).when(entity).getBukkitEntity();
            try {
                var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
                var field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
                field.setAccessible(true);
                field.set(api, scheduler);
                when(scheduler.schedule(any(), any(), anyLong())).thenThrow(new AssertionError("Owned native phase must not queue an entity tick"));
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        public void close() {
            bukkit.close();
            servers.close();
            ticks.close();
        }
    }

    @Test
    void ordinaryRealPlayerAttackDamagesAndWearsItsExactHeldStackInTheSameCallWithoutAreaLeases() throws Exception {
        boolean ams = GeneralCompatConfig.creativeOneHitKill, org = GeneralCompatConfig.creativeHitRemoveEntity;
        GeneralCompatConfig.creativeOneHitKill = false;
        GeneralCompatConfig.creativeHitRemoveEntity = false;
        try (var f = new Fixture(); var leases = mockStatic(CarpetRegionLease.class, CALLS_REAL_METHODS);
             var events = mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class, (event, context) -> when(event.callEvent()).thenReturn(true))) {
            assertFalse(fun.bm.lophine.carpet.OrgItemShadowGroups.managed(f.held));
            var completed = ScarpetNativeWork.observeNative(f.player, () -> {
                f.player.attack(f.victim);
                return null;
            });
            assertTrue(completed.isDone());
            completed.join();
            assertEquals(18F, f.health.get().floatValue());
            assertEquals(1, f.held.getDamageValue());
            assertSame(f.held, f.player.getMainHandItem());
            assertEquals(List.of("damage", "wear", "stats", "food", "post"), f.order);
            assertFalse(ScarpetPlayerInventoryGate.paused(f.player));
            assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
            leases.verifyNoInteractions();
            verify(f.world, never()).moonrise$getChunkTaskScheduler();
        } finally {
            GeneralCompatConfig.creativeOneHitKill = ams;
            GeneralCompatConfig.creativeHitRemoveEntity = org;
        }
    }

    @Test
    void realPlayerDamageChildStillHoldsTheTailAndGateUntilItCompletes() throws Exception {
        try (var f = new Fixture(); var events = mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class, (event, context) -> when(event.callEvent()).thenReturn(true))) {
            var child = new CompletableFuture<Void>();
            when(f.victim.hurtServer(eq(f.world), eq(f.damage), eq(2F))).thenAnswer(call -> {
                f.order.add("damage");
                f.health.set(18F);
                ScarpetNativeWork.record(child);
                return true;
            });
            var completed = ScarpetNativeWork.observeNative(f.player, () -> {
                f.player.attack(f.victim);
                return null;
            });
            assertFalse(completed.isDone());
            assertEquals(List.of("damage"), f.order);
            assertEquals(0, f.held.getDamageValue());
            assertFalse(ScarpetNativeWork.whenIdle(f.server).isDone());
            child.complete(null);
            completed.join();
            assertEquals(List.of("damage", "wear", "stats", "food", "post"), f.order);
            assertEquals(1, f.held.getDamageValue());
            assertFalse(ScarpetPlayerInventoryGate.paused(f.player));
            assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
    }

    @Test
    void realPlayerFailedDamageChildSuppressesTheRemainingAttackAndReleasesAdmission() throws Exception {
        try (var f = new Fixture(); var events = mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class, (event, context) -> when(event.callEvent()).thenReturn(true))) {
            var child = new CompletableFuture<Void>();
            when(f.victim.hurtServer(eq(f.world), eq(f.damage), eq(2F))).thenAnswer(call -> {
                f.order.add("damage");
                ScarpetNativeWork.record(child);
                return true;
            });
            var completed = ScarpetNativeWork.observeNative(f.player, () -> {
                f.player.attack(f.victim);
                return null;
            });
            var problem = new IllegalStateException("real damage native child");
            child.completeExceptionally(problem);
            assertSame(problem, assertThrows(java.util.concurrent.CompletionException.class, completed::join).getCause());
            assertEquals(List.of("damage"), f.order);
            assertEquals(0, f.held.getDamageValue());
            assertFalse(ScarpetPlayerInventoryGate.paused(f.player));
            assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
    }

    private static ItemStack plainStack(net.minecraft.world.item.Item item, int count) throws Exception {
        var constructor = ItemStack.class.getDeclaredConstructor(net.minecraft.core.Holder.class, int.class, PatchedDataComponentMap.class);
        constructor.setAccessible(true);
        var components = new PatchedDataComponentMap(DataComponentMap.EMPTY);
        components.set(DataComponents.MAX_STACK_SIZE, 64);
        return constructor.newInstance(item.builtInRegistryHolder(), count, components);
    }

    @Test
    void actualNumberKeySwapDuringDamageMovesTheOriginalWeaponToInventoryAndItsBreakCannotEraseEitherNewHand() throws Exception {
        try (var f = new Fixture(); var events = mockConstruction(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.class, (event, context) -> when(event.callEvent()).thenReturn(true))) {
            var inventory = new net.minecraft.world.entity.player.Inventory(f.player, new net.minecraft.world.entity.EntityEquipment());
            var replacementMain = plainStack(Items.PAPER, 3);
            var unchangedOffhand = plainStack(Items.GOLD_INGOT, 4);
            inventory.setItem(0, f.held);
            inventory.setItem(10, replacementMain);
            inventory.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, unchangedOffhand);
            doReturn(inventory).when(f.player).getInventory();
            doAnswer(call -> inventory.getSelectedItem()).when(f.player).getMainHandItem();
            doAnswer(call -> inventory.getSelectedItem()).when(f.player).getWeaponItem();
            doAnswer(call -> inventory.getItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND)).when(f.player).getOffhandItem();
            doAnswer(call -> {
                var hand = call.<net.minecraft.world.InteractionHand>getArgument(0);
                ItemStack replacement = call.getArgument(1);
                inventory.setItem(hand == net.minecraft.world.InteractionHand.MAIN_HAND ? inventory.getSelectedSlot() : net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, replacement);
                return null;
            }).when(f.player).setItemInHand(any(), any());
            var menu = new net.minecraft.world.inventory.AbstractContainerMenu(null, 0) {
                {
                    addSlot(new net.minecraft.world.inventory.Slot(inventory, 10, 0, 0));
                }

                @Override
                public org.bukkit.inventory.InventoryView getBukkitView() {
                    return null;
                }

                @Override
                public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) {
                    return ItemStack.EMPTY;
                }

                @Override
                public boolean stillValid(net.minecraft.world.entity.player.Player player) {
                    return true;
                }
            };
            f.player.containerMenu = menu;
            doReturn(false).when(f.player).hasInfiniteMaterials();
            doReturn(mock(net.minecraft.server.PlayerAdvancements.class)).when(f.player).getAdvancements();
            doNothing().when(f.player).onEquippedItemBroken(any(ItemStack.class), any(EquipmentSlot.class));
            f.held.set(DataComponents.MAX_DAMAGE, 1);
            doCallRealMethod().when(f.held).postHurtEnemy(f.victim, f.player);
            var plugins = mock(org.bukkit.plugin.PluginManager.class);
            var craft = (org.bukkit.craftbukkit.CraftServer) org.bukkit.Bukkit.getServer();
            var craftPlayer = f.player.getBukkitEntity();
            when(craft.getPluginManager()).thenReturn(plugins);
            doReturn(craft).when(craftPlayer).getServer();
            f.bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(plugins);
            var damageChild = new CompletableFuture<Void>();
            var wearChild = new CompletableFuture<Void>();
            when(f.victim.hurtServer(eq(f.world), eq(f.damage), eq(2F))).thenAnswer(call -> {
                f.order.add("damage");
                f.health.set(18F);
                ScarpetNativeWork.record(damageChild);
                return true;
            });
            doAnswer(call -> {
                if (call.getArgument(0) instanceof org.bukkit.event.player.PlayerItemDamageEvent damageEvent) {
                    assertEquals(1, damageEvent.getDamage());
                    assertSame(f.held, inventory.getItem(10));
                    f.order.add("wear");
                    ScarpetNativeWork.record(wearChild);
                } else if (call.getArgument(0) instanceof org.bukkit.event.player.PlayerItemBreakEvent)
                    f.order.add("break");
                else fail("Unexpected native item callback " + call.getArgument(0));
                return null;
            }).when(plugins).callEvent(any());
            var actual = ScarpetNativeWork.observeNative(f.player, () -> {
                f.player.attack(f.victim);
                return null;
            });
            assertEquals(List.of("damage"), f.order);
            assertFalse(actual.isDone());
            assertSame(f.held, inventory.getSelectedItem());
            assertFalse(ScarpetPlayerInventoryGate.paused(f.player));
            // Execute the same vanilla number-key branch used by a container click;
            // it transfers the actual stack object, rather than a test copy.
            menu.clicked(0, 0, net.minecraft.world.inventory.ContainerInput.SWAP, f.player);
            assertSame(f.held, inventory.getItem(10));
            assertSame(replacementMain, inventory.getSelectedItem());
            assertSame(unchangedOffhand, f.player.getOffhandItem());
            assertEquals(0, f.held.getDamageValue());
            assertFalse(actual.isDone());
            damageChild.complete(null);
            assertEquals(List.of("damage", "wear", "break"), f.order);
            assertTrue(f.held.isEmpty());
            assertEquals(0, f.held.getDamageValue());
            assertSame(f.held, inventory.getItem(10));
            assertSame(replacementMain, inventory.getSelectedItem());
            assertSame(unchangedOffhand, f.player.getOffhandItem());
            assertEquals(3, replacementMain.getCount());
            assertEquals(4, unchangedOffhand.getCount());
            assertFalse(actual.isDone());
            assertFalse(ScarpetNativeWork.whenIdle(f.server).isDone());
            wearChild.complete(null);
            actual.join();
            assertEquals(List.of("damage", "wear", "break", "stats", "food", "post"), f.order);
            verify(f.player, never()).setItemInHand(any(), any());
            assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
    }

    @Test
    void realMobAttackInsideANativeObserverCompletesLocallyWithoutLoadingAnArtificialDeathArea() throws Exception {
        try (var f = new Fixture(); var leases = mockStatic(CarpetRegionLease.class, CALLS_REAL_METHODS)) {
            var mob = mock(Mob.class, CALLS_REAL_METHODS);
            f.prepare(mob);
            doReturn(2D).when(mob).getAttributeValue(Attributes.ATTACK_DAMAGE);
            doReturn(0D).when(mob).getAttributeValue(Attributes.ATTACK_KNOCKBACK);
            doReturn(f.held).when(mob).getWeaponItem();
            doReturn(f.damage).when(f.held).getDamageSource(mob);
            doNothing().when(mob).setLastHurtMob(any());
            doNothing().when(mob).postPiercingAttack();
            var observed = ScarpetNativeWork.observeNative(mob, () -> mob.doHurtTarget(f.world, f.victim));
            assertTrue(observed.isDone());
            assertTrue(observed.join());
            assertEquals(18F, f.health.get().floatValue());
            assertTrue(ScarpetAttackContinuations.pendingHitResult(mob).join());
            leases.verifyNoInteractions();
        }
    }

    @Test
    void realNativePassengerAddUsesTheOwnedSiteImmediatelyAndRetainsActualSpawnChildren() throws Exception {
        try (var f = new Fixture(); var events = mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)) {
            var root = mock(Entity.class);
            when(root.level()).thenReturn(f.world);
            when(root.blockPosition()).thenReturn(BlockPos.ZERO);
            doCallRealMethod().when(f.world).carpetAddFreshEntityNativeAsync(eq(root), any());
            var child = new CompletableFuture<Void>();
            var called = new ArrayList<Entity>();
            when(f.world.getCurrentWorldData()).thenReturn(mock(io.papermc.paper.threadedregions.RegionizedWorldData.class));
            var lookup = mock(ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup.class);
            when(f.world.moonrise$getEntityLookup()).thenReturn(lookup);
            events.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.doEntityAddEventCalling(eq(f.world), eq(root), any())).thenReturn(true);
            when(lookup.addNewEntity(root)).thenAnswer(call -> {
                called.add(root);
                ScarpetNativeWork.record(child);
                return true;
            });
            var actual = OrgBlockDropRouting.addTreeNative(f.world, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.COMMAND, () -> List.of(root));
            assertEquals(List.of(root), called);
            assertFalse(actual.isDone());
            assertFalse(ScarpetNativeWork.whenIdle(f.server).isDone());
            child.complete(null);
            assertTrue(actual.join());
            assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
            verify(f.world, never()).moonrise$getChunkTaskScheduler();
        }
    }
}
