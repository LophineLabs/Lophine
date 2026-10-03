package fun.bm.lophine.carpet;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.earthme.luminol.config.ConfigsInstance;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.config.flags.NeedRun;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumLoadType;
import me.earthme.luminol.enums.EnumRunnableType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleRuleConfigTest {
    @TempDir
    Path directory;
    private ConfigsInstance config;
    private CommentedFileConfig file;
    private Map<String, Object> staged;
    private CountDownLatch saved;
    private boolean expectSave;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() throws ReflectiveOperationException {
        net.minecraft.SharedConstants.tryDetectVersion();
        Fixture.enabled = false;
        Fixture.restartOnly = false;
        Fixture.calls.clear();
        this.saved = new CountDownLatch(1);
        this.expectSave = false;
        this.config = new FixtureConfigs(this.directory);
        this.file = CommentedFileConfig.builder(this.directory.resolve("fixture.toml")).onSave(this.saved::countDown).build();
        this.file.set("carpet.fixture.enabled", false);
        this.file.set("carpet.fixture.restartOnly", false);
        this.file.set("carpet.other.pending", false);
        Field storage = ConfigsInstance.class.getDeclaredField("configFileInstance");
        storage.setAccessible(true);
        storage.set(this.config, this.file);
        Field instances = ConfigsInstance.class.getDeclaredField("allInstanced");
        instances.setAccessible(true);
        ((Map<Object, Set<Exception>>) instances.get(this.config)).put(new Fixture(), Set.of());
        Field pending = ConfigsInstance.class.getDeclaredField("stagedConfigMap");
        pending.setAccessible(true);
        this.staged = (Map<String, Object>) pending.get(this.config);
        this.staged.put("carpet.other.pending", true);
    }

    @AfterEach
    void close() throws InterruptedException {
        if (this.expectSave) this.saved.await(5, TimeUnit.SECONDS);
        if (this.file != null) this.file.close();
        Fixture.enabled = false;
        Fixture.restartOnly = false;
        Fixture.calls.clear();
    }

    @Test
    void temporaryChangePreservesTheFileAndOtherStagedValues() throws IllegalAccessException {
        this.staged.put("carpet.fixture.enabled", false);
        assertEquals(ConfigsInstance.SingleConfigResult.UPDATED, this.config.applySingleConfig("carpet.fixture.enabled", true, false));
        assertTrue(Fixture.enabled);
        assertEquals(Boolean.FALSE, this.file.get("carpet.fixture.enabled"));
        assertEquals(Map.of("carpet.other.pending", true), this.staged);
        assertEquals(List.of("unload", "before", "loaded"), Fixture.calls);
    }

    @Test
    void defaultChangeIsSavedWithoutApplyingUnrelatedStagedValues() throws IllegalAccessException, InterruptedException {
        this.staged.put("carpet.fixture.enabled", false);
        this.expectSave = true;
        this.config.applySingleConfig("carpet.fixture.enabled", true, true);
        assertTrue(this.saved.await(5, TimeUnit.SECONDS));
        try (CommentedFileConfig saved = CommentedFileConfig.of(this.directory.resolve("fixture.toml"))) {
            saved.load();
            assertEquals(Boolean.TRUE, saved.get("carpet.fixture.enabled"));
            assertEquals(Boolean.FALSE, saved.get("carpet.other.pending"));
        }
        assertEquals(Map.of("carpet.other.pending", true), this.staged);
    }

    @Test
    void restartOnlyValueIsSavedWithoutChangingRuntimeState() throws IllegalAccessException, InterruptedException {
        assertThrows(IllegalStateException.class, () -> this.config.applySingleConfig("carpet.fixture.restartOnly", true, false));
        this.expectSave = true;
        assertEquals(ConfigsInstance.SingleConfigResult.SAVED_FOR_RESTART, this.config.applySingleConfig("carpet.fixture.restartOnly", true, true));
        assertTrue(this.saved.await(5, TimeUnit.SECONDS));
        assertFalse(Fixture.restartOnly);
        assertEquals(Boolean.TRUE, this.file.get("carpet.fixture.restartOnly"));
        assertTrue(Fixture.calls.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void carpetReloadRestoresMissingDefaultsAndRunsBeforeLoadOnce() throws ReflectiveOperationException {
        Field name = ConfigsInstance.class.getDeclaredField("name");
        name.setAccessible(true);
        name.set(this.config, "lophine_carpet");
        this.config.alreadyInit = true;
        Field defaults = ConfigsInstance.class.getDeclaredField("defaultvalueMap");
        defaults.setAccessible(true);
        ((Map<String, Object>) defaults.get(this.config)).putAll(Map.of("carpet.fixture.enabled", false, "carpet.fixture.restartOnly", false));
        Fixture.enabled = true;
        Fixture.restartOnly = true;
        this.file.remove("carpet.fixture.enabled");
        this.file.remove("carpet.fixture.restartOnly");
        var load = ConfigsInstance.class.getDeclaredMethod("loadAllModules", boolean.class);
        load.setAccessible(true);
        load.invoke(this.config, true);
        assertFalse(Fixture.enabled);
        assertTrue(Fixture.restartOnly);
        assertEquals(Boolean.FALSE, this.file.get("carpet.fixture.enabled"));
        assertEquals(Boolean.FALSE, this.file.get("carpet.fixture.restartOnly"));
        assertEquals(List.of("before"), Fixture.calls);
        assertEquals(Map.of("carpet.other.pending", true), this.staged);
    }

    @Test
    @SuppressWarnings("unchecked")
    void prospectiveReadKeepsStagedChangesUntilTheRealFieldLoad() throws ReflectiveOperationException {
        Field defaults = ConfigsInstance.class.getDeclaredField("defaultvalueMap");
        defaults.setAccessible(true);
        ((Map<String, Object>) defaults.get(this.config)).put("carpet.fixture.enabled", false);
        this.staged.put("carpet.fixture.enabled", true);
        var peek = ConfigsInstance.class.getDeclaredMethod("peekCarpetConfigValue", String.class);
        peek.setAccessible(true);
        assertEquals(Boolean.TRUE, peek.invoke(this.config, "carpet.fixture.enabled"));
        assertEquals(Boolean.TRUE, this.staged.get("carpet.fixture.enabled"));
        this.staged.remove("carpet.fixture.enabled");
        this.file.remove("carpet.fixture.enabled");
        assertEquals(Boolean.FALSE, peek.invoke(this.config, "carpet.fixture.enabled"));
    }

    private static final class FixtureConfigs extends ConfigsInstance {
        FixtureConfigs(final Path directory) {
            super(SingleRuleConfigTest.class.getClassLoader(), directory.toFile(), "fixture", "fixture.toml", "fixture", "fixture");
        }
    }

    @ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "fixture", directory = {"carpet"})
    public static class Fixture {
        @ConfigInfo(name = "enabled")
        public static boolean enabled;
        @DoNotLoad(when = EnumLoadType.RELOAD)
        @ConfigInfo(name = "restartOnly")
        public static boolean restartOnly;
        static final List<String> calls = new ArrayList<>();

        @NeedRun(when = EnumRunnableType.ON_UNLOAD)
        public void unload() {
            calls.add("unload");
        }

        @NeedRun(when = EnumRunnableType.BEFORE_FINAL_LOAD)
        public void before() {
            calls.add("before");
        }

        @NeedRun(when = EnumRunnableType.ON_LOADED)
        public void loaded() {
            calls.add("loaded");
        }
    }
}
