package fun.bm.lophine.carpet;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsKeepWorldTickHeadTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void originalHeadResetsBeforeMicroTimingAndActualLightWaitFailure() {
        head(true, true);
    }

    @Test
    void disabledOriginalHeadLeavesResetUntouched() {
        head(false, false);
    }

    @Test
    void laterPhaseRuleToggleDoesNotChangeOriginalEntryDecision() {
        head(false, true);
    }

    void head(boolean enabled, boolean toggle) {
        boolean before = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.keepWorldTickUpdate;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.keepWorldTickUpdate = enabled;
            var level = mock(ServerLevel.class, CALLS_REAL_METHODS);
            var source = mock(ServerChunkCache.class);
            var light = mock(ThreadedLevelLightEngine.class);
            doReturn(source).when(level).getChunkSource();
            when(source.getLightEngine()).thenReturn(light);
            var order = new ArrayList<String>();
            doAnswer(call -> {
                order.add("reset");
                return null;
            }).when(level).resetEmptyTime();
            var failure = new IllegalStateException("actual native light failure");
            doAnswer(call -> {
                order.add("light");
                throw failure;
            }).when(light).carpetWaitForPendingTasks();
            var noop = TisMicroTiming.noop();
            try (var micro = mockStatic(TisMicroTiming.class)) {
                micro.when(() -> TisMicroTiming.phase(level, "world_border", null)).thenAnswer(call -> {
                    order.add("micro");
                    if (toggle) fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.keepWorldTickUpdate = !enabled;
                    return noop;
                });
                assertSame(failure, assertThrows(IllegalStateException.class, () -> level.tick(() -> true, null)));
                assertEquals(enabled ? List.of("reset", "micro", "light") : List.of("micro", "light"), order);
            }
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.keepWorldTickUpdate = before;
        }
    }
}