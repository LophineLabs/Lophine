package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetExplosionActors;
import com.mojang.brigadier.CommandDispatcher;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.entity.bot.CraftBot;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetPlayerKillReceiptTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final ServerBot bot = mock(ServerBot.class);
        final BotList bots = mock(BotList.class);
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final io.papermc.paper.threadedregions.EntityScheduler scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
        final AtomicReference<Consumer<Entity>> owner = new AtomicReference<>(), retired = new AtomicReference<>();
        final org.mockito.MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final org.mockito.MockedStatic<ScarpetExplosionActors> actors = mockStatic(ScarpetExplosionActors.class);
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        final java.lang.reflect.Field permission;
        final Object oldPermission;

        Fixture(boolean accepted) throws Exception {
            permission = fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.class.getField("commandPlayer");
            oldPermission = permission.get(null);
            permission.set(null, permission.getType() == String.class ? "true" : true);
            when(source.getServer()).thenReturn(server);
            when(source.getLevel()).thenReturn(world);
            when(source.getPosition()).thenReturn(Vec3.ZERO);
            when(source.callback()).thenReturn(net.minecraft.commands.CommandResultCallback.EMPTY);
            when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);
            when(world.getServer()).thenReturn(server);
            when(server.getBotList()).thenReturn(bots);
            var players = mock(PlayerList.class);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayerByName("Bot")).thenReturn(bot);
            var pack = net.minecraft.server.level.ServerPlayer.class.getField("carpetActionPack");
            pack.setAccessible(true);
            pack.set(bot, mock(CarpetPlayerActionPack.class));
            var bukkit = mock(CraftBot.class);
            when(bot.getBukkitEntity()).thenReturn(bukkit);
            var field = CraftEntity.class.getField("taskScheduler");
            field.setAccessible(true);
            field.set(bukkit, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                owner.set(call.getArgument(0)); retired.set(call.getArgument(1)); return accepted;
            });
            actors.when(() -> ScarpetExplosionActors.world(eq(world), eq(BlockPos.ZERO), any(Supplier.class)))
                    .thenAnswer(call -> CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(2)).get()));
            CarpetPlayerCommand.register(dispatcher, null);
        }

        CompletableFuture<Integer> execute(CarpetAsyncCommandResults.Scope scope) throws Exception {
            assertEquals(1, dispatcher.execute("player Bot kill", source));
            return scope.resultFuture(source);
        }

        public void close() throws Exception { permission.set(null, oldPermission); actors.close(); ticks.close(); }
    }

    @Test void rejectedOwnerSchedulingTerminatesTheDeferredCommandInsteadOfHanging() throws Exception {
        try (var f = new Fixture(false); var scope = CarpetAsyncCommandResults.open()) {
            var result = f.execute(scope);
            assertEquals(0, result.join());
            verify(f.bot.carpetActionPack, never()).stopAll();
            verify(f.bots, never()).carpetRemoveBotAsync(any(), any(), any(), anyBoolean(), anyBoolean());
        }
    }

    @Test void retirementBetweenSchedulingAndExecutionFinishesTheDeferredFailure() throws Exception {
        try (var f = new Fixture(true); var scope = CarpetAsyncCommandResults.open()) {
            var result = f.execute(scope);
            assertFalse(result.isDone());
            f.retired.get().accept(f.bot);
            assertEquals(0, result.join());
            verify(f.bot.carpetActionPack, never()).stopAll();
        }
    }

    @Test void synchronousActionStopFailureStillCompletesTheCommandCallback() throws Exception {
        try (var f = new Fixture(true); var scope = CarpetAsyncCommandResults.open()) {
            doThrow(new IllegalStateException("native stop failed")).when(f.bot.carpetActionPack).stopAll();
            var result = f.execute(scope);
            f.owner.get().accept(f.bot);
            assertEquals(0, result.join());
            verify(f.bots, never()).carpetRemoveBotAsync(any(), any(), any(), anyBoolean(), anyBoolean());
        }
    }
}
