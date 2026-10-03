package fun.bm.lophine.carpet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatible Org v0/v3 waypoint files under the world's existing Org config directory.
 */
public final class OrgWaypointStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path directory;
    private final Object lock;

    public record Waypoint(String name, BlockPos position, String dimension, String creator, String comment,
                           BlockPos another) {
        public Waypoint position(BlockPos value) {
            return new Waypoint(name, value, dimension, creator, comment, another);
        }

        public Waypoint comment(String value) {
            return new Waypoint(name, position, dimension, creator, value == null || value.isBlank() ? "" : value, another);
        }

        public Waypoint another(BlockPos value) {
            return new Waypoint(name, position, dimension, creator, comment, value);
        }
    }

    public OrgWaypointStore(Path worldRoot) {
        this.directory = OrgWorldFormat.directory(worldRoot).resolve("waypoint");
        this.lock = LOCKS.computeIfAbsent(this.directory, ignored -> new Object());
    }

    private Path path(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(character -> character < 32 || "<>:\\|?*/".indexOf(character) >= 0)) {
            throw new IllegalArgumentException("Invalid waypoint file name");
        }
        String filename = name.endsWith(".json") ? name : name + ".json";
        Path resolved = this.directory.resolve(filename).normalize();
        if (!resolved.getParent().equals(this.directory))
            throw new IllegalArgumentException("Waypoint path leaves its config directory");
        return resolved;
    }

    public List<String> names() throws IOException {
        synchronized (lock) {
            if (!Files.isDirectory(directory)) return List.of();
            try (var paths = Files.list(directory)) {
                return paths.filter(Files::isRegularFile).map(file -> file.getFileName().toString())
                        .filter(name -> name.endsWith(".json")).map(name -> name.substring(0, name.length() - 5)).sorted(java.util.Comparator.comparing(name -> name.toLowerCase(java.util.Locale.ROOT))).toList();
            }
        }
    }

    public Waypoint load(String name) throws IOException {
        synchronized (lock) {
            JsonObject data = JsonParser.parseString(Files.readString(path(name), StandardCharsets.UTF_8)).getAsJsonObject();
            boolean old = !data.has("pos");
            BlockPos pos = position(old ? data : data.getAsJsonObject("pos"), old ? "" : "");
            BlockPos another = old ? positionOptional(data, "another_") : data.has("another_pos") ? positionOptional(data.getAsJsonObject("another_pos"), "") : null;
            String dimension = data.has("dimension") ? data.get("dimension").getAsString() : "minecraft:overworld";
            String creator = data.has("creator") ? data.get("creator").getAsString() : "#none";
            String comment = data.has("comment") ? data.get("comment").getAsString() : data.has("illustrate") ? data.get("illustrate").getAsString() : "";
            return new Waypoint(name.endsWith(".json") ? name.substring(0, name.length() - 5) : name, pos, dimension, creator, comment, another);
        }
    }

    private static BlockPos position(JsonObject data, String prefix) {
        return new BlockPos(data.get(prefix + "x").getAsInt(), data.get(prefix + "y").getAsInt(), data.get(prefix + "z").getAsInt());
    }

    private static BlockPos positionOptional(JsonObject data, String prefix) {
        return data != null && data.has(prefix + "x") && data.has(prefix + "y") && data.has(prefix + "z") ? position(data, prefix) : null;
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject data = new JsonObject();
        if (pos != null) {
            data.addProperty("x", pos.getX());
            data.addProperty("y", pos.getY());
            data.addProperty("z", pos.getZ());
        }
        return data;
    }

    public void save(Waypoint waypoint, boolean replace) throws IOException {
        synchronized (lock) {
            Path target = path(waypoint.name);
            Files.createDirectories(directory);
            if (!replace && Files.exists(target)) throw new IOException("A waypoint with this name already exists");
            JsonObject data = new JsonObject();
            data.addProperty("data_version", 3);
            data.add("pos", position(waypoint.position));
            data.addProperty("dimension", waypoint.dimension);
            data.addProperty("creator", waypoint.creator);
            data.addProperty("comment", waypoint.comment);
            data.add("another_pos", position(waypoint.another));
            Path temporary = Files.createTempFile(directory, "waypoint-", ".tmp");
            try {
                Files.writeString(temporary, JSON.toJson(data), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public boolean remove(String name) throws IOException {
        synchronized (lock) {
            return Files.deleteIfExists(path(name));
        }
    }

    /**
     * Keep a command's read and replacement under the same world-directory lock.
     */
    public void update(String name, java.util.function.UnaryOperator<Waypoint> edit) throws IOException {
        synchronized (lock) {
            save(edit.apply(load(name)), true);
        }
    }
}
