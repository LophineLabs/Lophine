package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Lifecycle;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class OrgFinderCommandTest {
    @BeforeAll
    static void bootstrap() {
        OrgFinderStatisticsTest.bootstrap();
    }

    private record Fixture(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source) {
    }

    private Fixture fixture() {
        var enchantments = new MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable());
        Registry.register(enchantments, "mending", mock(Enchantment.class));
        enchantments.freeze();
        var lookup = HolderLookup.Provider.create(Stream.concat(BuiltInRegistries.REGISTRY.stream().map(registry -> (HolderLookup.RegistryLookup<?>) registry), Stream.of(enchantments)));
        var context = CommandBuildContext.simple(lookup, FeatureFlags.DEFAULT_FLAGS);
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var source = mock(CommandSourceStack.class);
        when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);
        OrgFinderCommands.register(dispatcher, context);
        return new Fixture(dispatcher, source);
    }

    private boolean parses(Fixture f, String text) {
        var parsed = f.dispatcher.parse(text, f.source);
        return !parsed.getReader().canRead() && parsed.getExceptions().isEmpty() && parsed.getContext().getCommand() != null;
    }

    @Test
    void sourceGrammarAcceptsNativePredicatesAreasOfflineQueriesAndTradeBranches() {
        String old = GeneralCompatConfig.commandFinder;
        GeneralCompatConfig.commandFinder = "true";
        try {
            var f = fixture();
            for (String text : new String[]{"finder block oak_log[axis=x]", "finder block chest{CustomName:'test'} 512", "finder block stone from 1 -64 2 to 4 319 6", "finder item stone", "finder item * 0", "finder item stone from 1 2 3 to 4 5 6", "finder item stone from offline_player", "finder trade item stone 128", "finder trade enchanted_book mending 64", "finder stop"})
                assertTrue(parses(f, text), text);
            assertFalse(parses(f, "finder block stone 513"));
            assertFalse(parses(f, "finder item stone -1"));
        } finally {
            GeneralCompatConfig.commandFinder = old;
        }
    }

    @Test
    void wildcardMatchesActualItemsWithoutCountingEmptyNativeSlots() {
        String old = GeneralCompatConfig.commandFinder;
        GeneralCompatConfig.commandFinder = "true";
        try {
            var f = fixture();
            String text = "finder item * from offline_player";
            var context = f.dispatcher.parse(text, f.source).getContext().build(text);
            var predicate = OrgFinderCommands.itemPredicate(context);
            assertFalse(predicate.test(ItemStack.EMPTY));
            assertTrue(predicate.test(new ItemStack(Items.STONE, 7)));
        } finally {
            GeneralCompatConfig.commandFinder = old;
        }
    }

    @Test
    void disabledFinderAndHiddenFunctionSwitchesAreCheckedByTheNativeTree() {
        String old = GeneralCompatConfig.commandFinder;
        try (var hidden = mockStatic(OrgHiddenPlayerActions.class)) {
            GeneralCompatConfig.commandFinder = "true";
            var f = fixture();
            assertFalse(parses(f, "finder xp from offline_player"));
            assertFalse(parses(f, "finder worldEater 1 2 3 4 5 6"));
            hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(true);
            assertTrue(parses(f, "finder xp from offline_player"));
            assertTrue(parses(f, "finder worldEater 1 2 3 4 5 6"));
            GeneralCompatConfig.commandFinder = "false";
            assertFalse(parses(f, "finder stop"));
        } finally {
            GeneralCompatConfig.commandFinder = old;
        }
    }
}
