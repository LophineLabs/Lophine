// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet AMS Addition fake-player identity rules, revision 750310179368b2569dd6121a2769b2fb1bbc7343.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import org.bukkit.command.CommandSender;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.ServerBot;

public final class AmsFakePlayers {
    private static String previousTeam = "false";

    private AmsFakePlayers() {
    }

    public record SpawnMode(net.minecraft.world.level.GameType mode, boolean flying) {}

    public static SpawnMode spawnMode(final net.minecraft.world.level.GameType explicitMode, final net.minecraft.server.level.ServerPlayer sender) {
        net.minecraft.world.level.GameType mode = net.minecraft.world.level.GameType.CREATIVE;
        boolean flying = false;
        if (sender != null) {
            mode = fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode
                ? net.minecraft.world.level.GameType.SURVIVAL : sender.gameMode.getGameModeForPlayer();
            boolean originalFlying = sender.getAbilities().flying;
            flying = fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.fakePlayerDefaultSurvivalMode ? false : originalFlying;
        }
        if (explicitMode != null) mode = explicitMode;
        if (mode == net.minecraft.world.level.GameType.SPECTATOR) flying = true;
        else if (mode.isSurvival()) flying = false;
        return new SpawnMode(mode, flying);
    }

    public static String spawnName(final String name) {
        final String team = GeneralCompatConfig.fancyFakePlayerName;
        return "false".equals(team) ? name : name + "_" + team;
    }

    public static boolean canSpawn(final MinecraftServer server, final String name, final CommandSender creator) {
        if (!GeneralCompatConfig.onlyOpCanSpawnRealPlayerInWhitelist || creator == null || creator.isOp()) {
            return true;
        }
        // StoredUserList is a concurrent map on this server. Match the official
        // whitelist identity without a blocking profile lookup on a region thread.
        for (var entry : server.getPlayerList().getWhiteList().getEntries()) {
            if (entry.getUser() != null && entry.getUser().name().equalsIgnoreCase(name)
                && (!GeneralCompatConfig.fakePlayerUseOfflinePlayerUUID
                    || entry.getUser().id().equals(net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name)))) {
                creator.sendMessage("Whitelisted players can only be spawned by operators");
                return false;
            }
        }
        return true;
    }

    public static void added(final ServerBot bot) {
        if (bot.carpetShadow) return;
        final String name = bot.getGameProfile().name();
        final MinecraftServer server = bot.level().getServer();
        AmsNativeCommandEffects.global(server, () -> {
            applyRuleChange(server);
            final String team = GeneralCompatConfig.fancyFakePlayerName;
            if (!"false".equals(team)) {
                addToTeam(server, name, team);
            }
            return (Void)null;
        });
    }

    public static void removed(final ServerBot bot) {
        if (bot.carpetShadow) return;
        final String name = bot.getGameProfile().name();
        final MinecraftServer server = bot.level().getServer();
        AmsNativeCommandEffects.global(server, () -> {
            final PlayerTeam current = server.getScoreboard().getPlayersTeam(name);
            if (current != null && current.getName().equals(GeneralCompatConfig.fancyFakePlayerName)) {
                server.getScoreboard().removePlayerFromTeam(name, current);
            }
            return (Void)null;
        });
    }

    public static void refresh() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null && BotList.INSTANCE != null) {
            AmsNativeCommandEffects.global(server, () -> {applyRuleChange(server);return (Void)null;});
        }
    }

    private static void applyRuleChange(final MinecraftServer server) {
        final String current = GeneralCompatConfig.fancyFakePlayerName;
        if (Objects.equals(current, previousTeam)) {
            return;
        }
        if (!"false".equals(previousTeam)) {
            final PlayerTeam oldTeam = server.getScoreboard().getPlayerTeam(previousTeam);
            if (oldTeam != null) {
                server.getScoreboard().removePlayerTeam(oldTeam);
            }
        }
        previousTeam = current;
        if (!"false".equals(current)) {
            for (ServerBot bot : server.getBotList().bots) {
                if (!bot.carpetShadow) addToTeam(server, bot.getGameProfile().name(), current);
            }
        }
    }

    private static void addToTeam(final MinecraftServer server, final String name, final String teamName) {
        PlayerTeam team = server.getScoreboard().getPlayerTeam(teamName);
        if (team == null) {
            team = server.getScoreboard().addPlayerTeam(teamName);
            team.setPlayerPrefix(Component.literal("[" + teamName + "] ").withStyle(ChatFormatting.BOLD));
            team.setColor(Optional.of(TeamColor.DARK_GREEN));
        }
        final PlayerTeam current = server.getScoreboard().getPlayersTeam(name);
        if (current != null && current != team) {
            server.getScoreboard().removePlayerFromTeam(name, current);
        }
        if (current != team) {
            server.getScoreboard().addPlayerToTeam(name, team);
        }
    }
}
