// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shared first world-directory access performs the upstream whole-directory migration.
 */
public final class OrgWorldFormat {
    private static final ConcurrentHashMap<Path, Attempt> ATTEMPTS = new ConcurrentHashMap<>();

    private static final class Attempt {
        boolean acquired;
    }

    private OrgWorldFormat() {
    }

    public static Path directory(MinecraftServer server) {
        return directory(server.getWorldPath(LevelResource.ROOT));
    }

    public static Path directory(Path worldRoot) {
        Path root = worldRoot.toAbsolutePath().normalize();
        Path modern = root.resolve("config/carpet-org-addition");
        Attempt attempt = ATTEMPTS.computeIfAbsent(root, ignored -> new Attempt());
        synchronized (attempt) {
            // Path-based stores share the server's one-shot latch and wait for its IO to finish.
            if (!attempt.acquired) {
                attempt.acquired = true;
                migrate(root, modern);
            }
        }
        return modern;
    }

    static void close(MinecraftServer server) {
        ATTEMPTS.remove(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
    }

    private static void migrate(Path root, Path modern) {
        try {
            Path legacy = root.resolve("carpetorgaddition");
            if (Files.isDirectory(legacy) && !Files.exists(modern)) {
                Files.createDirectories(modern.getParent());
                Files.move(legacy, modern, StandardCopyOption.ATOMIC_MOVE);
                LogUtils.getLogger().info("Carpet Org Addition has migrated the world config file from {} to {}", legacy, modern);
                JsonObject version = new JsonObject();
                version.addProperty("data_version", 1);
                writeVersion(modern.resolve("data_version.json"), new GsonBuilder().setPrettyPrinting().create().toJson(version));
                LogUtils.getLogger().info("Created 'data_version.json' file");
                Files.move(modern.resolve("config.json"), modern.resolve("rules.json"));
                LogUtils.getLogger().info("Renamed 'config.json' to 'rules.json'");
            }
        } catch (RuntimeException | IOException failure) {
            // Source consumes the latch before IO and logs a failure without retry or rollback of the directory.
            LogUtils.getLogger().error("Failed to migrate Carpet Org Addition config file", failure);
        }
    }

    /**
     * Upstream IOUtils.write preserves an existing version file until replacement succeeds.
     */
    private static void writeVersion(Path file, String content) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "data_version-", ".tmp");
        boolean original = Files.exists(file);
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            if (original) {
                Files.deleteIfExists(backup);
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                if (Files.exists(backup)) Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING);
                throw failure;
            }
            Files.deleteIfExists(backup);
        } catch (IOException failure) {
            if (original && Files.exists(backup)) {
                try {
                    Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException recovery) {
                    failure.addSuppressed(recovery);
                }
            }
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }
}
