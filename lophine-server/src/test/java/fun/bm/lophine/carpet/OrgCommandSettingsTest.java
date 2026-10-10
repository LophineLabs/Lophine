package fun.bm.lophine.carpet;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class OrgCommandSettingsTest {
    @TempDir
    Path directory;

    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    @Test
    void originalSortingBoundsAndStringArrayFallbackNamesAreReadExactly() {
        var defaults = OrgCommandSettings.parse(JsonParser.parseString("{}").getAsJsonObject());
        assertEquals(16, defaults.sortingItems());
        assertEquals(List.of("mail"), defaults.names("mail"));
        var selected = OrgCommandSettings.parse(JsonParser.parseString("{\"player_action_max_sorting_items\":999,\"custom_command_name\":{\"mail\":[\"post\",\"letters\",\"post\",{}],\"playerAction\":\"act\",\"xpTransfer\":[],\"creeper\":false,\"highlight\":\"client_alias\"}}").getAsJsonObject());
        assertEquals(256, selected.sortingItems());
        assertEquals(List.of("post", "letters"), selected.names("mail"));
        assertEquals(List.of("act"), selected.names("playerAction"));
        assertEquals(List.of("xpTransfer"), selected.names("xpTransfer"));
        assertEquals(List.of("creeper"), selected.names("creeper"));
        assertEquals(List.of("highlight"), selected.names("highlight"));
        assertEquals(1, OrgCommandSettings.parse(JsonParser.parseString("{\"player_action_max_sorting_items\":-9}").getAsJsonObject()).sortingItems());
        assertEquals("/post collect 42", OrgCommandSettings.rewriteCommand(selected, "/mail collect 42"));
        assertEquals("/player Ada spawn", OrgCommandSettings.rewriteCommand(selected, "/player Ada spawn"));
    }

    @Test
    void configuredNamesExecuteTheActualRootAndCompleteChildrenWithTheOriginalPermission() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var allowed = new AtomicBoolean(true);
        var source = mock(CommandSourceStack.class);
        var original = dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("mail").requires(ignored -> allowed.get()).executes(context -> 7).then(LiteralArgumentBuilder.<CommandSourceStack>literal("collect").executes(context -> 19)));
        var settings = OrgCommandSettings.parse(JsonParser.parseString("{\"custom_command_name\":{\"mail\":[\"post\",\"letters\"]}}").getAsJsonObject());
        OrgCommandSettings.apply(dispatcher, settings);
        assertNull(dispatcher.getRoot().getChild("mail"));
        assertSame(original.getChild("collect"), dispatcher.getRoot().getChild("post").getChild("collect"));
        try {
            assertEquals(7, dispatcher.execute("post", source));
            assertEquals(19, dispatcher.execute("post collect", source));
            assertEquals(19, dispatcher.execute("letters collect", source));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        allowed.set(false);
        assertFalse(dispatcher.getRoot().getChild("post").canUse(source));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> dispatcher.execute("letters collect", source));
    }

    @Test
    void aliasCollisionsMergeBothActualSubtreesAndMissingConfiguredCommandsAreNotInvented() throws Exception {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var source = mock(CommandSourceStack.class);
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("playerAction").then(LiteralArgumentBuilder.<CommandSourceStack>literal("craft").executes(context -> 4)));
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("mail").then(LiteralArgumentBuilder.<CommandSourceStack>literal("send").executes(context -> 8)));
        var settings = OrgCommandSettings.parse(JsonParser.parseString("{\"custom_command_name\":{\"playerAction\":\"tasks\",\"mail\":\"tasks\",\"finder\":\"where\"}}").getAsJsonObject());
        OrgCommandSettings.apply(dispatcher, settings);
        assertEquals(4, dispatcher.execute("tasks craft", source));
        assertEquals(8, dispatcher.execute("tasks send", source));
        assertNull(dispatcher.getRoot().getChild("playerAction"));
        assertNull(dispatcher.getRoot().getChild("mail"));
        assertNull(dispatcher.getRoot().getChild("where"));
    }

    @Test
    void originalLegacyNamesAreUsedOnlyWhenTheCurrentGlobalNamesSectionIsAbsent() throws Exception {
        Path current = directory.resolve("carpet-org-addition.json"), legacy = directory.resolve("custom_command_name.json");
        Files.writeString(current, "{\"player_action_max_sorting_items\":32,\"unrelated\":true}");
        Files.writeString(legacy, "{\"commands\":{\"mail\":\"legacy_post\"}}");
        var first = OrgCommandSettings.read(current, legacy);
        assertEquals(32, first.sortingItems());
        assertEquals(List.of("legacy_post"), first.names("mail"));
        assertTrue(Files.readString(current).contains("unrelated"));
        assertTrue(Files.isRegularFile(legacy));
        Files.writeString(current, "{\"custom_command_name\":{\"mail\":\"current_post\"}}");
        assertEquals(List.of("current_post"), OrgCommandSettings.read(current, legacy).names("mail"));
    }

    @Test
    void theActualSortingCommandTreeUsesTheConfiguredMaximumPredicateCount() {
        var lookup = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var access = net.minecraft.commands.CommandBuildContext.simple(lookup, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
        var settings = OrgCommandSettings.parse(JsonParser.parseString("{\"player_action_max_sorting_items\":3}").getAsJsonObject());
        var sorting = OrgFakePlayerActionCommands.sorting(access, settings.sortingItems()).build();
        var first = sorting.getChild("item1");
        var second = first.getChild("or").getChild("item2");
        var third = second.getChild("or").getChild("item3");
        assertNotNull(first.getChild("at"));
        assertNotNull(second.getChild("at"));
        assertNotNull(third.getChild("at"));
        assertNull(third.getChild("or"));
        assertNotNull(third.getChild("at").getChild("this").getChild("other").getCommand());
    }
}
