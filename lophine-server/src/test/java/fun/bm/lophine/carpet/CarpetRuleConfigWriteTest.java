package fun.bm.lophine.carpet;

import me.earthme.luminol.config.ConfigsInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CarpetRuleConfigWriteTest {
    @TempDir
    Path directory;

    static final class Fixture extends ConfigsInstance {
        Fixture(Path directory) {
            super(CarpetRuleConfigWriteTest.class.getClassLoader(), directory.toFile(), "lophine_carpet", "candidate.toml", "candidate", "carpet_rule_test_empty_package");
        }
    }

    @Test
    void actualCarpetConfigSaveHasWrittenTheRealFileBeforeReturning() throws Exception {
        var config = new Fixture(directory);
        config.preLoadConfig(false);
        try {
            config.getFileInstance().set("carpet.test.value", 73);
            config.saveConfigs();
            assertTrue(Files.readString(directory.resolve("candidate.toml")).contains("73"));
        } finally {
            config.getFileInstance().close();
        }
    }

    @Test
    void actualCarpetConfigWriteFailureIsReturnedToTheCommandPhase() throws Exception {
        var config = new Fixture(directory);
        config.preLoadConfig(false);
        try {
            Path file = directory.resolve("candidate.toml");
            Files.delete(file);
            Files.createDirectory(file);
            config.getFileInstance().set("carpet.test.value", 73);
            assertThrows(RuntimeException.class, config::saveConfigs);
        } finally {
            config.getFileInstance().close();
        }
    }
}
