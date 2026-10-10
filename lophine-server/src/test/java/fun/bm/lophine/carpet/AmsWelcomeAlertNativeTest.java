package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsWelcomeAlertNativeTest {
    @BeforeAll
    static void boot() throws Exception {
        AmsNativeManagementTest.bootstrap();
    }

    @TempDir
    Path directory;

    @Test
    void longConfiguredWelcomeKeepsItsOwnerStackBoundedAndSendsEveryLineInOrder() throws Exception {
        var lines = new com.google.gson.JsonArray();
        for (int line = 0; line < 1_000; line++) lines.add("line " + line);
        var json = new com.google.gson.JsonObject();
        json.add("welcomeMessage", lines);
        file(json.toString());
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            GeneralCompatConfig.welcomeMessage = true;
            var sent = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                assertSame(f.sourcePlayer, f.current);
                assertEquals("line " + sent.getAndIncrement(), ((Component) call.getArgument(0)).getString());
                return null;
            }).when(f.sourcePlayer).sendSystemMessage(any(Component.class));
            var actual = AmsWelcomeMessage.sendAsync(f.sourcePlayer);
            waitFor(f, actual::isDone);
            actual.join();
            assertEquals(1_000, sent.get());
            ScarpetNativeWork.whenIdle(f.server).join();
        }
    }

    private String previousLanguage;

    @BeforeEach
    void preserveLanguage() {
        previousLanguage = GeneralCompatConfig.language;
    }

    @AfterEach
    void reset() {
        GeneralCompatConfig.welcomeMessage = false;
        GeneralCompatConfig.sendPlayerDeathLocation = "false";
        GeneralCompatConfig.phantomSpawnAlert = false;
        GeneralCompatConfig.language = previousLanguage;
    }

    private void translations(AmsNativeManagementTest.Fixture f) {
        f.translations.when(AmsTranslations::serverLanguage).thenCallRealMethod();
        f.translations.when(() -> AmsTranslations.translateText(any(Component.class), anyString())).thenCallRealMethod();
        f.translations.when(() -> AmsTranslations.formatPattern(anyString(), any(Object[].class))).thenCallRealMethod();
        GeneralCompatConfig.language = "en_us";
    }

    private void waitFor(AmsNativeManagementTest.Fixture f, java.util.function.BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            f.drain();
            Thread.sleep(2);
        }
        assertTrue(ready.getAsBoolean());
    }

    private Path file() {
        return directory.resolve("carpetamsaddition/welcomeMessage.json");
    }

    private void file(String text) throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), text);
    }

    @Test
    void actualWelcomeFileAdmissionAndLiteralLinesWaitEveryRealOwnerChildWithoutRecheckingRule() throws Exception {
        file("{\"welcomeMessage\":[\"§3first\",\"second\"]}");
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            GeneralCompatConfig.welcomeMessage = true;
            var child = new CompletableFuture<Void>();
            var sent = new ArrayList<Component>();
            doAnswer(call -> {
                assertSame(f.sourcePlayer, f.current);
                Component line = call.getArgument(0);
                sent.add(line);
                if (sent.size() == 1) ScarpetNativeWork.record(child);
                return null;
            }).when(f.sourcePlayer).sendSystemMessage(any(Component.class));
            var actual = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                AmsWelcomeMessage.send(f.sourcePlayer);
                return 7;
            });
            waitFor(f, () -> sent.size() == 1);
            assertEquals("§3first", sent.getFirst().getString());
            assertEquals(Style.EMPTY, sent.getFirst().getStyle());
            assertFalse(actual.isDone());
            GeneralCompatConfig.welcomeMessage = false;
            child.complete(null);
            waitFor(f, actual::isDone);
            assertEquals(7, actual.join());
            assertEquals(List.of("§3first", "second"), sent.stream().map(Component::getString).toList());
            ScarpetNativeWork.whenIdle(f.server).join();
        }
    }

    @Test
    void actualWelcomeNativeSendFailureBlocksFollowingOriginalLineAndRetainsTrueCause() throws Exception {
        file("{\"welcomeMessage\":\"first\\nsecond\"}");
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            GeneralCompatConfig.welcomeMessage = true;
            var child = new CompletableFuture<Void>();
            var sent = new ArrayList<String>();
            doAnswer(call -> {
                sent.add(((Component) call.getArgument(0)).getString());
                ScarpetNativeWork.record(child);
                return null;
            }).when(f.sourcePlayer).sendSystemMessage(any(Component.class));
            var actual = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                AmsWelcomeMessage.send(f.sourcePlayer);
                return 7;
            });
            waitFor(f, () -> !sent.isEmpty());
            var failure = new IllegalStateException("native welcome send");
            child.completeExceptionally(failure);
            waitFor(f, actual::isDone);
            assertSame(failure, assertThrows(CompletionException.class, actual::join).getCause());
            assertEquals(List.of("first"), sent);
            assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
            ScarpetNativeWork.whenIdle(f.server).handle((value, error) -> null).join();
        }
    }

    @Test
    void welcomeGuestOnlyFailureKeepsRawParentButOriginalFollowingNativeLineRuns() throws Exception {
        file("{\"welcomeMessage\":[\"first\",\"second\"]}");
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            GeneralCompatConfig.welcomeMessage = true;
            var child = new CompletableFuture<Void>();
            var sent = new ArrayList<String>();
            doAnswer(call -> {
                sent.add(((Component) call.getArgument(0)).getString());
                if (sent.size() == 1) ScarpetNativeWork.record(child);
                return null;
            }).when(f.sourcePlayer).sendSystemMessage(any(Component.class));
            var actual = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                AmsWelcomeMessage.send(f.sourcePlayer);
                return 7;
            });
            waitFor(f, () -> sent.size() == 1);
            var failure = new IllegalStateException("guest welcome");
            var mark = ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure", Throwable.class);
            mark.setAccessible(true);
            mark.invoke(null, failure);
            child.completeExceptionally(failure);
            waitFor(f, actual::isDone);
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, actual::join)));
            assertEquals(List.of("first", "second"), sent);
            ScarpetNativeWork.whenIdle(f.server).handle((value, error) -> null).join();
        }
    }

    @Test
    void actualDefaultWelcomeFileUsesOriginalServerTranslationAndOriginalLiteralSectionCharacters() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            GeneralCompatConfig.welcomeMessage = true;
            var sent = new ArrayList<String>();
            doAnswer(call -> {
                sent.add(((Component) call.getArgument(0)).getString());
                return null;
            }).when(f.sourcePlayer).sendSystemMessage(any(Component.class));
            var actual = AmsWelcomeMessage.sendAsync(f.sourcePlayer);
            assertFalse(actual.cancel(false));
            waitFor(f, actual::isDone);
            actual.join();
            assertEquals(List.of("§3§o Modify the content in：", "§a[ save path ]/carpetamsaddition/welcomeMessage.json"), sent);
            assertTrue(Files.exists(file()));
        }
    }

    @Test
    void originalMalformedWelcomeConfigurationIsPreservedAndHandledWithoutAnySend() throws Exception {
        file("original malformed data");
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            GeneralCompatConfig.welcomeMessage = true;
            var actual = AmsWelcomeMessage.sendAsync(f.sourcePlayer);
            waitFor(f, actual::isDone);
            actual.join();
            assertEquals("original malformed data", Files.readString(file()));
            verify(f.sourcePlayer, never()).sendSystemMessage(any(Component.class));
        }
    }

    @Test
    void originalValidWelcomePrefixStillSendsWhenFollowingJsonArrayElementCannotBeConverted() throws Exception {
        file("{\"welcomeMessage\":[\"valid prefix\",null,\"unreachable\"]}");
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            GeneralCompatConfig.welcomeMessage = true;
            var sent = new ArrayList<String>();
            doAnswer(call -> {
                sent.add(((Component) call.getArgument(0)).getString());
                return null;
            }).when(f.sourcePlayer).sendSystemMessage(any(Component.class));
            var actual = AmsWelcomeMessage.sendAsync(f.sourcePlayer);
            waitFor(f, actual::isDone);
            actual.join();
            assertEquals(List.of("valid prefix"), sent);
            assertTrue(Files.readString(file()).contains("unreachable"));
        }
    }

    private void recipients(AmsNativeManagementTest.Fixture f) {
        var players = mock(PlayerList.class);
        when(f.server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of(f.sourcePlayer, f.target));
        when(f.sourcePlayer.getName()).thenReturn(Component.literal("source"));
        when(f.world.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(f.sourcePlayer.blockPosition()).thenReturn(new BlockPos(3, 64, -7));
    }

    @Test
    void deathMessageOwnsOriginalPlayerSnapshotThenConsoleAndEachActualRecipientAndKeepsSourceButtons() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            recipients(f);
            GeneralCompatConfig.sendPlayerDeathLocation = "all";
            var console = new CompletableFuture<Void>();
            var first = new CompletableFuture<Void>();
            var last = new CompletableFuture<Void>();
            var delivered = new ArrayList<Component>();
            doAnswer(call -> {
                assertTrue(f.global);
                f.order.add("console");
                ScarpetNativeWork.record(console);
                delivered.add(call.getArgument(0));
                return null;
            }).when(f.server).sendSystemMessage(any(Component.class));
            for (var recipient : List.of(f.sourcePlayer, f.target))
                doAnswer(call -> {
                    assertSame(recipient, f.current);
                    f.order.add(recipient == f.sourcePlayer ? "first" : "last");
                    delivered.add(call.getArgument(0));
                    ScarpetNativeWork.record(recipient == f.sourcePlayer ? first : last);
                    return null;
                }).when(recipient).sendSystemMessage(any(Component.class));
            var raw = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                AmsServerMessages.deathLocation(f.sourcePlayer);
                return 11;
            });
            f.drain();
            assertEquals(List.of("console"), f.order);
            when(f.sourcePlayer.blockPosition()).thenReturn(new BlockPos(999, 1, 999));
            console.complete(null);
            f.drain();
            assertEquals(List.of("console", "first"), f.order);
            first.complete(null);
            f.drain();
            assertEquals(List.of("console", "first", "last"), f.order);
            assertFalse(raw.isDone());
            last.complete(null);
            assertEquals(11, raw.join());
            Component msg = delivered.getFirst();
            assertEquals("source death location @ minecraft:overworld -> [ 3, 64, -7 ] [C] [+H]", msg.getString());
            for (Component same : delivered) assertSame(msg, same);
            Component copy = msg.getSiblings().getFirst(), highlight = msg.getSiblings().get(1);
            assertEquals(new ClickEvent.CopyToClipboard("3 64 -7"), copy.getStyle().getClickEvent());
            assertEquals(new ClickEvent.RunCommand("/coordCompass set 3 64 -7"), highlight.getStyle().getClickEvent());
            assertEquals("copy coord to clipboard", ((HoverEvent.ShowText) copy.getStyle().getHoverEvent()).value().getString());
            assertTrue(((HoverEvent.ShowText) highlight.getStyle().getHoverEvent()).value().getString().contains("fuzz mod"));
            ScarpetNativeWork.whenIdle(f.server).join();
        }
    }

    @Test
    void genuineConsoleFailureBlocksAllDeathRecipientsAndRetainsNativeParentFailure() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            recipients(f);
            GeneralCompatConfig.sendPlayerDeathLocation = "all";
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(child);
                return null;
            }).when(f.server).sendSystemMessage(any(Component.class));
            var raw = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                AmsServerMessages.deathLocation(f.sourcePlayer);
                return 11;
            });
            f.drain();
            child.completeExceptionally(new IllegalStateException("console native"));
            f.drain();
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, raw::join)));
            verify(f.sourcePlayer, never()).sendSystemMessage(any(Component.class));
            verify(f.target, never()).sendSystemMessage(any(Component.class));
            ScarpetNativeWork.whenIdle(f.server).handle((value, error) -> null).join();
        }
    }

    @Test
    void phantomMessageIsOriginalServerTranslatedLiteralAndDefaultRulesHaveNoEffects() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            translations(f);
            recipients(f);
            AmsServerMessages.phantomSpawnAsync(f.server, "source", 1.5, 64, -7).join();
            AmsServerMessages.deathLocationAsync(f.sourcePlayer).join();
            verify(f.server, never()).sendSystemMessage(any(Component.class));
            GeneralCompatConfig.phantomSpawnAlert = true;
            var sent = new ArrayList<Component>();
            doAnswer(call -> {
                sent.add(call.getArgument(0));
                return null;
            }).when(f.server).sendSystemMessage(any(Component.class));
            var raw = AmsServerMessages.phantomSpawnAsync(f.server, "source", 1.5, 64, -7);
            f.drain();
            raw.join();
            assertEquals("§b<phantomSpawnAlert> §r§esource §r§dsummoned phantoms §r§e@ §r§a[ 1.5, 64.0, -7.0 ]", sent.getFirst().getString());
            assertEquals(Style.EMPTY, sent.getFirst().getStyle());
        }
    }
}
