// SPDX-License-Identifier: LGPL-3.0-or-later
// AMS alert behavior adapted from Carpet AMS Addition revision 750310179368b2569dd6121a2769b2fb1bbc7343.
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * Server-side AMS alerts. Entity data is captured by the caller on its owning region.
 */
public final class AmsServerMessages {
    private AmsServerMessages() {
    }

    public static void deathLocation(final ServerPlayer player) {
        ScarpetNativeWork.record(deathLocationAsync(player));
    }

    public static CompletableFuture<Void> deathLocationAsync(final ServerPlayer player) {
        return AmsNativeCommandEffects.nativeReceipt(player.carpetSpawnServer(), () -> AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(player, () -> deathMessage(player)), prepared -> prepared == null
                ? CompletableFuture.completedFuture(null) : broadcast(prepared.server(), prepared.message())));
    }

    private record Message(net.minecraft.server.MinecraftServer server, Component message) {
    }

    private static Message deathMessage(final ServerPlayer player) {
        final String mode = GeneralCompatConfig.sendPlayerDeathLocation;
        final boolean fake = player instanceof org.leavesmc.leaves.bot.ServerBot;
        if (!(mode.equals("all") || mode.equals(fake ? "fakePlayerOnly" : "realPlayerOnly"))) {
            return null;
        }
        final BlockPos pos = player.blockPosition();
        final String coordinates = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        final MutableComponent message = Component.literal(player.getName().getString()
                + " " + serverText("rule.sendPlayerDeathLocation.location").getString() + " @ " + player.level().dimension().identifier()
                + " -> [ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " ]").withStyle(ChatFormatting.RED);
        message.append(Component.literal(" [C]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(coordinates))
                .withHoverEvent(new HoverEvent.ShowText(serverText("rule.sendPlayerDeathLocation.copy").withStyle(ChatFormatting.YELLOW)))));
        message.append(Component.literal(" [+H]").withStyle(style -> style.withColor(ChatFormatting.YELLOW).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/coordCompass set " + coordinates))
                .withHoverEvent(new HoverEvent.ShowText(serverText("fuzz.command.highlightCoordButtonHoverText").withStyle(ChatFormatting.YELLOW)))));
        return new Message(player.level().getServer(), message);
    }

    public static void phantomSpawn(final MinecraftServer server, final String playerName, final double x, final double y, final double z) {
        ScarpetNativeWork.record(phantomSpawnAsync(server, playerName, x, y, z));
    }

    public static CompletableFuture<Void> phantomSpawnAsync(final MinecraftServer server, final String playerName, final double x, final double y, final double z) {
        if (!GeneralCompatConfig.phantomSpawnAlert) return CompletableFuture.completedFuture(null);
        String coordinates = x + ", " + y + ", " + z;
        return AmsNativeCommandEffects.nativeReceipt(server, () -> broadcast(server, Component.literal(serverText("rule.phantomSpawnAlert.msg", playerName, coordinates).getString())));
    }

    private static MutableComponent serverText(String key, Object... args) {
        return AmsTranslations.translateText(Component.translatable("carpetamsaddition." + key, args), AmsTranslations.serverLanguage());
    }

    private static CompletableFuture<Void> broadcast(final MinecraftServer server, final Component message) {
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(server, () -> {
            server.sendSystemMessage(message);
            return (Void) null;
        }), ignored -> AmsNativeCommandEffects.broadcast(server, recipient -> recipient.sendSystemMessage(message)));
    }
}
