package fun.bm.lophine.protocol;

import fun.bm.lophine.carpet.org.OrgFinderByNameProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.leavesmc.leaves.protocol.core.LeavesProtocol;
import org.leavesmc.leaves.protocol.core.ProtocolHandler;

@LeavesProtocol.Register(namespace = "carpet-org-addition")
public final class OrgFinderProtocol implements LeavesProtocol {
    @Override
    public boolean isActive() {
        return true;
    }

    @ProtocolHandler.BytebufReceiver(key = "object_search_task")
    public static boolean receive(ServerPlayer player, FriendlyByteBuf buffer) {
        return OrgFinderByNameProtocol.receive(player, buffer);
    }
}
