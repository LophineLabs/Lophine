package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.WeakIdentityMap;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.ServerBot;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetPlayerLifecycleIdentityTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    /** Only metadata is exercised; bypass construction to retain the real Entity equality methods. */
    private static ServerBot player(int id) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        ServerBot player = (ServerBot) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ServerBot.class);
        player.setId(id);
        Field pack = ServerPlayer.class.getField("carpetActionPack");
        pack.setAccessible(true);
        pack.set(player, mock(CarpetPlayerActionPack.class));
        return player;
    }

    @Test void birthsRemainSeparatedAcrossReusedAndChangedNativeEntityIds() throws Exception {
        ServerBot old = player(7), fresh = player(7);
        assertEquals(old, fresh);
        var oldBirth = new CompletableFuture<Void>();
        var freshBirth = new CompletableFuture<Void>();
        CarpetPlayerBirths.admitPlayer(old, oldBirth);
        CarpetPlayerBirths.admitPlayer(fresh, freshBirth);
        try {
            old.setId(13);
            assertTrue(CarpetPlayerBirths.playerPending(old));
            var oldDone = CarpetPlayerBirths.playerCompletion(old);
            var freshDone = CarpetPlayerBirths.playerCompletion(fresh);
            oldBirth.complete(null);
            assertTrue(oldDone.isDone());
            assertFalse(freshDone.isDone());
            assertFalse(CarpetPlayerBirths.playerPending(old));
            assertTrue(CarpetPlayerBirths.playerPending(fresh));
        } finally { oldBirth.complete(null); freshBirth.complete(null); }
        assertFalse(CarpetPlayerBirths.playerPending(fresh));
    }

    @Test void actionSelectionAndPendingNativeWorkFollowTheObjectAfterNetworkIdReuse() throws Exception {
        ServerBot old = player(17), fresh = player(17);
        var oldAction = OrgFakePlayerActions.Action.simple("fishing", List.of());
        var freshAction = OrgFakePlayerActions.Action.simple("empty", List.of());
        try (var ticks = mockStatic(TickThread.class); var hidden = mockStatic(OrgHiddenPlayerActions.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(any(ServerPlayer.class))).thenReturn(true);
            OrgFakePlayerActions.set(old, oldAction);
            OrgFakePlayerActions.set(fresh, freshAction);
            OrgFakePlayerActions.pendingCompletion(old);
            OrgFakePlayerActions.pendingCompletion(fresh);
            Field field = OrgFakePlayerActions.class.getDeclaredField("COMPLETIONS");
            field.setAccessible(true);
            var completions = (WeakIdentityMap<ServerPlayer, CarpetActionCompletion>) field.get(null);
            assertNotSame(completions.get(old), completions.get(fresh));
            var accepted = completions.get(old).begin();
            try {
                old.setId(23);
                assertSame(oldAction, OrgFakePlayerActions.get(old));
                assertSame(freshAction, OrgFakePlayerActions.get(fresh));
                assertFalse(OrgFakePlayerActions.pendingCompletion(old).isDone());
                assertTrue(OrgFakePlayerActions.pendingCompletion(fresh).isDone());
                OrgFakePlayerActions.set(old, OrgFakePlayerActions.Action.simple("stop", List.of()));
                assertSame(freshAction, OrgFakePlayerActions.get(fresh));
            } finally {
                accepted.finish();
                OrgFakePlayerActions.set(fresh, OrgFakePlayerActions.Action.simple("stop", List.of()));
            }
        }
    }
}
