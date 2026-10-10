// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The vanilla command registrations targeted by AMS 26.3's permission mixins.
 */
public final class AmsAdministratorProtection {
    private static final List<String> RESTRICTED = List.of("advancement", "data", "defaultgamemode", "difficulty", "effect", "enchant", "experience", "xp", "fill", "gamemode", "gamerule", "give", "kill", "setblock", "summon", "teleport", "tp", "time", "weather");

    private AmsAdministratorProtection() {
    }

    public static boolean canCheat(CommandSourceStack source) {
        return !GeneralCompatConfig.preventAdministratorCheat || !(source.getEntity() instanceof ServerPlayer);
    }

    public static void apply(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : RESTRICTED) {
            var node = dispatcher.getRoot().getChild(name);
            if (node == null) continue;
            var previous = node.getRequirement();
            node.requirement = source -> previous.test(source) && canCheat(source);
        }
    }
}
