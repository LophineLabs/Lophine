package fun.bm.lophine.carpet;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class CarpetBlockStateParserTest {
    private boolean previous;

    @BeforeAll
    static void initializeRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void rememberRule() {
        this.previous = GeneralCompatConfig.failSoftBlockStateParsing;
        GeneralCompatConfig.failSoftBlockStateParsing = true;
    }

    @AfterEach
    void restoreRule() {
        GeneralCompatConfig.failSoftBlockStateParsing = this.previous;
    }

    @Test
    void skipsUnknownAndInvalidPropertiesWhileKeepingValidOnes() throws CommandSyntaxException {
        var result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "oak_log[unknown=hello,axis=bad,axis=x]", false);
        assertTrue(result.blockState().is(Blocks.OAK_LOG));
        assertEquals(net.minecraft.core.Direction.Axis.X, result.blockState().getValue(BlockStateProperties.AXIS));
    }

    @Test
    void structuralSyntaxAndDuplicateValidPropertiesStillFail() {
        assertThrows(CommandSyntaxException.class, () -> BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "oak_log[unknown]", false));
        assertThrows(CommandSyntaxException.class, () -> BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "oak_log[unknown=a", false));
        assertThrows(CommandSyntaxException.class, () -> BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "oak_log[axis=x,axis=y]", false));
    }

    @Test
    void disablingTheRuleRestoresVanillaPropertyErrors() {
        GeneralCompatConfig.failSoftBlockStateParsing = false;
        assertThrows(CommandSyntaxException.class, () -> BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "oak_log[unknown=a]", false));
        assertThrows(CommandSyntaxException.class, () -> BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "oak_log[axis=bad]", false));
    }
}
