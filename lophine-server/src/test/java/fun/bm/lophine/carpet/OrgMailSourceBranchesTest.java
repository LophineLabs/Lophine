package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OrgMailSourceBranchesTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private OrgMailPersistenceTest.Fixture fixture()throws Exception{var owner=new OrgMailPersistenceTest();owner.directory=directory;return owner.new Fixture();}
    private static TranslatableContents mail(Component value,String suffix){assertInstanceOf(TranslatableContents.class,value.getContents());var translated=(TranslatableContents)value.getContents();assertEquals("carpet-org-addition.command.mail."+suffix,translated.getKey());return translated;}
    private static void permission(Path directory,String value)throws Exception{Path file=directory.resolve("config/carpet-org-addition/permission.json");Files.createDirectories(file.getParent());Files.writeString(file,"{\"permission\":{\"mail.intercept\":\""+value+"\"}}");}
    @Test void actualNodePermitsNonOperatorInterceptWhileSourceListViewAndRecipientMaskRemainDistinct()throws Exception{
        String previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandMail;
        try(var f=fixture();var scope=CarpetAsyncCommandResults.open()){
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandMail="true";permission(directory,"true");
            var viewer=f.actors.viewer.player();var recipient=f.actors.target.player();var source=viewer.createCommandSourceStack();when(source.getPlayerOrException()).thenReturn(viewer);when(source.callback()).thenReturn(net.minecraft.commands.CommandResultCallback.EMPTY);
            var recipientSource=recipient.createCommandSourceStack();var sender=recipient.getBukkitEntity();when(recipientSource.getBukkitSender()).thenReturn(sender);when(sender.isPermissionSet("mail.intercept")).thenReturn(true);when(sender.hasPermission("mail.intercept")).thenReturn(false);
            var ops=f.actors.lookup.createSerializationContext(NbtOps.INSTANCE);Files.createDirectories(f.parcel(7).getParent());NbtIo.write(OrgMailService.document("external","recipient",f.actors.target.id(),List.of(new ItemStack(Items.DIAMOND,9)),ops),f.parcel(7));
            var rows=f.mail.list(viewer);f.pump();var row=mail(rows.join().getFirst(),"list.each");Component operation=(Component)row.getArgs()[3];mail(operation,"list.view");assertNull(operation.getStyle().getClickEvent());
            var late=new CompletableFuture<Void>();var messages=new ArrayList<Component>();doAnswer(call->{assertSame(recipient,f.actors.owner.get());messages.add(call.getArgument(0));ScarpetNativeWork.record(late);return null;}).when(recipient).sendSystemMessage(any(Component.class));
            var callbackCount=new java.util.concurrent.atomic.AtomicInteger();when(source.callback()).thenReturn((accepted,count)->{assertTrue(accepted);assertEquals(7,count);callbackCount.incrementAndGet();});
            var dispatcher=new CommandDispatcher<CommandSourceStack>();OrgMailCommands.register(dispatcher);
            var parent=ScarpetNativeWork.observeNative(viewer,()->{try{return dispatcher.execute("mail intercept 7",source);}catch(Exception failure){throw new AssertionError(failure);}});var view=scope.resultFuture(source);assertTrue(view.cancel(false));f.pump();
            assertTrue(NbtIo.read(f.parcel(7)).getListOrEmpty("items").isEmpty());assertEquals(0,callbackCount.get());assertFalse(parent.isDone());
            var notice=mail(messages.getLast(),"notice.intercept.recipient");Component masked=(Component)notice.getArgs()[0];assertEquals("carpet-org-addition.misc.operator",((TranslatableContents)masked.getContents()).getKey());assertTrue(messages.getLast().getStyle().isItalic());assertNotNull(messages.getLast().getStyle().getHoverEvent());
            late.complete(null);f.pump();parent.join();assertEquals(1,callbackCount.get());OrgServerPermissions.close(f.actors.server);
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandMail=previous;}
    }
    @Test void nativeRowsContainOperationLocalHoverTimeAndSourceMixedItemDisplay()throws Exception{
        OrgInventoryPersistenceTest.bootstrap();var ops=net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY).createSerializationContext(NbtOps.INSTANCE);
        var stacks=List.of(new ItemStack(Items.DIAMOND,5),new ItemStack(Items.EMERALD,3));var doc=OrgMailService.document("from","to",null,stacks,ops);
        Component row=OrgMailPresentation.line(18,doc,stacks,"collect");var translated=mail(row,"list.each");assertEquals(18,translated.getArgs()[0]);assertEquals(8,translated.getArgs()[2]);Component display=(Component)translated.getArgs()[1];assertTrue(display.getStyle().isItalic());assertEquals("carpet-org-addition.item.item",((TranslatableContents)display.getContents()).getKey());
        Component action=(Component)translated.getArgs()[3];assertEquals("/mail collect 18",((net.minecraft.network.chat.ClickEvent.RunCommand)action.getStyle().getClickEvent()).command());assertNull(row.getStyle().getHoverEvent());
        var hover=((HoverEvent.ShowText)action.getStyle().getHoverEvent()).value();var keys=hover.getSiblings().stream().filter(value->value.getContents() instanceof TranslatableContents).map(value->((TranslatableContents)value.getContents()).getKey()).toList();assertEquals(List.of("carpet-org-addition.command.mail.list.id","carpet-org-addition.command.mail.list.sender","carpet-org-addition.command.mail.list.recipient","carpet-org-addition.command.mail.list.item","carpet-org-addition.command.mail.list.time"),keys);
    }
    @Test void actualEmptyListReturnsZeroAndSourceInsufficientCapacityIsSuccessfulUnchangedParcel()throws Exception{
        String previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandMail;
        try(var f=fixture();var scope=CarpetAsyncCommandResults.open()){
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandMail="true";
            var viewer=f.actors.viewer.player();var source=viewer.createCommandSourceStack();when(source.getPlayerOrException()).thenReturn(viewer);when(source.callback()).thenReturn(net.minecraft.commands.CommandResultCallback.EMPTY);
            var messages=new ArrayList<Component>();doAnswer(call->{assertSame(viewer,f.actors.owner.get());messages.add(call.getArgument(0));return null;}).when(viewer).sendSystemMessage(any(Component.class));
            var dispatcher=new CommandDispatcher<CommandSourceStack>();OrgMailCommands.register(dispatcher);dispatcher.execute("mail list",source);f.pump();assertEquals(0,scope.resultFuture(source).join());mail(messages.getFirst(),"list.empty");
            var ops=f.actors.lookup.createSerializationContext(NbtOps.INSTANCE);Files.createDirectories(f.parcel(2).getParent());var original=OrgMailService.document("other","sender",f.actors.viewer.id(),List.of(new ItemStack(Items.DIAMOND,9)),ops);NbtIo.write(original,f.parcel(2));for(int index=0;index<36;index++)f.actors.viewer.inventory().setItem(index,new ItemStack(Items.EMERALD,64));
            var actual=f.mail.take(viewer,2,OrgMailService.Operation.COLLECT);f.pump();assertTrue(actual.join());mail(messages.getLast(),"collect.insufficient_capacity");assertEquals(original,NbtIo.read(f.parcel(2)));
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandMail=previous;}
    }
    @Test void actualJoinPromptFileOwnerAndLateMessageChildrenRemainOnTheRealNativeReceipt()throws Exception{
        try(var f=fixture()){
            var viewer=f.actors.viewer.player();var source=viewer.createCommandSourceStack();var ops=f.actors.lookup.createSerializationContext(NbtOps.INSTANCE);
            Files.createDirectories(f.parcel(3).getParent());NbtIo.write(OrgMailService.document("other","sender",f.actors.viewer.id(),List.of(new ItemStack(Items.DIAMOND,6)),ops),f.parcel(3));
            doAnswer(call->{viewer.sendSystemMessage(((java.util.function.Supplier<Component>)call.getArgument(0)).get());return null;}).when(source).sendSuccess(any(),eq(false));
            var messages=new ArrayList<Component>();var late=new CompletableFuture<Void>();doAnswer(call->{assertSame(viewer,f.actors.owner.get());messages.add(call.getArgument(0));ScarpetNativeWork.record(late);return null;}).when(viewer).sendSystemMessage(any(Component.class));
            var parent=ScarpetNativeWork.observeNative(viewer,()->{OrgMailService.observe(viewer);return null;});var idle=ScarpetNativeWork.whenIdle(f.actors.server);assertFalse(parent.isDone());assertFalse(idle.isDone());f.pump();
            var prompt=mail(messages.getLast(),"prompt_collect");assertEquals(6,prompt.getArgs()[0]);assertEquals("/mail collect 3",((net.minecraft.network.chat.ClickEvent.RunCommand)((Component)prompt.getArgs()[2]).getStyle().getClickEvent()).command());assertFalse(parent.isDone());assertFalse(idle.isDone());
            late.complete(null);f.pump();parent.join();idle.join();OrgMailService.retired(viewer);
        }
    }
    @Test void realVanillaDraftCloseWaitsPublicationAndActualRecipientNoticeTail()throws Exception{
        try(var f=fixture()){
            var method=OrgMailMultiplePersistenceTest.class.getDeclaredMethod("open",OrgMailPersistenceTest.Fixture.class);method.setAccessible(true);var previous=f.actors.viewer.player().containerMenu;
            var menu=(OrgMailDraftMenu)method.invoke(null,f);f.actors.owner.set(menu.player);menu.clicked(0,0,net.minecraft.world.inventory.ContainerInput.SWAP,menu.player);f.pump();
            var late=new CompletableFuture<Void>();doAnswer(call->{assertSame(f.actors.target.player(),f.actors.owner.get());ScarpetNativeWork.record(late);return null;}).when(f.actors.target.player()).sendSystemMessage(any(Component.class));
            f.actors.owner.set(menu.player);var parent=ScarpetNativeWork.observeNative(menu.player,()->{menu.removed(menu.player);menu.player.containerMenu=previous;return null;});var idle=ScarpetNativeWork.whenIdle(f.actors.server);assertFalse(parent.isDone());assertFalse(idle.isDone());f.pump();
            assertFalse(NbtIo.read(f.parcel(1)).getBooleanOr("_lophine_draft",false));assertFalse(parent.isDone());assertFalse(idle.isDone());late.complete(null);f.pump();parent.join();idle.join();
        }
    }
}
