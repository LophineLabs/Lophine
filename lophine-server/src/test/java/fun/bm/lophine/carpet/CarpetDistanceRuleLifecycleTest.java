package fun.bm.lophine.carpet;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;

class CarpetDistanceRuleLifecycleTest {
    @Test void liveRuleUpdatesUseNativeDefaultsAndRestoreServerPropertiesForZeroOrOne() {
        var server=mock(net.minecraft.server.dedicated.DedicatedServer.class);
        DedicatedPlayerList players=mock(DedicatedPlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(server.viewDistance()).thenReturn(10);
        when(server.simulationDistance()).thenReturn(6);
        when(players.getViewDistance()).thenReturn(8);
        when(players.getSimulationDistance()).thenReturn(8);
        CarpetDistanceRuleLifecycle.apply(server,16,20);
        verify(players).setViewDistance(16);
        verify(players).setSimulationDistance(20);
        CarpetDistanceRuleLifecycle.apply(server,0,1);
        verify(players).setViewDistance(10);
        verify(players).setSimulationDistance(6);
    }
}
