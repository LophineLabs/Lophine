package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgXpTransferNativeResultTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private static final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors;
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final AtomicReference<String> callback = new AtomicReference<>();
        final AtomicReference<Component> feedback = new AtomicReference<>();

        Fixture(Path directory) throws Exception {
            actors = new OrgInventoryPersistenceTest.Fixture(directory);
            var payer = actors.viewer.player();
            var destination = actors.target.player();
            var world = payer.level();
            when(source.getEntity()).thenReturn(payer);
            when(source.getPlayer()).thenReturn(payer);
            when(source.getPlayerOrException()).thenReturn(payer);
            when(source.getServer()).thenReturn(actors.server);
            when(source.getLevel()).thenReturn(world);
            when(source.getPosition()).thenReturn(Vec3.ZERO);
            when(source.getTextName()).thenReturn("Alice");
            when(source.callback()).thenReturn((success, value) -> {
                assertSame(payer, actors.owner.get());
                callback.set(success + ":" + value);
            });
            when(actors.server.getWorldPath(LevelResource.PLAYER_DATA_DIR)).thenReturn(directory.resolve("playerdata"));
            initialize(payer, "Alice", 10);
            initialize(destination, "Bob", 5);
            doAnswer(call -> {
                ServerPlayer player = call.getArgument(0);
                assertSame(player, actors.owner.get());
                CompoundTag tag = new CompoundTag(), pdc = new CompoundTag();
                tag.putInt("XpLevel", player.experienceLevel);
                tag.putFloat("XpP", player.experienceProgress);
                tag.putInt("XpTotal", player.totalExperience);
                actors.actors.get(player.getUUID()).pdc().forEach((key, value) -> {
                    if (value instanceof String string) pdc.putString(key.toString(), string);
                });
                tag.put("BukkitValues", pdc);
                actors.saved.put(player.getUUID(), tag);
                return null;
            }).when(actors.storage).save(any(Player.class));
            doAnswer(call -> {
                assertSame(payer, actors.owner.get());
                feedback.set(call.<java.util.function.Supplier<Component>>getArgument(0).get());
                return null;
            }).when(source).sendSuccess(any(), eq(false));
        }

        void initialize(ServerPlayer player, String name, int level) {
            when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            when(player.getDisplayName()).thenReturn(Component.literal(name));
            when(player.getScoreboardName()).thenReturn(name);
            player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            player.experienceLevel = level;
            player.experienceProgress = 0;
            player.totalExperience = OrgExperienceAmounts.forLevel(level).intValueExact();
            when(player.getXpNeededForNextLevel()).thenAnswer(call -> player.experienceLevel >= 30 ? 9 * player.experienceLevel - 158 : player.experienceLevel >= 15 ? 5 * player.experienceLevel - 38 : 2 * player.experienceLevel + 7);
            doAnswer(call -> {
                assertSame(player, actors.owner.get());
                player.experienceLevel = call.getArgument(0);
                player.experienceProgress = 0;
                return null;
            }).when(player).setExperienceLevels(anyInt());
            doAnswer(call -> {
                assertSame(player, actors.owner.get());
                player.experienceProgress = (float) call.<Integer>getArgument(0) / player.getXpNeededForNextLevel();
                return null;
            }).when(player).setExperiencePoints(anyInt());
        }

        void pointChild(ServerPlayer player, CompletableFuture<Void> child) {
            doAnswer(call -> {
                assertSame(player, actors.owner.get());
                player.experienceProgress = (float) call.<Integer>getArgument(0) / player.getXpNeededForNextLevel();
                ScarpetNativeWork.record(child);
                return null;
            }).when(player).setExperiencePoints(anyInt());
        }

        CompletableFuture<Integer> request(String mode, int amount) throws Exception {
            try (var scope = CarpetAsyncCommandResults.open()) {
                Method method = OrgExperienceTransfers.class.getDeclaredMethod("request", CommandSourceStack.class, ServerPlayer.class, ServerPlayer.class, String.class, int.class);
                method.setAccessible(true);
                assertEquals(1, method.invoke(null, source, actors.viewer.player(), actors.target.player(), mode, amount));
                return scope.resultFuture(source);
            }
        }

        void drain() {
            actors.drain(actors.target);
            actors.drain(actors.viewer);
        }

        @Override
        public void close() {
            actors.close();
        }
    }

    @Test
    void realDebitChildrenCreditChildrenAndOwnerFeedbackDetermineTheAmountResult() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var debitChild = new CompletableFuture<Void>();
            var creditChild = new CompletableFuture<Void>();
            var feedbackChild = new CompletableFuture<Void>();
            fixture.pointChild(fixture.actors.viewer.player(), debitChild);
            fixture.pointChild(fixture.actors.target.player(), creditChild);
            doAnswer(call -> {
                assertSame(fixture.actors.viewer.player(), fixture.actors.owner.get());
                fixture.feedback.set(call.<java.util.function.Supplier<Component>>getArgument(0).get());
                ScarpetNativeWork.record(feedbackChild);
                return null;
            }).when(fixture.source).sendSuccess(any(), eq(false));
            var actual = fixture.request("points", 17);
            assertFalse(actual.isDone());
            fixture.drain();
            assertEquals(BigInteger.valueOf(143), OrgExperienceAmounts.read(fixture.actors.viewer.player()));
            assertEquals(BigInteger.valueOf(55), OrgExperienceAmounts.read(fixture.actors.target.player()));
            assertNull(fixture.feedback.get());
            fixture.actors.owner.set(fixture.actors.target.player());
            OrgExperienceTransfers.tick(fixture.actors.target.player());
            assertEquals(BigInteger.valueOf(55), OrgExperienceAmounts.read(fixture.actors.target.player()));
            assertFalse(ScarpetNativeWork.whenIdle(fixture.actors.server).isDone());
            debitChild.complete(null);
            fixture.drain();
            assertEquals(BigInteger.valueOf(72), OrgExperienceAmounts.read(fixture.actors.target.player()));
            assertNull(fixture.feedback.get());
            assertFalse(actual.isDone());
            creditChild.complete(null);
            fixture.drain();
            assertNotNull(fixture.feedback.get());
            assertFalse(actual.isDone());
            assertNull(fixture.callback.get());
            feedbackChild.complete(null);
            fixture.drain();
            assertEquals(17, actual.join());
            assertEquals("true:17", fixture.callback.get());
            assertEquals("[]", Files.readString(directory.resolve("carpet-org-experience-transfers.json")).trim());
            assertNotNull(fixture.feedback.get().getStyle().getHoverEvent());
        }
    }

    @Test
    void actualInsufficientExperienceReturnsFailedZeroWithoutAQueuedSuccess() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var actual = fixture.request("points", 161);
            fixture.drain();
            fixture.drain();
            assertEquals(0, actual.join());
            assertEquals("false:0", fixture.callback.get());
            assertNull(fixture.feedback.get());
            assertEquals(BigInteger.valueOf(160), OrgExperienceAmounts.read(fixture.actors.viewer.player()));
            assertEquals(BigInteger.valueOf(55), OrgExperienceAmounts.read(fixture.actors.target.player()));
            assertFalse(Files.exists(directory.resolve("carpet-org-experience-transfers.json")));
        }
    }

    @Test
    void sourceNativeFailureStopsTheCreditTailAndPreservesPaidDurableRecovery() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var child = new CompletableFuture<Void>();
            fixture.pointChild(fixture.actors.viewer.player(), child);
            var result = new AtomicReference<CompletableFuture<Integer>>();
            var parent = ScarpetNativeWork.observeNative(null, () -> {
                try {
                    result.set(fixture.request("points", 17));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return null;
            });
            fixture.drain();
            assertEquals(BigInteger.valueOf(143), OrgExperienceAmounts.read(fixture.actors.viewer.player()));
            child.completeExceptionally(new IllegalStateException("actual debit packet failure"));
            fixture.drain();
            fixture.drain();
            assertEquals(0, result.get().join());
            assertEquals("false:0", fixture.callback.get());
            assertEquals(BigInteger.valueOf(55), OrgExperienceAmounts.read(fixture.actors.target.player()));
            assertTrue(Files.readString(directory.resolve("carpet-org-experience-transfers.json")).contains("debited"));
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(java.util.concurrent.CompletionException.class, parent::join)));
            fixture.actors.owner.set(fixture.actors.target.player());
            OrgExperienceTransfers.tick(fixture.actors.target.player());
            assertEquals(BigInteger.valueOf(72), OrgExperienceAmounts.read(fixture.actors.target.player()));
            assertEquals(BigInteger.valueOf(143), OrgExperienceAmounts.read(fixture.actors.viewer.player()));
            assertEquals("[]", Files.readString(directory.resolve("carpet-org-experience-transfers.json")).trim());
        }
    }

    @Test
    void zeroTransferReturnsSuccessfulZeroOnlyAfterActualParticipantPhases() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.actors.viewer.player().experienceLevel = 0;
            fixture.actors.viewer.player().totalExperience = 0;
            var actual = fixture.request("all", 0);
            assertFalse(actual.isDone());
            fixture.drain();
            fixture.drain();
            assertEquals(0, actual.join());
            assertEquals("true:0", fixture.callback.get());
            assertNotNull(fixture.feedback.get());
            assertFalse(Files.exists(directory.resolve("carpet-org-experience-transfers.json")));
        }
    }

    @Test
    void aGuestOnlyDebitFailureAllowsRealCreditWhileTheRawParentStaysFailed() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var guestChild = new CompletableFuture<Void>();
            var creditChild = new CompletableFuture<Void>();
            fixture.pointChild(fixture.actors.viewer.player(), guestChild);
            fixture.pointChild(fixture.actors.target.player(), creditChild);
            var result = new AtomicReference<CompletableFuture<Integer>>();
            var parent = ScarpetNativeWork.observeNative(null, () -> {
                try {
                    result.set(fixture.request("points", 17));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return null;
            });
            fixture.drain();
            var failure = new IllegalStateException("guest debit observer closed");
            var mark = ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure", Throwable.class);
            mark.setAccessible(true);
            mark.invoke(null, failure);
            guestChild.completeExceptionally(failure);
            fixture.drain();
            assertEquals(BigInteger.valueOf(72), OrgExperienceAmounts.read(fixture.actors.target.player()));
            assertFalse(result.get().isDone());
            assertNull(fixture.feedback.get());
            creditChild.complete(null);
            fixture.drain();
            fixture.drain();
            assertEquals(17, result.get().join());
            assertEquals("true:17", fixture.callback.get());
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(java.util.concurrent.CompletionException.class, parent::join)));
        }
    }
}
