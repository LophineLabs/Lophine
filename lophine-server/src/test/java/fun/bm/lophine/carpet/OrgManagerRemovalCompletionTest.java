package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.ServerBot;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgManagerRemovalCompletionTest {
    @TempDir
    Path directory;

    @Test
    void reloginDoesNotStartItsOfflineDelayUntilActualNativeRemovalCompletes() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var result = fixture.run();
            assertFalse(result.isDone());
            assertTrue(fixture.busy());
            assertEquals(100, fixture.due());
            fixture.removed.complete(true);
            assertTrue(result.join());
            assertFalse(fixture.busy());
            assertEquals(44, fixture.due());
            verify(fixture.bots).carpetRemoveBotAsync(eq(fixture.bot), any(), any(), eq(true), eq(false));
        }
    }

    @Test
    void aCancelledRealRemovalStopsReloginWithoutTreatingAcceptedSchedulingAsSuccess() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var result = fixture.run();
            assertFalse(result.isDone());
            fixture.removed.complete(false);
            assertFalse(result.join());
            assertFalse(fixture.jobs.containsValue(fixture.job));
            assertEquals(100, fixture.due());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerBot bot = mock(ServerBot.class);
        final BotList bots = mock(BotList.class);
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final CompletableFuture<Boolean> removed = new CompletableFuture<>();
        final OrgPlayerManager manager;
        final Object job;
        final Map<String, Object> jobs;
        final org.mockito.MockedStatic<OrgFakePlayerActions> actions;
        final org.mockito.MockedStatic<TickThread> ticks;

        @SuppressWarnings("unchecked")
        Fixture(Path directory) throws Exception {
            when(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).thenReturn(directory);
            when(server.getBotList()).thenReturn(bots);
            var level = mock(ServerLevel.class);
            when(bot.level()).thenReturn(level);
            when(level.getServer()).thenReturn(server);
            var constructor = OrgPlayerManager.class.getDeclaredConstructor(MinecraftServer.class, CommandBuildContext.class);
            constructor.setAccessible(true);
            manager = spy(constructor.newInstance(server, null));
            doReturn(new JsonObject()).when(manager).capture(bot, true);
            var jobType = Class.forName("fun.bm.lophine.carpet.OrgPlayerManager$Job");
            var create = jobType.getDeclaredConstructors()[0];
            create.setAccessible(true);
            job = create.newInstance("fake", "relogin", source, 100L, 30, new JsonObject());
            var busy = jobType.getDeclaredField("busy");
            busy.setAccessible(true);
            busy.setBoolean(job, true);
            var field = OrgPlayerManager.class.getDeclaredField("jobs");
            field.setAccessible(true);
            jobs = (Map<String, Object>) field.get(manager);
            jobs.put("relogin:fake", job);
            field = OrgPlayerManager.class.getDeclaredField("ticks");
            field.setAccessible(true);
            ((AtomicLong) field.get(manager)).set(42);
            when(bots.carpetRemoveBotAsync(eq(bot), any(), any(), eq(true), eq(false))).thenReturn(removed);
            when(source.getServer()).thenReturn(server);
            when(source.getEntity()).thenReturn(bot);
            when(bot.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
            ticks = mockStatic(TickThread.class);
            ticks.when(() -> TickThread.isTickThreadFor(bot)).thenReturn(true);
            org.mockito.MockedStatic<OrgFakePlayerActions> created = null;
            try {
                created = mockStatic(OrgFakePlayerActions.class);
                created.when(() -> OrgFakePlayerActions.owned(eq(bot), any())).thenAnswer(call -> CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(1)).get()));
                created.when(() -> OrgFakePlayerActions.whenIdleForRemoval(eq(bot), any())).thenAnswer(call -> CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(1)).get()));
                actions = created;
            } catch (RuntimeException | Error failure) {
                if (created != null) created.close();
                ticks.close();
                throw failure;
            }
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean> run() throws Exception {
            var method = OrgPlayerManager.class.getDeclaredMethod("captureAndRemove", ServerBot.class, job.getClass());
            method.setAccessible(true);
            return (CompletableFuture<Boolean>) method.invoke(manager, bot, job);
        }

        boolean busy() throws Exception {
            var field = job.getClass().getDeclaredField("busy");
            field.setAccessible(true);
            return field.getBoolean(job);
        }

        long due() throws Exception {
            var field = job.getClass().getDeclaredField("due");
            field.setAccessible(true);
            return field.getLong(job);
        }

        @Override
        public void close() {
            actions.close();
            ticks.close();
        }
    }
}
