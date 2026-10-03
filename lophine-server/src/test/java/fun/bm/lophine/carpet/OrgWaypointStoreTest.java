package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgWaypointStoreTest {
    @TempDir Path world;

    @Test void roundTripPreservesNativeOrgVersionThreeAndRejectsOverwrite() throws Exception {
        var store = new OrgWaypointStore(world);
        var original = new OrgWaypointStore.Waypoint("村民站", new BlockPos(-23, 64, 37), "minecraft:overworld", "Steve", "说明", new BlockPos(-3, 64, 4));
        store.save(original, false);
        assertEquals(original, store.load("村民站"));
        assertThrows(java.io.IOException.class, () -> store.save(original.comment("replacement"), false));
        assertEquals("说明", store.load("村民站").comment());
        store.save(original.comment("修改"), true);
        assertEquals("修改", store.load("村民站").comment());
        assertEquals(java.util.List.of("村民站"), store.names());
        assertTrue(store.remove("村民站"));
        assertFalse(store.remove("村民站"));
    }

    @Test void readsOriginalLegacyWaypointAndKeepsExistingFileUnmodified() throws Exception {
        Path data = world.resolve("config/carpet-org-addition/waypoint/old.json");
        Files.createDirectories(data.getParent());
        String json = "{\"x\":2,\"y\":63,\"z\":5,\"dimension\":\"minecraft:the_nether\",\"creator\":\"Alex\",\"illustrate\":\"legacy\",\"another_x\":16,\"another_y\":63,\"another_z\":40}";
        Files.writeString(data, json, StandardCharsets.UTF_8);
        var loaded = new OrgWaypointStore(world).load("old");
        assertEquals(new BlockPos(2, 63, 5), loaded.position());
        assertEquals(new BlockPos(16, 63, 40), loaded.another());
        assertEquals("legacy", loaded.comment());
        assertEquals(json, Files.readString(data, StandardCharsets.UTF_8));
    }

    @Test void fileOperationsCannotEscapeWaypointDirectory() throws Exception {
        var store = new OrgWaypointStore(world);
        for (String name : new String[] {"../outside", "..\\outside", "C:\\outside", "", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> store.remove(name));
            assertThrows(IllegalArgumentException.class, () -> store.load(name));
        }
        assertFalse(Files.exists(world.resolve("outside.json")));
    }

    @Test void concurrentCommandUpdatesKeepBothTheLatestPositionAndComment() throws Exception {
        var first = new OrgWaypointStore(world); var second = new OrgWaypointStore(world);
        first.save(new OrgWaypointStore.Waypoint("shared", BlockPos.ZERO, "minecraft:overworld", "Steve", "", null), false);
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var changingPosition = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { first.update("shared", previous -> {
                entered.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException problem) { throw new AssertionError(problem); }
                return previous.position(new BlockPos(-23, 73, 37));
            }); } catch (java.io.IOException problem) { throw new java.util.concurrent.CompletionException(problem); }
        });
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var changingComment = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { second.update("shared", previous -> previous.comment("preserved")); }
            catch (java.io.IOException problem) { throw new java.util.concurrent.CompletionException(problem); }
        });
        release.countDown(); java.util.concurrent.CompletableFuture.allOf(changingPosition, changingComment).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(new BlockPos(-23, 73, 37), first.load("shared").position());
        assertEquals("preserved", first.load("shared").comment());
    }
}
