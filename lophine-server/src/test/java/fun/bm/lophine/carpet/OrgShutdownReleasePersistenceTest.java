package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgShutdownReleasePersistenceTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private static CarpetPlayerActionPack pack(ServerPlayer player) throws Exception {
        player.gameMode = mock(net.minecraft.server.level.ServerPlayerGameMode.class);
        when(player.gameMode.getGameModeForPlayer()).thenReturn(net.minecraft.world.level.GameType.SURVIVAL);
        when(player.getViewVector(anyFloat())).thenReturn(new net.minecraft.world.phys.Vec3(0, 0, 1));
        when(player.getEyePosition(anyFloat())).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
        when(player.position()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
        when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        when(player.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 2, 1));
        var chunks = mock(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.class);
        var manager = mock(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.class);
        var managerField = ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.class.getField("chunkHolderManager");
        managerField.setAccessible(true);
        managerField.set(chunks, manager);
        when(player.level().moonrise$getChunkTaskScheduler()).thenReturn(chunks);
        var pack = new CarpetPlayerActionPack(player);
        var field = ServerPlayer.class.getField("carpetActionPack");
        field.setAccessible(true);
        field.set(player, pack);
        return pack;
    }

    @Test
    void precisionStopsOnlyTheCapturedActionAndDoesNotClearLaterMovement() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var player = fixture.target.player();
            fixture.owner.set(player);
            var pack = pack(player);
            var first = CarpetPlayerActionPack.Action.continuous();
            pack.start(CarpetPlayerActionPack.ActionType.USE, first);
            var captured = pack.captureForRemoval();
            var replacement = CarpetPlayerActionPack.Action.interval(4);
            pack.start(CarpetPlayerActionPack.ActionType.USE, replacement);
            pack.setForward(1);
            clearInvocations(player);
            pack.stopForRemoval(captured).join();
            assertSame(replacement, pack.getAction(CarpetPlayerActionPack.ActionType.USE));
            assertEquals(1, pack.getForward());
            verify(player, never()).releaseUsingItem();
        }
    }

    @Test
    void oldReleaseNativeTailRemainsAcceptedUnderPauseAndCancellationCannotPretendItFinished() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var player = fixture.target.player();
            fixture.owner.set(player);
            var pack = pack(player);
            pack.start(CarpetPlayerActionPack.ActionType.USE, CarpetPlayerActionPack.Action.continuous());
            var captured = pack.captureForRemoval();
            var tail = new CompletableFuture<Void>();
            var inside = new AtomicBoolean();
            doAnswer(call -> {
                inside.set(!ScarpetPlayerInventoryGate.paused(player));
                ScarpetNativeWork.record(tail);
                return null;
            }).when(player).releaseUsingItem();
            var pause = ScarpetPlayerInventoryGate.whenIdleForRemoval(player, () -> pack.stopForRemoval(captured));
            fixture.drain(fixture.target);
            assertTrue(inside.get());
            assertFalse(pause.isDone());
            var stopped = pause.getNow(null);
            assertNull(stopped);
            assertTrue(ScarpetPlayerInventoryGate.paused(player));
            tail.complete(null);
            fixture.drain(fixture.target);
            assertTrue(pause.isDone());
            assertFalse(ScarpetPlayerInventoryGate.paused(player));
            assertNull(pack.getAction(CarpetPlayerActionPack.ActionType.USE));
            pack.start(CarpetPlayerActionPack.ActionType.USE, CarpetPlayerActionPack.Action.continuous());
            var next = pack.captureForRemoval();
            var tail2 = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(tail2);
                return null;
            }).when(player).releaseUsingItem();
            var actual = pack.stopForRemoval(next);
            assertFalse(actual.cancel(false));
            assertFalse(actual.isDone());
            tail2.complete(null);
            assertTrue(actual.isDone());
        }
    }

    @Test
    void managerCapturesInventoryAfterReleaseButPersistsTheFrozenOriginalActionMetadata() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var hidden = mockStatic(OrgHiddenPlayerActions.class)) {
            var player = (ServerBot) fixture.target.player();
            fixture.owner.set(player);
            var pack = pack(player);
            pack.start(CarpetPlayerActionPack.ActionType.USE, CarpetPlayerActionPack.Action.continuous());
            var stop = new JsonObject();
            stop.addProperty("name", "stop");
            stop.add("data", new JsonObject());
            hidden.when(() -> OrgHiddenPlayerActions.get(eq(player))).thenReturn(stop);
            hidden.when(() -> OrgHiddenPlayerActions.sanitizeActionPackSnapshot(eq(player), any())).thenAnswer(call -> call.getArgument(1));
            hidden.when(() -> OrgHiddenPlayerActions.whenIdleForRemoval(eq(player), any())).thenAnswer(call -> pack.whenIdleForRemoval((Supplier<?>) call.getArgument(1)));
            var constructor = OrgPlayerManager.class.getDeclaredConstructor(MinecraftServer.class, CommandBuildContext.class);
            constructor.setAccessible(true);
            OrgPlayerManager manager = spy(constructor.newInstance(fixture.server, null));
            var captures = new AtomicInteger();
            doAnswer(call -> {
                captures.incrementAndGet();
                var value = new JsonObject();
                value.addProperty("_lophine_name", "fake");
                value.addProperty("count", fixture.target.inventory().getItem(0).getCount());
                return value;
            }).when(manager).capture(player, true);
            var realRelease = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(realRelease);
                return null;
            }).when(player).releaseUsingItem();
            var saved = manager.captureForShutdown(player);
            fixture.drain(fixture.target);
            fixture.drain(fixture.target);
            assertEquals(0, captures.get());
            assertFalse(saved.isDone());
            fixture.target.inventory().getItem(0).shrink(3);
            realRelease.complete(null);
            for (int tick = 0; tick < 4; tick++) fixture.drain(fixture.target);
            JsonObject result = saved.join();
            assertEquals(1, captures.get());
            assertEquals(17, result.get("count").getAsInt());
            var metadata = OrgPlayerManager.decode(result.get("_lophine_action_pack").getAsString());
            assertEquals("USE", ((net.minecraft.nbt.CompoundTag) metadata.getListOrEmpty("actions").getFirst()).getStringOr("type", ""));
            assertTrue(result.getAsJsonObject("simple_action").has("use"));
            assertNull(pack.getAction(CarpetPlayerActionPack.ActionType.USE));
        }
    }

    @Test
    void hiddenUseWithoutAPackUseActionWaitsItsRealReleaseAndNeverStopsANewerUseController() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var hidden = mockStatic(OrgHiddenPlayerActions.class)) {
            var player = fixture.target.player();
            fixture.owner.set(player);
            pack(player);
            var stop = new JsonObject();
            stop.addProperty("name", "stop");
            stop.add("data", new JsonObject());
            hidden.when(() -> OrgHiddenPlayerActions.get(eq(player))).thenReturn(stop);
            hidden.when(() -> OrgHiddenPlayerActions.sanitizeActionPackSnapshot(eq(player), any())).thenAnswer(call -> call.getArgument(1));
            var using = new AtomicBoolean(true);
            var reference = fixture.target.inventory().getItem(0);
            when(player.isUsingItem()).thenAnswer(call -> using.get());
            when(player.getUseItem()).thenReturn(reference);
            when(player.getUsedItemHand()).thenReturn(net.minecraft.world.InteractionHand.MAIN_HAND);
            when(player.carpetOrgUseStartTime()).thenReturn(42L);
            var frozen = OrgShutdownActionSnapshot.freeze(player);
            var tail = new CompletableFuture<Void>();
            doAnswer(call -> {
                using.set(false);
                ScarpetNativeWork.record(tail);
                return null;
            }).when(player).releaseUsingItem();
            var actual = frozen.stop(player);
            assertFalse(actual.isDone());
            verify(player).releaseUsingItem();
            tail.complete(null);
            assertTrue(actual.isDone());
            using.set(true);
            var replacement = OrgShutdownActionSnapshot.freeze(player);
            when(player.carpetOrgUseStartTime()).thenReturn(43L);
            clearInvocations(player);
            replacement.stop(player).join();
            verify(player, never()).releaseUsingItem();
        }
    }
}

