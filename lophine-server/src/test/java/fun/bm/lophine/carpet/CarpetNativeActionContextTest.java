package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CarpetNativeActionContextTest {
    @Test void asynchronousActorRelayRestoresWholeJobRootAndRetainsDynamicNativeChildren(){
        var playerIdentity=new Object();var root=new AtomicReference<ScarpetNativeWork.Token>();var input=new CompletableFuture<Integer>();
        var child=new CompletableFuture<Void>();var ownerCommit=new CompletableFuture<Void>();
        var observed=ScarpetNativeWork.observeNative(null,()->CarpetNativeActionContext.with(playerIdentity,()->{
            root.set(ScarpetNativeWork.capture());ScarpetNativeWork.record(ownerCommit);
            var relay=CarpetNativeActionContext.relay(playerIdentity,root.get(),input);ScarpetNativeWork.record(relay);
            relay.thenAccept(value->{assertSame(playerIdentity,CarpetNativeActionContext.current());assertSame(root.get(),ScarpetNativeWork.capture());ScarpetNativeWork.record(child);});return null;
        }));
        input.complete(42);assertFalse(observed.isDone());ownerCommit.complete(null);assertFalse(observed.isDone());child.complete(null);assertTrue(observed.isDone());
        assertNull(CarpetNativeActionContext.current());assertNull(ScarpetNativeWork.capture());
    }
    @Test void snapshotContextClearsAcceptedIdentityAndTokenThenRestoresBoth(){
        var identity=new Object();var oldTail=new CompletableFuture<Void>();var isolatedTail=new CompletableFuture<Void>();var isolated=new AtomicReference<CompletableFuture<Void>>();
        var outer=ScarpetNativeWork.observeNative(null,()->CarpetNativeActionContext.with(identity,()->{
            var root=ScarpetNativeWork.capture();ScarpetNativeWork.record(oldTail);
            isolated.set(CarpetNativeActionContext.inNative(null,()->CarpetNativeActionContext.with(null,()->{
                assertNull(CarpetNativeActionContext.current());assertNull(ScarpetNativeWork.capture());
                return ScarpetNativeWork.<Void>observeNative(null,()->{ScarpetNativeWork.record(isolatedTail);return null;});
            })));
            assertSame(identity,CarpetNativeActionContext.current());assertSame(root,ScarpetNativeWork.capture());return null;
        }));
        oldTail.complete(null);assertTrue(outer.isDone());assertFalse(isolated.get().isDone());isolatedTail.complete(null);assertTrue(isolated.get().isDone());
    }
}
