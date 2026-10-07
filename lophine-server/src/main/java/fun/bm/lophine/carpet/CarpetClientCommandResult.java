package fun.bm.lophine.carpet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Collect a client command's output until its actual Folia continuations finish.
 */
public final class CarpetClientCommandResult {
    private static final int MAX_LINES = 12;
    private static final int MAX_CODE_POINTS = 512;
    private final ArrayList<String> output = new ArrayList<>();
    private String error;
    private int value;

    private CarpetClientCommandResult() {
    }

    public static CompletableFuture<CompoundTag> execute(String id, Consumer<CarpetClientCommandResult> command) {
        var result = new CarpetClientCommandResult();
        CompletableFuture<Void> actual;
        try (var scope = CarpetAsyncCommandResults.open()) {
            try {
                command.accept(result);
            } catch (RuntimeException failure) {
                result.failed(failure);
            }
            actual = scope.completionFuture();
        }
        return actual.handle((ignored, failure) -> {
            if (failure != null) result.failed(failure);
            return result.snapshot(id);
        });
    }

    public synchronized void message(Component message) {
        if (output.size() < MAX_LINES) output.add(limit(message.getString()));
    }

    public synchronized void returned(boolean success, int value) {
        if (error == null) this.value = success ? value : 0;
    }

    private synchronized void failed(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null)
            failure = failure.getCause();
        error = limit(failure.getMessage() == null ? "Command failed" : failure.getMessage());
        value = 0;
    }

    private synchronized CompoundTag snapshot(String id) {
        var result = new CompoundTag();
        result.putString("id", id);
        result.putInt("return", value);
        if (error != null) result.putString("error", error);
        if (!output.isEmpty()) {
            var lines = new ListTag();
            for (String line : output) lines.add(StringTag.valueOf(line));
            result.put("output", lines);
        }
        return result;
    }

    private static String limit(String value) {
        return value.codePointCount(0, value.length()) <= MAX_CODE_POINTS ? value
                : value.substring(0, value.offsetByCodePoints(0, MAX_CODE_POINTS));
    }
}
