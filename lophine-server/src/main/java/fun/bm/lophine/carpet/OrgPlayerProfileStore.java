package fun.bm.lophine.carpet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.UnaryOperator;

/** World-local upstream-compatible player_data profiles; every mutation reloads, atomically saves and verifies. */
public final class OrgPlayerProfileStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    public OrgPlayerProfileStore(Path worldRoot) { directory = OrgWorldFormat.directory(worldRoot).resolve("player_data"); }
    private Path path(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.chars().anyMatch(character -> character < 32 || "<>:\\|?*/".indexOf(character) >= 0)) throw new IllegalArgumentException("Invalid fake-player profile name");
        Path path = directory.resolve(name + ".json").normalize(); if (!directory.equals(path.getParent())) throw new IllegalArgumentException("Profile path leaves its world directory"); return path;
    }
    public synchronized List<String> names() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.list(directory)) { return paths.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".json")).map(name -> name.substring(0, name.length() - 5)).sorted().toList(); }
    }
    public synchronized JsonObject load(String name) throws IOException {
        JsonObject profile;
        try { profile = OrgPlayerProfileUpdater.update(JsonParser.parseString(Files.readString(path(name), StandardCharsets.UTF_8)).getAsJsonObject()); validate(profile); }
        catch (RuntimeException failure) { throw new IOException("Invalid fake-player profile " + name, failure); }
        return profile;
    }
    static void validate(JsonObject profile) {
        var pos = profile.getAsJsonObject("pos");
        for (String key : List.of("x", "y", "z")) if (!Double.isFinite(pos.get(key).getAsDouble())) throw new IllegalArgumentException("Non-finite fake-player coordinate");
        var direction = profile.getAsJsonObject("direction"); for (String key : List.of("yaw", "pitch")) if (!Float.isFinite(direction.get(key).getAsFloat())) throw new IllegalArgumentException("Non-finite fake-player rotation");
        net.minecraft.resources.Identifier.parse(profile.get("dimension").getAsString());
        if (!List.of("survival", "creative", "adventure", "spectator").contains(profile.get("gamemode").getAsString())) throw new IllegalArgumentException("Invalid fake-player game mode");
    }
    public synchronized void save(String name, JsonObject profile, boolean replace) throws IOException {
        profile = OrgPlayerProfileUpdater.update(profile); validate(profile); Path target = path(name); Files.createDirectories(directory);
        if (!replace && Files.exists(target)) throw new IOException("This fake-player profile already exists; use modify resave");
        byte[] content = (JSON.toJson(profile) + "\n").getBytes(StandardCharsets.UTF_8); Path temporary = Files.createTempFile(directory, "player-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) { ByteBuffer bytes = ByteBuffer.wrap(content); while (bytes.hasRemaining()) file.write(bytes); file.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!profile.equals(load(name))) throw new IOException("Cannot verify the saved fake-player profile");
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized JsonObject modify(String name, UnaryOperator<JsonObject> change) throws IOException { JsonObject changed = change.apply(load(name).deepCopy()); save(name, changed, true); return changed.deepCopy(); }
    public synchronized boolean remove(String name) throws IOException { return Files.deleteIfExists(path(name)); }
}
