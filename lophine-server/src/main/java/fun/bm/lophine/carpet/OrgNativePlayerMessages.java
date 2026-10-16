// SPDX-License-Identifier: MIT
// Source FakePlayerSpawner.SILENCE c2142c213269f85fb1851263bf60f147849224a1; Folia lifetime adapter.
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.WeakIdentityMap;
import fun.bm.lophine.carpet.CarpetFakePlayerManager.Removal;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * One actual join/removal carries source silence across async placement and actor retirement.
 */
public final class OrgNativePlayerMessages {
    private static final WeakIdentityMap<CarpetFakePlayer, Boolean> JOINS = new WeakIdentityMap<>(), LEAVES = new WeakIdentityMap<>();

    private record MessagePolicy(boolean silence, boolean keepTab) {
    }

    private static final WeakIdentityMap<Removal, MessagePolicy> EVENTS = new WeakIdentityMap<>();

    private OrgNativePlayerMessages() {
    }

    public static void place(net.minecraft.server.MinecraftServer server, CarpetFakePlayer bot, net.minecraft.server.level.ServerLevel world, net.minecraft.world.phys.Vec3 position, net.minecraft.world.phys.Vec2 rotation, net.minecraft.world.level.storage.ValueInput input, boolean silence) {
        if (silence) JOINS.computeIfAbsent(bot, ignored -> true);
        try {
            server.getCarpetFakePlayers().place(bot, world, position, rotation, input);
        } catch (Throwable failure) {
            Boolean flag = JOINS.get(bot);
            if (flag != null) JOINS.remove(bot, flag);
            throw failure;
        }
    }

    public static boolean consumeJoin(CarpetFakePlayer bot) {
        Boolean flag = JOINS.get(bot);
        if (flag != null) JOINS.remove(bot, flag);
        return Boolean.TRUE.equals(flag);
    }

    public static CompletableFuture<Boolean> remove(CarpetFakePlayer bot, boolean silence, Supplier<CompletableFuture<Boolean>> nativeRemoval) {
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

    private static void clearLeave(CarpetFakePlayer bot) {
        Boolean flag = LEAVES.get(bot);
        if (flag != null) LEAVES.remove(bot, flag);
    }

    public static void prepared(CarpetFakePlayer bot, Removal event) {
        Boolean flag = LEAVES.get(bot);
        boolean silence = Boolean.TRUE.equals(flag);
        clearLeave(bot);
        boolean keepTab = OrgPlayerManager.reloginKeepTab(bot);
        if (silence || keepTab) EVENTS.computeIfAbsent(event, ignored -> new MessagePolicy(silence, keepTab));
    }

    public static boolean keepTab(Removal event) {
        MessagePolicy flag = EVENTS.get(event);
        return flag != null && flag.keepTab();
    }

    public static boolean consumeLeave(Removal event) {
        MessagePolicy flag = EVENTS.get(event);
        if (flag != null) EVENTS.remove(event, flag);
        return flag != null && flag.silence();
    }
}
