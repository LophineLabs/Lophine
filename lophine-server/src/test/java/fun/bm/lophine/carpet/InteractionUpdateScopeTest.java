package fun.bm.lophine.carpet;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionUpdateScopeTest {
    @Test
    void nestedScopeRestoresParentAfterAnException() {
        assertFalse(InteractionUpdateHelper.shouldSkipUpdates());
        InteractionUpdateHelper.runWithSuppressedUpdates(() -> {
            assertTrue(InteractionUpdateHelper.shouldSkipUpdates());
            assertThrows(IllegalStateException.class, () -> InteractionUpdateHelper.runWithSuppressedUpdates(() -> {
                throw new IllegalStateException("fixture");
            }));
            assertTrue(InteractionUpdateHelper.shouldSkipUpdates());
        });
        assertFalse(InteractionUpdateHelper.shouldSkipUpdates());
    }

    @Test
    void concurrentRegionThreadsHaveIndependentScopes() {
        InteractionUpdateHelper.runWithSuppressedUpdates(() -> {
            CompletableFuture.runAsync(() -> {
                assertFalse(InteractionUpdateHelper.shouldSkipUpdates());
                InteractionUpdateHelper.runWithSuppressedUpdates(() -> assertTrue(InteractionUpdateHelper.shouldSkipUpdates()));
                assertFalse(InteractionUpdateHelper.shouldSkipUpdates());
            }).join();
            assertTrue(InteractionUpdateHelper.shouldSkipUpdates());
        });
        assertFalse(InteractionUpdateHelper.shouldSkipUpdates());
    }
}
