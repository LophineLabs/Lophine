// SPDX-License-Identifier: MIT
// Carpet Org Addition fake_player_pathfinder wire, c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class OrgHiddenPathProtocol {
    private static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("carpet-org-addition", "fake_player_pathfinder");

    private OrgHiddenPathProtocol() {
    }

    public static void registerLogger() {
        if (OrgHiddenPlayerActions.enabled())
            CarpetLoggerProtocol.registerLogger("fakePlayerPathfinding", "", List.of(), false);
    }

    static void path(ServerPlayer bot, List<Vec3> nodes) {
        if (!OrgHiddenPlayerActions.enabled() || !CarpetLoggerProtocol.hasSubscribers("fakePlayerPathfinding")) return;
        byte[] data = encode(bot.getId(), nodes);
        var server = bot.level().getServer();
        OrgCommandNativeEffects.global(server, () -> {
            for (ServerPlayer recipient : List.copyOf(server.getPlayerList().getPlayers()))
                OrgMenuNativeEffects.run(recipient, () -> {
                    if (!recipient.isRemoved() && !(recipient instanceof org.leavesmc.leaves.bot.ServerBot) && CarpetLoggerProtocol.subscriptions(recipient.getScoreboardName()).containsKey("fakePlayerPathfinding"))
                        ProtocolUtils.sendRawPayloadPacket(recipient, CHANNEL, data);
                    return null;
                });
            return null;
        });
    }

    /**
     * Source unsubscribe callback clears all client path displays with entity id -1.
     */
    public static void subscriptionChanged(String playerName) {
        if (CarpetLoggerProtocol.subscriptions(playerName).containsKey("fakePlayerPathfinding")) return;
        var server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null) return;
        OrgCommandNativeEffects.global(server, () -> {
            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
            if (player != null) OrgMenuNativeEffects.run(player, () -> {
                if (!player.isRemoved() && !(player instanceof org.leavesmc.leaves.bot.ServerBot) && !CarpetLoggerProtocol.subscriptions(playerName).containsKey("fakePlayerPathfinding"))
                    ProtocolUtils.sendRawPayloadPacket(player, CHANNEL, encode(-1, List.of()));
                return null;
            });
            return null;
        });
    }

    static byte[] encode(int entityId, List<Vec3> nodes) {
        List<Vec3> compressed = compress(nodes);
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(entityId);
            output.writeInt(compressed.size());
            for (Vec3 node : compressed) {
                output.writeDouble(node.x);
                output.writeDouble(node.y);
                output.writeDouble(node.z);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static List<Vec3> compress(List<Vec3> nodes) {
        if (nodes.isEmpty()) return List.of();
        var result = new ArrayList<Vec3>();
        int start = 0;
        result.add(nodes.getFirst());
        for (int i = 1; i < nodes.size() - 1; i++) {
            Vec3 a = nodes.get(start), b = nodes.get(i), c = nodes.get(i + 1);
            Vec3 cross = b.subtract(a).cross(c.subtract(a));
            if (Math.abs(cross.x) < 1e-9 && Math.abs(cross.y) < 1e-9 && Math.abs(cross.z) < 1e-9
                    && inRange(a.x, c.x, b.x) && inRange(a.y, c.y, b.y) && inRange(a.z, c.z, b.z)) continue;
            result.add(b);
            start = i;
        }
        result.add(nodes.getLast());
        return result;
    }

    private static boolean inRange(double a, double b, double value) {
        return a >= value && value >= b || a <= value && value <= b;
    }
}
