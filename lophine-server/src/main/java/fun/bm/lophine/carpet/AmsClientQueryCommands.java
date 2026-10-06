// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.AmsNetworkProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AmsClientQueryCommands {
    private static final Object PING_LIFECYCLE = new Object();
    private static final Map<PingOwner, PingJob> PINGS = new ConcurrentHashMap<>();
    private static final carpet.script.external.WeakIdentityMap<net.minecraft.server.MinecraftServer, Boolean> STOPPED_SERVERS = new carpet.script.external.WeakIdentityMap<>();

    private record PingOwner(net.minecraft.server.MinecraftServer server, java.util.UUID player, Object output) {
        static PingOwner of(CommandSourceStack source) {
            var entity = source.getEntity();
            return new PingOwner(source.getServer(), entity == null ? null : entity.getUUID(), entity == null ? source.source : null);
        }

        @Override public boolean equals(Object other) {
            return other instanceof PingOwner owner && server == owner.server && java.util.Objects.equals(player, owner.player) && output == owner.output;
        }

        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(server) + java.util.Objects.hashCode(player)) + System.identityHashCode(output);
        }
    }

    private static final class PingJob {
        volatile boolean stopped;
        Thread worker;
        volatile java.util.concurrent.CompletableFuture<Void> reply = java.util.concurrent.CompletableFuture.completedFuture(null);
        final java.util.concurrent.CompletableFuture<Void> actual = new java.util.concurrent.CompletableFuture<>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };

        synchronized void stop() {
            stopped = true;
            if (worker != null) worker.interrupt();
        }
    }

    private AmsClientQueryCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("getClientPlayerFps")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandGetClientPlayerFps))
                .then(Commands.argument("player", EntityArgument.player()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> AmsNetworkProtocol.requestFps(context.getSource(), EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("help").executes(context -> AmsNativeCommandEffects.command(context, ignored -> tell(context.getSource(), "command.getClientPlayerFps.help")))));
        dispatcher.register(Commands.literal("pings")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandPacketInternetGroper))
                .then(Commands.argument("targetIpOrDomainName", StringArgumentType.string())
                        .then(Commands.argument("pingQuantity", IntegerArgumentType.integer()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> ping(context.getSource(), StringArgumentType.getString(context, "targetIpOrDomainName"), IntegerArgumentType.getInteger(context, "pingQuantity"))))))
                .then(Commands.literal("stop").executes(context -> AmsNativeCommandEffects.command(context, ignored -> stop(context.getSource()))))
                .then(Commands.literal("help").executes(context -> AmsNativeCommandEffects.command(context, ignored -> {
                    tell(context.getSource(), "command.ping.help.ping");
                    return tell(context.getSource(), "command.ping.help.stop");
                }))));
        dispatcher.register(Commands.literal("amsp")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandAmspDebug))
                .then(Commands.literal("show")
                        .then(Commands.literal("supportClientList").executes(context -> AmsNativeCommandEffects.command(context, ignored -> showClients(context.getSource()))))
                        .then(Commands.literal("serverSupportStatus").executes(context -> AmsNativeCommandEffects.command(context, ignored -> {
                            boolean supported = AmsNetworkProtocol.serverSupport();
                            return tellColored(context.getSource(), supported ? ChatFormatting.GREEN : ChatFormatting.RED, "command.amsp.server_support_status", Boolean.toString(supported));
                        })))
                        .then(Commands.literal("clientModVersion").then(Commands.argument("player", EntityArgument.player()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> AmsNetworkProtocol.requestVersion(context.getSource(), EntityArgument.getPlayer(context, "player"))))))
                        .then(Commands.literal("serverModVersion").executes(context -> AmsNativeCommandEffects.command(context, ignored -> tellColored(context.getSource(), ChatFormatting.AQUA, "command.amsp.server_mod_version_feedback", AmsNetworkProtocol.IMPLEMENTATION, AmsNetworkProtocol.VERSION)))))
                .then(Commands.literal("deny")
                        .then(Commands.literal("clientConnection").then(Commands.argument("player", EntityArgument.player()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> {
                            ServerPlayer target = EntityArgument.getPlayer(context, "player");
                            AmsNetworkProtocol.deny(target.getUUID());
                            return tellColored(context.getSource(), ChatFormatting.LIGHT_PURPLE, "command.amsp.deny_client_feedback", target.getGameProfile().name());
                        }))))
                        .then(Commands.literal("all").executes(context -> AmsNativeCommandEffects.command(context, ignored -> {
                            AmsNetworkProtocol.denyAll();
                            return tellColored(context.getSource(), ChatFormatting.LIGHT_PURPLE, "command.amsp.deny_all_client_feedback");
                        }))))
                .then(Commands.literal("set").then(Commands.literal("serverSupport")
                        .then(Commands.argument("boolean", BoolArgumentType.bool()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> {
                            boolean value = BoolArgumentType.getBool(context, "boolean");
                            AmsNetworkProtocol.setServerSupport(value);
                            return tellColored(context.getSource(), ChatFormatting.GREEN, "command.amsp.set_server_support_feedback", Boolean.toString(value));
                        })))))
                .then(Commands.literal("request").then(Commands.literal("handshake")
                        .executes(context -> AmsNativeCommandEffects.command(context, ignored -> {
                            CommandSourceStack source = context.getSource();
                            AmsNativeCommandEffects.receipt(AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(source.getServer(), () -> List.copyOf(source.getServer().getPlayerList().getPlayers())), all -> requestHandshakesAsync(source, all.iterator())));
                            return 1;
                        }))
                        .then(Commands.argument("players", EntityArgument.players()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> requestHandshakes(context.getSource(), EntityArgument.getPlayers(context, "players"))))))));
    }

    private static int tell(CommandSourceStack source, String key, Object... args) {
        AmsNativeCommandEffects.reply(source, () -> AmsNetworkProtocol.send(source, AmsTranslations.message(source, key, args)));
        return 1;
    }

    private static int tellColored(CommandSourceStack source, ChatFormatting color, String key, Object... args) {
        AmsNativeCommandEffects.reply(source, () -> AmsNetworkProtocol.send(source, AmsTranslations.message(source, key, args).withStyle(color)));
        return 1;
    }

    private static int showClients(CommandSourceStack source) {
        var clients = AmsNetworkProtocol.supportClients();
        if (clients.isEmpty()) {
            tellColored(source, ChatFormatting.YELLOW, "command.amsp.support_client_set_is_none");
            return 0;
        }
        tellColored(source, ChatFormatting.AQUA, "command.amsp.support_client_list_title");
        clients.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                AmsNativeCommandEffects.reply(source, () -> AmsNetworkProtocol.send(source, Component.literal(entry.getKey() + " - " + entry.getValue().name()).withStyle(ChatFormatting.AQUA))));
        return 1;
    }

    private static int requestHandshakes(CommandSourceStack source, java.util.Collection<ServerPlayer> players) {
        AmsNativeCommandEffects.receipt(requestHandshakesAsync(source, players.iterator()));
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<Void> requestHandshakesAsync(CommandSourceStack source, java.util.Iterator<ServerPlayer> players) {
        return AmsNativeCommandEffects.sequence(source.getServer(), players, target -> AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () -> {
            AmsNetworkProtocol.requestHandshake(target);
            return target.getGameProfile().name();
        }), name ->
                AmsNativeCommandEffects.source(source, () -> {
                    AmsNetworkProtocol.send(source, AmsTranslations.message(source, "command.amsp.request_handshake_feedback", name).withStyle(ChatFormatting.GREEN));
                    return (Void) null;
                })));
    }

    private static int ping(CommandSourceStack source, String target, int quantity) {
        PingOwner owner = PingOwner.of(source);
        PingJob job = new PingJob();
        carpet.script.external.ScarpetNativeWork.record(job.actual);
        carpet.script.external.ScarpetNativeWork.trackNative(source.getServer(), job.actual);
        job.worker = Thread.ofVirtual().name("AMS pings").unstarted(() -> {
            Throwable problem = null;
            try {
                if (job.stopped) return;
                int success = 0, lost = 0;
                long total = 0;
                for (int i = 0; i < quantity; ++i) {
                    if (job.stopped) return;
                    long delay = pingAttempt(job, source, target, i == 0);
                    if (job.stopped) return;
                    // Preserve pinned AMS's returned-zero timeout/error accounting.
                    if (delay >= 0) {
                        ++success;
                        total += delay;
                    } else ++lost;
                    Thread.sleep(1000L);
                }
                if (!job.stopped) pingReply(job, source, Component.literal("<commandPacketInternetGroper> Sent = " + quantity + ", Received = " + success + ", Lost = " + lost + ", Average delay = " + (success > 0 ? total / success : 0) + "ms").withStyle(ChatFormatting.GREEN));
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                problem = failure;
            } finally {
                PINGS.remove(owner, job);
                Throwable workerFailure = problem;
                // Interrupting the worker does not erase an already accepted source reply.
                job.reply.whenComplete((ignored, failure) -> {
                    Throwable actualFailure = workerFailure == null ? failure : workerFailure;
                    if (actualFailure == null) job.actual.complete(null);
                    else job.actual.completeExceptionally(actualFailure);
                });
            }
        });
        synchronized (PING_LIFECYCLE) {
            if (STOPPED_SERVERS.get(source.getServer()) != null || carpet.script.external.ScarpetNativeWork.isDraining(source.getServer())) {
                job.actual.completeExceptionally(new IllegalStateException("AMS ping server is stopping"));
                return 1;
            }
            PingJob previous = PINGS.put(owner, job);
            if (previous != null) previous.stop();
            try {
                job.worker.start();
            } catch (Throwable failure) {
                PINGS.remove(owner, job);
                job.actual.completeExceptionally(failure);
            }
        }
        return 1;
    }

    private static void pingReply(PingJob job, CommandSourceStack source, Component message) throws InterruptedException, java.util.concurrent.ExecutionException {
        java.util.concurrent.CompletableFuture<Void> actual;
        synchronized (job) {
            if (job.stopped) return;
            actual = AmsNetworkProtocol.sendAsync(source, message);
            job.reply = actual;
        }
        actual.get();
    }

    private static long pingAttempt(PingJob job, CommandSourceStack source, String target, boolean first) throws InterruptedException, java.util.concurrent.ExecutionException {
        try {
            InetAddress address = InetAddress.getByName(target);
            if (job.stopped) return 0;
            if (first)
                pingReply(job, source, Component.literal("<commandPacketInternetGroper> Ping " + target + " [ " + address.getHostAddress() + " ] ...").withStyle(ChatFormatting.AQUA));
            if (job.stopped) return 0;
            long start = System.currentTimeMillis();
            boolean reachable = address.isReachable(5000);
            long delay = System.currentTimeMillis() - start;
            if (job.stopped) return 0;
            if (reachable) {
                pingReply(job, source, Component.literal("<commandPacketInternetGroper> Replay from [ " + address.getHostAddress() + " ] Time = " + delay + "ms").withStyle(ChatFormatting.GREEN));
                return delay;
            }
            pingReply(job, source, Component.literal("<commandPacketInternetGroper> Request time out.").withStyle(ChatFormatting.RED));
            return 0;
        } catch (java.io.IOException failure) {
            org.slf4j.LoggerFactory.getLogger("AMS-pings").error("[commandPacketInternetGroper] An error occurred while performing ping operation", failure);
            return 0;
        }
    }

    private static int stop(CommandSourceStack source) {
        PingJob job = PINGS.get(PingOwner.of(source));
        if (job == null) return tell(source, "command.ping.active_ping_is_null");
        job.stop();
        carpet.script.external.ScarpetNativeWork.record(job.actual);
        return tell(source, "command.ping.stop_ping");
    }

    public static void stopAtShutdown() {
        synchronized (PING_LIFECYCLE) {
            var server = net.minecraft.server.MinecraftServer.getServer();
            if (server != null) STOPPED_SERVERS.put(server, Boolean.TRUE);
            PINGS.forEach((owner, job) -> {
                STOPPED_SERVERS.put(owner.server(), Boolean.TRUE);
                job.stop();
            });
            PINGS.clear();
        }
        AmsNetworkProtocol.denyAll();
    }
}
