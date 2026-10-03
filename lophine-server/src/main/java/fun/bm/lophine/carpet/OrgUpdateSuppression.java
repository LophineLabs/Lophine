package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Named-shulker update suppression, retaining the original ClassCastException trigger. */
public final class OrgUpdateSuppression {
    private OrgUpdateSuppression() {}

    public static boolean namedSuppressor(String name) {
        String value = GeneralCompatConfig.CCEUpdateSuppression;
        if (name == null || value == null || value.equals("false")) return false;
        if (value.equals("true")) return name.equals("更新抑制器") || name.equalsIgnoreCase("updateSuppression");
        return value.equalsIgnoreCase(name);
    }

    public static final class CCE extends ClassCastException {
        private final BlockPos position;
        private final String dimension;
        public CCE(BlockPos position, String dimension) {
            super("CCE Update Suppress triggered on " + dimension + " " + position.toShortString());
            this.position = position.immutable(); this.dimension = dimension;
        }
    }

    public static void packetFailure(RuntimeException exception, Packet<?> packet, PacketListener listener) {
        if (!(listener instanceof ServerGamePacketListenerImpl game)) return;
        Throwable cause = exception;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (cause != null && seen.add(cause)) {
            if (cause instanceof CCE cce) {
                String activity = packet instanceof ServerboundUseItemOnPacket ? "placing or interacting with a block"
                    : packet instanceof ServerboundPlayerActionPacket action ? switch (action.getAction()) {
                        case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> "breaking a block";
                        case DROP_ALL_ITEMS, DROP_ITEM -> "dropping an item";
                        case RELEASE_USE_ITEM -> "using an item";
                        case SWAP_ITEM_WITH_OFFHAND -> "swapping main-hand and off-hand items";
                        default -> "sending action " + action.getAction();
                    } : "sending a " + packet.getClass().getSimpleName() + " packet";
                com.mojang.logging.LogUtils.getLogger().info("{} triggered CCE update suppression while {} at {} {}",
                    game.player.getScoreboardName(), activity, cce.dimension, cce.position.toShortString());
                return;
            }
            cause = cause.getCause();
        }
    }
}
