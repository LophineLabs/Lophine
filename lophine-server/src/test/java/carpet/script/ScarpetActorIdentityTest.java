package carpet.script;

import carpet.script.external.WeakIdentityMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ScarpetActorIdentityTest {
    /**
     * Exactly the Native Entity equality contract, including respawn/reused ids and setId().
     */
    private static final class Actor {
        int id;

        Actor(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Actor actor && actor.id == id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }

    @Test
    void permissionAndPendingStateRemainDistinctAcrossReusedAndChangedNetworkIds() {
        Actor retired = new Actor(7), respawn = new Actor(7);
        assertEquals(retired, respawn);
        WeakIdentityMap<Actor, String> states = new WeakIdentityMap<>();
        states.put(retired, "old-permissions");
        states.put(respawn, "new-permissions");
        assertEquals("old-permissions", states.get(retired));
        assertEquals("new-permissions", states.get(respawn));
        retired.id = 13;
        assertEquals("old-permissions", states.get(retired));
        assertFalse(states.remove(respawn, "old-permissions"));
        assertTrue(states.remove(retired, "old-permissions"));
        assertEquals("new-permissions", states.get(respawn));
    }
}
