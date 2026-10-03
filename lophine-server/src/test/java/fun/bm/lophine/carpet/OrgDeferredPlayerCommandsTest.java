package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

public class OrgDeferredPlayerCommandsTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }
    @Test void callbackCommandReturnsBeforeItsActionThenSnapshotsOnlyTheTrueFinalState() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            var player=fixture.viewer.player();fixture.owner.set(player);
            var actual=new CompletableFuture<Void>();var value=new AtomicInteger();
            var saved=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Integer>>();
            var nativeWork=ScarpetNativeWork.<Void>observeNative(player,()->{
                ScarpetNativeWork.record(actual);
                try(var accepted=ScarpetPlayerInventoryGate.acceptedScope(player)){
                    assertTrue(OrgDeferredPlayerCommands.deferIfCausal(player,()->{
                        assertNull(ScarpetNativeWork.capture());
                        saved.set(ScarpetPlayerInventoryGate.whenIdle(player,value::get));
                    },failure->fail(failure)));
                }
                return null;
            });
            ScarpetPlayerInventoryGate.trackAccepted(player,nativeWork);
            fixture.drain(fixture.viewer);assertNotNull(saved.get());assertFalse(saved.get().isDone());
            value.set(9);actual.complete(null);fixture.drain(fixture.viewer);assertEquals(9,saved.get().join());nativeWork.join();
        }
    }
}
