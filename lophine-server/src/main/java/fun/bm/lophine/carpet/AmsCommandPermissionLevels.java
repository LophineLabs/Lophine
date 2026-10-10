// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Restore exact root requirements when overrides or the rule are disabled.
 */
public final class AmsCommandPermissionLevels {
    private record Requirement(Predicate<CommandSourceStack> original, Predicate<CommandSourceStack> installed) {
    }

    private static final carpet.script.external.WeakIdentityMap<CommandNode<CommandSourceStack>, Requirement> DEFAULTS = new carpet.script.external.WeakIdentityMap<>();
    private static final Set<String> AMS_RESTRICTED = Set.of("advancement", "data", "defaultgamemode", "difficulty", "effect", "enchant", "experience", "xp", "fill", "gamemode", "gamerule", "give", "kill", "setblock", "summon", "teleport", "tp", "time", "weather");
    private static final Set<String> TIS_RESTRICTED = Set.of("fill", "gamemode", "give", "setblock", "summon", "teleport", "tp");

    private AmsCommandPermissionLevels() {
    }

    public static synchronized void apply(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (var node : dispatcher.getRoot().getChildren()) {
            String name = node.getName();
            Requirement previous = DEFAULTS.get(node);
            // The installed predicate reads live settings. Replacing it on every
            // global tick only races the asynchronous per-player command builders.
            if (previous != null && node.getRequirement() == previous.installed()) continue;
            Predicate<CommandSourceStack> original = node.getRequirement();
            Predicate<CommandSourceStack> installed = source -> {
                Integer level = AmsManagementSettings.enabled(GeneralCompatConfig.commandCustomCommandPermissionLevel) ? AmsManagementSettings.PERMISSIONS.get(name) : null;
                if (level == null) return original.test(source);
                if (AMS_RESTRICTED.contains(name) && !AmsAdministratorProtection.canCheat(source)) return false;
                if (TIS_RESTRICTED.contains(name) && !TisUtilityCommands.canCheat(source)) return false;
                return level <= 0 || level <= 4 && CarpetCommandPermissions.canUse(source, Integer.toString(level));
            };
            node.requirement = installed;
            DEFAULTS.put(node, new Requirement(original, installed));
        }
    }

    public static void refresh(CommandDispatcher<CommandSourceStack> dispatcher, MinecraftServer server) {
        var actual = AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(server, () -> {
            apply(dispatcher);
            return (Void) null;
        }), ignored ->
                AmsNativeCommandEffects.broadcast(server, player -> server.getCommands().sendCommands(player)));
        carpet.script.external.ScarpetNativeWork.record(actual);
    }

    public static void tick(MinecraftServer server, long tick) {
        if (tick % 20 == 0) apply(server.getCommands().getDispatcher());
    }
}
