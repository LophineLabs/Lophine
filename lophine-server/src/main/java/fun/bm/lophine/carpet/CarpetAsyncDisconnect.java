// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import net.minecraft.world.level.ChunkPos;

/** Keeps Folia's region connection until the asynchronous player-removal ticket is ready. */
public final class CarpetAsyncDisconnect {
    private CarpetAsyncDisconnect() {}

    public static boolean cleanupReady(boolean switchingConfig, ChunkPos disconnectPos) {
        return switchingConfig || disconnectPos != null;
    }
}
