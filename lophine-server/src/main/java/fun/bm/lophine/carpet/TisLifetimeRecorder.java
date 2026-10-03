// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TisLifetimeRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("TISCM-LifetimeRecorder");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final Path BASE = Path.of("config", "carpettisaddition", "lifetime");
    private static final Path CONFIG = BASE.resolve("recorder_config.json");
    private static volatile Config config = load();
    private static volatile Writer writer;
    private static volatile String state = "stopped";

    private TisLifetimeRecorder() { }

    static boolean hasPermission(CommandSourceStack source) {
        var player = source.getPlayer();
        if (config.consoleOrSinglePlayerOwnerOnly && !(source.source instanceof net.minecraft.server.MinecraftServer
            || player != null && source.getServer().isSingleplayerOwner(player.nameAndId()))) return false;
        return CarpetCommandPermissions.canUse(source, Integer.toString(config.requiredPermissionLevel));
    }

    static int status(CommandSourceStack source) {
        Writer active = writer;
        TisRaycastCommand.feedback(source, "Lifetime recorder: " + (config.enabled ? "enabled" : "disabled") + ", " + state);
        if (hasPermission(source)) {
            TisRaycastCommand.feedback(source, "Config: " + CONFIG.toAbsolutePath() + "; output: " + config.outputDirectory
                + "; sampleRate=" + config.sampleRate + "; maxRecords=" + config.maxOutputRecordCount
                + "; maxFileBytes=" + config.maxOutputFileBytes);
            if (active != null) TisRaycastCommand.feedback(source, "File: " + active.path.toAbsolutePath() + "; records="
                + active.records.get() + "; bytes=" + active.bytes.get() + "; queued=" + active.queue.size()
                + "; dropped=" + active.dropped.get());
        }
        return 1;
    }

    static synchronized int reload(CommandSourceStack source) {
        try {
            Config next = Files.exists(CONFIG) ? GSON.fromJson(Files.readString(CONFIG, StandardCharsets.UTF_8), Config.class) : new Config();
            validate(next);
            config = next;
            save();
            stop(source);
            int id = TisLifetimeTracker.activeTrackId();
            if (id >= 0) start(source, id);
            TisRaycastCommand.feedback(source, "Lifetime recorder configuration reloaded");
            return 1;
        } catch (Exception failure) {
            TisRaycastCommand.feedback(source, "Could not reload lifetime recorder: " + failure.getMessage());
            return 0;
        }
    }

    static synchronized int enabled(CommandSourceStack source, boolean enabled) {
        if (config.enabled == enabled) { TisRaycastCommand.feedback(source, "Recorder is already " + (enabled ? "enabled" : "disabled")); return 0; }
        config.enabled = enabled;
        save();
        if (enabled && TisLifetimeTracker.activeTrackId() >= 0) start(source, TisLifetimeTracker.activeTrackId());
        else stop(source);
        return 1;
    }

    static synchronized void start(CommandSourceStack source, int id) {
        stop(source);
        Config settings = config;
        if (!settings.enabled) return;
        try {
            Path folder = Path.of(settings.outputDirectory);
            Files.createDirectories(folder);
            try (var files = Files.list(folder)) {
                List<Path> outputs = files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jsonl")).toList();
                long totalBytes = 0;
                for (Path path : outputs) totalBytes += Files.size(path);
                if (settings.maxTotalOutputFileCount > 0 && outputs.size() >= settings.maxTotalOutputFileCount
                    || settings.maxTotalOutputFileBytes > 0 && totalBytes >= settings.maxTotalOutputFileBytes) {
                    TisRaycastCommand.feedback(source, "Lifetime recorder total file limit reached");
                    return;
                }
            }
            String filename = "rec_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_" + id + ".jsonl";
            Writer next = new Writer(folder.resolve(filename), settings.maxOutputRecordCount, settings.maxOutputFileBytes);
            writer = next;
            state = "running";
            next.thread.start();
            TisRaycastCommand.feedback(source, "Lifetime recording started: " + filename);
        } catch (IOException failure) {
            TisRaycastCommand.feedback(source, "Could not start lifetime recorder: " + failure.getMessage());
            LOGGER.error("Could not start lifetime recorder", failure);
        }
    }

    static synchronized void stop(CommandSourceStack source) {
        Writer current = writer;
        writer = null;
        state = "stopped";
        if (current == null) return;
        current.working.set(false);
        current.thread.interrupt();
        try { current.thread.join(5000L); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (source != null) TisRaycastCommand.feedback(source, "Lifetime recording stopped: " + current.records.get()
            + " records, " + current.bytes.get() + " bytes");
    }

    static void add(String json) {
        Writer current = writer;
        Config settings = config;
        if (current == null || !current.working.get() || !settings.enabled) return;
        // Preserve the pinned implementation's comparison when sampleRate is below one.
        if (settings.sampleRate < 1.0 && ThreadLocalRandom.current().nextFloat() < settings.sampleRate) return;
        if (!current.queue.offer(json)) current.dropped.incrementAndGet();
    }

    static boolean isRecording() {
        Writer current = writer;
        return config.enabled && current != null && current.working.get();
    }

    private static Config load() {
        Config settings = new Config();
        try {
            if (Files.isRegularFile(CONFIG)) settings = GSON.fromJson(Files.readString(CONFIG, StandardCharsets.UTF_8), Config.class);
            validate(settings);
        } catch (Exception failure) {
            LOGGER.error("Could not read lifetime recorder configuration", failure);
            settings = new Config();
        }
        try {
            Files.createDirectories(BASE);
            Files.writeString(CONFIG, GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException failure) { LOGGER.error("Could not save lifetime recorder configuration", failure); }
        return settings;
    }

    private static void validate(Config settings) {
        if (settings == null || settings.outputDirectory == null || settings.outputDirectory.isBlank()
            || settings.requiredPermissionLevel < 0 || settings.requiredPermissionLevel > 4
            || !Double.isFinite(settings.sampleRate) || settings.sampleRate < 0 || settings.sampleRate > 1) {
            throw new IllegalArgumentException("Invalid lifetime recorder configuration");
        }
    }

    private static void save() {
        try { Files.createDirectories(BASE); Files.writeString(CONFIG, GSON.toJson(config), StandardCharsets.UTF_8); }
        catch (IOException failure) { LOGGER.error("Could not save lifetime recorder configuration", failure); }
    }

    private static final class Config {
        public boolean enabled = false;
        public int requiredPermissionLevel = 4;
        public boolean consoleOrSinglePlayerOwnerOnly = true;
        public String outputDirectory = BASE.resolve("records").toString();
        public long maxOutputRecordCount = -1;
        public long maxOutputFileBytes = 100 * 1024 * 1024;
        public long maxTotalOutputFileCount = 500;
        public long maxTotalOutputFileBytes = 1024 * 1024 * 1024;
        public double sampleRate = 1;
    }

    private static final class Writer {
        final Path path;
        final long maxRecords;
        final long maxBytes;
        final AtomicLong records = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        final AtomicBoolean working = new AtomicBoolean(true);
        final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(10000);
        final Thread thread;

        Writer(Path path, long maxRecords, long maxBytes) {
            this.path = path;
            this.maxRecords = maxRecords;
            this.maxBytes = maxBytes;
            this.thread = Thread.ofVirtual().name("tiscm-lifetime-record-writer").unstarted(this::write);
        }

        void write() {
            byte[] newline = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
            try (var output = new BufferedOutputStream(Files.newOutputStream(path))) {
                while (working.get()) {
                    String json = queue.take();
                    byte[] line = json.getBytes(StandardCharsets.UTF_8);
                    output.write(line);
                    output.write(newline);
                    output.flush();
                    long count = records.incrementAndGet();
                    long size = bytes.addAndGet(line.length + newline.length);
                    if (maxRecords > 0 && count >= maxRecords || maxBytes > 0 && size >= maxBytes) break;
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException failure) { LOGGER.error("Could not write lifetime records to {}", path, failure); }
            finally {
                working.set(false);
                queue.clear();
                if (writer == this) state = "paused";
            }
        }
    }
}
