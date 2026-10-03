// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import org.leavesmc.leaves.bot.ServerBot;

/** The upstream rule broadcasts the summoner on successful spawn; it does not change player identity. */
public final class OrgPlayerSummoner {
    private OrgPlayerSummoner() {}
    public static void spawned(ServerBot bot, Component summoner, boolean silence) {
        if (!GeneralCompatConfig.displayPlayerSummoner || summoner == null || silence) return;
        TickThread.ensureTickThread(bot, "Org spawn announcement requires the fake owner");
        Component hover = Component.literal(bot.level().dimension().identifier() + ": " + bot.blockPosition().toShortString());
        Component message = announcement(summoner).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(true).withHoverEvent(new HoverEvent.ShowText(hover)));
        broadcast(bot.level().getServer(), message);
        MinecraftServer.LOGGER.info("{} has summoned {} at {} [{}]", summoner.getString(), bot.getScoreboardName(), bot.level().dimension().identifier(), bot.blockPosition().toShortString());
    }
    public static void batch(MinecraftServer server, Component summoner, int count) {
        if (!GeneralCompatConfig.displayPlayerSummoner || count == 0) return;
        broadcast(server, announcement(summoner).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
    private static net.minecraft.network.chat.MutableComponent announcement(Component summoner) {
        return Component.literal(String.format(java.util.Locale.ROOT,
            OrgRuleTranslations.text("carpet-org-addition.rule.message.displayPlayerSummoner", "Summoner: %s"), summoner.getString()));
    }
    private static void broadcast(MinecraftServer server, Component message) {
        OrgCommandNativeEffects.broadcast(server, message);
    }
}
