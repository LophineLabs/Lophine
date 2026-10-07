package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.ServerBot;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsFakePlayerRemovalReceiptTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test void disabledFancyNamesDoNotQueueAnEmptyGlobalChildAfterPhysicalLogout() throws Exception {
        String rule = GeneralCompatConfig.fancyFakePlayerName;
        var previous = AmsFakePlayers.class.getDeclaredField("previousTeam"); previous.setAccessible(true);
        Object old = previous.get(null);
        try (var global = mockStatic(OrgCommandNativeEffects.class)) {
            GeneralCompatConfig.fancyFakePlayerName = "false"; previous.set(null, "false");
            var bot = mock(ServerBot.class);
            var actual = ScarpetNativeWork.observeNative(null, () -> { AmsFakePlayers.removed(bot); return null; });
            assertTrue(actual.isDone()); actual.join(); global.verifyNoInteractions();
            verify(bot, never()).level();
        } finally { GeneralCompatConfig.fancyFakePlayerName = rule; previous.set(null, old); }
    }

    @Test void anEnabledTeamKeepsItsActualGlobalCleanupReceipt() throws Exception { pendingCleanup("bots", "bots"); }
    @Test void aPreviousTeamStillKeepsCleanupWhileTheRuleIsBeingDisabled() throws Exception { pendingCleanup("false", "bots"); }

    private void pendingCleanup(String current, String previousName) throws Exception {
        String rule = GeneralCompatConfig.fancyFakePlayerName;
        var previous = AmsFakePlayers.class.getDeclaredField("previousTeam"); previous.setAccessible(true);
        Object old = previous.get(null);
        try (var global = mockStatic(OrgCommandNativeEffects.class)) {
            GeneralCompatConfig.fancyFakePlayerName = current; previous.set(null, previousName);
            var server = mock(MinecraftServer.class); var world = mock(ServerLevel.class); var bot = mock(ServerBot.class);
            when(bot.level()).thenReturn(world); when(world.getServer()).thenReturn(server);
            when(bot.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "test_bot"));
            var cleanup = new CompletableFuture<Void>();
            global.when(() -> OrgCommandNativeEffects.global(eq(server), any())).thenAnswer(call -> cleanup);
            var actual = ScarpetNativeWork.observeNative(null, () -> { AmsFakePlayers.removed(bot); return null; });
            assertFalse(actual.isDone()); cleanup.complete(null); actual.join();
            global.verify(() -> OrgCommandNativeEffects.global(eq(server), any()));
        } finally { GeneralCompatConfig.fancyFakePlayerName = rule; previous.set(null, old); }
    }
}
