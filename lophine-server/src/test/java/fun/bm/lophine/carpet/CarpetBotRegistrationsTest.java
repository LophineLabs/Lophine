package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class CarpetBotRegistrationsTest {
    @Test
    void concurrentLoginsCannotPublishOneUuidUnderDifferentNamesOrOneNameUnderDifferentUuids() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int epoch = 0; epoch < 200; epoch++) {
                var registrations = new CarpetBotRegistrations<Object>();
                UUID first = UUID.randomUUID(), second = epoch % 2 == 0 ? first : UUID.randomUUID();
                String firstName = "Bot", secondName = epoch % 2 == 0 ? "Other" : "BOT";
                var ready = new CyclicBarrier(2);
                var a = executor.submit(() -> publish(registrations, first, firstName, ready));
                var b = executor.submit(() -> publish(registrations, second, secondName, ready));
                Object one = a.get(), two = b.get();
                assertTrue((one == null) != (two == null));
                assertEquals(1, registrations.byUuid().size());
                assertEquals(1, registrations.byName().size());
                assertSame(registrations.byUuid().values().iterator().next(), registrations.byName().values().iterator().next());
            }
        }
    }

    private static Object publish(CarpetBotRegistrations<Object> registrations, UUID uuid, String name, CyclicBarrier ready) throws Exception {
        ready.await();
        try (var reservation = registrations.reserve(uuid, name)) {
            Object actor = new Object();
            reservation.publish(actor);
            return actor;
        } catch (IllegalStateException occupied) {
            return null;
        }
    }

    @Test
    void failedCreationReleasesBothKeysAndLateRemovalCannotDeleteAReplacementWithEqualNetworkId() {
        var registrations = new CarpetBotRegistrations<Actor>();
        UUID uuid = UUID.randomUUID();
        try (var cancelled = registrations.reserve(uuid, "Bot")) {
        }
        Actor old = new Actor(7), replacement = new Actor(7);
        try (var first = registrations.reserve(uuid, "Bot")) {
            first.publish(old);
        }
        registrations.remove(uuid, "Bot", old);
        try (var second = registrations.reserve(uuid, "BOT")) {
            second.publish(replacement);
        }
        registrations.remove(uuid, "Bot", old);
        assertSame(replacement, registrations.byUuid().get(uuid));
        assertSame(replacement, registrations.byName().get("bot"));
    }

    private record Actor(int networkId) {
    }
}
