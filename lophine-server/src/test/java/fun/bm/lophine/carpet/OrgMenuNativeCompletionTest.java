package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgMenuNativeCompletionTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private static CommandSourceStack source(OrgInventoryPersistenceTest.Fixture fixture) {
        var source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(fixture.server);
        when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
        return source;
    }

    @Test
    void queuedNativeMenuEffectsAreRegisteredBeforeOwnerAndCallerCancelCannotEndTheirRealChildren() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player();
            fixture.owner.set(null);
            var child = new CompletableFuture<Void>();
            var calls = new AtomicInteger();
            var caller = OrgMenuNativeEffects.run(player, () -> {
                calls.incrementAndGet();
                ScarpetNativeWork.record(child);
                return true;
            });
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            assertTrue(caller.cancel(false));
            fixture.drain(fixture.viewer);
            assertEquals(1, calls.get());
            assertFalse(idle.isDone());
            child.complete(null);
            idle.join();
            assertTrue(caller.isCancelled());
        }
    }

    @Test
    void anUnstartedPausedMenuIntentDoesNotBlockAStableSnapshotAndOnlyStartsWhenTheGateReallyOpens() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player();
            fixture.owner.set(player);
            var held = new CompletableFuture<Void>();
            var pause = ScarpetPlayerInventoryGate.whenIdle(player, () -> held);
            var calls = new AtomicInteger();
            var menu = OrgMenuNativeEffects.run(player, () -> {
                calls.incrementAndGet();
                return false;
            });
            fixture.drain(fixture.viewer);
            assertEquals(0, calls.get());
            assertFalse(menu.isDone());
            var stable = ScarpetPlayerInventoryGate.whenIdleForRemoval(player, () -> "stable");
            fixture.drain(fixture.viewer);
            assertEquals("stable", stable.join());
            assertEquals(0, calls.get());
            held.complete(null);
            fixture.drain(fixture.viewer);
            assertFalse(menu.join());
            pause.join();
            assertEquals(1, calls.get());
        }
    }

    @Test
    void anOrdinaryAutoNativeCommandWaitsActualMenuChildrenAndReportsTheRealCancelledOpenResult() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            var player = fixture.viewer.player();
            fixture.owner.set(player);
            var source = source(fixture);
            var child = new CompletableFuture<Void>();
            var nativeCommand = ScarpetNativeWork.observeNative(player, () -> OrgMenuNativeEffects.snapshotCommand(source, player, () -> OrgMenuNativeEffects.run(player, () -> {
                ScarpetNativeWork.record(child);
                return false;
            }), "Cancelled menu"));
            var commandResult = scope.resultFuture(source);
            assertNotNull(commandResult, "The normal ExecuteCommand C token is not a causal parent");
            assertFalse(commandResult.isDone());
            assertFalse(nativeCommand.isDone());
            child.complete(null);
            assertEquals(0, commandResult.join());
            assertEquals(1, nativeCommand.join());
        }
    }

    @Test
    void aTrueNativeCallbackSnapshotReturnsAdmissionOnceAndItsExternalJobHasNoOriginalParentOrAcceptedIdentity() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player();
            fixture.owner.set(player);
            var source = source(fixture);
            var output = source(fixture);
            when(source.withCallback(CommandResultCallback.EMPTY)).thenReturn(output);
            var callbacks = new AtomicInteger();
            CommandResultCallback callback = (success, result) -> callbacks.incrementAndGet();
            when(source.callback()).thenReturn(callback);
            var parentToken = new AtomicReference<ScarpetNativeWork.Token>();
            var child = new CompletableFuture<Void>();
            var starts = new AtomicInteger();
            var parent = ScarpetNativeWork.observeNative(player, () -> {
                parentToken.set(ScarpetNativeWork.capture());
                ScarpetPlayerInventoryGate.trackAccepted(player, ScarpetNativeWork.completionOf(parentToken.get()));
                try (var scope = CarpetAsyncCommandResults.open()) {
                    int admitted = OrgMenuNativeEffects.snapshotCommand(source, player, () -> {
                        assertNull(ScarpetNativeWork.capture());
                        assertFalse(ScarpetPlayerInventoryGate.captureAccepted().contains(player));
                        return ScarpetPlayerInventoryGate.whenIdle(player, () -> "stable").thenCompose(snapshot -> OrgMenuNativeEffects.run(player, () -> {
                            starts.incrementAndGet();
                            ScarpetNativeWork.record(child);
                            return true;
                        }));
                    }, "Cannot open menu");
                    assertNull(scope.resultFuture(source));
                    source.callback().onResult(true, admitted);
                    return admitted;
                }
            });
            assertEquals(1, parent.join(), "The original damage callback can return without waiting on its own stable snapshot");
            assertEquals(1, callbacks.get());
            assertEquals(0, starts.get());
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            fixture.drain(fixture.viewer);
            assertEquals(1, starts.get());
            assertFalse(idle.isDone());
            child.complete(null);
            idle.join();
            assertEquals(1, callbacks.get());
        }
    }

    @Test
    void anActualOnlineInventoryOpenCancelledByItsProviderReportsFalseOnlyAfterItsRealNativeChildren() throws Exception {
        String previous = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.openPlayerInventory;
        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.openPlayerInventory = "any_player";
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player();
            fixture.owner.set(player);
            var child = new CompletableFuture<Void>();
            when(player.openMenu(any(net.minecraft.world.MenuProvider.class))).thenAnswer(call -> {
                ScarpetNativeWork.record(child);
                return java.util.OptionalInt.empty();
            });
            var selected = OrgPlayerInventoryMenus.interactAsync(player, player);
            assertNotNull(selected);
            assertFalse(selected.isDone());
            var global = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(global.isDone());
            child.complete(null);
            assertFalse(selected.join());
            global.join();
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.openPlayerInventory = "false";
            assertNull(OrgPlayerInventoryMenus.interactAsync(player, player));
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.openPlayerInventory = previous;
        }
    }

    @Test
    void actualRecipeRemovalReturnsRealGridMaterialsAndWaitsTheForeignFakeOwnersActionAssignment() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player();
            var fake = fixture.actor(org.leavesmc.leaves.bot.ServerBot.class);
            fixture.owner.set(player);
            when(fixture.server.getPlayerList().getPlayer(fake.id())).thenReturn(fake.player());
            when(player.getHealth()).thenReturn(20f);
            player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            var type = Class.forName("fun.bm.lophine.carpet.OrgFakePlayerRecipeMenus$CraftMenu");
            var constructor = type.getDeclaredConstructor(int.class, net.minecraft.world.entity.player.Inventory.class, net.minecraft.world.inventory.ContainerLevelAccess.class, java.util.UUID.class);
            constructor.setAccessible(true);
            var access = net.minecraft.world.inventory.ContainerLevelAccess.create(player.level(), net.minecraft.core.BlockPos.ZERO);
            var menu = (net.minecraft.world.inventory.CraftingMenu) constructor.newInstance(8, fixture.viewer.inventory(), access, fake.id());
            menu.craftSlots.getContents().set(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
            int before = fixture.viewer.inventory().getContents().stream().filter(stack -> stack.is(net.minecraft.world.item.Items.DIAMOND)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            var child = new CompletableFuture<Void>();
            var listener = mock(carpet.script.value.ScreenValue.ScarpetScreenHandlerListener.class);
            doAnswer(call -> {
                ScarpetNativeWork.record(child);
                return null;
            }).when(listener).onClose(player);
            var real = spy(menu);
            doReturn(java.util.List.of(listener)).when(real).carpetScreenListeners();
            var removal = OrgMenuNativeEffects.run(player, () -> {
                real.removed(player);
                return true;
            });
            assertFalse(removal.isDone());
            assertTrue(real.craftSlots.isEmpty());
            int after = fixture.viewer.inventory().getContents().stream().filter(stack -> stack.is(net.minecraft.world.item.Items.DIAMOND)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            assertEquals(before + 3, after);
            fixture.owner.set(fake.player());
            assertEquals("stop", OrgFakePlayerActions.get(fake.player()).kind());
            fixture.drain(fake);
            assertEquals("craft_inventory", OrgFakePlayerActions.get(fake.player()).kind());
            assertFalse(removal.isDone());
            child.complete(null);
            assertTrue(removal.join());
        }
    }

    private static final carpet.script.ScriptServer FILES = new carpet.script.ScriptServer() {
        @Override
        public Path resolveResource(String name) {
            return Path.of(name);
        }
    };

    private static final class Host extends carpet.script.ScriptHost {
        Host() {
            super(null, FILES, false, null, carpet.script.Expression.LoadOverride.DEFAULT);
        }

        @Override
        protected carpet.script.Module getModuleOrLibraryByName(String name) {
            return null;
        }

        @Override
        protected void runModuleCode(carpet.script.Context context, carpet.script.Module module) {
        }

        @Override
        protected carpet.script.ScriptHost duplicate() {
            return new Host();
        }
    }

    private static final class ReadyContext extends carpet.script.Context {
        ReadyContext(Host host) {
            super(host);
            initialize();
        }
    }

    @Test
    void aDeferredCausalCommandKeepsFlagsAfterHostCloseAndStripsEveryOriginalPhysicalIdentity() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player();
            fixture.owner.set(player);
            var host = new Host();
            var context = new ReadyContext(host);
            var calls = new AtomicInteger();
            var original = ScarpetNativeWork.observeNative(player, () -> {
                try (var frame = carpet.script.external.ScarpetRuntime.enterContext(context); var closing = carpet.script.external.ScarpetRuntime.closingHost(host); var damage = carpet.script.external.ScarpetRuntime.damageCallback(player); var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                    boolean oldFill = carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get(), oldGeneration = carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.get(), oldEvents = carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get();
                    try {
                        carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
                        carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.set(true);
                        carpet.script.external.ScarpetRuntime.EVENT_DISABLED.set(true);
                        return CarpetNativeActionContext.with(player, () -> carpet.script.external.ScarpetRuntime.nativeDeathDecision(player, () -> {
                            assertTrue(OrgDeferredPlayerCommands.deferIfCausal(player, () -> {
                                assertNull(ScarpetNativeWork.capture());
                                assertNull(CarpetNativeActionContext.current());
                                assertNull(carpet.script.external.ScarpetAttribution.capture());
                                assertFalse(ScarpetPlayerInventoryGate.captureAccepted().contains(player));
                                assertFalse(carpet.script.external.ScarpetRuntime.isDamageCallbackFor(player));
                                assertFalse(carpet.script.external.ScarpetRuntime.currentNativeDecision(player, carpet.script.external.ScarpetNativeDeaths.EVENT_KEY));
                                assertFalse(carpet.script.external.ScarpetRuntime.canRunClosingHost(host));
                                assertTrue(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
                                assertTrue(carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.get());
                                assertTrue(carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get());
                                calls.incrementAndGet();
                            }, failure -> fail(failure)));
                            assertTrue(carpet.script.external.ScarpetRuntime.isDamageCallbackFor(player));
                            assertSame(player, CarpetNativeActionContext.current());
                            assertTrue(ScarpetPlayerInventoryGate.captureAccepted().contains(player));
                            assertTrue(carpet.script.external.ScarpetRuntime.canRunClosingHost(host));
                            return CompletableFuture.completedFuture(null);
                        }));
                    } finally {
                        carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(oldFill);
                        carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.set(oldGeneration);
                        carpet.script.external.ScarpetRuntime.EVENT_DISABLED.set(oldEvents);
                    }
                }
            });
            original.join();
            host.onClose();
            assertEquals(0, calls.get());
            fixture.drain(fixture.viewer);
            assertEquals(1, calls.get());
            assertFalse(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertFalse(carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.get());
            assertFalse(carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get());
        }
    }

    @Test
    void anActualQueuedInventoryInteractionKeepsItsGlobalReceiptWhenTheCallerCancelsBeforeAnyOwnerExecutes() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var viewer = fixture.viewer.player();
            var child = new CompletableFuture<Void>();
            var opens = new AtomicInteger();
            when(viewer.openMenu(any(net.minecraft.world.MenuProvider.class))).thenAnswer(call -> {
                opens.incrementAndGet();
                ScarpetNativeWork.record(child);
                return java.util.OptionalInt.of(9);
            });
            fixture.owner.set(null);
            var caller = OrgPlayerInventoryMenus.openAsync(viewer, viewer, false);
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            assertEquals(0, opens.get());
            assertTrue(caller.cancel(false));
            assertFalse(idle.isDone());
            fixture.drain(fixture.viewer);
            assertEquals(1, opens.get());
            assertFalse(idle.isDone());
            child.complete(null);
            idle.join();
            assertTrue(caller.isCancelled());
        }
    }


    @Test
    void aRealGuestFailureKeepsItsParentFailureButCannotEraseTheActualMenuReturnValue() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player();
            fixture.owner.set(player);
            var runtime = carpet.script.external.ScarpetRuntime.of(fixture.server);
            var child = new CompletableFuture<Void>();
            var guest = new AtomicReference<CompletableFuture<Object>>();
            var menu = new AtomicReference<CompletableFuture<Boolean>>();
            var problem = new IllegalStateException("actual menu guest callback");
            try {
                var parent = ScarpetNativeWork.observeNative(player, () -> {
                    menu.set(OrgMenuNativeEffects.run(player, () -> {
                        guest.set(runtime.submit(() -> {
                            throw problem;
                        }));
                        ScarpetNativeWork.record(child);
                        return true;
                    }));
                    return true;
                });
                assertSame(problem, assertThrows(java.util.concurrent.ExecutionException.class, () -> guest.get().get(3, java.util.concurrent.TimeUnit.SECONDS)).getCause());
                var idle = ScarpetNativeWork.whenIdle(fixture.server);
                assertFalse(menu.get().isDone());
                assertFalse(parent.isDone());
                assertFalse(idle.isDone());
                child.complete(null);
                assertTrue(menu.get().join());
                assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(java.util.concurrent.CompletionException.class, parent::join)));
                idle.join();
            } finally {
                carpet.script.external.ScarpetRuntime.beginShutdown(fixture.server, () -> {
                });
            }
        }
    }

    @Test
    void anAcceptedNativeInteractionSurvivesShutdownUntilTheActualMenuAndItsOwnedTailChildrenEnd() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var player = fixture.viewer.player();
            fixture.owner.set(player);
            var physical = new CompletableFuture<Void>();
            var tail = new CompletableFuture<Void>();
            Host host = new Host();
            var context = new ReadyContext(host);
            net.minecraft.world.InteractionResult.Deferred pending;
            boolean fill = carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get(), events = carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get(), generation = carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.get();
            try (var frame = carpet.script.external.ScarpetRuntime.enterContext(context); var scope = carpet.script.external.ScarpetInteractionContinuations.open()) {
                try {
                    carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
                    carpet.script.external.ScarpetRuntime.EVENT_DISABLED.set(true);
                    carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.set(true);
                    pending = (net.minecraft.world.InteractionResult.Deferred) carpet.script.external.ScarpetInteractionContinuations.after(player, physical, () -> {
                        assertTrue(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
                        assertTrue(carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get());
                        assertTrue(carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.get());
                        assertFalse(ScarpetPlayerInventoryGate.paused(player));
                        ScarpetNativeWork.record(tail);
                        return net.minecraft.world.InteractionResult.SUCCESS;
                    });
                } finally {
                    carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(fill);
                    carpet.script.external.ScarpetRuntime.EVENT_DISABLED.set(events);
                    carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.set(generation);
                }
            }
            host.onClose();
            var view = pending.plan().future();
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            assertTrue(view.cancel(false));
            carpet.script.external.ScarpetInteractionContinuations.shutdown(fixture.server);
            assertFalse(pending.plan().future().isDone());
            assertFalse(idle.isDone());
            physical.complete(null);
            fixture.drain(fixture.viewer);
            assertFalse(pending.plan().future().isDone());
            assertFalse(idle.isDone());
            tail.complete(null);
            assertSame(net.minecraft.world.InteractionResult.SUCCESS, pending.plan().future().join());
            idle.join();
            assertTrue(view.isCancelled());
            assertFalse(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertFalse(carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get());
            assertFalse(carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.get());
        }
    }
}
