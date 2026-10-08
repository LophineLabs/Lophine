package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.EntityActors;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.ServerBot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetNativeBotRosterTest {
    @Test
    void nativePlayerRosterAndActualScarpetLookupIncludeCarpetBotsWithAStableSnapshot() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class, CALLS_REAL_METHODS);
        BotList bots = mock(BotList.class);
        ServerPlayer real = mock(ServerPlayer.class);
        ServerBot carpet = mock(ServerBot.class), legacy = mock(ServerBot.class);
        carpet.carpetNativePlayer = true;
        carpet.carpetPlacementReady = true;
        var playerField = PlayerList.class.getDeclaredField("players");
        playerField.setAccessible(true);
        playerField.set(players, new CopyOnWriteArrayList<>(List.of(real)));
        var serverField = PlayerList.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(players, server);
        var botField = BotList.class.getField("bots");
        botField.setAccessible(true);
        botField.set(bots, new CopyOnWriteArrayList<>(List.of(carpet, legacy)));
        when(server.getBotList()).thenReturn(bots);
        when(server.getPlayerList()).thenReturn(players);
        when(real.getScoreboardName()).thenReturn("Viewer");
        when(carpet.getScoreboardName()).thenReturn("NativeBot");
        when(real.getUUID()).thenReturn(java.util.UUID.randomUUID());
        when(carpet.getUUID()).thenReturn(java.util.UUID.randomUUID());
        carpet.carpetPlacementReady = false;
        assertEquals(List.of(real), players.getPlayers());
        carpet.carpetPlacementReady = true;
        List<ServerPlayer> snapshot = players.getPlayers();
        assertEquals(List.of(real, carpet), snapshot);
        assertEquals(2, players.getPlayerCount());
        try (var global = mockStatic(RegionizedServer.class); var tick = mockStatic(TickThread.class)) {
            global.when(RegionizedServer::isGlobalTickThread).thenReturn(true);
            tick.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            assertSame(carpet, EntityActors.player(server, "nativebot"));
            assertNull(EntityActors.player(server, "LegacyBot"));
        }
        bots.bots.clear();
        assertEquals(List.of(real, carpet), snapshot);
        assertEquals(List.of(real), players.getPlayers());
        assertEquals(1, players.getPlayerCount());
    }
}
