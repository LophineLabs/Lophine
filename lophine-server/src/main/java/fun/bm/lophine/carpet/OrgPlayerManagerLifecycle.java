// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

/** Manager snapshots finish on live region actors before Folia halts them. */
public final class OrgPlayerManagerLifecycle {
    private static final Set<MinecraftServer> STARTED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<MinecraftServer, Stop> STOPS = new ConcurrentHashMap<>();
    private static final class Stop {
        final AtomicBoolean started = new AtomicBoolean();
        volatile boolean ready;
    }
    private OrgPlayerManagerLifecycle() {}

    public static void tick(MinecraftServer server) {
        if (server.isReady() && !STOPS.containsKey(server) && STARTED.add(server)) { OrgServerPermissions.initialize(server); OrgPlayerManager.start(server); OrgFinderPlayerData.start(server); }
    }

    public static boolean beforeStop(MinecraftServer server, Runnable nativeStop) {
        Stop stop = STOPS.computeIfAbsent(server, ignored -> new Stop());
        if (!stop.started.compareAndSet(false, true)) return !stop.ready;
        var saved = OrgPlayerManager.beforeShutdown(server).handle((profiles, failure) -> {
            if (failure != null) MinecraftServer.LOGGER.error("Cannot finish Org resident profiles before shutdown", failure);
            return null;
        }).thenCompose(ignored -> carpet.script.external.ScarpetNativeWork.whenIdle(server));
        Runnable finish = () -> {
            try { saved.join(); }
            catch (Throwable failure) { MinecraftServer.LOGGER.error("Cannot finish Org resident profiles before shutdown", failure); }
            finally {
                OrgHiddenBedrockSignals.clearAll();
                OrgPlayerManager.close(server); OrgServerPermissions.close(server); OrgFinderPlayerData.close(server); STARTED.remove(server); stop.ready = true;
            }
        };
        if (saved.isDone()) { finish.run(); return false; }
        Thread.ofPlatform().name("Org player manager shutdown").start(() -> { finish.run(); nativeStop.run(); });
        return true;
    }
}
