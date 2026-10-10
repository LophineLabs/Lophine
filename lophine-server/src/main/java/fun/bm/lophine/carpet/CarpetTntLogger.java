// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from fabric-carpet f358000, carpet.logging.logHelpers.TNTLogHelper.
package fun.bm.lophine.carpet;

import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class CarpetTntLogger {
    private static long lastGameTime = Long.MIN_VALUE;
    private static int count;
    private Vec3 primed;
    private Vec3 motion;

    public void tick(Vec3 position, Vec3 velocity) {
        if (primed == null) {
            primed = position;
            motion = velocity;
        }
    }

    private static synchronized int nextCount(long gameTime) {
        if (gameTime != lastGameTime) {
            count = 0;
            lastGameTime = gameTime;
        }
        return ++count;
    }

    public void exploded(Vec3 position, long gameTime) {
        if (primed == null) return;
        int number = nextCount(gameTime);
        CarpetLoggerProtocol.log("tnt", option -> {
            boolean full = "full".equals(option);
            if (!full && !"brief".equals(option)) return List.of();
            var message = Component.empty();
            if (full) message.append(Component.literal("#" + number).withStyle(ChatFormatting.RED))
                    .append(Component.literal(" @" + gameTime).withStyle(ChatFormatting.LIGHT_PURPLE)).append(": ");
            message.append(Component.literal("P ").withStyle(ChatFormatting.AQUA))
                    .append(CarpetTrajectoryLogger.coordinatesText(primed, ChatFormatting.AQUA, full)).append(" ")
                    .append(CarpetTrajectoryLogger.coordinatesText(motion, ChatFormatting.AQUA, full));
            message.append(Component.literal(" E ").withStyle(ChatFormatting.RED))
                    .append(CarpetTrajectoryLogger.coordinatesText(position, ChatFormatting.RED, full));
            return List.of(message);
        });
    }
}
