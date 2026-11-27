// SPDX-License-Identifier: LGPL-3.0-or-later
// Location tag storage and front-side sign recognition adapted from Carpet AMS Addition 66b8c3b0.
package fun.bm.lophine.carpet.ams;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class AmsLocationTags {
    private static final Logger LOGGER = LoggerFactory.getLogger(AmsLocationTags.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Recognition DEFAULT = new Recognition("amsloc", "minecraft:oak_sign");
    private static volatile State active;

    private record Key(String dimension, int x, int z) {
        Key(ServerLevel world, int x, int z) {
            this(world.dimension().identifier().toString(), x, z);
        }
    }

    record Recognition(String marker, String sign) {}
    record Entry(int id, String name, String dimension, int x, int y, int z) {}
    private record Data(int version, String marker, String sign, int nextId, List<Entry> entries) {}
    private record Scan(ServerLevel level, int x, int z) {}

    private static final class State {
        final MinecraftServer server;
        final Object io = new Object();
        final Map<Key, Map<BlockPos, String>> chunks = new HashMap<>();
        volatile Recognition recognition = DEFAULT;
        List<Entry> listed;
        boolean available = true;
        boolean saving;
        long version;
        long saved;

        State(MinecraftServer server) {
            this.server = server;
        }

        synchronized List<Entry> entries() {
            if (listed != null) return listed;
            var all = new ArrayList<Entry>();
            chunks.forEach((key, tags) -> tags.forEach((pos, name) ->
                    all.add(new Entry(0, name, key.dimension(), pos.getX(), pos.getY(), pos.getZ()))));
            all.sort(Comparator.comparing(Entry::name).thenComparing(Entry::dimension)
                    .thenComparingInt(Entry::x).thenComparingInt(Entry::y).thenComparingInt(Entry::z));
            for (int index = 0; index < all.size(); ++index) {
                var old = all.get(index);
                all.set(index, new Entry(index + 1, old.name(), old.dimension(), old.x(), old.y(), old.z()));
            }
            return listed = List.copyOf(all);
        }

        synchronized void replace(Key key, Recognition expected, Map<BlockPos, String> tags) {
            if (recognition != expected || !enabled()) return;
            var previous = tags.isEmpty() ? chunks.remove(key) : chunks.put(key, tags);
            if (tags.isEmpty() ? previous != null : !tags.equals(previous)) markDirty();
        }

        synchronized void change(Key key, Recognition expected, BlockPos pos, String name) {
            if (recognition != expected || !enabled()) return;
            var tags = chunks.get(key);
            if (name == null) {
                if (tags == null || tags.remove(pos) == null) return;
                if (tags.isEmpty()) chunks.remove(key);
            } else {
                if (tags == null) {
                    tags = new HashMap<>();
                    chunks.put(key, tags);
                }
                if (name.equals(tags.put(pos.immutable(), name))) return;
            }
            markDirty();
        }

        private void markDirty() {
            listed = null;
            version++;
            if (saving) return;
            saving = true;
            CompletableFuture.runAsync(this::flush, CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS));
        }

        void flush() {
            synchronized (io) {
                Recognition settings;
                List<Entry> entries;
                long revision;
                synchronized (this) {
                    saving = false;
                    if (!available || saved == version) return;
                    settings = recognition;
                    entries = entries();
                    revision = version;
                }
                try {
                    write(settings, entries);
                    synchronized (this) {
                        saved = revision;
                    }
                } catch (IOException failure) {
                    LOGGER.error("Cannot save AMS location tags", failure);
                }
            }
        }

        void configure(String marker, String sign) throws IOException {
            if (!available) throw new IOException("Location tag storage is unavailable");
            synchronized (io) {
                var current = recognition;
                var updated = checkedRecognition(marker == null ? current.marker() : marker, sign == null ? current.sign() : sign);
                write(updated, List.of());
                synchronized (this) {
                    chunks.clear();
                    listed = List.of();
                    saved = ++version;
                    recognition = updated;
                }
            }
        }

        private void write(Recognition settings, List<Entry> entries) throws IOException {
            var path = AmsManagementSettings.file(server, "location_tags");
            Files.createDirectories(path.getParent());
            var temporary = Files.createTempFile(path.getParent(), "location_tags-", ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(new Data(1, settings.marker(), settings.sign(), entries.size() + 1, entries)));
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException failure) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private AmsLocationTags() {}

    static void load(MinecraftServer server) {
        var state = new State(server);
        var path = AmsManagementSettings.file(server, "location_tags");
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                var data = GSON.fromJson(reader, Data.class);
                if (data.version() != 1) throw new IllegalArgumentException("Invalid location tag version");
                state.recognition = checkedRecognition(data.marker(), data.sign());
                var ids = new java.util.HashSet<Integer>();
                for (Entry entry : data.entries()) {
                    if (entry.id() < 1 || !ids.add(entry.id()) || entry.name().isBlank() || entry.dimension().isBlank())
                        throw new IllegalArgumentException("Invalid or duplicate location tag");
                    var key = new Key(entry.dimension(), entry.x() >> 4, entry.z() >> 4);
                    var previous = state.chunks.computeIfAbsent(key, ignored -> new HashMap<>())
                            .put(new BlockPos(entry.x(), entry.y(), entry.z()), entry.name());
                    if (previous != null) throw new IllegalArgumentException("Duplicate location tag position");
                }
            } catch (IOException | RuntimeException failure) {
                state.available = false;
                state.chunks.clear();
                state.recognition = DEFAULT;
                LOGGER.error("Cannot read AMS location tags from {}", path, failure);
            }
        }
        active = state;
    }

    public static void flush(MinecraftServer server) {
        State state = active;
        if (state != null && state.server == server) state.flush();
    }

    static boolean enabled() {
        return !"false".equals(GeneralCompatConfig.commandLocationTag);
    }

    static List<String> signTypes() {
        return BuiltInRegistries.BLOCK.listElements().filter(holder -> holder.value() instanceof SignBlock)
                .map(holder -> holder.unwrapKey().orElseThrow().identifier().toString()).sorted().toList();
    }

    private static Recognition checkedRecognition(String marker, String sign) {
        if (marker == null || marker.isBlank() || marker.indexOf('\n') >= 0 || marker.indexOf('\r') >= 0)
            throw new IllegalArgumentException("invalid_marker");
        String id = sign.strip();
        if (!id.contains(":")) id = "minecraft:" + id;
        if (!signTypes().contains(id)) throw new IllegalArgumentException("invalid_sign");
        return new Recognition(marker.strip(), id);
    }

    static Recognition recognition() {
        State state = active;
        return state == null ? DEFAULT : state.recognition;
    }

    static List<Entry> entries() {
        State state = active;
        return state == null ? List.of() : state.entries();
    }

    static void configure(MinecraftServer server, String marker, String sign) throws IOException {
        State state = active;
        if (state == null || state.server != server) throw new IOException("Location tag storage is unavailable");
        state.configure(marker, sign);
    }

    private static String name(BlockEntity entity, Recognition recognition) {
        if (!(entity instanceof SignBlockEntity sign) || sign.isRemoved()
                || !recognition.sign().equals(BuiltInRegistries.BLOCK.getKey(sign.getBlockState().getBlock()).toString())) return null;
        var lines = sign.getText(SignTextSlot.FRONT).getMessages(false);
        if (!lines.getFirst().getString().strip().equals(recognition.marker())) return null;
        return tagName(lines.get(1).getString(), lines.get(2).getString(), lines.get(3).getString());
    }

    static String tagName(String first, String second, String third) {
        String result = first.strip() + second.strip() + third.strip();
        return result.isEmpty() ? null : result;
    }

    public static void changed(ServerLevel world, BlockPos pos, BlockEntity sign) {
        State state = active;
        if (state == null || state.server != world.getServer() || !state.available || !enabled()) return;
        var settings = state.recognition;
        state.change(new Key(world, pos.getX() >> 4, pos.getZ() >> 4), settings, pos, name(sign, settings));
    }

    public static void scan(ServerLevel world, LevelChunk chunk) {
        State state = active;
        if (state == null || state.server != world.getServer() || !state.available || !enabled()) return;
        var settings = state.recognition;
        var tags = new HashMap<BlockPos, String>();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            if (!(chunk.getBlockState(pos).getBlock() instanceof SignBlock)) continue;
            String name = name(chunk.getBlockEntity(pos), settings);
            if (name != null) tags.put(pos, name);
        }
        state.replace(new Key(world, chunk.getPos().x(), chunk.getPos().z()), settings, tags);
    }

    public static CompletableFuture<Integer> rescan(MinecraftServer server) {
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(server, () -> {
            var chunks = new ArrayList<Scan>();
            for (var level : server.getAllLevels()) {
                for (var holder : level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolders()) {
                    if (level.getChunkSource().getChunkAtIfLoadedImmediately(holder.chunkX, holder.chunkZ) != null)
                        chunks.add(new Scan(level, holder.chunkX, holder.chunkZ));
                }
            }
            return chunks;
        }), chunks -> {
            var work = AmsNativeCommandEffects.sequence(server, chunks.iterator(), target ->
                    AmsNativeCommandEffects.world(target.level(), new BlockPos(target.x() << 4, 0, target.z() << 4), () -> {
                        var chunk = target.level().getChunkSource().getChunkNow(target.x(), target.z());
                        if (chunk != null) scan(target.level(), chunk);
                        return (Void) null;
                    }));
            return work.thenApply(ignored -> chunks.size());
        });
    }
}
