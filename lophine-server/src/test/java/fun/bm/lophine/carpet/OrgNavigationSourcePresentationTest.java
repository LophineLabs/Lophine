package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OrgNavigationSourcePresentationTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }
    static CommandSourceStack source(OrgInventoryPersistenceTest.Fixture fixture, java.util.List<Component> feedback, java.util.List<Component> packets) throws Exception {
        var source = mock(CommandSourceStack.class); var player = fixture.viewer.player();
        when(source.getServer()).thenReturn(fixture.server); when(source.getEntity()).thenReturn(player); when(source.getPlayer()).thenReturn(player);
        var world=player.level();when(source.getPlayerOrException()).thenReturn(player); when(source.getLevel()).thenReturn(world); when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
        when(source.getPosition()).thenReturn(new Vec3(0, 73, 0)); when(player.createCommandSourceStack()).thenReturn(source);
        for (var actor : java.util.List.of(fixture.viewer, fixture.target)) {
            when(actor.player().level().dimension()).thenReturn(Level.OVERWORLD);
            when(actor.player().getDisplayName()).thenAnswer(call -> { assertSame(actor.player(), fixture.owner.get()); return Component.literal("StyledName").withStyle(ChatFormatting.GOLD); });
            when(actor.player().blockPosition()).thenReturn(new BlockPos(0, 73, 0));
        }
        when(player.getEyeY()).thenReturn(73.5); when(player.getY()).thenReturn(73.0);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        doAnswer(call -> { assertSame(player, fixture.owner.get()); feedback.add(((java.util.function.Supplier<Component>)call.getArgument(0)).get()); return null; }).when(source).sendSuccess(any(),eq(false));
        doAnswer(call -> { assertSame(player, fixture.owner.get()); feedback.add(call.getArgument(0)); return null; }).when(source).sendFailure(any());
        doAnswer(call -> { assertSame(player, fixture.owner.get()); packets.add(((ClientboundSetActionBarTextPacket)call.getArgument(0)).text()); return null; }).when(player.connection).send(any(ClientboundSetActionBarTextPacket.class));
        return source;
    }
    static ArrayList<TranslatableContents> translations(Component component) {
        var values = new ArrayList<TranslatableContents>(); collect(component, values); return values;
    }
    private static void collect(Component component, java.util.List<TranslatableContents> values) {
        if(component.getContents() instanceof TranslatableContents translated) { values.add(translated); for(Object argument:translated.getArgs())if(argument instanceof Component child)collect(child,values); }
        component.getSiblings().forEach(child -> collect(child,values));
    }
    static TranslatableContents translation(Component component, String suffix) {
        return translations(component).stream().filter(value -> value.getKey().equals("carpet-org-addition.command.navigate."+suffix)).findFirst().orElseThrow();
    }
    @Test void realBlockCommandKeepsClickableColoredCoordinatesAndRendersUnnamedSourceHudSpacing() throws Exception {
        String previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate="true";
            var feedback=new ArrayList<Component>();var packets=new ArrayList<Component>();var source=source(fixture,feedback,packets);
            var dispatcher=new CommandDispatcher<CommandSourceStack>();OrgNavigation.register(dispatcher);
            assertEquals(1,dispatcher.execute("navigate blockPos 60 73 0",source));fixture.drain(fixture.viewer);assertEquals(1,scope.resultFuture(source).join());
            var start=translation(feedback.getFirst(),"start");assertEquals(net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.GOLD),((Component)start.getArgs()[0]).getStyle().getColor());
            Component position=(Component)start.getArgs()[1];assertEquals(net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.GREEN),position.getStyle().getColor());
            assertEquals("60 73 0",((net.minecraft.network.chat.ClickEvent.CopyToClipboard)position.getSiblings().getFirst().getStyle().getClickEvent()).value());
            fixture.owner.set(fixture.viewer.player());OrgNavigation.tick(fixture.viewer.player());fixture.drain(fixture.viewer);ScarpetNativeWork.whenIdle(fixture.server).join();
            Component hud=packets.getLast();assertFalse(translations(hud).stream().anyMatch(value->value.getKey().endsWith("hud.in")));
            assertEquals("<<  ",hud.getContents().visit(java.util.Optional::of).orElseThrow());
            assertEquals(60,translation(hud,"hud.distance").getArgs()[0]);assertEquals("    ",hud.getSiblings().getLast().getString());
            OrgNavigation.disconnected(fixture.viewer.player());
        } finally {fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate=previous;}
    }
    @Test void actualWaypointAssignmentKeepsSourceNameAndItalicAlternatePositionAcrossDimensions() throws Exception {
        String previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate="true";
            var feedback=new ArrayList<Component>();var packets=new ArrayList<Component>();var source=source(fixture,feedback,packets);
            var method=OrgNavigation.class.getDeclaredMethod("assignWaypoint",CommandSourceStack.class,net.minecraft.server.level.ServerPlayer.class,OrgWaypointStore.Waypoint.class,boolean.class);method.setAccessible(true);
            var waypoint=new OrgWaypointStore.Waypoint("Portal",new BlockPos(20,73,0),"minecraft:the_nether","","",new BlockPos(160,73,0));
            var actual=(CompletableFuture<Boolean>)method.invoke(null,source,fixture.viewer.player(),waypoint,false);fixture.drain(fixture.viewer);assertTrue(actual.join());
            assertEquals("[Portal]",((Component)translation(feedback.getFirst(),"start").getArgs()[1]).getString());
            fixture.owner.set(fixture.viewer.player());OrgNavigation.tick(fixture.viewer.player());fixture.drain(fixture.viewer);ScarpetNativeWork.whenIdle(fixture.server).join();
            var in=translation(packets.getLast(),"hud.in");assertEquals("Portal",((Component)in.getArgs()[0]).getString());assertTrue(((Component)in.getArgs()[1]).getStyle().isItalic());
            assertEquals(160,translation(packets.getLast(),"hud.distance").getArgs()[0]);OrgNavigation.disconnected(fixture.viewer.player());
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate=previous;}
    }
    @Test void missingDeathReportsSourceTargetComponentAndActualWorldPermissionDeniesCommandNode() throws Exception {
        String previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate="true";
            var feedback=new ArrayList<Component>();var packets=new ArrayList<Component>();var source=source(fixture,feedback,packets);
            when(fixture.viewer.player().getLastDeathLocation()).thenReturn(java.util.Optional.empty());
            var dispatcher=new CommandDispatcher<CommandSourceStack>();OrgNavigation.register(dispatcher);dispatcher.execute("navigate death",source);fixture.drain(fixture.viewer);assertEquals(0,scope.resultFuture(source).join());
            var missing=translation(feedback.getFirst(),"unable_to_find");assertEquals(net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.GOLD),((Component)missing.getArgs()[0]).getStyle().getColor());
            assertEquals("carpet-org-addition.command.navigate.name.death",((TranslatableContents)((Component)missing.getArgs()[1]).getContents()).getKey());
            OrgServerPermissions.close(fixture.server);var file=directory.resolve("config/carpet-org-addition/permission.json");java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,"{\"permission\":{\"navigate.death\":\"false\"}}");
            assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,()->dispatcher.execute("navigate death",source));OrgServerPermissions.close(fixture.server);
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate=previous;}
    }
    @Test void failedActualHudUpdateSendsSourceErrorAndRetainsItsPhysicalChildBeforeIdleCloses() throws Exception {
        String previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate="true";
            var feedback=new ArrayList<Component>();var packets=new ArrayList<Component>();var source=source(fixture,feedback,packets);
            var dispatcher=new CommandDispatcher<CommandSourceStack>();OrgNavigation.register(dispatcher);dispatcher.execute("navigate blockPos 60 73 0",source);fixture.drain(fixture.viewer);scope.resultFuture(source).join();
            var child=new CompletableFuture<Void>();var calls=new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call->{assertSame(fixture.viewer.player(),fixture.owner.get());if(calls.getAndIncrement()==0)throw new IllegalStateException("native packet rejected");packets.add(((ClientboundSetActionBarTextPacket)call.getArgument(0)).text());ScarpetNativeWork.record(child);return null;}).when(fixture.viewer.player().connection).send(any(ClientboundSetActionBarTextPacket.class));
            fixture.owner.set(fixture.viewer.player());OrgNavigation.tick(fixture.viewer.player());fixture.drain(fixture.viewer);
            var idle=ScarpetNativeWork.whenIdle(fixture.server);assertFalse(idle.isDone());assertNotNull(translation(packets.getLast(),"error"));
            child.complete(null);idle.join();OrgNavigation.disconnected(fixture.viewer.player());
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate=previous;}
    }
}
