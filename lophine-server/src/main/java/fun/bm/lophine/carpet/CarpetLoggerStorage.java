// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;

/** TIS UUID subscriptions, including explicit empty subscriptions, with ordered atomic persistence. */
public final class CarpetLoggerStorage {
    private static final Path FILE = Path.of("config", "carpet-tis-addition", "logger_subscriptions.json");
    private static final Map<UUID, Map<String, String>> VALUES = new ConcurrentHashMap<>();
    private static final Map<String, UUID> NAMES = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("CarpetLoggerStorage").factory());
    private static final AtomicLong VERSION = new AtomicLong();
    private static final AtomicBoolean QUEUED = new AtomicBoolean();
    private static volatile boolean readable = true;
    private static volatile long saved;
    private CarpetLoggerStorage() {}
    public static synchronized void load() {
        VALUES.clear(); NAMES.clear(); readable = true;
        try { VALUES.putAll(readFile(FILE)); }
        catch (IOException failure) {
            readable = false;
            LoggerFactory.getLogger("CarpetLoggerStorage").error("Cannot read logger subscriptions; retaining original file", failure);
        }
    }
    public static Map<String, String> joined(String name, UUID id) { NAMES.put(name, id); return VALUES.get(id); }
    public static void record(String name, Map<String, String> subscriptions) {
        UUID id = NAMES.get(name);
        if (id == null) return;
        Map<String, String> value = Map.copyOf(subscriptions);
        if (value.equals(VALUES.put(id, value))) return;
        VERSION.incrementAndGet(); queueSave();
    }
    private static void queueSave() {
        if (!readable || !QUEUED.compareAndSet(false, true)) return;
        IO.execute(() -> {
            boolean success = true;
            try {
                while (saved < VERSION.get()) {
                    long revision = VERSION.get();
                    writeFile(FILE, Map.copyOf(VALUES));
                    saved = revision;
                }
            } catch (IOException failure) {
                success = false;
                LoggerFactory.getLogger("CarpetLoggerStorage").error("Cannot save logger subscriptions; retaining original file", failure);
            } finally {
                QUEUED.set(false);
                if (success && saved < VERSION.get()) queueSave();
            }
        });
    }
    public static void flushAtShutdown() {
        queueSave();
        try { IO.submit(() -> {}).get(5, TimeUnit.SECONDS); }
        catch (Exception failure) { LoggerFactory.getLogger("CarpetLoggerStorage").error("Logger subscription flush failed", failure); }
    }
    static Map<UUID, Map<String, String>> readFile(Path file) throws IOException {
        if (!Files.exists(file)) return Map.of();
        try {
            var root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) throw new IllegalArgumentException("Expected an object");
            Map<UUID, Map<String, String>> values = new HashMap<>();
            for (var player : root.getAsJsonObject().entrySet()) {
                UUID id = UUID.fromString(player.getKey());
                if (!player.getValue().isJsonObject()) throw new IllegalArgumentException("Expected logger object");
                Map<String, String> options = new HashMap<>();
                for (var logger : player.getValue().getAsJsonObject().entrySet()) {
                    var option = logger.getValue();
                    if (!option.isJsonNull() && (!option.isJsonPrimitive() || !option.getAsJsonPrimitive().isString())) throw new IllegalArgumentException("Expected logger string");
                    options.put(logger.getKey(), option.isJsonNull() ? "" : option.getAsString());
                }
                values.put(id, Map.copyOf(options));
            }
            return Map.copyOf(values);
        } catch (RuntimeException malformed) { throw new IOException("Invalid logger subscription data", malformed); }
    }
    static void writeFile(Path file, Map<UUID, Map<String, String>> values) throws IOException {
        JsonObject root = new JsonObject();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(player -> {
            JsonObject entry = new JsonObject();
            player.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(logger -> entry.addProperty(logger.getKey(), logger.getValue()));
            root.add(player.getKey().toString(), entry);
        });
        Path absolute = file.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), "logger-subscriptions-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
