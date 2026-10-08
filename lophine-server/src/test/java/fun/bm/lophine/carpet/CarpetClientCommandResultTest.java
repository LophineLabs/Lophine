package fun.bm.lophine.carpet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CarpetClientCommandResultTest {
    @Test
    void waitsForCrossRegionOutputAndActualCommandResult() throws Exception {
        var pending = new CompletableFuture<Integer>();
        var sink = new AtomicReference<CarpetClientCommandResult>();
        var response = CarpetClientCommandResult.execute("request", output -> {
            sink.set(output);
            CarpetAsyncCommandResults.trackContext(pending);
        });
        assertFalse(response.isDone());
        var owner = new Thread(() -> {
            sink.get().message(Component.literal("Spawn complete"));
            sink.get().returned(true, 37);
            pending.complete(37);
        });
        owner.start();
        owner.join();
        CompoundTag result = response.join();
        assertEquals("request", result.getString("id").orElseThrow());
        assertEquals(37, result.getInt("return").orElseThrow());
        assertEquals("Spawn complete", result.getListOrEmpty("output").getString(0).orElseThrow());
    }

    @Test
    void failureWaitsForAcceptedChildrenAndReturnsActualError() {
        var child = new CompletableFuture<Integer>();
        var sink = new AtomicReference<CarpetClientCommandResult>();
        var response = CarpetClientCommandResult.execute("failed", output -> {
            sink.set(output);
            CarpetAsyncCommandResults.trackContext(child);
            throw new IllegalStateException("Command failed after acceptance");
        });
        assertFalse(response.isDone());
        sink.get().returned(true, 12);
        child.complete(0);
        assertEquals("Command failed after acceptance", response.join().getString("error").orElseThrow());
        assertEquals(0, response.join().getInt("return").orElseThrow());
        var deferred = CarpetClientCommandResult.execute("deferred", output ->
                CarpetAsyncCommandResults.trackContext(CompletableFuture.failedFuture(new IllegalStateException("Owner retired"))));
        assertEquals("Owner retired", deferred.join().getString("error").orElseThrow());
        assertEquals(0, deferred.join().getInt("return").orElseThrow());
    }

    @Test
    void concurrentOutputIsBoundedAndPreservesUnicodeCodePoints() throws Exception {
        String line = "\uD83D\uDE00".repeat(600);
        var result = CarpetClientCommandResult.execute("bounded", output -> {
            Thread[] owners = new Thread[4];
            for (int i = 0; i < owners.length; i++) {
                owners[i] = new Thread(() -> {
                    for (int count = 0; count < 20; count++) output.message(Component.literal(line));
                });
                owners[i].start();
            }
            for (Thread owner : owners) {
                try {
                    owner.join();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
        }).join();
        var lines = result.getListOrEmpty("output");
        assertEquals(12, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.getString(i).orElseThrow();
            assertEquals(512, text.codePointCount(0, text.length()));
            assertEquals(line.substring(0, 1024), text);
        }
    }
}
