// SPDX-License-Identifier: LGPL-3.0-or-later
// Player loader control adapted from Carpet AMS Addition revision 750310179368b2569dd6121a2769b2fb1bbc7343.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class AmsPlayerChunkLoading {
    private static final ConcurrentHashMap<String, Boolean> PLAYER_LOADING = new ConcurrentHashMap<>();

    private AmsPlayerChunkLoading() {
    }

    public static boolean loadsChunks(final ServerPlayer player) {
        return (GeneralCompatConfig.creativePlayersLoadChunks || !player.isCreative())
            && ("false".equals(GeneralCompatConfig.commandPlayerChunkLoadController)
                || PLAYER_LOADING.getOrDefault(player.getName().getString(), true));
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("playerChunkLoading")
            .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandPlayerChunkLoadController))
            .executes(context -> OrgCommandNativeEffects.command(context.getSource(), 1, () -> execute(context.getSource(), null)))
            .then(Commands.argument("boolean", BoolArgumentType.bool())
                .executes(context -> OrgCommandNativeEffects.command(context.getSource(), 1, () -> execute(context.getSource(), BoolArgumentType.getBool(context, "boolean")))))
            .then(Commands.literal("help").executes(context -> OrgCommandNativeEffects.command(context.getSource(), 1,
                () -> message(context.getSource(), "help", net.minecraft.ChatFormatting.GRAY, 1)))));
    }

    private record Read(String name, boolean enabled, ServerPlayer player) {}

    private static java.util.concurrent.CompletableFuture<Integer> execute(final CommandSourceStack source, final Boolean setting) {
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.source(source, source::getTextName), name ->
            AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(source.getServer(), () -> {
                boolean enabled = PLAYER_LOADING.getOrDefault(name, true);
                ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
                if (setting != null && player != null) PLAYER_LOADING.put(name, setting);
                return new Read(name, enabled, player);
            }), read -> {
                if (read.player() == null) return message(source, "no_player_specified", net.minecraft.ChatFormatting.RED, 0);
                if (setting != null) return message(source, "set", net.minecraft.ChatFormatting.LIGHT_PURPLE, 1, read.name(), String.valueOf(setting));
                return message(source, read.enabled() ? "chunk_loading_true" : "chunk_loading_false", net.minecraft.ChatFormatting.LIGHT_PURPLE, 1);
            }));
    }

    private static java.util.concurrent.CompletableFuture<Integer> message(final CommandSourceStack source, final String key,
        final net.minecraft.ChatFormatting color, final int result, final Object... args) {
        return AmsNativeCommandEffects.source(source, () -> {
            Component reply = AmsTranslations.message(source, "command.playerChunkLoading." + key, args).withStyle(color);
            if (!"help".equals(key)) reply = reply.copy().withStyle(net.minecraft.ChatFormatting.BOLD);
            CarpetMessenger.send(source, java.util.List.of(reply));
            return result;
        });
    }
}
