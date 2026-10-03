// SPDX-License-Identifier: MIT
// Source Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;

/**
 * Pinned source wire encoding; server uses the source removed/client setting's static true default.
 */
final class OrgNavigationProtocol {
    static final Identifier UPDATE = Identifier.fromNamespaceAndPath("carpet-org-addition", "waypoint_update");
    static final Identifier CLEAR = Identifier.fromNamespaceAndPath("carpet-org-addition", "waypoint_clear");

    private OrgNavigationProtocol() {
    }

    static byte[] encode(Vec3 point, String dimension, int entityId) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeDouble(point.x);
            buffer.writeDouble(point.y);
            buffer.writeDouble(point.z);
            buffer.writeIdentifier(Identifier.parse(dimension));
            buffer.writeInt(entityId);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.getBytes(0, data);
            return data;
        } finally {
            buffer.release();
        }
    }

    static void update(ServerPlayer player, Vec3 point, String dimension, int entityId) {
        ProtocolUtils.sendRawPayloadPacket(player, UPDATE, encode(point, dimension, entityId));
    }

    static void clear(ServerPlayer player) {
        ProtocolUtils.sendEmptyPacket(player, CLEAR);
    }

    /**
     * Pinned NavigatorManager.clearNavigator calls setNavigator(null), then sends a second clear.
     */
    static void clearManager(ServerPlayer player) {
        clear(player);
        clear(player);
    }
}
