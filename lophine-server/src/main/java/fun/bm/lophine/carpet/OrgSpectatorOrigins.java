// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Reads both official Org spectator position schemas in the migrated world directory.
 */
final class OrgSpectatorOrigins {
    private OrgSpectatorOrigins() {
    }

    record Origin(Path file, String serialized, String dimension, double x, double y, double z, float yaw,
                  float pitch) {
    }

    static Origin read(MinecraftServer server, UUID player) {
        for (Path directory : java.util.List.of(OrgWorldFormat.directory(server).resolve("spectator"))) {
            Path file = directory.resolve(player + ".json");
            if (!Files.isRegularFile(file)) continue;
            try {
                String serialized = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(serialized).getAsJsonObject();
                int version = json.has("data_version") ? json.get("data_version").getAsInt() : 0;
                if (version < 0 || version > 1)
                    throw new IllegalArgumentException("Unsupported spectator position version");
                JsonObject pos = version == 0 ? json : json.getAsJsonObject("pos");
                JsonObject direction = version == 0 ? json : json.getAsJsonObject("direction");
                return new Origin(file, serialized, json.get("dimension").getAsString(), pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble(),
                        direction.get("yaw").getAsFloat(), direction.get("pitch").getAsFloat());
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException("The saved spectator position cannot be read", failure);
            }
        }
        return null;
    }

    static void remove(Origin origin) {
        try {
            if (!Files.readString(origin.file(), StandardCharsets.UTF_8).equals(origin.serialized()))
                throw new IOException("The saved spectator origin changed during teleport");
            Files.delete(origin.file());
        } catch (IOException failure) {
            throw new IllegalStateException("The completed spectator origin cannot be removed", failure);
        }
    }
}
