package fun.bm.lophine.carpet;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetWorldPathConcurrencyTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test void concurrentPlayerDirectoryCacheMissesDoNotInvalidateAnotherPathsComputation() throws Exception {
        var source = new LevelStorageSource(directory, directory.resolve("backups"), new DirectoryValidator(path -> false), mock(com.mojang.datafixers.DataFixer.class));
        try (var access = source.createAccess("world"); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Path root = mock(Path.class);
            Path advancements = directory.resolve("world").resolve(LevelResource.PLAYER_ADVANCEMENTS_DIR.id());
            Path stats = directory.resolve("world").resolve(LevelResource.PLAYER_STATS_DIR.id());
            var firstEntered = new CountDownLatch(1); var secondEntered = new CountDownLatch(1); var firstCommitted = new CountDownLatch(1);
            when(root.resolve(LevelResource.PLAYER_ADVANCEMENTS_DIR.id())).thenAnswer(call -> {
                firstEntered.countDown();
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS), "Both actual cache computations must overlap");
                return advancements;
            });
            when(root.resolve(LevelResource.PLAYER_STATS_DIR.id())).thenAnswer(call -> {
                secondEntered.countDown();
                assertTrue(firstCommitted.await(5, TimeUnit.SECONDS), "The first key must commit while the second mapper is pending");
                return stats;
            });
            Field field = access.getClass().getDeclaredField("levelDirectory"); field.setAccessible(true);
            field.set(access, new LevelStorageSource.LevelDirectory(root));
            var first = workers.submit(() -> access.getLevelPath(LevelResource.PLAYER_ADVANCEMENTS_DIR));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var second = workers.submit(() -> access.getLevelPath(LevelResource.PLAYER_STATS_DIR));
            try {
                assertSame(advancements, first.get(5, TimeUnit.SECONDS));
                firstCommitted.countDown();
                assertSame(stats, second.get(5, TimeUnit.SECONDS));
                assertSame(advancements, access.getLevelPath(new LevelResource(LevelResource.PLAYER_ADVANCEMENTS_DIR.id())));
                assertSame(stats, access.getLevelPath(LevelResource.PLAYER_STATS_DIR));
                verify(root, times(1)).resolve(LevelResource.PLAYER_ADVANCEMENTS_DIR.id());
                verify(root, times(1)).resolve(LevelResource.PLAYER_STATS_DIR.id());
            } finally { secondEntered.countDown(); firstCommitted.countDown(); first.cancel(true); second.cancel(true); }
        }
    }
}
