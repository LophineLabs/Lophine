package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.CarpetRegionLease;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetAttackEndpointsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // Server data loading binds item components; retain real durability processing in this native fixture.
        var shield = net.minecraft.world.item.Items.SHIELD;
        try {
            shield.builtInRegistryHolder().components();
        } catch (NullPointerException unbound) {
            shield.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder()
                    .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 1)
                    .set(net.minecraft.core.component.DataComponents.MAX_DAMAGE, 336)
                    .set(net.minecraft.core.component.DataComponents.DAMAGE, 0).build());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel original = mock(ServerLevel.class), destination = mock(ServerLevel.class);
        final LivingEntity attacker, victim;
        final AtomicReference<Entity> owner;
        final AtomicReference<ServerLevel> victimWorld = new AtomicReference<>(original);
        final Map<Entity, ArrayDeque<Runnable>> queues = new IdentityHashMap<>();
        final org.mockito.MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final org.mockito.MockedStatic<CarpetRegionLease> leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();

        Fixture() throws Exception {
            this(LivingEntity.class, LivingEntity.class);
        }

        Fixture(Class<? extends LivingEntity> sourceType, Class<? extends LivingEntity> victimType) throws Exception {
            attacker = mock(sourceType);
            victim = mock(victimType);
            owner = new AtomicReference<>(attacker);
            when(original.getServer()).thenReturn(server);
            when(destination.getServer()).thenReturn(server);
            when(original.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(123));
            when(destination.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(456));
            when(attacker.level()).thenAnswer(call -> {
                assertSame(attacker, owner.get());
                return original;
            });
            when(victim.level()).thenAnswer(call -> {
                assertSame(victim, owner.get());
                return victimWorld.get();
            });
            when(attacker.blockPosition()).thenReturn(BlockPos.ZERO);
            when(victim.blockPosition()).thenReturn(BlockPos.ZERO);
            for (var entity : List.of(attacker, victim)) {
                var craft = entity instanceof net.minecraft.world.entity.player.Player
                        ? mock(org.bukkit.craftbukkit.entity.CraftPlayer.class) : mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class);
                when(entity.getBukkitEntity()).thenReturn(craft);
                var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
                Field field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
                field.setAccessible(true);
                field.set(craft, scheduler);
                queues.put(entity, new ArrayDeque<>());
                when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                    Consumer<Entity> action = call.getArgument(0);
                    queues.get(entity).add(() -> action.accept(entity));
                    return true;
                });
            }
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> call.getArgument(0) == owner.get());
            ticks.when(() -> TickThread.isTickThreadFor(any(ServerLevel.class), any(BlockPos.class))).thenAnswer(call ->
                    owner.get() == attacker && call.getArgument(0) == original || owner.get() == victim && call.getArgument(0) == victimWorld.get());
            leases.when(() -> CarpetRegionLease.runValue(any(), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                assertEquals(owner.get() == attacker ? original : victimWorld.get(), call.getArgument(0));
                Function<CarpetRegionLease.Lease<?>, Object> action = call.getArgument(5);
                return CompletableFuture.completedFuture(action.apply(null));
            });
        }

        void run(Entity entity) {
            var previous = owner.get();
            owner.set(entity);
            try {
                assertFalse(queues.get(entity).isEmpty());
                queues.get(entity).remove().run();
            } finally {
                owner.set(previous);
            }
        }

        public void close() {
            leases.close();
            ticks.close();
        }
    }

    @Test
    void typedNonPlayerTailKeepsSourceVictimSourceOrderAcrossWorldsAndWaitsBothNativeChildren() throws Exception {
        boolean previous = ScarpetRuntime.FILL_SKIP_UPDATES.get();
        try (var fixture = new Fixture()) {
            var outcome = new CompletableFuture<Boolean>();
            var targetChild = new CompletableFuture<Void>();
            var sourceChild = new CompletableFuture<Void>();
            var order = new ArrayList<String>();
            ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
            var actual = ScarpetAttackContinuations.afterDamageNativeAsync(fixture.attacker, outcome, hurt -> {
                assertTrue(hurt);
                assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());
                assertSame(fixture.attacker, fixture.owner.get());
                order.add("source snapshot");
                return ScarpetNativeDeathActors.target(fixture.victim, () -> {
                    assertSame(fixture.victim, fixture.owner.get());
                    assertSame(fixture.destination, fixture.victim.level());
                    order.add("victim effect");
                    ScarpetNativeWork.record(targetChild);
                    return null;
                }).thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetNativeDeathActors.target(fixture.attacker, () -> {
                    assertSame(fixture.attacker, fixture.owner.get());
                    order.add("source cooldown");
                    ScarpetNativeWork.record(sourceChild);
                    return true;
                })));
            });
            ScarpetRuntime.FILL_SKIP_UPDATES.set(false);
            var completed = actual.thenAccept(value -> assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get()));
            assertFalse(actual.cancel(false));
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            fixture.victimWorld.set(fixture.destination);
            fixture.owner.set(null);
            outcome.complete(true);
            fixture.run(fixture.attacker);
            fixture.run(fixture.victim);
            assertEquals(List.of("source snapshot", "victim effect"), order);
            assertFalse(actual.isDone());
            assertFalse(idle.isDone());
            targetChild.complete(null);
            fixture.run(fixture.attacker);
            assertEquals(List.of("source snapshot", "victim effect", "source cooldown"), order);
            assertFalse(actual.isDone());
            sourceChild.complete(null);
            assertTrue(actual.join());
            completed.join();
            assertFalse(ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertTrue(idle.isDone());
            assertNull(ScarpetNativeWork.capture());
        } finally {
            ScarpetRuntime.FILL_SKIP_UPDATES.set(previous);
        }
    }

    @Test
    void victimOnlyTailUsesTheMovedVictimsOwnerAndPreservesTheFalseOutcome() throws Exception {
        try (var fixture = new Fixture()) {
            var outcome = new CompletableFuture<Boolean>();
            var child = new CompletableFuture<Void>();
            var actual = ScarpetAttackContinuations.afterDamageTarget(fixture.attacker, fixture.victim, outcome, hurt -> {
                assertFalse(hurt);
                assertSame(fixture.victim, fixture.owner.get());
                assertSame(fixture.destination, fixture.victim.level());
                ScarpetNativeWork.record(child);
                return hurt;
            });
            fixture.victimWorld.set(fixture.destination);
            fixture.owner.set(null);
            outcome.complete(false);
            fixture.run(fixture.attacker);
            fixture.run(fixture.victim);
            assertFalse(actual.isDone());
            child.complete(null);
            assertFalse(actual.join());
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

    @Test
    void sourceOnlyTailDoesNotRequireTheVictimOwnerAndAFailedDamageCannotRunEitherTail() throws Exception {
        try (var fixture = new Fixture()) {
            var outcome = new CompletableFuture<Boolean>();
            var actual = ScarpetAttackContinuations.afterDamageSource(fixture.attacker, outcome, hurt -> {
                assertSame(fixture.attacker, fixture.owner.get());
                return hurt;
            });
            fixture.victimWorld.set(fixture.destination);
            fixture.owner.set(null);
            outcome.complete(true);
            fixture.run(fixture.attacker);
            assertTrue(actual.join());
            assertTrue(fixture.queues.get(fixture.victim).isEmpty());
            fixture.owner.set(fixture.attacker);
            var failed = new CompletableFuture<Boolean>();
            var failure = new IllegalStateException("Real damage failed");
            var rejected = ScarpetAttackContinuations.afterDamageTarget(fixture.attacker, fixture.victim, failed, hurt -> {
                fail("Failed damage must not run a victim tail");
                return hurt;
            });
            failed.completeExceptionally(failure);
            assertSame(failure, assertThrows(CompletionException.class, rejected::join).getCause());
            assertTrue(fixture.queues.get(fixture.attacker).isEmpty());
            assertTrue(fixture.queues.get(fixture.victim).isEmpty());
        }
    }

    @Test
    void realRamTailWaitsItemDamageShieldResponsesAndRamKnockbackBeforeTheSourceCooldownAcrossWorlds() throws Exception {
        try (var fixture = new Fixture(net.minecraft.world.entity.animal.goat.Goat.class, net.minecraft.world.entity.player.Player.class);
             var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            var goat = (net.minecraft.world.entity.animal.goat.Goat) fixture.attacker;
            var player = (net.minecraft.world.entity.player.Player) fixture.victim;
            var sources = mock(net.minecraft.world.damagesource.DamageSources.class);
            var source = mock(net.minecraft.world.damagesource.DamageSource.class);
            var brain = mock(net.minecraft.world.entity.ai.Brain.class);
            when(goat.getBrain()).thenReturn(brain);
            when(goat.damageSources()).thenReturn(sources);
            when(sources.mobAttack(goat)).thenReturn(source);
            when(goat.position()).thenAnswer(call -> {
                assertSame(goat, fixture.owner.get());
                return net.minecraft.world.phys.Vec3.ZERO;
            });
            when(goat.getSpeed()).thenReturn(1F);
            when(goat.getSecondsToDisableBlocking()).thenReturn(1F);
            when(player.position()).thenAnswer(call -> {
                assertSame(player, fixture.owner.get());
                return new net.minecraft.world.phys.Vec3(1, 0, 0);
            });
            when(player.getX()).thenReturn(1D);
            when(player.getUsedItemHand()).thenReturn(net.minecraft.world.InteractionHand.MAIN_HAND);
            var cooldowns = mock(net.minecraft.world.item.ItemCooldowns.class);
            when(player.getCooldowns()).thenReturn(cooldowns);
            var blocks = new net.minecraft.world.item.component.BlocksAttacks(0, 1,
                    List.of(new net.minecraft.world.item.component.BlocksAttacks.DamageReduction(360, Optional.empty(), 1, 0)),
                    new net.minecraft.world.item.component.BlocksAttacks.ItemDamageFunction(0, 0, 1), Optional.empty(), Optional.empty(), Optional.empty());
            var shield = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD);
            shield.set(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS, blocks);
            when(player.getItemBlockingWith()).thenReturn(shield);
            var itemChild = new CompletableFuture<Void>();
            var shieldChild = new CompletableFuture<Void>();
            var disableChild = new CompletableFuture<Void>();
            var ramChild = new CompletableFuture<Void>();
            var order = new ArrayList<String>();
            var plugins = mock(org.bukkit.plugin.PluginManager.class);
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(plugins);
            doAnswer(call -> {
                assertSame(player, fixture.owner.get());
                assertSame(player, ScarpetNativeWork.capture().owner());
                if (call.getArgument(0) instanceof io.papermc.paper.event.entity.EntityDamageItemEvent) {
                    order.add("item damage event");
                    ScarpetNativeWork.record(itemChild);
                } else if (call.getArgument(0) instanceof io.papermc.paper.event.player.PlayerShieldDisableEvent) {
                    order.add("shield disable event");
                    ScarpetNativeWork.record(disableChild);
                } else fail("Unexpected native callback " + call.getArgument(0));
                return null;
            }).when(plugins).callEvent(any());
            doAnswer(call -> {
                assertSame(player, fixture.owner.get());
                assertSame(player, ScarpetNativeWork.capture().owner());
                var cause = call.getArgument(6);
                if (cause == io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.SHIELD_BLOCK) {
                    order.add("shield knockback");
                    ScarpetNativeWork.record(shieldChild);
                } else {
                    assertEquals(io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.ENTITY_ATTACK, cause);
                    assertEquals(0.825D, (double) call.getArgument(0), 0.00001);
                    order.add("ram knockback");
                    ScarpetNativeWork.record(ramChild);
                }
                return null;
            }).when(player).knockback(anyDouble(), anyDouble(), anyDouble(), same(source), eq(2F), same(goat), any());
            doAnswer(call -> {
                assertSame(goat, fixture.owner.get());
                order.add("source cooldown");
                return null;
            })
                    .when(brain).setMemory(eq(net.minecraft.world.entity.ai.memory.MemoryModuleType.RAM_COOLDOWN_TICKS), eq(4));
            doAnswer(call -> {
                assertSame(goat, fixture.owner.get());
                order.add("impact sound");
                return null;
            })
                    .when(fixture.original).playSound(isNull(), same(goat), eq(net.minecraft.sounds.SoundEvents.GOAT_RAM_IMPACT), any(), eq(1F), eq(1F));
            var behavior = new net.minecraft.world.entity.ai.behavior.RamTarget(body -> net.minecraft.util.valueproviders.UniformInt.of(4, 4),
                    mock(net.minecraft.world.entity.ai.targeting.TargetingConditions.class), 1, body -> 1, body -> net.minecraft.sounds.SoundEvents.GOAT_RAM_IMPACT,
                    body -> net.minecraft.sounds.SoundEvents.GOAT_HORN_BREAK);
            Field direction = behavior.getClass().getDeclaredField("ramDirection");
            direction.setAccessible(true);
            direction.set(behavior, new net.minecraft.world.phys.Vec3(1, 0, 0));
            var method = behavior.getClass().getDeclaredMethod("carpetFinishTargetRamAsync", net.minecraft.world.entity.animal.goat.Goat.class, LivingEntity.class,
                    net.minecraft.world.damagesource.DamageSource.class, float.class, boolean.class, ScarpetAttackEnchantments.SourceAdmission.class);
            method.setAccessible(true);
            var admission = ScarpetAttackContinuations.postAttackAdmission(goat, fixture.original);
            var outcome = new CompletableFuture<Boolean>();
            var actual = ScarpetAttackContinuations.afterDamageNativeAsync(goat, outcome, hurt -> {
                try {
                    return (CompletableFuture<Boolean>) method.invoke(behavior, goat, player, source, 2F, hurt, admission);
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            });
            fixture.victimWorld.set(fixture.destination);
            fixture.owner.set(null);
            outcome.complete(false);
            fixture.run(goat);
            fixture.run(player);
            assertEquals(List.of("item damage event"), order);
            assertEquals(1, shield.getDamageValue());
            assertFalse(actual.isDone());
            itemChild.complete(null);
            fixture.run(player);
            assertEquals(List.of("item damage event", "shield knockback"), order);
            shieldChild.complete(null);
            fixture.run(player);
            assertEquals(List.of("item damage event", "shield knockback", "shield disable event"), order);
            disableChild.complete(null);
            fixture.run(player);
            assertEquals(List.of("item damage event", "shield knockback", "shield disable event", "ram knockback"), order);
            assertFalse(actual.isDone());
            ramChild.complete(null);
            fixture.run(goat);
            assertFalse(actual.join());
            assertEquals(List.of("item damage event", "shield knockback", "shield disable event", "ram knockback", "source cooldown", "impact sound"), order);
            verify(brain).eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.RAM_TARGET);
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

    @Test
    void realSonicKnockbackReadsOnlyTheMovedVictimAndWaitsTheActualPushChild() throws Exception {
        try (var fixture = new Fixture(net.minecraft.world.entity.monster.warden.Warden.class, LivingEntity.class)) {
            var body = (net.minecraft.world.entity.monster.warden.Warden) fixture.attacker;
            when(fixture.victim.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE))
                    .thenAnswer(call -> {
                        assertSame(fixture.victim, fixture.owner.get());
                        return 0.2D;
                    });
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                assertSame(fixture.victim, fixture.owner.get());
                assertEquals(2D, (double) call.getArgument(0));
                assertEquals(0.4D, (double) call.getArgument(1));
                assertEquals(2D, (double) call.getArgument(2));
                ScarpetNativeWork.record(child);
                return null;
            }).when(fixture.victim).push(anyDouble(), anyDouble(), anyDouble(), same(body));
            var behavior = new net.minecraft.world.entity.ai.behavior.warden.SonicBoom();
            var method = behavior.getClass().getDeclaredMethod("carpetFinishSonicKnockback", net.minecraft.world.entity.monster.warden.Warden.class, LivingEntity.class, net.minecraft.world.phys.Vec3.class);
            method.setAccessible(true);
            var outcome = new CompletableFuture<Boolean>();
            var actual = ScarpetAttackContinuations.afterDamageTarget(body, fixture.victim, outcome, hurt -> {
                try {
                    if (hurt) method.invoke(behavior, body, fixture.victim, new net.minecraft.world.phys.Vec3(1, 1, 1));
                    return hurt;
                } catch (ReflectiveOperationException failure) {
                    throw new RuntimeException(failure);
                }
            });
            fixture.victimWorld.set(fixture.destination);
            fixture.owner.set(null);
            outcome.complete(true);
            fixture.run(body);
            fixture.run(fixture.victim);
            assertFalse(actual.isDone());
            child.complete(null);
            assertTrue(actual.join());
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

    @Test
    void realChargeDamageEntryDefersVictimKnockbackSourceDampingAndCooldownUntilTheActualMovedTargetChild() throws Exception {
        try (var fixture = new Fixture(net.minecraft.world.entity.animal.Animal.class, LivingEntity.class)) {
            var body = (net.minecraft.world.entity.animal.Animal) fixture.attacker;
            var sources = mock(net.minecraft.world.damagesource.DamageSources.class);
            var source = mock(net.minecraft.world.damagesource.DamageSource.class);
            when(fixture.original.damageSources()).thenReturn(sources);
            when(body.damageSources()).thenReturn(sources);
            when(sources.mobAttack(body)).thenReturn(source);
            var brain = mock(net.minecraft.world.entity.ai.Brain.class);
            when(body.getBrain()).thenReturn(brain);
            when(body.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)).thenReturn(1D);
            when(body.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)).thenReturn(2D);
            when(body.getDeltaMovement()).thenAnswer(call -> {
                assertSame(body, fixture.owner.get());
                return new net.minecraft.world.phys.Vec3(1, 0, 0);
            });
            var outcome = new CompletableFuture<Boolean>();
            var child = new CompletableFuture<Void>();
            var order = new ArrayList<String>();
            when(fixture.victim.hurtServer(fixture.original, source, 2F)).thenAnswer(call -> {
                ScarpetDamageContinuations.publishBodyResult(fixture.victim, outcome);
                return false;
            });
            doCallRealMethod().when(body).carpetCauseExtraKnockbackAsync(same(fixture.victim), eq(1F), any(), same(source), eq(2F), eq(false));
            doAnswer(call -> {
                assertSame(fixture.victim, fixture.owner.get());
                assertSame(fixture.destination, fixture.victim.level());
                assertSame(fixture.victim, ScarpetNativeWork.capture().owner());
                order.add("victim knockback");
                ScarpetNativeWork.record(child);
                return null;
            }).when(fixture.victim).knockback(eq(1D), eq(0D), eq(-1D), same(source), eq(2F), eq(false), same(body), any());
            doAnswer(call -> {
                assertSame(body, fixture.owner.get());
                assertEquals(new net.minecraft.world.phys.Vec3(0.6, 0, 0), call.getArgument(0));
                order.add("source damping");
                return null;
            }).when(body).setDeltaMovement(any(net.minecraft.world.phys.Vec3.class));
            doAnswer(call -> {
                assertSame(body, fixture.owner.get());
                order.add("source cooldown");
                return null;
            })
                    .when(brain).setMemory(eq(net.minecraft.world.entity.ai.memory.MemoryModuleType.CHARGE_COOLDOWN_TICKS), eq(6));
            var behavior = new net.minecraft.world.entity.ai.behavior.ChargeAttack(6, mock(net.minecraft.world.entity.ai.targeting.TargetingConditions.class), 1, 1, 8, 8,
                    net.minecraft.sounds.SoundEvents.GOAT_RAM_IMPACT);
            var method = behavior.getClass().getDeclaredMethod("dealDamageToTarget", ServerLevel.class, net.minecraft.world.entity.animal.Animal.class, LivingEntity.class, long.class);
            method.setAccessible(true);
            assertEquals(true, method.invoke(behavior, fixture.original, body, fixture.victim, 15L));
            var actual = ScarpetAttackContinuations.pendingHitResult(body);
            assertNotNull(actual);
            assertTrue(order.isEmpty());
            assertFalse(actual.isDone());
            fixture.victimWorld.set(fixture.destination);
            fixture.owner.set(null);
            outcome.complete(false);
            fixture.run(body);
            fixture.run(fixture.victim);
            assertEquals(List.of("victim knockback"), order);
            assertFalse(actual.isDone());
            child.complete(null);
            fixture.run(body);
            assertFalse(actual.join());
            assertEquals(List.of("victim knockback", "source damping", "source cooldown"), order);
            verify(brain).eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

    private static net.minecraft.world.item.ItemStack emptyWeapon() throws Exception {
        var constructor = net.minecraft.world.item.ItemStack.class.getDeclaredConstructor(net.minecraft.core.Holder.class, int.class,
                net.minecraft.core.component.PatchedDataComponentMap.class);
        constructor.setAccessible(true);
        return spy(constructor.newInstance(net.minecraft.world.item.Items.STICK.builtInRegistryHolder(), 1,
                new net.minecraft.core.component.PatchedDataComponentMap(net.minecraft.core.component.DataComponentMap.EMPTY)));
    }

    private static void drain(Fixture fixture) {
        for (int pass = 0; pass < 100; pass++) {
            boolean ran = false;
            for (var entity : List.of(fixture.attacker, fixture.victim))
                if (!fixture.queues.get(entity).isEmpty()) {
                    fixture.run(entity);
                    ran = true;
                }
            if (!ran) return;
        }
        fail("Native attack endpoints did not become idle");
    }

    private static void movedDamageWorld(boolean stab) throws Exception {
        try (var fixture = new Fixture(net.minecraft.world.entity.Mob.class, LivingEntity.class);
             var enchantments = mockStatic(net.minecraft.world.item.enchantment.EnchantmentHelper.class, CALLS_REAL_METHODS)) {
            var attacker = (net.minecraft.world.entity.Mob) fixture.attacker;
            var stack = emptyWeapon();
            var source = mock(net.minecraft.world.damagesource.DamageSource.class);
            when(attacker.getWeaponItem()).thenReturn(stack);
            when(attacker.getItemBySlot(any())).thenReturn(stack);
            when(fixture.victim.getItemBySlot(any())).thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
            when(attacker.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)).thenReturn(2D);
            when(fixture.victim.getDeltaMovement()).thenAnswer(call -> {
                assertSame(fixture.victim, fixture.owner.get());
                return net.minecraft.world.phys.Vec3.ZERO;
            });
            doReturn(source).when(stack).getDamageSource(attacker);
            doCallRealMethod().when(attacker).carpetGetKnockbackAsync(any(), any());
            doCallRealMethod().when(attacker).carpetCauseExtraKnockbackAsync(any(), anyFloat(), any(), any(), anyFloat(), anyBoolean());
            doCallRealMethod().when(attacker).carpetPostAttackEffectsAsync(any(), any(), any());
            var enchantment = new CompletableFuture<Float>();
            var damageChild = new CompletableFuture<Void>();
            var order = new ArrayList<String>();
            enchantments.when(() -> net.minecraft.world.item.enchantment.EnchantmentHelper.carpetModifyDamageAsync(any(), same(stack), same(fixture.victim), same(source), eq(2F)))
                    .thenReturn(enchantment);
            when(fixture.victim.hurtServer(same(fixture.original), same(source), anyFloat()))
                    .thenThrow(new AssertionError("Moved victim damage must not use its source's cached world"));
            when(fixture.victim.hurtServer(same(fixture.destination), same(source), eq(2F))).thenAnswer(call -> {
                assertSame(fixture.victim, fixture.owner.get());
                assertSame(fixture.destination, fixture.victim.level());
                order.add("damage in destination");
                ScarpetNativeWork.record(damageChild);
                return true;
            });
            doAnswer(call -> {
                assertSame(attacker, fixture.owner.get());
                order.add("source last hit");
                return null;
            }).when(attacker).setLastHurtMob(fixture.victim);
            doAnswer(call -> {
                assertSame(attacker, fixture.owner.get());
                order.add("source piercing tail");
                return null;
            }).when(attacker).postPiercingAttack();
            if (stab)
                doCallRealMethod().when(attacker).carpetStabNativeAsync(same(fixture.original), eq(net.minecraft.world.entity.EquipmentSlot.MAINHAND), same(fixture.victim), eq(2F), eq(true), eq(false), eq(false));
            else
                doCallRealMethod().when(attacker).carpetDoHurtTargetAsync(same(fixture.original), same(fixture.victim));
            var actual = new AtomicReference<CompletableFuture<Boolean>>();
            var observed = ScarpetNativeWork.observeNative(attacker, () -> {
                actual.set(stab ? attacker.carpetStabNativeAsync(fixture.original, net.minecraft.world.entity.EquipmentSlot.MAINHAND, fixture.victim, 2F, true, false, false)
                        : attacker.carpetDoHurtTargetAsync(fixture.original, fixture.victim));
                ScarpetNativeWork.record(actual.get());
                return null;
            });
            assertFalse(actual.get().isDone());
            assertTrue(order.isEmpty());
            fixture.victimWorld.set(fixture.destination);
            fixture.owner.set(null);
            enchantment.complete(2F);
            drain(fixture);
            assertEquals(List.of("damage in destination"), order);
            assertFalse(actual.get().isDone());
            assertFalse(observed.isDone());
            damageChild.complete(null);
            drain(fixture);
            assertTrue(actual.get().join());
            observed.join();
            assertEquals(stab ? List.of("damage in destination", "source last hit")
                    : List.of("damage in destination", "source last hit", "source piercing tail"), order);
        }
    }

    @Test
    void realMobAttackReentersAMovedVictimsCurrentWorldAfterItsEnchantmentPreparation() throws Exception {
        movedDamageWorld(false);
    }

    @Test
    void realLivingStabReentersAMovedVictimsCurrentWorldAfterItsEnchantmentPreparation() throws Exception {
        movedDamageWorld(true);
    }
}
