// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.server.MinecraftServer;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keep a native function's queue and return frame alive while a Carpet command awaits its actual result.
 */
public final class CarpetCommandContextContinuations {
    private static final Map<ExecutionContext<?>, MinecraftServer> ACTIVE = new ConcurrentHashMap<>();

    private CarpetCommandContextContinuations() {
    }

    public static void configure(CommandSourceStack source, ExecutionContext<?> context, Runnable resume) {
        var owner = source.getEntity();
        boolean followOwner = owner != null && TickThread.isTickThreadFor(owner);
        boolean global = RegionizedServer.isGlobalTickThread();
        var world = source.getLevel();
        var position = net.minecraft.core.BlockPos.containing(source.getPosition());
        boolean suppressUpdates = !fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fillUpdates;
        var restore = carpet.script.external.ScarpetRuntime.captureNativeFunction((Runnable action) -> {
            if (suppressUpdates) InteractionUpdateHelper.supplyWithSuppressedUpdates(() -> {
                action.run();
                return null;
            });
            else action.run();
            return null;
        });
        context.carpetConfigureContinuation(action -> {
            Runnable restored = () -> restore.apply(action);
            if (followOwner) {
                boolean accepted = owner.getBukkitEntity().taskScheduler.schedule(owned -> restored.run(),
                        retired -> context.carpetFail(new IllegalStateException("Native command context owner retired")), 1);
                if (!accepted)
                    context.carpetFail(new IllegalStateException("Native command context scheduler retired"));
            } else if (global || world == null) RegionizedServer.getInstance().addTask(restored);
            else
                source.getServer().server.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE, world.getWorld(),
                        position.getX() >> 4, position.getZ() >> 4, restored);
        }, resume);
        var actual = context.carpetCompletion();
        carpet.script.external.ScarpetNativeWork.record(actual);
        var server = source.getServer();
        if (server != null) {
            ACTIVE.put(context, server);
            carpet.script.external.ScarpetNativeWork.trackNative(server, actual);
        }
        actual.whenComplete((result, failure) -> ACTIVE.remove(context));
        CarpetAsyncCommandResults.trackContext(actual);
    }

    public static void shutdown(MinecraftServer server) {
        for (var entry : List.copyOf(ACTIVE.entrySet()))
            if (entry.getValue() == server)
                entry.getKey().carpetFail(new IllegalStateException("Server stopped during native command context"));
    }
}
