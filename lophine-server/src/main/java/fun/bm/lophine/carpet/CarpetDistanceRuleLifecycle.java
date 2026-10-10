package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.server.MinecraftServer;

/**
 * Changes the actual global defaults consumed by the native per-player chunk loaders.
 */
public final class CarpetDistanceRuleLifecycle {
    private CarpetDistanceRuleLifecycle() {
    }

    public static void refresh() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !server.isReady() || server.getPlayerList() == null) return;
        int view = GeneralCompatConfig.viewDistance, simulation = GeneralCompatConfig.simulationDistance;
        AmsNativeCommandEffects.global(server, () -> {
            apply(server, view, simulation);
            return (Void) null;
        });
    }

    static void apply(MinecraftServer server, int view, int simulation) {
        if (!(server instanceof net.minecraft.server.dedicated.DedicatedServer dedicated)) return;
        int requestedView = view >= 2 ? view : dedicated.viewDistance();
        int requestedSimulation = simulation >= 2 ? simulation : dedicated.simulationDistance();
        var players = server.getPlayerList();
        if (players.getViewDistance() != requestedView) players.setViewDistance(requestedView);
        if (players.getSimulationDistance() != requestedSimulation) players.setSimulationDistance(requestedSimulation);
    }
}
