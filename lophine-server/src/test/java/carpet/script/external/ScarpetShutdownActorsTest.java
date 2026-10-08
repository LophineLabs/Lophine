package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.*;
import carpet.script.value.NumericValue;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Runs the real shutdown interpreter and dispatcher while the tick fixtures never wait.
 */
public class ScarpetShutdownActorsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Host extends ScriptHost {
        Host(CarpetScriptServer server) {
            super(null, server, false, null, Expression.LoadOverride.DEFAULT);
        }

        @Override
        protected carpet.script.Module getModuleOrLibraryByName(String name) {
            return null;
        }

        @Override
        protected void runModuleCode(Context context, carpet.script.Module module) {
        }

        @Override
        protected ScriptHost duplicate() {
            return new Host((CarpetScriptServer) scriptServer());
        }
    }

    private static final class ReadyContext extends CarpetContext {
        ReadyContext(ScriptHost host, CommandSourceStack source) {
            super(host, source, BlockPos.ZERO);
            initialize();
        }
    }

    @Test
    void closeCallbackCarriesOnlyItsAuthorizedFrameThroughWorldAndEntityOwners() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel world = mock(ServerLevel.class);
        Entity entity = mock(Entity.class);
        CraftServer craft = mock(CraftServer.class);
        CraftEntity bukkit = mock(CraftEntity.class);
        var regionScheduler = mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);
        var entityScheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
        CarpetScriptServer scripts = mock(CarpetScriptServer.class);
        CarpetEventServer events = mock(CarpetEventServer.class);
        var scriptsServer = CarpetScriptServer.class.getField("server");
        scriptsServer.setAccessible(true);
        scriptsServer.set(scripts, server);
        scripts.events = events;
        var eventsHandle = CarpetEventServer.class.getField("handleEvents");
        eventsHandle.setAccessible(true);
        eventsHandle.set(events, new carpet.script.utils.GlocalFlag(true));
        Host host = new Host(scripts);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(server);
        when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(craft.getRegionScheduler()).thenReturn(regionScheduler);
        var serverField = MinecraftServer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(server, craft);
        var taskScheduler = CraftEntity.class.getField("taskScheduler");
        taskScheduler.setAccessible(true);
        taskScheduler.set(bukkit, entityScheduler);
        when(world.getServer()).thenReturn(server);
        when(world.getWorld()).thenReturn(mock(CraftWorld.class));
        when(entity.getBukkitEntity()).thenReturn(bukkit);
        when(entity.isRemoved()).thenReturn(false);
        BlockingQueue<Runnable> regionTasks = new LinkedBlockingQueue<>(), entityTasks = new LinkedBlockingQueue<>();
        AtomicBoolean regionOwner = new AtomicBoolean(), entityOwner = new AtomicBoolean();
        AtomicLong value = new AtomicLong(-1L);
        AtomicBoolean didRejectOrdinaryFrame = new AtomicBoolean();
        CountDownLatch nativeShutdown = new CountDownLatch(1);
        doAnswer(call -> {
            regionTasks.add(call.getArgument(4));
            return null;
        }).when(regionScheduler).execute(any(), any(org.bukkit.World.class), anyInt(), anyInt(), any(Runnable.class));
        when(entityScheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
            Consumer<Entity> task = call.getArgument(0);
            entityTasks.add(() -> task.accept(entity));
            return true;
        });
        try (var ticks = mockStatic(TickThread.class); var servers = mockStatic(MinecraftServer.class); var bukkitServer = mockStatic(org.bukkit.Bukkit.class)) {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            bukkitServer.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            // This singleton is initialized on the fixture thread before the VM needs it.
            assertNotNull(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenAnswer(call -> regionOwner.get());
            ticks.when(() -> TickThread.isTickThreadFor(eq(entity))).thenAnswer(call -> entityOwner.get());
            ScarpetRuntime runtime = ScarpetRuntime.of(server);
            runtime.setScriptServer(scripts);
            doAnswer(call -> {
                host.onClose();
                try (var ignored = ScarpetRuntime.closingContext(); var closing = ScarpetRuntime.closingHost(host)) {
                    CarpetContext context = new ReadyContext(host, source);
                    Expression expression = new Expression("compat_close_chain()");
                    expression.addContextFunction("compat_close_chain", 0, (guest, type, args) -> NumericValue.of(ScarpetRuntime.await(
                            ScarpetRuntime.atBlockFuture(world, BlockPos.ZERO, () -> {
                                // A new ordinary frame cannot reuse the surrounding close authority.
                                try (var plain = ScarpetRuntime.closingContext()) {
                                    didRejectOrdinaryFrame.set(ScarpetRuntime.atEntityFuture(entity, () -> 99L).isCompletedExceptionally());
                                }
                                return ScarpetRuntime.atEntityFuture(entity, () -> 42L);
                            }).thenCompose(next -> next))));
                    value.set(expression.executeAndEvaluate(context, false, Expression.LoadOverride.DEFAULT, null).getLeft().readInteger());
                }
                return null;
            }).when(scripts).onClose();
            assertTrue(ScarpetRuntime.beginShutdown(server, nativeShutdown::countDown));
            Runnable worldTask = regionTasks.poll(3L, TimeUnit.SECONDS);
            assertNotNull(worldTask);
            assertEquals(1L, nativeShutdown.getCount());
            regionOwner.set(true);
            try {
                worldTask.run();
            } finally {
                regionOwner.set(false);
            }
            Runnable entityTask = entityTasks.poll(3L, TimeUnit.SECONDS);
            assertNotNull(entityTask);
            assertEquals(1L, nativeShutdown.getCount());
            assertTrue(didRejectOrdinaryFrame.get());
            entityOwner.set(true);
            try {
                entityTask.run();
            } finally {
                entityOwner.set(false);
            }
            assertTrue(nativeShutdown.await(3L, TimeUnit.SECONDS));
            assertEquals(42L, value.get());
            assertTrue(ScarpetRuntime.atBlockFuture(world, BlockPos.ZERO, () -> 1L).isCompletedExceptionally());
            assertFalse(ScarpetRuntime.canRunClosingHost(host));
        }
    }
}
