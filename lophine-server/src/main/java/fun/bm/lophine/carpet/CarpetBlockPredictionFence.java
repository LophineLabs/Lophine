// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import carpet.script.external.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * A prediction ack cannot pass a block interaction whose actual native result is still pending.
 */
public final class CarpetBlockPredictionFence {
    private static final WeakIdentityMap<ServerGamePacketListenerImpl, State> STATES = new WeakIdentityMap<>();

    private static final class State {
        final TreeMap<Integer, CompletableFuture<?>> pending = new TreeMap<>();
    }

    private CarpetBlockPredictionFence() {
    }

    public static void retain(ServerGamePacketListenerImpl connection, int sequence, CompletableFuture<?> actual) {
        if (sequence < 0) throw new IllegalArgumentException("Block prediction sequence must be nonnegative");
        if (actual.isDone()) return;
        State state = STATES.computeIfAbsent(connection, ignored -> new State());
        CompletableFuture<?> retained;
        synchronized (state) {
            CompletableFuture<?> previous = state.pending.get(sequence);
            retained = previous == null || previous.isDone() ? actual : CompletableFuture.allOf(previous, actual);
            state.pending.put(sequence, retained);
        }
        var job = retained;
        retained.whenComplete((ignored, failure) -> {
            synchronized (state) {
                state.pending.remove(sequence, job);
            }
        });
    }

    public static int readyThrough(ServerGamePacketListenerImpl connection, int requested) {
        State state = STATES.get(connection);
        if (state == null) return requested;
        synchronized (state) {
            return state.pending.isEmpty() || state.pending.firstKey() > requested
                    ? requested : state.pending.firstKey() - 1;
        }
    }

    /**
     * Read block/BE snapshots on their original owners, then deliver on the same live player session.
     */
    public static void resyncBlocks(ServerGamePacketListenerImpl connection, ServerLevel world, BlockPos... positions) {
        var player = connection.player;
        var reads = new ArrayList<CompletableFuture<List<Packet<?>>>>();
        for (BlockPos position : positions) {
            BlockPos pos = position.immutable();
            reads.add(ScarpetExplosionActors.world(world, pos, () -> {
                var packets = new ArrayList<Packet<?>>();
                var state = world.getBlockStateIfLoaded(pos);
                if (state == null) return List.<Packet<?>>of();
                packets.add(new ClientboundBlockUpdatePacket(pos, state));
                if (state.hasBlockEntity()) {
                    var entity = world.getBlockEntity(pos);
                    var update = entity == null ? null : entity.getUpdatePacket();
                    if (update != null) packets.add(update);
                }
                return List.copyOf(packets);
            }));
        }
        var actual = CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetNativeDeathActors.entity(player, () -> {
                    if (connection.player == player && player.connection == connection && player.level() == world && !player.hasDisconnected()) {
                        for (var read : reads) for (var packet : read.join()) connection.send(packet);
                    }
                    return (Void) null;
                })));
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(world.getServer(), actual);
    }
}
