package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.Foods;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Production hidden command, cycle, tracker and inventory branches on actual serial actor queues.
 */
class OrgHiddenAiSourceBranchesTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
        for (var item : List.of(Items.SHULKER_BOX, Items.TORCH, Items.BREAD, Items.FISHING_ROD, Items.PISTON, Items.LEVER, Items.WHEAT_SEEDS)) {
            try {
                item.builtInRegistryHolder().components();
            } catch (NullPointerException unbound) {
                item.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
            }
        }
    }

    private static java.lang.reflect.Field field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(Object target, String name) {
        try {
            var method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void bindHands(ServerPlayer bot) throws Exception {
        when(bot.getMainHandItem()).thenAnswer(call -> bot.getInventory().getSelectedItem());
        when(bot.getOffhandItem()).thenAnswer(call -> bot.getInventory().getItem(40));
        when(bot.getItemInHand(any())).thenAnswer(call -> call.getArgument(0) == InteractionHand.OFF_HAND ? bot.getOffhandItem() : bot.getMainHandItem());
        doAnswer(call -> {
            bot.getInventory().setItem(call.getArgument(0) == InteractionHand.OFF_HAND ? 40 : bot.getInventory().getSelectedSlot(), call.getArgument(1));
            return null;
        }).when(bot).setItemInHand(any(), any());
        when(bot.blockPosition()).thenReturn(BlockPos.ZERO);
        when(bot.position()).thenReturn(Vec3.ZERO);
        when(bot.getDisplayName()).thenReturn(Component.literal("fake"));
        var pack = ServerPlayer.class.getField("carpetActionPack");
        pack.setAccessible(true);
        pack.set(bot, mock(CarpetPlayerActionPack.class));
        var slots = net.minecraft.world.inventory.AbstractContainerMenu.class.getField("slots");
        slots.setAccessible(true);
        slots.set(bot.containerMenu, net.minecraft.core.NonNullList.create());
    }

    private static CompletableFuture<Void> observed(OrgInventoryPersistenceTest.Fixture fixture, OrgHiddenPlayerActions.Engine engine, Supplier<CompletableFuture<?>> work) {
        fixture.owner.set(engine.player);
        return ScarpetNativeWork.observeNative(engine.player, () -> {
            engine.jobToken = ScarpetNativeWork.capture();
            ScarpetNativeWork.record(work.get());
            return null;
        });
    }

    private static void pump(OrgInventoryPersistenceTest.Fixture fixture, CompletableFuture<?> completion) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!completion.isDone() && System.nanoTime() < deadline) {
            fixture.drain(fixture.viewer);
            fixture.drain(fixture.target);
            Thread.sleep(1);
        }
        assertTrue(completion.isDone());
        completion.join();
    }

    private static OrgHiddenPlayerActions.Engine gotoEntity(ServerPlayer bot, Entity target) throws Exception {
        Class<?> type = Class.forName(OrgHiddenPlayerActions.class.getName() + "$Goto");
        var constructor = type.getDeclaredConstructor(ServerPlayer.class, BlockPos.class, Entity.class);
        constructor.setAccessible(true);
        var action = (OrgHiddenPlayerActions.Engine) constructor.newInstance(bot, null, target);
        var path = mock(OrgHiddenPathfinder.class);
        when(path.isFinished()).thenReturn(true);
        field(action, "pathfinder").set(action, path);
        return action;
    }

    @Test
    void finishedGotoStillRefreshesSmallEntityMovementOnTheSourceSixtyTickSchedule() throws Exception {
        try (var areas = mockConstruction(CarpetPlayerTargetArea.class, (area, context) -> when(area.ready(any(), any())).thenReturn(true)); var f = new OrgInventoryPersistenceTest.Fixture(directory, true); var servers = mockStatic(net.minecraft.server.MinecraftServer.class, CALLS_REAL_METHODS)) {
            var bot = f.target.player();
            bindHands(bot);
            f.owner.set(bot);
            var entity = f.viewer.player();
            var world = bot.level();
            when(entity.level()).thenReturn(world);
            AtomicLong time = new AtomicLong();
            AtomicReference<Vec3> position = new AtomicReference<>(new Vec3(10.5, 0, 0.5));
            servers.when(net.minecraft.server.MinecraftServer::getServer).thenReturn(f.server);
            when(bot.level().getGameTime()).thenAnswer(call -> time.get());
            when(entity.position()).thenAnswer(call -> position.get());
            when(entity.blockPosition()).thenAnswer(call -> BlockPos.containing(position.get()));
            var action = gotoEntity(bot, entity);
            pump(f, observed(f, action, action::tick));
            assertEquals(new BlockPos(10, 0, 0), field(action, "target").get(action));
            position.set(new Vec3(12.5, 0, 0.5));
            for (long tick : new long[]{1, 30, 59}) {
                time.set(tick);
                pump(f, observed(f, action, action::tick));
                assertEquals(new BlockPos(10, 0, 0), field(action, "target").get(action));
            }
            time.set(60);
            pump(f, observed(f, action, action::tick));
            assertEquals(new BlockPos(12, 0, 0), field(action, "target").get(action));
        }
    }

    @Test
    void finishedGotoForcedLargeMovementDoesNotResetItsPeriodicCacheClock() throws Exception {
        try (var areas = mockConstruction(CarpetPlayerTargetArea.class, (area, context) -> when(area.ready(any(), any())).thenReturn(true)); var f = new OrgInventoryPersistenceTest.Fixture(directory, true); var servers = mockStatic(net.minecraft.server.MinecraftServer.class, CALLS_REAL_METHODS)) {
            var bot = f.target.player();
            bindHands(bot);
            f.owner.set(bot);
            var entity = f.viewer.player();
            var world = bot.level();
            when(entity.level()).thenReturn(world);
            AtomicLong time = new AtomicLong();
            AtomicReference<Vec3> position = new AtomicReference<>(new Vec3(10.5, 0, 0.5));
            servers.when(net.minecraft.server.MinecraftServer::getServer).thenReturn(f.server);
            when(bot.level().getGameTime()).thenAnswer(call -> time.get());
            when(entity.position()).thenAnswer(call -> position.get());
            when(entity.blockPosition()).thenAnswer(call -> BlockPos.containing(position.get()));
            var action = gotoEntity(bot, entity);
            pump(f, observed(f, action, action::tick));
            time.set(20);
            position.set(new Vec3(14.5, 0, 0.5));
            pump(f, observed(f, action, action::tick));
            assertEquals(new BlockPos(14, 0, 0), field(action, "target").get(action));
            assertEquals(0L, field(action, "lastUpdate").get(action));
            time.set(60);
            position.set(new Vec3(16.5, 0, 0.5));
            pump(f, observed(f, action, action::tick));
            assertEquals(new BlockPos(16, 0, 0), field(action, "target").get(action));
        }
    }

    @Test
    void actualBedrockMinuteThreeWarningsAndSoundWaitTheirRecipientAndLateNativeChildren() throws Exception {
        try (var areas = mockConstruction(CarpetPlayerTargetArea.class, (area, context) -> when(area.ready(any(), any())).thenReturn(true)); var f = new OrgInventoryPersistenceTest.Fixture(directory, true); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            var server = mock(io.papermc.paper.threadedregions.RegionizedServer.class);
            var queue = new ArrayDeque<Runnable>();
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(server);
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(false);
            doAnswer(call -> {
                queue.add(call.getArgument(0));
                return null;
            }).when(server).addTask(any());
            var bot = f.target.player();
            bindHands(bot);
            f.owner.set(bot);
            when(bot.getScoreboardName()).thenReturn("fake");
            when(f.viewer.player().blockPosition()).thenReturn(BlockPos.ZERO);
            var action = new OrgHiddenBedrock(bot, OrgHiddenBedrockSelection.cuboid(BlockPos.ZERO, BlockPos.ZERO), true, false);
            field(action, "nonAction").setLong(action, 3599);
            var packet = new CompletableFuture<Void>();
            var late = new CompletableFuture<Void>();
            var sound = new CompletableFuture<Void>();
            var messages = new ArrayList<Component>();
            doAnswer(call -> {
                assertSame(f.viewer.player(), f.owner.get());
                messages.add(call.getArgument(0));
                ScarpetNativeWork.record(packet);
                if (messages.size() == 1)
                    packet.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored, failure) -> ScarpetNativeWork.record(late)));
                return null;
            }).when(f.viewer.player()).sendSystemMessage(any(Component.class));
            var recipientWorld = f.viewer.player().level();
            doAnswer(call -> {
                assertSame(f.viewer.player(), f.owner.get());
                ScarpetNativeWork.record(sound);
                return null;
            }).when(recipientWorld).playSound(isNull(), anyDouble(), anyDouble(), anyDouble(), eq(net.minecraft.sounds.SoundEvents.ANVIL_PLACE), eq(net.minecraft.sounds.SoundSource.PLAYERS), eq(1F), eq(1F));
            var parent = observed(f, action, () -> (CompletableFuture<?>) invoke(action, "cycle"));
            var idle = ScarpetNativeWork.whenIdle(f.server);
            assertFalse(parent.isDone());
            assertEquals(3, queue.size());
            assertTrue(messages.isEmpty());
            while (!queue.isEmpty()) queue.remove().run();
            f.drain(f.viewer);
            assertEquals(3, messages.size());
            assertEquals("", messages.getFirst().getString());
            assertEquals("carpet-org-addition.command.playerAction.bedrock.no_action.first", ((net.minecraft.network.chat.contents.TranslatableContents) messages.get(1).getContents()).getKey());
            assertEquals("carpet-org-addition.command.playerAction.bedrock.no_action.second", ((net.minecraft.network.chat.contents.TranslatableContents) messages.get(2).getContents()).getKey());
            for (int index = 1; index <= 2; index++) {
                assertTrue(messages.get(index).getStyle().isItalic());
                assertEquals(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GRAY), messages.get(index).getStyle().getColor());
            }
            assertFalse(parent.isDone());
            assertFalse(idle.isDone());
            packet.complete(null);
            sound.complete(null);
            assertFalse(parent.isDone());
            late.complete(null);
            pump(f, parent);
            idle.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void actualBedrockWarningRecipientFailureRemainsANativeParentFailure() throws Exception {
        try (var areas = mockConstruction(CarpetPlayerTargetArea.class, (area, context) -> when(area.ready(any(), any())).thenReturn(true)); var f = new OrgInventoryPersistenceTest.Fixture(directory, true); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);
            var bot = f.target.player();
            bindHands(bot);
            f.owner.set(bot);
            when(f.viewer.player().blockPosition()).thenReturn(BlockPos.ZERO);
            var action = new OrgHiddenBedrock(bot, OrgHiddenBedrockSelection.cuboid(BlockPos.ZERO, BlockPos.ZERO), true, false);
            field(action, "nonAction").setLong(action, 4799);
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(child);
                return null;
            }).when(f.viewer.player()).sendSystemMessage(any(Component.class));
            var parent = observed(f, action, () -> (CompletableFuture<?>) invoke(action, "cycle"));
            f.drain(f.viewer);
            assertFalse(parent.isDone());
            child.completeExceptionally(new IllegalStateException("actual bedrock recipient failed"));
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, parent::join)));
        }
    }

    @Test
    void actualHiddenBedrockCommandPreservesFlagsAndWaitsForTheSourceOverlayReminder() throws Exception {
        try (var areas = mockConstruction(CarpetPlayerTargetArea.class, (area, context) -> when(area.ready(any(), any())).thenReturn(true)); var f = new OrgInventoryPersistenceTest.Fixture(directory, true); var hidden = mockStatic(OrgHiddenPlayerActions.class, CALLS_REAL_METHODS); var scope = CarpetAsyncCommandResults.open()) {
            hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(true);
            hidden.when(OrgHiddenPlayerActions::debug).thenReturn(false);
            var bot = f.target.player();
            bindHands(bot);
            when(f.viewer.player().blockPosition()).thenReturn(BlockPos.ZERO);
            when(f.server.getPlayerList().getPlayerByName("fake")).thenReturn(bot);
            var source = mock(CommandSourceStack.class);
            when(source.getServer()).thenReturn(f.server);
            when(source.getEntity()).thenReturn(f.viewer.player());
            when(source.getPlayer()).thenReturn(f.viewer.player());
            when(source.permissions()).thenReturn(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
            when(source.callback()).thenReturn(net.minecraft.commands.CommandResultCallback.EMPTY);
            var commandWorld = bot.level();
            when(source.getPosition()).thenReturn(Vec3.ZERO);
            when(source.getRotation()).thenReturn(net.minecraft.world.phys.Vec2.ZERO);
            when(source.getLevel()).thenReturn(commandWorld);
            var overlay = new CompletableFuture<Void>();
            var overlays = new AtomicInteger();
            doAnswer(call -> {
                assertSame(f.viewer.player(), f.owner.get());
                overlays.incrementAndGet();
                ScarpetNativeWork.record(overlay);
                return null;
            }).when(f.viewer.player()).sendOverlayMessage(any(Component.class));
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            OrgHiddenActionCommands.register(dispatcher);
            f.owner.set(null);
            var parent = ScarpetNativeWork.observeNative(f.viewer.player(), () -> {
                try {
                    return dispatcher.execute("playerAction fake bedrock cylinder 0 0 0 2 3 true true", source);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            var result = scope.resultFuture(source);
            assertNotNull(result);
            assertFalse(result.isDone());
            f.drain(f.target);
            f.drain(f.viewer);
            assertEquals(1, overlays.get());
            assertFalse(result.isDone());
            assertFalse(parent.isDone());
            overlay.complete(null);
            pump(f, result);
            assertEquals(1, result.join());
            parent.get(3, TimeUnit.SECONDS);
            f.owner.set(bot);
            var data = OrgHiddenPlayerActions.get(bot).getAsJsonObject("data");
            assertEquals("cylinder", data.get("region_type").getAsString());
            assertEquals(2, data.get("radius").getAsInt());
            assertEquals(3, data.get("height").getAsInt());
            assertTrue(data.get("ai").getAsBoolean());
            assertTrue(data.get("timed_material_recycling").getAsBoolean());
            OrgHiddenPlayerActions.onRetired(bot);
            OrgServerPermissions.close(f.server);
        }
    }

    @Test
    void realInventoryContainsOnlyReadsSingleOperableBoxesAndHonorsTheHandlingFlag() throws Exception {
        boolean previous = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerShulkerBoxItemHandling;
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var bot = f.target.player();
            bindHands(bot);
            var inventory = new OrgHiddenInventory(bot);
            var box = new ItemStack(Items.SHULKER_BOX, 2);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.TORCH, 3))));
            bot.getInventory().setItem(1, box);
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerShulkerBoxItemHandling = true;
            assertFalse(inventory.contains(stack -> stack.is(Items.TORCH)));
            box.setCount(1);
            assertTrue(inventory.contains(stack -> stack.is(Items.TORCH)));
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerShulkerBoxItemHandling = false;
            assertFalse(inventory.contains(stack -> stack.is(Items.TORCH)));
            bot.getInventory().setItem(40, new ItemStack(Items.TORCH));
            assertTrue(inventory.contains(stack -> stack.is(Items.TORCH)));
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerShulkerBoxItemHandling = previous;
        }
    }

    @Test
    void actualBedrockEatingDoesNotEnterEatForFoodInsideAnInoperableStackedBox() throws Exception {
        boolean previous = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerShulkerBoxItemHandling;
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var bot = f.target.player();
            bindHands(bot);
            f.owner.set(bot);
            when(bot.getAbilities()).thenReturn(new net.minecraft.world.entity.player.Abilities());
            FoodData food = new FoodData();
            food.setFoodLevel(10);
            when(bot.getFoodData()).thenReturn(food);
            var bread = new ItemStack(Items.BREAD);
            bread.set(DataComponents.FOOD, Foods.BREAD);
            var box = new ItemStack(Items.SHULKER_BOX, 2);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(bread)));
            bot.getInventory().setItem(1, box);
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerShulkerBoxItemHandling = true;
            var action = new OrgHiddenBedrock(bot, OrgHiddenBedrockSelection.cuboid(BlockPos.ZERO, BlockPos.ZERO), true, false);
            assertEquals(false, invoke(action, "shouldEat"));
            box.setCount(1);
            assertEquals(true, invoke(action, "shouldEat"));
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerShulkerBoxItemHandling = previous;
        }
    }

    @Test
    void actualFishingSnagSwapStillSelectsNonRodAndRunsIndependentNibbleUse() throws Exception {
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var bot = (ServerBot) f.target.player();
            bindHands(bot);
            f.owner.set(bot);
            bot.getInventory().setItem(0, new ItemStack(Items.FISHING_ROD));
            bot.getInventory().setItem(1, new ItemStack(Items.DIAMOND));
            bot.getInventory().setItem(40, new ItemStack(Items.FISHING_ROD));
            var hook = mock(FishingHook.class);
            when(hook.onGround()).thenReturn(true);
            hook.nibble = 1;
            bot.fishing = hook;
            f.ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(hook)).thenReturn(true);
            OrgFakePlayerActions.set(bot, OrgFakePlayerActions.Action.simple("fishing", List.of()));
            var states = OrgFakePlayerActions.class.getDeclaredField("STATES");
            states.setAccessible(true);
            var state = ((carpet.script.external.WeakIdentityMap<net.minecraft.server.level.ServerPlayer, ?>) states.get(null)).get(bot);
            var method = OrgFakePlayerActions.class.getDeclaredMethod("fishing", ServerBot.class, state.getClass());
            method.setAccessible(true);
            var actions = new ArrayList<CarpetPlayerActionPack.ActionType>();
            var child = new CompletableFuture<Void>();
            when(bot.carpetActionPack.start(any(), any())).thenAnswer(call -> {
                actions.add(call.getArgument(0));
                ScarpetNativeWork.record(child);
                return bot.carpetActionPack;
            });
            var parent = ScarpetNativeWork.observeNative(bot, () -> {
                try {
                    method.invoke(null, bot, state);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return null;
            });
            assertEquals(List.of(CarpetPlayerActionPack.ActionType.SWAP_HANDS, CarpetPlayerActionPack.ActionType.USE), actions);
            assertFalse(bot.getMainHandItem().is(Items.FISHING_ROD));
            assertEquals(10, field(state, "timer").get(state));
            assertFalse(parent.isDone());
            child.complete(null);
            parent.get(3, TimeUnit.SECONDS);
            OrgFakePlayerActions.set(bot, OrgFakePlayerActions.Action.simple("stop", List.of()));
        }
    }

    @Test
    void actualPlantTickWaitsTheRealOffhandUseBeforeSwingAndCompletion() throws Exception {
        try (var areas = mockConstruction(CarpetPlayerTargetArea.class, (area, context) -> when(area.ready(any(), any())).thenReturn(true)); var f = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var bot = f.target.player();
            bindHands(bot);
            f.owner.set(bot);
            bot.getInventory().setItem(40, new ItemStack(Items.WHEAT_SEEDS, 2));
            when(bot.isCreative()).thenReturn(true);
            var world = bot.level();
            var floor = mock(net.minecraft.world.level.block.state.BlockState.class);
            when(floor.is(net.minecraft.tags.BlockTags.SUPPORTS_CROPS)).thenReturn(true);
            when(world.getBlockState(BlockPos.ZERO)).thenReturn(floor);
            when(world.getBlockState(BlockPos.ZERO.above())).thenReturn(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            when(bot.isWithinBlockInteractionRange(BlockPos.ZERO, 0)).thenReturn(true);
            var mode = mock(net.minecraft.server.level.ServerPlayerGameMode.class);
            var modeField = ServerPlayer.class.getField("gameMode");
            modeField.setAccessible(true);
            modeField.set(bot, mode);
            var used = new CompletableFuture<Void>();
            var late = new CompletableFuture<Void>();
            var calls = new AtomicInteger();
            when(mode.useItemOn(eq(bot), eq(world), any(ItemStack.class), eq(InteractionHand.OFF_HAND), any())).thenAnswer(call -> {
                assertSame(bot, f.owner.get());
                calls.incrementAndGet();
                var hit = call.<net.minecraft.world.phys.BlockHitResult>getArgument(4);
                assertEquals(BlockPos.ZERO.above(), hit.getBlockPos());
                assertEquals(net.minecraft.core.Direction.UP, hit.getDirection());
                assertFalse(hit.isInside());
                ScarpetNativeWork.record(used);
                used.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored, failure) -> ScarpetNativeWork.record(late)));
                return net.minecraft.world.InteractionResult.SUCCESS;
            });
            var action = new OrgHiddenPlant(bot);
            var parent = observed(f, action, action::tick);
            assertEquals(1, calls.get());
            assertFalse(parent.isDone());
            verify(bot, never()).swing(any(), any(), anyBoolean());
            used.complete(null);
            assertFalse(parent.isDone());
            verify(bot, never()).swing(any(), any(), anyBoolean());
            late.complete(null);
            pump(f, parent);
            verify(bot).swing(eq(InteractionHand.OFF_HAND), any(), eq(true));
        }
    }

    @Test
    void realEntityGotoAssignmentCapturesTheSourceDisplayNameOnItsOwnerAndRetainsItForInfo() throws Exception {
        try (var areas = mockConstruction(CarpetPlayerTargetArea.class, (area, context) -> when(area.ready(any(), any())).thenReturn(true)); var f = new OrgInventoryPersistenceTest.Fixture(directory, true); var servers = mockStatic(net.minecraft.server.MinecraftServer.class, CALLS_REAL_METHODS); var hidden = mockStatic(OrgHiddenPlayerActions.class, CALLS_REAL_METHODS)) {
            servers.when(net.minecraft.server.MinecraftServer::getServer).thenReturn(f.server);
            hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(true);
            var bot = f.target.player();
            bindHands(bot);
            f.owner.set(bot);
            when(f.viewer.player().getDisplayName()).thenAnswer(call -> {
                assertSame(f.viewer.player(), f.owner.get());
                return Component.literal("original target");
            });
            var parent = ScarpetNativeWork.observeNative(bot, () -> {
                OrgHiddenPlayerActions.setGotoEntity(bot, f.viewer.player());
                return null;
            });
            assertFalse(parent.isDone());
            pump(f, parent);
            f.owner.set(bot);
            doReturn(Component.literal("later target")).when(f.viewer.player()).getDisplayName();
            var info = OrgHiddenPlayerActions.info(bot);
            assertEquals(1, info.size());
            var translation = (net.minecraft.network.chat.contents.TranslatableContents) info.getFirst().getContents();
            assertEquals("carpet-org-addition.command.playerAction.goto.info.entity", translation.getKey());
            assertEquals("original target", ((Component) translation.getArgs()[1]).getString());
            OrgHiddenPlayerActions.onRetired(bot);
        }
    }

    @Test
    void actualPublicInfoCommandEmitsHiddenSourceLinesAndWaitsTheirNativeFeedback() throws Exception {
        try (var areas = mockConstruction(CarpetPlayerTargetArea.class, (area, context) -> when(area.ready(any(), any())).thenReturn(true)); var f = new OrgInventoryPersistenceTest.Fixture(directory, true); var hidden = mockStatic(OrgHiddenPlayerActions.class, CALLS_REAL_METHODS); var scope = CarpetAsyncCommandResults.open()) {
            hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(true);
            var bot = f.target.player();
            bindHands(bot);
            f.owner.set(bot);
            OrgHiddenPlayerActions.setBedrockCylinder(bot, BlockPos.ZERO, 2, 3, true, true);
            when(f.server.getPlayerList().getPlayerByName("fake")).thenReturn(bot);
            when(f.viewer.player().blockPosition()).thenReturn(BlockPos.ZERO);
            var enchantments = new net.minecraft.core.MappedRegistry<net.minecraft.world.item.enchantment.Enchantment>(net.minecraft.core.registries.Registries.ENCHANTMENT, com.mojang.serialization.Lifecycle.stable());
            enchantments.freeze();
            var lookup = net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.concat(net.minecraft.core.registries.BuiltInRegistries.REGISTRY.stream().map(registry -> (net.minecraft.core.HolderLookup.RegistryLookup<?>) registry), java.util.stream.Stream.of(enchantments)));
            var context = net.minecraft.commands.CommandBuildContext.simple(lookup, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            OrgFakePlayerActionCommands.register(dispatcher, context);
            var source = mock(CommandSourceStack.class);
            when(source.getServer()).thenReturn(f.server);
            when(source.getEntity()).thenReturn(f.viewer.player());
            when(source.getPlayer()).thenReturn(f.viewer.player());
            when(source.permissions()).thenReturn(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
            when(source.callback()).thenReturn(net.minecraft.commands.CommandResultCallback.EMPTY);
            var child = new CompletableFuture<Void>();
            var lines = new ArrayList<Component>();
            doAnswer(call -> {
                assertSame(f.viewer.player(), f.owner.get());
                lines.add(call.getArgument(0));
                ScarpetNativeWork.record(child);
                return null;
            }).when(f.viewer.player()).sendSystemMessage(any(Component.class));
            f.owner.set(null);
            var parent = ScarpetNativeWork.observeNative(f.viewer.player(), () -> {
                try {
                    return dispatcher.execute("playerAction fake info", source);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            var result = scope.resultFuture(source);
            assertNotNull(result);
            f.drain(f.target);
            f.drain(f.viewer);
            assertEquals(5, lines.size());
            for (int index = 0; index < 5; index++)
                assertInstanceOf(net.minecraft.network.chat.contents.TranslatableContents.class, lines.get(index).getContents());
            assertFalse(parent.isDone());
            assertFalse(result.isDone());
            child.complete(null);
            pump(f, result);
            parent.get(3, TimeUnit.SECONDS);
            f.owner.set(bot);
            OrgHiddenPlayerActions.onRetired(bot);
        }
    }

    @Test
    void actualPathAndUnsubscribePacketsWaitTheirRealGlobalRecipientAndLateNativeChildren() throws Exception {
        for (boolean clear : new boolean[]{false, true})
            try (var f = new OrgInventoryPersistenceTest.Fixture(directory.resolve("packet-" + clear), true); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class); var servers = mockStatic(net.minecraft.server.MinecraftServer.class, CALLS_REAL_METHODS); var loggers = mockStatic(fun.bm.lophine.protocol.CarpetLoggerProtocol.class); var protocol = mockStatic(org.leavesmc.leaves.protocol.core.ProtocolUtils.class); var hidden = mockStatic(OrgHiddenPlayerActions.class, CALLS_REAL_METHODS)) {
                hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(true);
                servers.when(net.minecraft.server.MinecraftServer::getServer).thenReturn(f.server);
                var queue = new ArrayDeque<Runnable>();
                var global = mock(io.papermc.paper.threadedregions.RegionizedServer.class);
                globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);
                globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(false);
                doAnswer(call -> {
                    queue.add(call.getArgument(0));
                    return null;
                }).when(global).addTask(any());
                var bot = f.target.player();
                bindHands(bot);
                when(bot.getId()).thenReturn(7);
                when(f.viewer.player().blockPosition()).thenReturn(BlockPos.ZERO);
                when(f.viewer.player().getScoreboardName()).thenReturn("viewer");
                when(f.server.getPlayerList().getPlayerByName("viewer")).thenReturn(f.viewer.player());
                loggers.when(() -> fun.bm.lophine.protocol.CarpetLoggerProtocol.hasSubscribers("fakePlayerPathfinding")).thenReturn(true);
                loggers.when(() -> fun.bm.lophine.protocol.CarpetLoggerProtocol.subscriptions("viewer")).thenReturn(clear ? java.util.Map.of() : java.util.Map.of("fakePlayerPathfinding", ""));
                var packet = new CompletableFuture<Void>();
                var late = new CompletableFuture<Void>();
                var sent = new AtomicReference<byte[]>();
                protocol.when(() -> org.leavesmc.leaves.protocol.core.ProtocolUtils.sendRawPayloadPacket(eq(f.viewer.player()), any(net.minecraft.resources.Identifier.class), any(byte[].class))).thenAnswer(call -> {
                    assertSame(f.viewer.player(), f.owner.get());
                    assertEquals("carpet-org-addition:fake_player_pathfinder", call.getArgument(1).toString());
                    sent.set(call.getArgument(2));
                    ScarpetNativeWork.record(packet);
                    packet.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored, failure) -> ScarpetNativeWork.record(late)));
                    return null;
                });
                f.owner.set(bot);
                var parent = ScarpetNativeWork.observeNative(bot, () -> {
                    if (clear) OrgHiddenPathProtocol.subscriptionChanged("viewer");
                    else OrgHiddenPathProtocol.path(bot, List.of(Vec3.ZERO, new Vec3(1, 2, 3)));
                    return null;
                });
                assertFalse(parent.isDone());
                assertNull(sent.get());
                assertEquals(1, queue.size());
                queue.remove().run();
                f.drain(f.viewer);
                assertNotNull(sent.get());
                var bytes = new java.io.DataInputStream(new java.io.ByteArrayInputStream(sent.get()));
                assertEquals(clear ? -1 : 7, bytes.readInt());
                assertEquals(clear ? 0 : 2, bytes.readInt());
                assertFalse(parent.isDone());
                packet.complete(null);
                assertFalse(parent.isDone());
                late.complete(null);
                parent.get(3, TimeUnit.SECONDS);
            }
    }
}
