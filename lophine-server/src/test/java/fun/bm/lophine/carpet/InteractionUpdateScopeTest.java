package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

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
