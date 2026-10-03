package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import java.lang.reflect.Field;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarpetPlayerCommandGrammarTest {
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private CommandSourceStack source;
    private Field permission;
    private Object previousPermission;

    @BeforeEach
    void registerNativePlayerTree() throws ReflectiveOperationException {
        permission = FakePlayerCompatConfig.class.getField("commandPlayer");
        previousPermission = permission.get(null);
        permission.set(null, permission.getType() == String.class ? "true" : true);
        source = mock(CommandSourceStack.class);
        when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);
        dispatcher = new CommandDispatcher<>();
        CarpetPlayerCommand.register(dispatcher, null);
    }

    @AfterEach
    void restorePermission() throws IllegalAccessException {
        permission.set(null, previousPermission);
    }

    @Test
    void parsesCarpetActionModesAndDropSelectors() {
        for (String action : new String[]{"use", "jump", "attack", "drop", "dropStack", "swapHands"}) {
            assertTrue(parses("player Steve " + action));
            assertTrue(parses("player Steve " + action + " once"));
            assertTrue(parses("player Steve " + action + " continuous"));
            assertTrue(parses("player Steve " + action + " interval 10"));
            assertFalse(parses("player Steve " + action + " interval 0"));
        }
        for (String slot : new String[]{"all", "mainhand", "offhand", "0", "40"}) {
            assertTrue(parses("player Steve drop " + slot));
            assertTrue(parses("player Steve dropStack " + slot));
        }
        assertFalse(parses("player Steve drop 41"));
        assertFalse(parses("player Steve hotbar 0"));
        assertTrue(parses("player Steve hotbar 9"));
    }

    @Test
    void parsesSpawnPlacementRotationAndDimensionChain() {
        assertTrue(parses("player Alex spawn"));
        assertTrue(parses("player Alex spawn in survival"));
        assertTrue(parses("player Alex spawn at 1 64 2 facing 90 0 in minecraft:overworld in creative"));
        assertTrue(parses("player Alex look at 1 64 2"));
        assertTrue(parses("player Alex turn left"));
        assertTrue(parses("player Alex mount anything"));
        assertTrue(parses("player Alex shadow"));
        assertFalse(parses("player Alex spawn at 1 64"));
    }

    @Test
    void orgExtensionDoesNotEnableNativePlayerActions() throws IllegalAccessException {
        permission.set(null, "false");
        boolean old = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerCommandCloseScreen;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerCommandCloseScreen = true;
            OrgPlayerInventoryMenus.register(dispatcher);
            assertTrue(parses("player Alex closeScreen"));
            assertFalse(parses("player Alex kill"));
            assertFalse(parses("player Alex attack continuous"));
            assertFalse(parses("player Alex spawn"));
        } finally { fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerCommandCloseScreen = old; }
    }

    private boolean parses(String command) {
        var parsed = dispatcher.parse(command, source);
        return !parsed.getReader().canRead() && parsed.getExceptions().isEmpty() && parsed.getContext().getCommand() != null;
    }
}
