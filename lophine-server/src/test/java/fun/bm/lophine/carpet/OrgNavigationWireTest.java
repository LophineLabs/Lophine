package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import com.mojang.brigadier.CommandDispatcher;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OrgNavigationWireTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    @Test void exactSourceCodecUsesThreeDoublesIdentifierAndFixedWidthIntWithoutCapabilityHandshake(){
        byte[] encoded=OrgNavigationProtocol.encode(new Vec3(1.25,-2.75,9.5),"custom:area",257);
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));try{assertEquals(1.25,buffer.readDouble());assertEquals(-2.75,buffer.readDouble());assertEquals(9.5,buffer.readDouble());assertEquals("custom:area",buffer.readIdentifier().toString());assertEquals(4,buffer.readableBytes());assertEquals(257,buffer.readInt());assertEquals(0,buffer.readableBytes());}finally{buffer.release();}
    }
    @Test void actualStartTickStopWireAndDefaultHighlightRetainRealPhysicalChildren()throws Exception{
        String previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate;
        try(var f=new OrgInventoryPersistenceTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate="true";
            var feedback=new ArrayList<Component>();var hud=new ArrayList<Component>();var source=OrgNavigationSourcePresentationTest.source(f,feedback,hud);var player=f.viewer.player();var packets=new ArrayList<DiscardedPayload>();var late=new CompletableFuture<Void>();
            doAnswer(call->{assertSame(player,f.owner.get());packets.add((DiscardedPayload)((ClientboundCustomPayloadPacket)call.getArgument(0)).payload());ScarpetNativeWork.record(late);return null;}).when(player.connection).send(any(ClientboundCustomPayloadPacket.class));
            var dispatcher=new CommandDispatcher<CommandSourceStack>();OrgNavigation.register(dispatcher);var parent=ScarpetNativeWork.observeNative(player,()->{try{return dispatcher.execute("navigate blockPos 60 73 0",source);}catch(Exception error){throw new AssertionError(error);}});var view=scope.resultFuture(source);assertTrue(view.cancel(false));f.drain(f.viewer);assertEquals(OrgNavigationProtocol.CLEAR,packets.getFirst().id());assertEquals(0,packets.getFirst().data().length);assertFalse(parent.isDone());
            late.complete(null);f.drain(f.viewer);parent.join();Component start=(Component)OrgNavigationSourcePresentationTest.translation(feedback.getFirst(),"start").getArgs()[1];assertEquals(" [H]",start.getSiblings().getLast().getString());assertEquals("/highlight 60 73 0",((net.minecraft.network.chat.ClickEvent.RunCommand)start.getSiblings().getLast().getStyle().getClickEvent()).command());
            f.owner.set(player);OrgNavigation.tick(player);f.drain(f.viewer);ScarpetNativeWork.whenIdle(f.server).join();assertEquals(1,packets.stream().filter(packet->packet.id().equals(OrgNavigationProtocol.UPDATE)).count());
            var update=new FriendlyByteBuf(Unpooled.wrappedBuffer(packets.getLast().data()));try{assertEquals(60.5,update.readDouble());assertEquals(73.5,update.readDouble());assertEquals(0.5,update.readDouble());assertEquals(Level.OVERWORLD.identifier(),update.readIdentifier());assertEquals(-1,update.readInt());}finally{update.release();}
            f.owner.set(player);OrgNavigation.tick(player);f.drain(f.viewer);assertEquals(1,packets.stream().filter(packet->packet.id().equals(OrgNavigationProtocol.UPDATE)).count());
            dispatcher.execute("navigate stop",source);f.drain(f.viewer);assertEquals(3,packets.stream().filter(packet->packet.id().equals(OrgNavigationProtocol.CLEAR)).count());OrgNavigation.disconnected(player);
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate=previous;}
    }
    @Test void sourceRespawnCopyRetainsPublishedTargetWithNewObserverAndOldDisconnectCannotEraseIt()throws Exception{
        String previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate;
        try(var f=new OrgInventoryPersistenceTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate="true";
            var feedback=new ArrayList<Component>();var hud=new ArrayList<Component>();var source=OrgNavigationSourcePresentationTest.source(f,feedback,hud);var old=f.viewer.player();var dispatcher=new CommandDispatcher<CommandSourceStack>();OrgNavigation.register(dispatcher);dispatcher.execute("navigate blockPos 60 73 0",source);f.drain(f.viewer);scope.resultFuture(source).join();
            var next=mock(ServerPlayer.class);var world=old.level();var identity=old.getUUID();when(next.getUUID()).thenReturn(identity);when(next.level()).thenReturn(world);when(next.blockPosition()).thenReturn(BlockPos.ZERO);when(next.getEyeY()).thenReturn(73.5);when(next.createCommandSourceStack()).thenReturn(source);next.connection=mock(ServerGamePacketListenerImpl.class);
            doAnswer(call->{assertSame(next,f.owner.get());hud.add(((net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket)call.getArgument(0)).text());return null;}).when(next.connection).send(any(net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket.class));
            f.owner.set(next);OrgNavigation.copyFrom(next,old);OrgNavigation.disconnected(old);OrgNavigation.tick(next);ScarpetNativeWork.whenIdle(f.server).join();assertNotNull(OrgNavigationSourcePresentationTest.translation(hud.getLast(),"hud.distance"));verify(next.connection,never()).send(any(ClientboundCustomPayloadPacket.class));OrgNavigation.disconnected(next);
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate=previous;}
    }
}
