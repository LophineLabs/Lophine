package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.Commands;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CarpetCommandTreePacketReceiptTest {
    @BeforeAll static void bootstrap()throws Exception{
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var config=new io.papermc.paper.configuration.GlobalConfiguration();config.misc=config.new Misc();
        try(var configurations=mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)){
            configurations.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);Class.forName("net.minecraft.network.Connection");
        }
    }
    @Test void actualEmptyCommandTreeWaitsItsPhysicalPacketAndLateNativeChildren()throws Exception{check(false);}
    @Test void actualEmptyCommandTreeKeepsPhysicalWriteFailure()throws Exception{check(true);}
    void check(boolean fail)throws Exception{
        int before=org.spigotmc.SpigotConfig.tabComplete;
        try(var ticks=mockStatic(TickThread.class)){
            org.spigotmc.SpigotConfig.tabComplete=-1;
            var player=mock(ServerPlayer.class);var server=mock(MinecraftServer.class);when(player.carpetSpawnServer()).thenReturn(server);
            var commands=mock(Commands.class,CALLS_REAL_METHODS);var connection=mock(ServerGamePacketListenerImpl.class);
            var wire=mock(net.minecraft.network.Connection.class);var channel=mock(io.netty.channel.Channel.class);var closing=mock(io.netty.channel.ChannelFuture.class);
            when(wire.isConnected()).thenReturn(true);when(channel.isOpen()).thenReturn(true);when(channel.closeFuture()).thenReturn(closing);
            var wireField=net.minecraft.network.Connection.class.getField("channel");wireField.setAccessible(true);wireField.set(wire,channel);
            var connectionField=net.minecraft.server.network.ServerCommonPacketListenerImpl.class.getField("connection");connectionField.setAccessible(true);connectionField.set(connection,wire);
            var field=ServerPlayer.class.getField("connection");field.setAccessible(true);field.set(player,connection);
            ticks.when(()->TickThread.isTickThreadFor(player)).thenReturn(true);
            var listener=new AtomicReference<io.netty.channel.ChannelFutureListener>();var child=new CompletableFuture<Void>();
            doAnswer(call->{listener.set(call.getArgument(1));ScarpetNativeWork.record(child);return null;}).when(connection).send(any(Packet.class),any(io.netty.channel.ChannelFutureListener.class));
            var actual=commands.carpetReloadCommands(player);assertFalse(actual.isDone());assertFalse(actual.cancel(false));assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            var sent=mock(io.netty.channel.ChannelFuture.class);when(sent.isSuccess()).thenReturn(!fail);var failure=new IllegalStateException("real command packet write failed");when(sent.cause()).thenReturn(failure);
            listener.get().operationComplete(sent);assertFalse(actual.isDone());child.complete(null);
            if(fail){assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS));}
            else {actual.get(3,TimeUnit.SECONDS);ScarpetNativeWork.whenIdle(server).get(3,TimeUnit.SECONDS);}
        }finally{org.spigotmc.SpigotConfig.tabComplete=before;}
    }
}
