package fun.bm.lophine.carpet.configsections;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import me.earthme.luminol.config.ConfigsInstance;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CarpetConfigSectionsTest {
    @TempDir
    Path directory;

    @BeforeEach
    void defaults() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Rules.amsNetworkProtocol = false;
        Rules.microTiming = false;
        Rules.commandFinder = "true";
        Rules.commandScript = "true";
    }

    @Test
    void oldValuesAndCommentsMoveToSeparateTablesOnLoadAndRemainOnReload() throws Exception {
        Files.writeString(directory.resolve("sections.toml"), """
                [carpet.general]
                # Keep my protocol setting
                amsNetworkProtocol = true
                microTiming = true
                commandFinder = "ops"
                commandScript = "false"
                unrelated = 47
                """);
        var config = new Fixture(directory);
        try {
            config.preLoadConfig(true);
            assertTrue(Rules.amsNetworkProtocol);
            assertTrue(Rules.microTiming);
            assertEquals("ops", Rules.commandFinder);
            assertEquals("false", Rules.commandScript);
            String saved = Files.readString(directory.resolve("sections.toml"));
            assertTrue(saved.contains("[carpet.ams]"));
            assertTrue(saved.contains("[carpet.tis]"));
            assertTrue(saved.contains("[carpet.org]"));
            assertTrue(saved.contains("Keep my protocol setting"));
            try (var readback = CommentedFileConfig.of(directory.resolve("sections.toml"))) {
                readback.load();
                assertEquals(Boolean.TRUE, readback.get("carpet.ams.amsNetworkProtocol"));
                assertEquals(47, (Integer) readback.get("carpet.general.unrelated"));
                assertFalse(readback.contains("carpet.general.amsNetworkProtocol"));
                assertFalse(readback.contains("carpet.general.microTiming"));
                assertFalse(readback.contains("carpet.general.commandFinder"));
            }
            config.alreadyInit = true;
            config.preLoadConfig(true);
            assertTrue(Rules.amsNetworkProtocol);
            assertTrue(Rules.microTiming);
            assertEquals("ops", Rules.commandFinder);
        } finally {
            config.getFileInstance().close();
        }
    }

    @Test
    void newValuesTakePrecedenceWhenBothLocationsExist() throws Exception {
        Files.writeString(directory.resolve("sections.toml"), """
                [carpet.general]
                amsNetworkProtocol = true
                [carpet.ams]
                # New setting wins
                amsNetworkProtocol = false
                """);
        var config = new Fixture(directory);
        try {
            config.preLoadConfig(true);
            assertFalse(Rules.amsNetworkProtocol);
            assertFalse(config.getFileInstance().contains("carpet.general.amsNetworkProtocol"));
            assertEquals(" New setting wins", config.getFileInstance().getComment("carpet.ams.amsNetworkProtocol"));
        } finally {
            config.getFileInstance().close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeDefaultsAndStagedChangesUseTheSameNewPaths() throws Exception {
        var config = new Fixture(directory);
        try {
            config.preLoadConfig(true);
            assertEquals(ConfigsInstance.SingleConfigResult.UPDATED,
                    config.applySingleConfig("carpet.ams.amsNetworkProtocol", true, true));
            assertEquals(ConfigsInstance.SingleConfigResult.UNKNOWN_KEY,
                    config.applySingleConfig("carpet.general.amsNetworkProtocol", false, false));
            Field pending = ConfigsInstance.class.getDeclaredField("stagedConfigMap");
            pending.setAccessible(true);
            ((Map<String, Object>) pending.get(config)).put("carpet.tis.microTiming", true);
            config.reApplyStagedConfigs();
            assertTrue(Rules.microTiming);
            config.alreadyInit = true;
            config.getFileInstance().remove("carpet.ams.amsNetworkProtocol");
            config.getFileInstance().remove("carpet.tis.microTiming");
            config.saveConfigs();
            config.preLoadConfig(true);
            assertFalse(Rules.amsNetworkProtocol);
            assertFalse(Rules.microTiming);
            assertTrue(config.completeConfigPath("carpet.").contains("carpet.ams"));
        } finally {
            config.getFileInstance().close();
        }
    }

    @Test
    void migratedDefaultsIncludeFalseValuesFromFakeplayerAndHopperSections() throws Exception {
        RulesFromFakeplayer.fakePlayerDefaultSurvivalMode = true;
        RulesFromHoppers.hopperCountersUnlimitedSpeed = true;
        Files.writeString(directory.resolve("sections.toml"), """
                [carpet.fakeplayer]
                fakePlayerDefaultSurvivalMode = false
                [carpet.hopper_counter]
                hopperCountersUnlimitedSpeed = false
                """);
        var config = new Fixture(directory);
        try {
            config.preLoadConfig(true);
            assertFalse(RulesFromFakeplayer.fakePlayerDefaultSurvivalMode);
            assertFalse(RulesFromHoppers.hopperCountersUnlimitedSpeed);
            assertFalse(config.getFileInstance().contains("carpet.fakeplayer"));
            assertFalse(config.getFileInstance().contains("carpet.hopper_counter"));
            assertEquals(Boolean.FALSE, config.getFileInstance().get("carpet.ams.fakePlayerDefaultSurvivalMode"));
            assertEquals(Boolean.FALSE, config.getFileInstance().get("carpet.tis.hopperCountersUnlimitedSpeed"));
        } finally {
            config.getFileInstance().close();
            RulesFromFakeplayer.fakePlayerDefaultSurvivalMode = false;
            RulesFromHoppers.hopperCountersUnlimitedSpeed = false;
        }
    }

    private static final class Fixture extends ConfigsInstance {
        Fixture(Path directory) {
            super(CarpetConfigSectionsTest.class.getClassLoader(), directory.toFile(), "lophine_carpet",
                    "sections.toml", "sections", "fun.bm.lophine.carpet.configsections");
        }
    }

    @ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "general", directory = {"carpet"})
    public static class Rules {
        @ConfigInfo(name = "amsNetworkProtocol", section = "ams")
        public static boolean amsNetworkProtocol;
        @ConfigInfo(name = "microTiming", section = "tis")
        public static boolean microTiming;
        @ConfigInfo(name = "commandFinder", section = "org")
        public static String commandFinder = "true";
        @ConfigInfo(name = "commandScript")
        public static String commandScript = "true";
    }

    @ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "fakeplayer", directory = {"carpet"})
    public static class RulesFromFakeplayer {
        @ConfigInfo(name = "fakePlayerDefaultSurvivalMode", section = "ams")
        public static boolean fakePlayerDefaultSurvivalMode;
    }

    @ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "hopper_counter", directory = {"carpet"})
    public static class RulesFromHoppers {
        @ConfigInfo(name = "hopperCountersUnlimitedSpeed", section = "tis")
        public static boolean hopperCountersUnlimitedSpeed;
    }
}
