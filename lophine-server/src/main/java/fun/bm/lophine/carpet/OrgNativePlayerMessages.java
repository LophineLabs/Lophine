// SPDX-License-Identifier: MIT
// Source FakePlayerSpawner.SILENCE c2142c213269f85fb1851263bf60f147849224a1; Folia lifetime adapter.
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.WeakIdentityMap;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.event.bot.BotRemoveEvent;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * One actual join/removal carries source silence across async placement and actor retirement.
 */
public final class OrgNativePlayerMessages {
    private static final WeakIdentityMap<ServerBot, Boolean> JOINS = new WeakIdentityMap<>(), LEAVES = new WeakIdentityMap<>();

    private record Removal(boolean silence, boolean keepTab) {
    }

    private static final WeakIdentityMap<BotRemoveEvent, Removal> EVENTS = new WeakIdentityMap<>();

    private OrgNativePlayerMessages() {
    }

    public static void place(net.minecraft.server.MinecraftServer server, ServerBot bot, net.minecraft.server.level.ServerLevel world, org.bukkit.Location position, net.minecraft.world.level.storage.ValueInput input, boolean silence) {
        if (silence) JOINS.computeIfAbsent(bot, ignored -> true);
        try {
            server.getBotList().placeNewBot(bot, world, position, input);
        } catch (Throwable failure) {
            Boolean flag = JOINS.get(bot);
            if (flag != null) JOINS.remove(bot, flag);
            throw failure;
        }
    }

    public static boolean consumeJoin(ServerBot bot) {
        Boolean flag = JOINS.get(bot);
        if (flag != null) JOINS.remove(bot, flag);
        return Boolean.TRUE.equals(flag);
    }

    public static CompletableFuture<Boolean> remove(ServerBot bot, boolean silence, Supplier<CompletableFuture<Boolean>> nativeRemoval) {
        if (silence) LEAVES.computeIfAbsent(bot, ignored -> true);
        CompletableFuture<Boolean> actual;
        try {
            actual = nativeRemoval.get();
        } catch (Throwable failure) {
            clearLeave(bot);
            return CompletableFuture.failedFuture(failure);
        }
        ScarpetNativeWork.record(actual);
        actual.whenComplete((value, failure) -> clearLeave(bot));
        var caller = actual.copy();
        ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }

    private static void clearLeave(ServerBot bot) {
        Boolean flag = LEAVES.get(bot);
        if (flag != null) LEAVES.remove(bot, flag);
    }

    public static void prepared(ServerBot bot, BotRemoveEvent event) {
        Boolean flag = LEAVES.get(bot);
        boolean silence = Boolean.TRUE.equals(flag);
        clearLeave(bot);
        boolean keepTab = OrgPlayerManager.reloginKeepTab(bot);
        if (silence || keepTab) EVENTS.computeIfAbsent(event, ignored -> new Removal(silence, keepTab));
    }

    public static boolean keepTab(BotRemoveEvent event) {
        Removal flag = EVENTS.get(event);
        return flag != null && flag.keepTab();
    }

    public static boolean consumeLeave(BotRemoveEvent event) {
        Removal flag = EVENTS.get(event);
        if (flag != null) EVENTS.remove(event, flag);
        return flag != null && flag.silence();
    }
}
