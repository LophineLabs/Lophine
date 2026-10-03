// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TisFakePlayerRules {
    private static final ConcurrentMap<String, String> LAST_DANGEROUS_INPUT = new ConcurrentHashMap<>();

    private TisFakePlayerRules() {
    }

    public static String spawnName(String name) {
        String prefix = GeneralCompatConfig.fakePlayerNamePrefix;
        String suffix = GeneralCompatConfig.fakePlayerNameSuffix;
        if (!"#none".equals(prefix) && !name.startsWith(prefix)) name = prefix + name;
        if (!"#none".equals(suffix) && !name.endsWith(suffix)) name += suffix;
        return name;
    }

    // Call on the source player's owner, because creative mode is mutable player state.
    public static boolean canSpawnRemotely(CommandSourceStack source, ServerPlayer sender, ServerLevel level, Vec3 position) {
        if (CarpetCommandPermissions.canUse(source, GeneralCompatConfig.fakePlayerRemoteSpawning)
                || Commands.LEVEL_GAMEMASTERS.check(source.permissions()) || sender != null && sender.gameMode.isCreative()) {
            return true;
        }
        return source.getLevel() == level && position.distanceTo(source.getPosition()) < 16.0;
    }

    /**
     * Command changes require repeated confirmation for a name fragment outside vanilla syntax. Config loads pass null.
     */
    public static synchronized boolean validateNameSetting(String rule, String value, CommandSourceStack source) {
        if (source == null) return true;
        if (!"#none".equals(value) && !value.matches("[a-zA-Z_0-9]{1,16}")) {
            String previous = LAST_DANGEROUS_INPUT.put(rule, value);
            boolean accepted = value.equals(previous);
            tell(source, "Name fragment '" + value + "' for " + rule
                    + " contains unsupported characters or length. "
                    + (accepted ? "Repeated value accepted." : "Submit the same value again to apply it."), !accepted);
            if (!accepted) return false;
        }
        LAST_DANGEROUS_INPUT.remove(rule);
        return true;
    }

    private static void tell(CommandSourceStack source, String message, boolean failure) {
        Runnable send = () -> {
            if (failure) source.sendFailure(Component.literal(message));
            else source.sendSuccess(() -> Component.literal(message), false);
        };
        ServerPlayer player = source.getPlayer();
        if (player == null) send.run();
        else player.getBukkitEntity().taskScheduler.scheduleOrExecute(entity -> send.run());
    }
}
