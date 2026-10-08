package fun.bm.lophine.carpet;

import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Actual MinecraftServer sample selection, including actual default-value rule precedence.
 */
public class CarpetPingPlayerListLimitTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static ServerStatus.Players status(int limit, int count, boolean hidden) throws Exception {
        int previous = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.pingPlayerListLimit, oldSpigot = org.spigotmc.SpigotConfig.playerSample;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.pingPlayerListLimit = limit;
            org.spigotmc.SpigotConfig.playerSample = 1;
            var server = mock(MinecraftServer.class, CALLS_REAL_METHODS);
            var players = mock(PlayerList.class);
            doReturn(players).when(server).getPlayerList();
            doReturn(20).when(server).getMaxPlayers();
            doReturn(hidden).when(server).hidesOnlinePlayers();
            var random = MinecraftServer.class.getDeclaredField("random");
            random.setAccessible(true);
            random.set(server, RandomSource.create(42L));
            List<ServerPlayer> online = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                var player = mock(ServerPlayer.class);
                when(player.allowsListing()).thenReturn(true);
                when(player.nameAndId()).thenReturn(new NameAndId(new UUID(0, i), "Player" + i));
                online.add(player);
            }
            when(players.getPlayers()).thenReturn(online);
            var playerList = MinecraftServer.class.getDeclaredField("playerList");
            playerList.setAccessible(true);
            playerList.set(server, players);
            var realPlayers = PlayerList.class.getField("realPlayers");
            realPlayers.setAccessible(true);
            realPlayers.set(players, new java.util.concurrent.CopyOnWriteArrayList<>(online));
            var bots = mock(org.leavesmc.leaves.bot.BotList.class);
            doReturn(bots).when(server).getBotList();
            var botField = org.leavesmc.leaves.bot.BotList.class.getField("bots");
            botField.setAccessible(true);
            botField.set(bots, java.util.List.of());
            var method = MinecraftServer.class.getDeclaredMethod("buildPlayerStatus");
            method.setAccessible(true);
            return (ServerStatus.Players) method.invoke(server);
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.pingPlayerListLimit = previous;
            org.spigotmc.SpigotConfig.playerSample = oldSpigot;
        }
    }

    @Test
    void ruleTwelveReturnsTwelveActualSamplesDespiteSpigotOne() throws Exception {
        var status = status(12, 13, false);
        assertEquals(12, status.sample().size());
        assertEquals(13, status.online());
        assertEquals(12, status.sample().stream().map(NameAndId::id).distinct().count());
    }

    @Test
    void actualZeroRuleReturnsNoSamplesAndHiddenModeStillWins() throws Exception {
        assertTrue(status(0, 13, false).sample().isEmpty());
        assertTrue(status(12, 13, true).sample().isEmpty());
    }

    @Test
    void sampleIsBoundedByActualOnlineCount() throws Exception {
        assertEquals(2, status(12, 2, false).sample().size());
    }
}
