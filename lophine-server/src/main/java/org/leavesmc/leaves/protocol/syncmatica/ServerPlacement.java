/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.protocol.syncmatica;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class ServerPlacement {

    private final UUID id;

    private final String fileName;
    private String displayName;
    private final UUID hashValue;
    private int litematicVersion = -1;
    private int dataVersion = -1;

    private PlayerIdentifier owner;
    private PlayerIdentifier lastModifiedBy;

    private ServerPosition origin;
    private Rotation rotation;
    private Mirror mirror;

    private SubRegionData subRegionData = new SubRegionData();

    public ServerPlacement(final UUID id, final String fileName, final UUID hashValue, final PlayerIdentifier owner) {
        this(id, fileName, removeExtension(fileName), hashValue, owner, -1, -1);
    }

    public ServerPlacement(
            final UUID id,
            final String fileName,
            final String displayName,
            final UUID hashValue,
            final PlayerIdentifier owner,
            final int litematicVersion,
            final int dataVersion
    ) {
        this.id = id;
        this.fileName = removeExtension(fileName);
        this.displayName = displayName == null ? this.fileName : displayName;
        this.hashValue = hashValue;
        this.owner = owner;
        lastModifiedBy = owner;
        this.litematicVersion = litematicVersion;
        this.dataVersion = dataVersion;
    }

    private static String removeExtension(final String fileName) {
        final int pos = fileName.lastIndexOf(".");
        if (pos < 0) {
            return fileName;
        }
        return fileName.substring(0, pos);
    }

    @Nullable
    public static ServerPlacement fromJson(final @NotNull JsonObject obj) {
        if (obj.has("id")
                && obj.has("file_name")
                && obj.has("hash")
                && obj.has("origin")
                && obj.has("rotation")
                && obj.has("mirror")) {
            final UUID id = UUID.fromString(obj.get("id").getAsString());
            final String name = obj.get("file_name").getAsString();
            final UUID hashValue = UUID.fromString(obj.get("hash").getAsString());

            PlayerIdentifier owner = PlayerIdentifier.MISSING_PLAYER;
            if (obj.has("owner")) {
                owner = SyncmaticaProtocol.getPlayerIdentifierProvider().fromJson(obj.get("owner").getAsJsonObject());
            }

            final String displayName = obj.has("display_name")
                    ? obj.get("display_name").getAsString()
                    : removeExtension(name);
            final int litematicVersion = obj.has("litematicVersion")
                    ? obj.get("litematicVersion").getAsInt()
                    : -1;
            final int dataVersion = obj.has("dataVersion")
                    ? obj.get("dataVersion").getAsInt()
                    : -1;
            final ServerPlacement newPlacement = new ServerPlacement(
                    id, name, displayName, hashValue, owner, litematicVersion, dataVersion
            );
            final ServerPosition pos = ServerPosition.fromJson(obj.get("origin").getAsJsonObject());
            if (pos == null) {
                return null;
            }
            newPlacement.origin = pos;
            newPlacement.rotation = Rotation.valueOf(obj.get("rotation").getAsString());
            newPlacement.mirror = Mirror.valueOf(obj.get("mirror").getAsString());

            if (obj.has("lastModifiedBy")) {
                newPlacement.lastModifiedBy = SyncmaticaProtocol.getPlayerIdentifierProvider()
                        .fromJson(obj.get("lastModifiedBy").getAsJsonObject());
            } else {
                newPlacement.lastModifiedBy = owner;
            }

            if (obj.has("subregionData")) {
                newPlacement.subRegionData = SubRegionData.fromJson(obj.get("subregionData"));
            }

            return newPlacement;
        }

        return null;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return fileName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getLitematicVersion() {
        return litematicVersion;
    }

    public int getDataVersion() {
        return dataVersion;
    }

    public void setMetadata(final String displayName, final int litematicVersion, final int dataVersion) {
        this.displayName = displayName == null ? this.fileName : displayName;
        this.litematicVersion = litematicVersion;
        this.dataVersion = dataVersion;
    }

    public UUID getHash() {
        return hashValue;
    }

    public String getDimension() {
        return origin.getDimensionId();
    }

    public BlockPos getPosition() {
        return origin.getBlockPosition();
    }

    public ServerPosition getOrigin() {
        return origin;
    }

    public Rotation getRotation() {
        return rotation;
    }

    public Mirror getMirror() {
        return mirror;
    }

    public ServerPlacement move(final String dimensionId, final BlockPos origin, final Rotation rotation, final Mirror mirror) {
        move(new ServerPosition(origin, dimensionId), rotation, mirror);
        return this;
    }

    public ServerPlacement move(final ServerPosition origin, final Rotation rotation, final Mirror mirror) {
        this.origin = origin;
        this.rotation = rotation;
        this.mirror = mirror;
        return this;
    }

    public PlayerIdentifier getOwner() {
        return owner;
    }

    public void setOwner(final PlayerIdentifier playerIdentifier) {
        owner = playerIdentifier;
    }

    public PlayerIdentifier getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(final PlayerIdentifier lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public SubRegionData getSubRegionData() {
        return subRegionData;
    }

    public JsonObject toJson() {
        final JsonObject obj = new JsonObject();
        obj.add("id", new JsonPrimitive(id.toString()));

        obj.add("file_name", new JsonPrimitive(fileName));
        obj.add("display_name", new JsonPrimitive(displayName));
        obj.add("hash", new JsonPrimitive(hashValue.toString()));

        if (litematicVersion >= 0) {
            obj.add("litematicVersion", new JsonPrimitive(litematicVersion));
        }
        if (dataVersion >= 0) {
            obj.add("dataVersion", new JsonPrimitive(dataVersion));
        }

        obj.add("origin", origin.toJson());
        obj.add("rotation", new JsonPrimitive(rotation.name()));
        obj.add("mirror", new JsonPrimitive(mirror.name()));

        obj.add("owner", owner.toJson());
        if (!owner.equals(lastModifiedBy)) {
            obj.add("lastModifiedBy", lastModifiedBy.toJson());
        }

        if (subRegionData.isModified()) {
            obj.add("subregionData", subRegionData.toJson());
        }

        return obj;
    }
}
