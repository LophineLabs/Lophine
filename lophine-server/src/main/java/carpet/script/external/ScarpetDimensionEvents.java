// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.CarpetEventServer.Event;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Destination-owner observation after Folia has actually placed the teleported player. */
public final class ScarpetDimensionEvents {
    private record Origin(Vec3 position, ResourceKey<Level> dimension) {}
    private static final WeakIdentityMap<ServerPlayer, Origin> PENDING = new WeakIdentityMap<>();
    private ScarpetDimensionEvents() {}
    public static void begin(ServerPlayer player) {
        if (Event.PLAYER_CHANGES_DIMENSION.isNeeded() && !ScarpetRuntime.EVENT_DISABLED.get())
            PENDING.put(player, new Origin(player.position(), player.level().dimension()));
    }
    public static void complete(ServerPlayer player) {
        Origin origin = PENDING.get(player);
        if (origin == null || !PENDING.remove(player, origin)) return;
        if (Event.PLAYER_CHANGES_DIMENSION.isNeeded() && !ScarpetRuntime.EVENT_DISABLED.get())
            Event.PLAYER_CHANGES_DIMENSION.onDimensionChange(player, origin.position(), player.position(), origin.dimension(), player.level().dimension());
    }
    /** Entering the credits has no destination entity position in the upstream event. */
    public static void credits(ServerPlayer player) {
        if (Event.PLAYER_CHANGES_DIMENSION.isNeeded() && !ScarpetRuntime.EVENT_DISABLED.get()) {
            var reference = carpet.script.value.EntityValue.snapshotForRetiredEvent(player);
            var position = carpet.script.value.ValueConversions.of(player.position());
            var from = carpet.script.value.NBTSerializableValue.nameFromRegistryId(player.level().dimension().identifier());
            var to = carpet.script.value.NBTSerializableValue.nameFromRegistryId(Level.OVERWORLD.identifier());
            var source = ScarpetRuntime.entitySource(player);
            Event.PLAYER_CHANGES_DIMENSION.handler.call(() -> java.util.List.of(reference, position, from, carpet.script.value.Value.NULL, to), () -> source);
        }
    }
}
