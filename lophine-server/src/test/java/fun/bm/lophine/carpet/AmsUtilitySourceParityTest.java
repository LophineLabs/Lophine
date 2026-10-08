package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsUtilitySourceParityTest {
    @BeforeAll
    static void boot() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var config = new io.papermc.paper.configuration.GlobalConfiguration();
        config.misc = config.new Misc();
        try (var configs = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)) {
            configs.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);
            Class.forName("net.minecraft.network.Connection");
        }
    }

    @TempDir
    Path directory;

    static com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack> root(String name) {
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        CarpetAMSCommands.register(dispatcher);
        return dispatcher.getRoot().getChild(name);
    }

    static void names(AmsNativeManagementTest.Fixture f) {
        var sourceProfile = new com.mojang.authlib.GameProfile(f.sourcePlayer.getUUID(), "source");
        var targetProfile = new com.mojang.authlib.GameProfile(f.target.getUUID(), "target");
        when(f.world.dimension()).thenReturn(Level.OVERWORLD);
        when(f.source.getTextName()).thenReturn("source");
        when(f.sourcePlayer.getGameProfile()).thenReturn(sourceProfile);
        when(f.target.getGameProfile()).thenReturn(targetProfile);
    }

    @SuppressWarnings("unchecked")
    static <T> T invoke(String name, Class<?>[] types, Object... args) throws Exception {
        var method = CarpetAMSCommands.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return (T) method.invoke(null, args);
    }

    static void drainUntil(AmsNativeManagementTest.Fixture f, java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            f.drain();
            Thread.sleep(2);
        }
        f.drain();
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void actualHeldItemLeafKeepsOriginalRegexStylesCopyHoverAndTrueReplyFence() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            var stack = mock(ItemStack.class);
            when(stack.getItem()).thenReturn(Items.MUSIC_DISC_13);
            when(f.sourcePlayer.getMainHandItem()).thenReturn(stack);
            var reply = new CompletableFuture<Void>();
            var messages = new ArrayList<Component>();
            f.messenger.when(() -> CarpetMessenger.sendAsync(eq(f.source), anyCollection())).thenAnswer(call -> {
                messages.add(((Collection<Component>) call.getArgument(1)).iterator().next());
                return reply;
            });
            root("getHeldItemID").getCommand().run(f.context());
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertFalse(actual.isDone());
            var message = messages.getFirst();
            assertEquals("<commandGetHeldItemID> minecraft:music_disc_ [C] ", message.getString());
            assertEquals(TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.AQUA).getValue(), message.getStyle().getColor().getValue());
            var button = message.getSiblings().getLast();
            assertEquals(new ClickEvent.CopyToClipboard("minecraft:music_disc_"), button.getStyle().getClickEvent());
            assertTrue(button.getStyle().isBold());
            var hover = (HoverEvent.ShowText) button.getStyle().getHoverEvent();
            assertEquals("command.commandGetHeldItemID.getHeldItemID.copy", hover.value().getString());
            reply.complete(null);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            verify(f.callback).onResult(true, 1);
        }
    }

    @Test
    void actualHereLeafWaitsBroadcastThenItsOriginalGlowBeforeSourceInt() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            names(f);
            when(f.source.getPosition()).thenReturn(new Vec3(-17.4, 70.9, -25.5));
            var broadcast = new CompletableFuture<Void>();
            var glow = new CompletableFuture<Void>();
            var messages = new ArrayList<Component>();
            f.messenger.when(() -> CarpetMessenger.print_server_messageAsync(eq(f.server), any(Component.class))).thenAnswer(call -> {
                f.order.add("broadcast");
                messages.add(call.getArgument(1));
                return broadcast;
            });
            when(f.sourcePlayer.addEffect(any(MobEffectInstance.class))).thenAnswer(call -> {
                assertSame(f.sourcePlayer, f.current);
                assertEquals(600, ((MobEffectInstance) call.getArgument(0)).getDuration());
                f.order.add("glow");
                ScarpetNativeWork.record(glow);
                return false;
            });
            root("here").getCommand().run(f.context());
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(List.of("broadcast"), f.order);
            var message = messages.getFirst();
            assertTrue(message.getString().contains("-17, 70, -25"));
            assertTrue(message.getString().contains("-2, 70, -3"));
            assertEquals(new ClickEvent.CopyToClipboard("-17 70 -25"), message.getSiblings().get(0).getStyle().getClickEvent());
            assertEquals(new ClickEvent.RunCommand("/coordCompass set -17 70 -25"), message.getSiblings().getLast().getStyle().getClickEvent());
            broadcast.complete(null);
            f.drain();
            assertEquals(List.of("broadcast", "glow"), f.order);
            assertFalse(actual.isDone());
            glow.complete(null);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualHereBroadcastFailureStopsGlowAndFailsSourceInt() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            names(f);
            var broadcast = new CompletableFuture<Void>();
            f.messenger.when(() -> CarpetMessenger.print_server_messageAsync(eq(f.server), any())).thenReturn(broadcast);
            root("here").getCommand().run(f.context());
            var actual = scope.resultFuture(f.source);
            f.drain();
            broadcast.completeExceptionally(new IllegalStateException("actual broadcast"));
            f.drain();
            assertEquals(0, actual.get(3, TimeUnit.SECONDS));
            verify(f.sourcePlayer, never()).addEffect(any());
            verify(f.callback).onResult(false, 0);
            ScarpetNativeWork.whenIdle(f.server).handle((value, failure) -> null).join();
        }
    }

    @Test
    void actualWhereBodyWaitsOriginalSenderReplyThenTranslatedBroadcastThenTargetGlow() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            names(f);
            when(f.target.getX()).thenReturn(17.5);
            when(f.target.getY()).thenReturn(70.5);
            when(f.target.getZ()).thenReturn(-5.2);
            var reply = new CompletableFuture<Void>();
            var broadcast = new CompletableFuture<Void>();
            var glow = new CompletableFuture<Void>();
            f.messenger.when(() -> CarpetMessenger.sendAsync(eq(f.sourcePlayer), anyCollection())).thenAnswer(call -> {
                f.order.add("reply");
                assertTrue(((Collection<Component>) call.getArgument(1)).iterator().next().getString().contains("17, 70, -5"));
                return reply;
            });
            f.messenger.when(() -> CarpetMessenger.print_server_messageAsync(eq(f.server), any())).thenAnswer(call -> {
                f.order.add("broadcast");
                Component message = call.getArgument(1);
                assertEquals("command.where.who_get_who", message.getString());
                assertTrue(message.getStyle().isItalic());
                return broadcast;
            });
            when(f.target.addEffect(any(MobEffectInstance.class))).thenAnswer(call -> {
                assertSame(f.target, f.current);
                f.order.add("glow");
                ScarpetNativeWork.record(glow);
                return false;
            });
            invoke("where", new Class<?>[]{net.minecraft.commands.CommandSourceStack.class, ServerPlayer.class}, f.source, f.target);
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(List.of("reply"), f.order);
            reply.complete(null);
            f.drain();
            assertEquals(List.of("reply", "broadcast"), f.order);
            broadcast.complete(null);
            f.drain();
            assertEquals(List.of("reply", "broadcast", "glow"), f.order);
            assertFalse(actual.isDone());
            glow.complete(null);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            verify(f.callback).onResult(true, 1);
        }
    }

    @Test
    void actualWhereSenderReplyFailureBlocksAnnouncementAndGlow() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            names(f);
            var reply = new CompletableFuture<Void>();
            f.messenger.when(() -> CarpetMessenger.sendAsync(eq(f.sourcePlayer), anyCollection())).thenReturn(reply);
            invoke("where", new Class<?>[]{net.minecraft.commands.CommandSourceStack.class, ServerPlayer.class}, f.source, f.target);
            var actual = scope.resultFuture(f.source);
            f.drain();
            reply.completeExceptionally(new IllegalStateException("actual sender reply"));
            f.drain();
            assertEquals(0, actual.get(3, TimeUnit.SECONDS));
            f.messenger.verify(() -> CarpetMessenger.print_server_messageAsync(any(), any()), never());
            verify(f.target, never()).addEffect(any());
            ScarpetNativeWork.whenIdle(f.server).handle((value, failure) -> null).join();
        }
    }

    @Test
    void actualSkullLeafCreatesOriginalStackProfileThenWaitsIgnoredFalseAdd() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open(); var created = mockConstruction(ItemStack.class, (stack, context) -> assertEquals(List.of(Items.PLAYER_HEAD, 4), context.arguments()))) {
            var ctx = f.context();
            when(ctx.getArgument("player", String.class)).thenReturn("target");
            when(ctx.getArgument("count", int.class)).thenReturn(4);
            var added = new CompletableFuture<Void>();
            when(f.sourcePlayer.addItem(any(ItemStack.class))).thenAnswer(call -> {
                assertSame(f.sourcePlayer, f.current);
                ScarpetNativeWork.record(added);
                return false;
            });
            root("getPlayerSkull").getChild("player").getChild("count").getCommand().run(ctx);
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(1, created.constructed().size());
            var skull = created.constructed().getFirst();
            var order = inOrder(skull, f.sourcePlayer);
            order.verify(skull).set(eq(net.minecraft.core.component.DataComponents.PROFILE), any(ResolvableProfile.class));
            order.verify(f.sourcePlayer).addItem(skull);
            assertFalse(actual.isDone());
            added.complete(null);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            verify(f.callback).onResult(true, 1);
        }
    }

    @Test
    void actualGotoPositionKeepsOriginalCastScalingAndSameDimensionBlockFloor() throws Exception {
        var player = mock(ServerPlayer.class);
        var source = mock(ServerLevel.class);
        var target = mock(ServerLevel.class);
        when(player.level()).thenReturn(source);
        when(player.getX()).thenReturn(-17.4);
        when(player.getY()).thenReturn(70.9);
        when(player.getZ()).thenReturn(24.2);
        when(source.dimension()).thenReturn(Level.OVERWORLD);
        when(target.dimension()).thenReturn(Level.NETHER);
        assertEquals(new BlockPos(-2, 70, 3), invoke("gotoPosition", new Class<?>[]{ServerPlayer.class, ServerLevel.class, BlockPos.class}, player, target, null));
        when(source.dimension()).thenReturn(Level.NETHER);
        when(target.dimension()).thenReturn(Level.OVERWORLD);
        assertEquals(new BlockPos(-139, 70, 193), invoke("gotoPosition", new Class<?>[]{ServerPlayer.class, ServerLevel.class, BlockPos.class}, player, target, null));
        when(source.dimension()).thenReturn(Level.OVERWORLD);
        var original = new BlockPos(-18, 70, 24);
        when(player.blockPosition()).thenReturn(original);
        assertSame(original, invoke("gotoPosition", new Class<?>[]{ServerPlayer.class, ServerLevel.class, BlockPos.class}, player, target, null));
        var explicit = new BlockPos(2, 3, 4);
        assertSame(explicit, invoke("gotoPosition", new Class<?>[]{ServerPlayer.class, ServerLevel.class, BlockPos.class}, player, target, explicit));
    }

    @Test
    void actualGotoWaitsOriginalRotationAndAsyncFalseTeleportBeforeReturningOriginalOne() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            names(f);
            when(f.sourcePlayer.blockPosition()).thenReturn(new BlockPos(-18, 70, 24));
            when(f.sourcePlayer.getViewXRot(1)).thenReturn(19F);
            var teleported = new CompletableFuture<Boolean>();
            when(f.sourcePlayer.getBukkitEntity().teleportAsync(any(org.bukkit.Location.class), eq(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND))).thenAnswer(call -> {
                assertSame(f.sourcePlayer, f.current);
                org.bukkit.Location to = call.getArgument(0);
                assertEquals(-18, to.getX());
                assertEquals(70, to.getY());
                assertEquals(24, to.getZ());
                assertEquals(19F, to.getYaw());
                assertEquals(1F, to.getPitch());
                return teleported;
            });
            invoke("goTo", new Class<?>[]{net.minecraft.commands.CommandSourceStack.class, ServerLevel.class, BlockPos.class}, f.source, f.world, null);
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertFalse(actual.isDone());
            teleported.complete(false);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            verify(f.callback).onResult(true, 1);
        }
    }

    @Test
    void actualGotoTrueAsyncFailureFailsTheOriginalSourceResult() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            names(f);
            var teleported = new CompletableFuture<Boolean>();
            when(f.sourcePlayer.getBukkitEntity().teleportAsync(any(org.bukkit.Location.class), eq(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND))).thenReturn(teleported);
            invoke("goTo", new Class<?>[]{net.minecraft.commands.CommandSourceStack.class, ServerLevel.class, BlockPos.class}, f.source, f.world, BlockPos.ZERO);
            var actual = scope.resultFuture(f.source);
            f.drain();
            teleported.completeExceptionally(new IllegalStateException("actual teleport"));
            f.drain();
            assertEquals(0, actual.get(3, TimeUnit.SECONDS));
            verify(f.callback).onResult(false, 0);
            ScarpetNativeWork.whenIdle(f.server).handle((value, failure) -> null).join();
        }
    }

    @Test
    void actualSaveSizeLeafWaitsSaveThenOriginalReplyThenFileThenSizeReply() throws Exception {
        Files.write(directory.resolve("world-payload.bin"), new byte[2 * 1024 * 1024]);
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            var save = new CompletableFuture<Void>();
            var savedReply = new CompletableFuture<Void>();
            var sizeReply = new CompletableFuture<Void>();
            var messages = new ArrayList<Component>();
            when(f.server.saveEverything(false, true, true)).thenAnswer(call -> {
                assertTrue(f.global);
                f.order.add("save");
                ScarpetNativeWork.record(save);
                return false;
            });
            when(f.server.getWorldPath(LevelResource.ROOT)).thenAnswer(call -> {
                f.order.add("file");
                return directory;
            });
            f.messenger.when(() -> CarpetMessenger.sendAsync(eq(f.source), anyCollection())).thenAnswer(call -> {
                messages.add(((Collection<Component>) call.getArgument(1)).iterator().next());
                f.order.add("reply" + messages.size());
                return messages.size() == 1 ? savedReply : sizeReply;
            });
            root("getSaveSize").getCommand().run(f.context());
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(List.of("save"), f.order);
            save.complete(null);
            f.drain();
            assertEquals(List.of("save", "reply1"), f.order);
            assertEquals("command.getSaveSize.save_fail_msg", messages.getFirst().getString());
            savedReply.complete(null);
            drainUntil(f, () -> messages.size() == 2);
            assertEquals(List.of("save", "reply1", "file", "reply2"), f.order);
            assertTrue(messages.getLast().getString().endsWith("0.002 GB"));
            assertFalse(actual.isDone());
            sizeReply.complete(null);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            verify(f.callback).onResult(true, 1);
        }
    }

    @Test
    void actualFolderSizeKeepsOriginalNullListingAndRecursiveFileLengths() throws Exception {
        Files.createDirectories(directory.resolve("sub"));
        Files.write(directory.resolve("one"), new byte[7]);
        Files.write(directory.resolve("sub/two"), new byte[11]);
        assertEquals(Long.valueOf(18L), (Object) invoke("folderSize", new Class<?>[]{java.io.File.class}, directory.toFile()));
        var file = mock(java.io.File.class);
        when(file.listFiles()).thenReturn(null);
        assertEquals(Long.valueOf(0L), (Object) invoke("folderSize", new Class<?>[]{java.io.File.class}, file));
    }

    @Test
    void actualSystemInfoLeafKeepsSixTranslatedLinesSeparatorsAndReplyFence() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            var reply = new CompletableFuture<Void>();
            var messages = new ArrayList<Component>();
            f.messenger.when(() -> CarpetMessenger.sendAsync(eq(f.source), anyCollection())).thenAnswer(call -> {
                messages.add(((Collection<Component>) call.getArgument(1)).iterator().next());
                return reply;
            });
            root("getSystemInfo").getCommand().run(f.context());
            var actual = scope.resultFuture(f.source);
            f.drain();
            var message = messages.getFirst();
            String text = message.getString();
            assertTrue(text.startsWith("===================================\ncommand.getSystemInfo.os\n"));
            assertTrue(text.contains("command.getSystemInfo.os_arch\ncommand.getSystemInfo.available_processors\ncommand.getSystemInfo.max_memory\ncommand.getSystemInfo.total_memory\ncommand.getSystemInfo.free_memory\n"));
            assertTrue(text.endsWith("===================================\n"));
            assertEquals(TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.DARK_AQUA).getValue(), message.getStyle().getColor().getValue());
            assertFalse(actual.isDone());
            reply.complete(null);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
        }
    }
}
