// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.AmsNetworkProtocol;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class AmsClientQueryCommands {
    private static final Map<CommandSourceStack,PingJob> PINGS=new ConcurrentHashMap<>();
    private static final class PingJob{volatile boolean stopped;Thread worker;final java.util.concurrent.CompletableFuture<Void> actual=new java.util.concurrent.CompletableFuture<>(){@Override public boolean cancel(boolean interrupt){return false;}};}
    private AmsClientQueryCommands() {}

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
                    boolean supported=AmsNetworkProtocol.serverSupport();
                    return tellColored(context.getSource(),supported?ChatFormatting.GREEN:ChatFormatting.RED,"command.amsp.server_support_status",Boolean.toString(supported));
                })))
                .then(Commands.literal("clientModVersion").then(Commands.argument("player", EntityArgument.player()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> AmsNetworkProtocol.requestVersion(context.getSource(), EntityArgument.getPlayer(context, "player"))))))
                .then(Commands.literal("serverModVersion").executes(context -> AmsNativeCommandEffects.command(context, ignored -> tellColored(context.getSource(),ChatFormatting.AQUA,"command.amsp.server_mod_version_feedback", AmsNetworkProtocol.IMPLEMENTATION, AmsNetworkProtocol.VERSION)))))
            .then(Commands.literal("deny")
                .then(Commands.literal("clientConnection").then(Commands.argument("player", EntityArgument.player()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> {
                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                    AmsNetworkProtocol.deny(target.getUUID());
                    return tellColored(context.getSource(),ChatFormatting.LIGHT_PURPLE,"command.amsp.deny_client_feedback", target.getGameProfile().name());
                }))))
                .then(Commands.literal("all").executes(context -> AmsNativeCommandEffects.command(context, ignored -> { AmsNetworkProtocol.denyAll(); return tellColored(context.getSource(),ChatFormatting.LIGHT_PURPLE,"command.amsp.deny_all_client_feedback"); }))))
            .then(Commands.literal("set").then(Commands.literal("serverSupport")
                .then(Commands.argument("boolean", BoolArgumentType.bool()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> {
                    boolean value = BoolArgumentType.getBool(context, "boolean");
                    AmsNetworkProtocol.setServerSupport(value);
                    return tellColored(context.getSource(),ChatFormatting.GREEN,"command.amsp.set_server_support_feedback", Boolean.toString(value));
                })))))
            .then(Commands.literal("request").then(Commands.literal("handshake")
                .executes(context -> AmsNativeCommandEffects.command(context, ignored -> {
                    CommandSourceStack source = context.getSource();
                    AmsNativeCommandEffects.receipt(AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(source.getServer(),()->List.copyOf(source.getServer().getPlayerList().getPlayers())),all->requestHandshakesAsync(source,all.iterator())));
                    return 1;
                }))
                .then(Commands.argument("players", EntityArgument.players()).executes(context -> AmsNativeCommandEffects.command(context, ignored -> requestHandshakes(context.getSource(), EntityArgument.getPlayers(context, "players"))))))));
    }

    private static int tell(CommandSourceStack source, String key, Object... args) {
        AmsNativeCommandEffects.reply(source,()->AmsNetworkProtocol.send(source, AmsTranslations.message(source, key, args)));
        return 1;
    }
    private static int tellColored(CommandSourceStack source,ChatFormatting color,String key,Object...args) {
        AmsNativeCommandEffects.reply(source,()->AmsNetworkProtocol.send(source,AmsTranslations.message(source,key,args).withStyle(color)));
        return 1;
    }
    private static int showClients(CommandSourceStack source) {
        var clients = AmsNetworkProtocol.supportClients();
        if (clients.isEmpty()) { tellColored(source,ChatFormatting.YELLOW,"command.amsp.support_client_set_is_none"); return 0; }
        tellColored(source,ChatFormatting.AQUA,"command.amsp.support_client_list_title");
        clients.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            AmsNativeCommandEffects.reply(source,()->AmsNetworkProtocol.send(source, Component.literal(entry.getKey() + " - " + entry.getValue().name()).withStyle(ChatFormatting.AQUA))));
        return 1;
    }
    private static int requestHandshakes(CommandSourceStack source,java.util.Collection<ServerPlayer> players){
        AmsNativeCommandEffects.receipt(requestHandshakesAsync(source,players.iterator()));return 1;
    }
    private static java.util.concurrent.CompletableFuture<Void> requestHandshakesAsync(CommandSourceStack source,java.util.Iterator<ServerPlayer> players){
        if(!players.hasNext())return java.util.concurrent.CompletableFuture.completedFuture(null);ServerPlayer target=players.next();
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target,()->{AmsNetworkProtocol.requestHandshake(target);return target.getGameProfile().name();}),name->
            AmsNativeCommandEffects.then(AmsNativeCommandEffects.source(source,()->{AmsNetworkProtocol.send(source,AmsTranslations.message(source,"command.amsp.request_handshake_feedback",name).withStyle(ChatFormatting.GREEN));return (Void)null;}),ignored->requestHandshakesAsync(source,players)));
    }

    private static int ping(CommandSourceStack source,String target,int quantity){
        PingJob previous=PINGS.get(source);if(previous!=null)previous.stopped=true;
        PingJob job=new PingJob();carpet.script.external.ScarpetNativeWork.record(job.actual);carpet.script.external.ScarpetNativeWork.trackNative(source.getServer(),job.actual);
        job.worker=Thread.ofVirtual().name("AMS pings").unstarted(()->{
            Throwable problem=null;
            try{
                int success=0,lost=0;long total=0;
                for(int i=0;i<quantity;++i){
                    if(job.stopped)return;
                    long delay=pingAttempt(source,target,i==0);
                    // Preserve pinned AMS's returned-zero timeout/error accounting.
                    if(delay>=0){++success;total+=delay;}else ++lost;
                    Thread.sleep(1000L);
                }
                AmsNetworkProtocol.sendAsync(source,Component.literal("<commandPacketInternetGroper> Sent = "+quantity+", Received = "+success+", Lost = "+lost+", Average delay = "+(success>0?total/success:0)+"ms").withStyle(ChatFormatting.GREEN)).join();
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            catch(Throwable failure){problem=failure;}
            finally{PINGS.remove(source,job);if(problem==null)job.actual.complete(null);else job.actual.completeExceptionally(problem);}
        });
        PINGS.put(source,job);try{job.worker.start();}catch(Throwable failure){PINGS.remove(source,job);job.actual.completeExceptionally(failure);}return 1;
    }
    private static long pingAttempt(CommandSourceStack source,String target,boolean first){
        try{
            InetAddress address=InetAddress.getByName(target);
            if(first)AmsNetworkProtocol.sendAsync(source,Component.literal("<commandPacketInternetGroper> Ping "+target+" [ "+address.getHostAddress()+" ] ...").withStyle(ChatFormatting.AQUA)).join();
            long start=System.currentTimeMillis();boolean reachable=address.isReachable(5000);long delay=System.currentTimeMillis()-start;
            if(reachable){AmsNetworkProtocol.sendAsync(source,Component.literal("<commandPacketInternetGroper> Replay from [ "+address.getHostAddress()+" ] Time = "+delay+"ms").withStyle(ChatFormatting.GREEN)).join();return delay;}
            AmsNetworkProtocol.sendAsync(source,Component.literal("<commandPacketInternetGroper> Request time out.").withStyle(ChatFormatting.RED)).join();return 0;
        }catch(java.io.IOException failure){org.slf4j.LoggerFactory.getLogger("AMS-pings").error("[commandPacketInternetGroper] An error occurred while performing ping operation",failure);return 0;}
    }
    private static int stop(CommandSourceStack source){
        PingJob job=PINGS.get(source);if(job==null)return tell(source,"command.ping.active_ping_is_null");
        job.stopped=true;carpet.script.external.ScarpetNativeWork.record(job.actual);return tell(source,"command.ping.stop_ping");
    }

    public static void stopAtShutdown() {
        PINGS.values().forEach(job->{job.stopped=true;if(job.worker!=null)job.worker.interrupt();});
        PINGS.clear();
        AmsNetworkProtocol.denyAll();
    }
}
