package fun.bm.lophine.carpet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.bot.ServerBotGameMode;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetNativeBotGameModeTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() throws Exception {
        OrgInventoryPersistenceTest.bootstrap();
        AmsNativeManagementTest.bootstrap();
    }

    private static final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors;
        final org.mockito.MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
        final org.mockito.MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final PluginManager plugins = mock(PluginManager.class);
        final ServerBot bot;
        final Abilities abilities = new Abilities();
        final ServerBotGameMode mode;
        final boolean previousSurvival;

        Fixture(Path directory, boolean nativePlayer) throws Exception {
            actors = new OrgInventoryPersistenceTest.Fixture(directory, true);
            bot = (ServerBot) actors.target.player();
            bot.carpetNativePlayer = nativePlayer;
            when(bot.getAbilities()).thenReturn(abilities);
            bot.connection = mock(ServerGamePacketListenerImpl.class);
            when(bot.getMainHandItem()).thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
            when(bot.getOffhandItem()).thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
            when(bot.getItemInHand(any())).thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
            when(actors.server.getDefaultGameType()).thenReturn(GameType.SURVIVAL);
            servers.when(MinecraftServer::getServer).thenReturn(actors.server);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            previousSurvival = fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode;
            fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode = false;
            mode = new ServerBotGameMode(bot);
            bot.gameMode = mode;
            actors.owner.set(bot);
        }

        public void close() {
            fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode = previousSurvival;
            bukkit.close();
            servers.close();
            actors.close();
        }
    }

    @Test
    void actualPlacementModeSetterMakesNativeBotsCreativeAndUpdatesNativeAbilities() throws Exception {
        try (var fixture = new Fixture(directory, true)) {
            doCallRealMethod().when(fixture.bot).setGameMode(GameType.CREATIVE, PlayerGameModeChangeEvent.Cause.COMMAND, null);
            var event = fixture.bot.setGameMode(GameType.CREATIVE, PlayerGameModeChangeEvent.Cause.COMMAND, null);
            assertNotNull(event);
            assertFalse(event.isCancelled());
            assertEquals(PlayerGameModeChangeEvent.Cause.COMMAND, event.getCause());
            assertEquals(GameType.CREATIVE, fixture.mode.getGameModeForPlayer());
            assertEquals(GameType.SURVIVAL, fixture.mode.getPreviousGameModeForPlayer());
            assertTrue(fixture.mode.isCreative());
            assertTrue(fixture.abilities.invulnerable);
            assertTrue(fixture.abilities.mayfly);
            assertTrue(fixture.abilities.instabuild);
            verify(fixture.plugins).callEvent(event);
        }
    }

    @Test
    void deprecatedNativeModeEntryAlsoUsesTheRealModeTransition() throws Exception {
        try (var fixture = new Fixture(directory, true)) {
            assertTrue(fixture.mode.changeGameModeForPlayer(GameType.SPECTATOR));
            assertEquals(GameType.SPECTATOR, fixture.mode.getGameModeForPlayer());
            assertTrue(fixture.abilities.invulnerable);
            assertTrue(fixture.abilities.flying);
            assertFalse(fixture.abilities.instabuild);
        }
    }

    @Test
    void cancellationRetainsThePreviousNativeModeAndAbilities() throws Exception {
        try (var fixture = new Fixture(directory, true)) {
            doAnswer(call -> {
                ((PlayerGameModeChangeEvent) call.getArgument(0)).setCancelled(true);
                return null;
            })
                    .when(fixture.plugins).callEvent(any(PlayerGameModeChangeEvent.class));
            var event = fixture.mode.changeGameModeForPlayer(GameType.CREATIVE, PlayerGameModeChangeEvent.Cause.COMMAND, null);
            assertNotNull(event);
            assertTrue(event.isCancelled());
            assertEquals(GameType.SURVIVAL, fixture.mode.getGameModeForPlayer());
            assertFalse(fixture.abilities.invulnerable);
            assertFalse(fixture.abilities.mayfly);
            verify(fixture.bot, never()).onUpdateAbilities();
        }
    }

    @Test
    void legacyBotModeRestrictionsRemainInEffect() throws Exception {
        try (var fixture = new Fixture(directory, false)) {
            assertFalse(fixture.mode.changeGameModeForPlayer(GameType.CREATIVE));
            assertNull(fixture.mode.changeGameModeForPlayer(GameType.CREATIVE, PlayerGameModeChangeEvent.Cause.COMMAND, null));
            assertEquals(GameType.SURVIVAL, fixture.mode.getGameModeForPlayer());
            assertFalse(fixture.abilities.invulnerable);
            verifyNoInteractions(fixture.plugins);
        }
    }

    @Test
    void nativeMiningTickCompletesItsDelayedBreakInsteadOfLeavingItPendingForever() throws Exception {
        try (var fixture = new Fixture(directory, true); var blocks = mockStatic(org.bukkit.craftbukkit.block.CraftBlock.class)) {
            var world = fixture.bot.level();
            var pos = net.minecraft.core.BlockPos.ZERO;
            var state = mock(net.minecraft.world.level.block.state.BlockState.class);
            when(world.getLagCompensationTick()).thenReturn(20L);
            when(world.getBlockStateIfLoaded(pos)).thenReturn(state);
            when(world.getBlockState(pos)).thenReturn(state);
            when(state.getDestroyProgress(fixture.bot, world, pos)).thenReturn(0.1F);
            fixture.actors.ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), eq(pos))).thenReturn(true);
            when(fixture.bot.blockActionRestricted(world, pos, GameType.SURVIVAL)).thenReturn(true);
            blocks.when(() -> org.bukkit.craftbukkit.block.CraftBlock.at(world, pos)).thenReturn(mock(org.bukkit.craftbukkit.block.CraftBlock.class));
            fixture.mode.hasDelayedDestroy = true;
            fixture.mode.tick();
            assertFalse(fixture.mode.hasDelayedDestroy);
            verify(world).destroyBlockProgress(fixture.bot.getId(), pos, 21);
            verify(world, never()).removeBlock(any(), anyBoolean());
        }
    }

    @Test
    void nativeAdventureRestrictionStopsBreakingBeforeWorldMutation() throws Exception {
        try (var fixture = new Fixture(directory, true); var blocks = mockStatic(org.bukkit.craftbukkit.block.CraftBlock.class)) {
            var world = fixture.bot.level();
            var pos = net.minecraft.core.BlockPos.ZERO;
            when(world.getBlockState(pos)).thenReturn(mock(net.minecraft.world.level.block.state.BlockState.class));
            when(fixture.bot.blockActionRestricted(world, pos, GameType.SURVIVAL)).thenReturn(true);
            blocks.when(() -> org.bukkit.craftbukkit.block.CraftBlock.at(world, pos)).thenReturn(mock(org.bukkit.craftbukkit.block.CraftBlock.class));
            assertFalse(fixture.mode.destroyBlock(pos));
            verify(fixture.bot).blockActionRestricted(world, pos, GameType.SURVIVAL);
            verify(world, never()).removeBlock(any(), anyBoolean());
        }
    }

    @Test
    void nativeFailedDestroyResynchronizesThePredictedBlock() throws Exception {
        try (var fixture = new Fixture(directory, true); var blocks = mockStatic(org.bukkit.craftbukkit.block.CraftBlock.class)) {
            var world = fixture.bot.level();
            var pos = net.minecraft.core.BlockPos.ZERO;
            when(world.getBlockState(pos)).thenReturn(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            when(fixture.bot.blockActionRestricted(world, pos, GameType.SURVIVAL)).thenReturn(true);
            blocks.when(() -> org.bukkit.craftbukkit.block.CraftBlock.at(world, pos)).thenReturn(mock(org.bukkit.craftbukkit.block.CraftBlock.class));
            fixture.mode.destroyAndAck(pos, 7, "native denied prediction");
            verify(fixture.bot.connection).send(any(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket.class));
            verify(world, never()).removeBlock(any(), anyBoolean());
        }
    }

    @Test
    void nativeSpectatorUsesTheRealContainerInteractionWhileLegacyBotsKeepTheirOriginalPath() throws Exception {
        for (boolean nativePlayer : new boolean[]{true, false})
            try (var fixture = new Fixture(directory.resolve(Boolean.toString(nativePlayer)), nativePlayer); var events = mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)) {
                var world = fixture.bot.level();
                var pos = net.minecraft.core.BlockPos.ZERO;
                var block = mock(net.minecraft.world.level.block.Block.class);
                var state = mock(net.minecraft.world.level.block.state.BlockState.class);
                var menu = mock(net.minecraft.world.MenuProvider.class);
                var stack = net.minecraft.world.item.ItemStack.EMPTY;
                var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.ZERO, net.minecraft.core.Direction.UP, pos, false);
                when(world.getBlockState(pos)).thenReturn(state);
                when(state.getBlock()).thenReturn(block);
                when(block.isEnabled(any())).thenReturn(true);
                when(state.getMenuProvider(world, pos)).thenReturn(menu);
                when(state.useItemOn(stack, world, fixture.bot, net.minecraft.world.InteractionHand.MAIN_HAND, hit)).thenReturn(net.minecraft.world.InteractionResult.PASS);
                when(fixture.bot.getCooldowns()).thenReturn(mock(net.minecraft.world.item.ItemCooldowns.class));
                when(fixture.bot.openMenu(menu)).thenReturn(java.util.OptionalInt.of(4));
                var event = mock(org.bukkit.event.player.PlayerInteractEvent.class);
                when(event.useInteractedBlock()).thenReturn(org.bukkit.event.Event.Result.DEFAULT);
                when(event.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DEFAULT);
                events.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerInteractEvent(eq(fixture.bot), eq(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK), eq(pos), eq(net.minecraft.core.Direction.UP), eq(stack), anyBoolean(), anyBoolean(), eq(net.minecraft.world.InteractionHand.MAIN_HAND), eq(net.minecraft.world.phys.Vec3.ZERO))).thenReturn(event);
                if (nativePlayer) assertTrue(fixture.mode.changeGameModeForPlayer(GameType.SPECTATOR));
                var result = fixture.mode.useItemOn(fixture.bot, world, stack, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                assertSame(nativePlayer ? net.minecraft.world.InteractionResult.CONSUME : net.minecraft.world.InteractionResult.PASS, result);
                verify(fixture.bot, nativePlayer ? times(1) : never()).openMenu(menu);
            }
    }
}
