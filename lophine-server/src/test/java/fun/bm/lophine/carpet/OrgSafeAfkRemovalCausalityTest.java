package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetAttackContinuations;
import carpet.script.external.ScarpetNativeDeaths;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.threadedregions.EntityScheduler;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.CraftBot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgSafeAfkRemovalCausalityTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Fixture {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final ServerBot bot = mock(ServerBot.class);
        final BotList bots = mock(BotList.class);
        final DamageSource source = mock(DamageSource.class);
        final CommandSourceStack command = mock(CommandSourceStack.class);
        final CommandResultCallback callback = mock(CommandResultCallback.class);
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        final AtomicInteger cleanups = new AtomicInteger();
        final AtomicBoolean removed = new AtomicBoolean();
        final CompletableFuture<Void> cleanupChild = new CompletableFuture<>();
        final CompletableFuture<Void> postDamageChild = new CompletableFuture<>();
        final AtomicInteger postDamageTails = new AtomicInteger();

        Fixture() throws Exception {
            bot.carpetNativePlayer = true;
            when(bot.level()).thenReturn(world);
            when(bot.carpetSpawnServer()).thenReturn(server);
            when(bot.blockPosition()).thenReturn(BlockPos.ZERO);
            when(bot.isRemoved()).thenAnswer(call -> removed.get());
            when(bot.getDisplayName()).thenReturn(Component.literal("Bot"));
            when(bot.getItemInHand(any())).thenReturn(ItemStack.EMPTY);
            when(bot.getFoodData()).thenReturn(mock(net.minecraft.world.food.FoodData.class));
            when(bot.getInventory()).thenReturn(mock(Inventory.class));
            bot.containerMenu = mock(AbstractContainerMenu.class);
            var slots = AbstractContainerMenu.class.getField("slots");
            slots.setAccessible(true);
            slots.set(bot.containerMenu, NonNullList.create());
            when(bot.containerMenu.getCarried()).thenReturn(ItemStack.EMPTY);
            var random = mock(RandomSource.class);
            when(bot.getRandom()).thenReturn(random);
            when(random.nextFloat()).thenReturn(1F);
            when(world.getServer()).thenReturn(server);
            when(server.getBotList()).thenReturn(bots);
            var players = mock(PlayerList.class);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayers()).thenReturn(List.of());
            when(source.getMsgId()).thenReturn("fall");
            when(command.getServer()).thenReturn(server);
            when(command.getLevel()).thenReturn(world);
            when(command.getPosition()).thenReturn(Vec3.ZERO);
            when(command.callback()).thenReturn(callback);
            var bukkit = mock(CraftBot.class);
            when(bot.getBukkitEntity()).thenReturn(bukkit);
            var scheduler = mock(EntityScheduler.class);
            var field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
            field.setAccessible(true);
            field.set(bukkit, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                Consumer<Entity> action = call.getArgument(0);
                queued.add(() -> action.accept(bot));
                return true;
            });
            // Use the real removal admission gate and nested tail lifetime. The
            // mock replaces only the physical save/disconnect body after admission.
            when(bots.carpetRemoveBotAsync(eq(bot), any(), isNull(), eq(true), eq(false))).thenAnswer(call -> {
                var removal = ScarpetPlayerInventoryGate.whenIdleForRemoval(bot, () -> {
                    cleanups.incrementAndGet();
                    ScarpetNativeWork.record(cleanupChild);
                    return cleanupChild.thenApply(ignored -> { removed.set(true); return true; });
                }).thenCompose(value -> value);
                ScarpetNativeWork.record(removal);
                ScarpetNativeWork.trackNative(server, removal);
                return removal;
            });
        }

        org.mockito.MockedStatic<TickThread> ticks() {
            var ticks = mockStatic(TickThread.class);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            return ticks;
        }

        void runCleanup() {
            if (cleanups.get() == 0) {
                assertEquals(1, queued.size());
                queued.remove().run();
            } else {
                assertTrue(queued.isEmpty());
            }
            assertEquals(1, cleanups.get());
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            assertTrue(ScarpetPlayerInventoryGate.paused(bot));
        }
    }

    @Test void realBotDeathSkipsItsSafeAfkDamageWaiterButDrainsIndependentAcceptedWork() throws Exception {
        lethalDamage(false, false);
    }

    @Test void persistentParrotDamageKeepsItsWholeReceiptCausalThroughRealBotDeath() throws Exception {
        lethalDamage(true, false);
    }

    @Test void betterTotemDamageKeepsItsNativeBodyCausalThroughRealBotDeath() throws Exception {
        lethalDamage(false, true);
    }

    @Test void combinedPersistentParrotsAndBetterTotemDrainTheSameRealBotDeath() throws Exception {
        lethalDamage(true, true);
    }

    @Test void realNativeKillCommandPublishesOnlyAfterItsActualBotDeathAndIndependentCleanup() throws Exception {
        lethalDamage(false, false, true);
    }

    @Test void inlinePersistentParrotDeathPublishesItsCausalAdmissionBeforeTheRemovalGateSnapshot() throws Exception {
        lethalDamage(true, false, false, true);
    }

    @Test void inlineBetterTotemDeathPublishesItsCausalAdmissionBeforeTheRemovalGateSnapshot() throws Exception {
        lethalDamage(false, true, false, true);
    }

    @Test void inlineCombinedParrotsAndBetterTotemDoNotRetainTheirOwnRemovalGateWaiters() throws Exception {
        lethalDamage(true, true, false, true);
    }

    @Test void typedPostDamageTailRecognizesItsSelfVictimDeathAndWaitsItsActualSuffixChild() throws Exception {
        lethalDamage(false, false, false, false, true);
    }

    private static void lethalDamage(boolean parrots, boolean betterTotem) throws Exception {
        lethalDamage(parrots, betterTotem, false);
    }

    private static void lethalDamage(boolean parrots, boolean betterTotem, boolean nativeCommand) throws Exception {
        lethalDamage(parrots, betterTotem, nativeCommand, false);
    }

    private static void lethalDamage(boolean parrots, boolean betterTotem, boolean nativeCommand, boolean inlineDeath) throws Exception {
        lethalDamage(parrots, betterTotem, nativeCommand, inlineDeath, false);
    }

    private static void lethalDamage(boolean parrots, boolean betterTotem, boolean nativeCommand, boolean inlineDeath,
                                      boolean typedTail) throws Exception {
        var fixture = new Fixture();
        var physicalDeath = inlineDeath ? CompletableFuture.<Void>completedFuture(null) : new CompletableFuture<Void>();
        var independent = new CompletableFuture<Void>();
        var damage = new AtomicReference<CompletableFuture<Boolean>>();
        boolean previousParrots = GeneralCompatConfig.persistentParrots;
        String previousTotem = GeneralCompatConfig.betterTotemOfUndying;
        GeneralCompatConfig.persistentParrots = parrots;
        GeneralCompatConfig.betterTotemOfUndying = betterTotem ? "inventory" : "vanilla";
        doCallRealMethod().when(fixture.bot).die(fixture.source);
        try (var ticks = fixture.ticks(); var deaths = mockStatic(ScarpetNativeDeaths.class); var servers = mockStatic(MinecraftServer.class);
             var manager = mockStatic(OrgPlayerManager.class); var commandScope = CarpetAsyncCommandResults.open()) {
            servers.when(MinecraftServer::getServer).thenReturn(fixture.server);
            manager.when(() -> OrgPlayerManager.safeThreshold(fixture.bot)).thenReturn(-1F);
            deaths.when(() -> ScarpetNativeDeaths.shakeOff(fixture.bot)).thenReturn(CompletableFuture.completedFuture(null));
            deaths.when(() -> ScarpetNativeDeaths.defer(eq(fixture.bot), eq(fixture.source), any(Runnable.class)))
                    .thenAnswer(call -> { ScarpetNativeWork.record(physicalDeath); return true; });
            deaths.when(() -> ScarpetNativeDeaths.afterDeath(eq(fixture.bot), any(Runnable.class), any(java.util.function.BooleanSupplier.class)))
                    .thenCallRealMethod();
            ScarpetPlayerInventoryGate.trackAccepted(fixture.bot, independent);
            Supplier<Boolean> nativeDamage = () -> {
                var actual = ScarpetNativeDeaths.afterDeath(fixture.bot, () -> fixture.bot.die(fixture.source), () -> true);
                damage.set(actual);
                ScarpetDamageContinuations.publishBodyResult(fixture.bot, actual);
                // Player.hurtServer appends this sibling shoulder-release phase
                // after LivingEntity published the real asynchronous death result.
                if (nativeCommand) ScarpetAttackContinuations.afterDamage(fixture.bot, fixture.bot, actual, hurt -> {
                    assertTrue(hurt);
                    fixture.postDamageTails.incrementAndGet();
                    ScarpetNativeWork.record(fixture.postDamageChild);
                    return null;
                });
                else if (typedTail) ScarpetAttackContinuations.afterDamageNativeAsync(fixture.bot, actual, hurt -> {
                    assertTrue(hurt);
                    fixture.postDamageTails.incrementAndGet();
                    ScarpetNativeWork.record(fixture.postDamageChild);
                    return fixture.postDamageChild.thenApply(ignored -> true);
                });
                return false;
            };
            Supplier<Boolean> withParrots = () -> {
                if (!parrots) return nativeDamage.get();
                var actual = CarpetParrotDamageContinuations.hurt(fixture.bot, 12F, nativeDamage);
                return actual.isDone() && !actual.isCompletedExceptionally() && actual.getNow(false);
            };
            Supplier<Boolean> damageEntry = () -> OrgSafeAfk.withDamageOuter(fixture.bot, fixture.source, 12F, () -> betterTotem
                    ? OrgShadowDamageContinuations.hurt(fixture.bot, withParrots) : withParrots.get());
            if (nativeCommand) {
                doAnswer(call -> { assertFalse(damageEntry.get()); return null; }).when(fixture.bot).kill(fixture.world);
                assertEquals(1, CarpetKillCommand.execute(fixture.command, List.of(fixture.bot)));
                assertFalse(commandScope.resultFuture(fixture.command).isDone());
            } else assertFalse(damageEntry.get());
            assertFalse(damage.get().isDone());
            physicalDeath.complete(null);
            assertEquals(0, fixture.cleanups.get());
            assertTrue(fixture.queued.isEmpty());
            independent.complete(null);
            fixture.runCleanup();
            assertFalse(damage.get().isDone());
            fixture.cleanupChild.complete(null);
            assertTrue(damage.get().isDone());
            assertTrue(damage.get().join());
            if (nativeCommand || typedTail) {
                assertEquals(1, fixture.postDamageTails.get());
                assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());
                if (nativeCommand) {
                    assertFalse(commandScope.resultFuture(fixture.command).isDone());
                    verifyNoInteractions(fixture.callback);
                }
                fixture.postDamageChild.complete(null);
            }
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.bot));
            assertTrue(fixture.removed.get());
            if (nativeCommand) {
                assertEquals(1, commandScope.resultFuture(fixture.command).join());
                verify(fixture.callback).onResult(true, 1);
            }
            var restored = ServerBot.class.getDeclaredField("carpetRestoreAfterDeath");
            restored.setAccessible(true);
            assertTrue(restored.getBoolean(fixture.bot));
        } finally {
            GeneralCompatConfig.persistentParrots = previousParrots;
            GeneralCompatConfig.betterTotemOfUndying = previousTotem;
        }
    }

    @Test void typedPostDamageRemovalRecognizesItsOwnSuffixObserverBeforeTakingTheInventorySnapshot() throws Exception {
        var fixture = new Fixture();
        var physicalDamage = new CompletableFuture<Void>();
        var independent = new CompletableFuture<Void>();
        try (var ticks = fixture.ticks()) {
            ScarpetPlayerInventoryGate.trackAccepted(fixture.bot, independent);
            var outcome = ScarpetNativeWork.observeNative(fixture.bot, () -> {
                ScarpetNativeWork.record(physicalDamage);
                return true;
            });
            var suffix = ScarpetAttackContinuations.afterDamageNativeAsync(fixture.bot, outcome, hurt -> {
                assertTrue(hurt);
                return fixture.bots.carpetRemoveBotAsync(fixture.bot,
                        org.leavesmc.leaves.event.bot.BotRemoveEvent.RemoveReason.COMMAND, null, true, false);
            });
            physicalDamage.complete(null);
            assertEquals(0, fixture.cleanups.get());
            independent.complete(null);
            fixture.runCleanup();
            assertFalse(suffix.isDone());
            fixture.cleanupChild.complete(null);
            assertTrue(suffix.join());
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.bot));
        }
    }

    @Test void realNonlethalSafeAfkRemovalRecognizesItsPostDamageWaiterAndRetainsTheCleanupTail() throws Exception {
        var fixture = new Fixture();
        when(fixture.bot.getHealth()).thenReturn(4F);
        var physicalDamage = new CompletableFuture<Void>();
        var independent = new CompletableFuture<Void>();
        try (var ticks = fixture.ticks(); var manager = mockStatic(OrgPlayerManager.class);
             var regions = mockStatic(RegionizedServer.class)) {
            manager.when(() -> OrgPlayerManager.safeThreshold(fixture.bot)).thenReturn(5F);
            regions.when(RegionizedServer::isGlobalTickThread).thenReturn(true);
            ScarpetPlayerInventoryGate.trackAccepted(fixture.bot, independent);
            assertFalse(OrgSafeAfk.withDamageOuter(fixture.bot, fixture.source, 8F, () -> {
                var actual = ScarpetNativeWork.observeNative(fixture.bot, () -> {
                    ScarpetNativeWork.record(physicalDamage);
                    return true;
                });
                ScarpetDamageContinuations.publishBodyResult(fixture.bot, actual);
                return false;
            }));
            physicalDamage.complete(null);
            assertEquals(0, fixture.cleanups.get());
            assertTrue(fixture.queued.isEmpty());
            independent.complete(null);
            fixture.runCleanup();
            fixture.cleanupChild.complete(null);
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.bot));
            verify(fixture.bot.getFoodData()).setFoodLevel(20);
        }
    }
}
