package fun.bm.lophine.carpet;

import net.minecraft.server.MinecraftServer;

/**
 * Global overworld clock published to metrics running in any dimension.
 */
public final class CarpetServerClock {
    private static volatile long gameTime;

    private CarpetServerClock() {
    }

    public static void refresh(MinecraftServer server) {
        if (server.overworld() != null) gameTime = server.overworld().getGameTime();
    }

    public static long gameTime() {
        return gameTime;
    }
}
