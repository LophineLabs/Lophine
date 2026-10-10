package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CarpetLoggerSubscriptionTest {
    @Test
    void changingDefaultsPreservesManualSubscriptions() {
        var previous = GeneralCompatConfig.defaultLoggers;
        String name = "logger-default-lifecycle-fixture";
        CarpetLoggerProtocol.subscribe(name, "counter", "red");
        try {
            GeneralCompatConfig.defaultLoggers = List.of("tps");
            CarpetLoggerProtocol.refreshConfiguredDefaults(false);
            assertEquals(Map.of("counter", "red"), CarpetLoggerProtocol.subscriptions(name));
            GeneralCompatConfig.defaultLoggers = List.of();
            CarpetLoggerProtocol.refreshConfiguredDefaults(false);
            assertEquals(Map.of("counter", "red"), CarpetLoggerProtocol.subscriptions(name));
        } finally {
            GeneralCompatConfig.defaultLoggers = previous;
            CarpetLoggerProtocol.refreshConfiguredDefaults(false);
            CarpetLoggerProtocol.unsubscribe(name, null);
        }
    }

    @Test
    void clearingOneLoggerPreservesTheOthersAndToggleUsesDefault() {
        String name = "logger-subscription-fixture";
        try {
            CarpetLoggerProtocol.subscribe(name, "counter", "red");
            CarpetLoggerProtocol.subscribe(name, "tps", null);
            CarpetLoggerProtocol.unsubscribe(name, "counter");
            assertEquals(Map.of("tps", ""), CarpetLoggerProtocol.subscriptions(name));
            assertTrue(CarpetLoggerProtocol.toggle(name, "counter"));
            assertEquals("white", CarpetLoggerProtocol.subscriptions(name).get("counter"));
            assertFalse(CarpetLoggerProtocol.toggle(name, "counter"));
            assertEquals(Map.of("tps", ""), CarpetLoggerProtocol.subscriptions(name));
        } finally {
            CarpetLoggerProtocol.unsubscribe(name, null);
        }
    }

    @Test
    void unknownLoggerDoesNotChangeSubscription() {
        String name = "logger-invalid-fixture";
        assertThrows(IllegalArgumentException.class, () -> CarpetLoggerProtocol.subscribe(name, "missing_logger", null));
        assertTrue(CarpetLoggerProtocol.subscriptions(name).isEmpty());
    }
}
