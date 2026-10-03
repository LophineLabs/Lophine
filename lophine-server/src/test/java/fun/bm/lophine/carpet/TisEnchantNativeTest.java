package fun.bm.lophine.carpet;

import carpet.script.external.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.server.commands.EnchantCommand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TisEnchantNativeTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private boolean previous;
    private final MinecraftServer server=mock(MinecraftServer.class);
    private final ServerLevel world=mock(ServerLevel.class);
    private final Entity sourceEntity=mock(Entity.class);
    private final CommandSourceStack source=mock(CommandSourceStack.class);
    private final CommandResultCallback callback=mock(CommandResultCallback.class);
    private final Enchantment enchantment=mock(Enchantment.class);
    private final Holder<Enchantment> holder=Holder.direct(enchantment);
    private final List<String> order=new ArrayList<>();
    private final List<Component> messages=new ArrayList<>();
    private final List<Component> failures=new ArrayList<>();
    private final AtomicReference<Entity> running=new AtomicReference<>();
    @BeforeEach void setup(){
        previous=GeneralCompatConfig.enchantCommandNoRestriction;GeneralCompatConfig.enchantCommandNoRestriction=true;
        when(world.getServer()).thenReturn(server);bind(sourceEntity);
        when(source.getServer()).thenReturn(server);when(source.getLevel()).thenReturn(world);when(source.getEntity()).thenReturn(sourceEntity);
        when(source.getPosition()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);when(source.callback()).thenReturn(callback);
        when(enchantment.getMaxLevel()).thenAnswer(call->{order.add("max");return 2;});
        doAnswer(call->{messages.add(((Supplier<Component>)call.getArgument(0)).get());order.add("success");return null;}).when(source).sendSuccess(any(),eq(true));
        doAnswer(call->{failures.add(call.getArgument(0));order.add("failure");return null;}).when(source).sendFailure(any());
    }
    @AfterEach void restore(){GeneralCompatConfig.enchantCommandNoRestriction=previous;}
    private void bind(Entity entity){when(entity.level()).thenReturn(world);when(entity.blockPosition()).thenReturn(BlockPos.ZERO);when(entity.position()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);}
    private LivingEntity target(String name,ItemStack item){
        LivingEntity target=mock(LivingEntity.class);bind(target);
        when(target.getMainHandItem()).thenAnswer(call->{order.add(name+":item");return item;});
        when(target.getDisplayName()).thenAnswer(call->{order.add(name+":name");return Component.literal(name);});return target;
    }
    private ItemStack item(String name){
        ItemStack item=mock(ItemStack.class);when(item.isEmpty()).thenReturn(false);
        when(enchantment.canEnchant(item)).thenAnswer(call->{order.add(name+":can");return false;});
        doAnswer(call->{order.add(name+":enchant");return null;}).when(item).enchant(holder,100);return item;
    }
    private org.mockito.MockedStatic<TickThread> owned(){var ticks=mockStatic(TickThread.class);ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);ticks.when(TickThread::isTickThread).thenReturn(true);return ticks;}
    private org.mockito.MockedStatic<EnchantmentHelper> helpers(ItemStack... items){
        var helpers=mockStatic(EnchantmentHelper.class);ItemEnchantments old=mock(ItemEnchantments.class);when(old.keySet()).thenReturn(Set.of(holder));
        for(ItemStack item:items)helpers.when(()->EnchantmentHelper.getEnchantmentsForCrafting(item)).thenAnswer(call->{order.add("crafting");return old;});
        helpers.when(()->EnchantmentHelper.isEnchantmentCompatible(anyCollection(),eq(holder))).thenAnswer(call->{assertTrue(((Collection<?>)call.getArgument(0)).isEmpty());order.add("compatible-empty");return true;});return helpers;
    }
    private int invoke(List<? extends Entity> targets)throws Exception{Method m=EnchantCommand.class.getDeclaredMethod("enchant",CommandSourceStack.class,Collection.class,Holder.class,int.class);m.setAccessible(true);try{return(int)m.invoke(null,source,targets,holder,100);}catch(InvocationTargetException e){throw(Exception)e.getCause();}}
    private org.mockito.MockedStatic<Enchantment> fullname(){var names=mockStatic(Enchantment.class);names.when(()->Enchantment.getFullname(holder,100)).thenReturn(Component.literal("ench100"));return names;}
    private static String key(Component message){return((net.minecraft.network.chat.contents.TranslatableContents)message.getContents()).getKey();}
    @Test void realProducerEvaluatesOriginalExpressionsAndReportsActualSingleCount()throws Exception{
        ItemStack item=item("first");LivingEntity target=target("first",item);
        try(var ticks=owned();var helpers=helpers(item);var names=fullname();var scope=CarpetAsyncCommandResults.open()){
            assertEquals(1,invoke(List.of(target)));assertEquals(1,scope.resultFuture(source).join());
            assertEquals(List.of("max","first:item","first:can","crafting","compatible-empty","first:enchant","first:name","success"),order);
            verify(callback).onResult(true,1);assertEquals("commands.enchant.success.single",key(messages.getFirst()));
            verify(item).enchant(same(holder),eq(100));
        }
    }
    @Test void targetsWaitNativeChildrenInInputOrderAndFeedbackWaitsItsOwnChildren()throws Exception{
        ItemStack first=item("first"),second=item("second");LivingEntity a=target("first",first),b=target("second",second);
        var child=new CompletableFuture<Void>();var feedbackChild=new CompletableFuture<Void>();
        doAnswer(call->{order.add("first:enchant");ScarpetNativeWork.record(child);return null;}).when(first).enchant(holder,100);
        doAnswer(call->{messages.add(((Supplier<Component>)call.getArgument(0)).get());ScarpetNativeWork.record(feedbackChild);return null;}).when(source).sendSuccess(any(),eq(true));
        try(var ticks=owned();var helpers=helpers(first,second);var names=fullname();var scope=CarpetAsyncCommandResults.open()){
            invoke(List.of(a,b));verify(b,never()).getMainHandItem();verifyNoInteractions(callback);assertFalse(scope.resultFuture(source).isDone());
            child.complete(null);verify(second).enchant(holder,100);assertEquals("commands.enchant.success.multiple",key(messages.getFirst()));verifyNoInteractions(callback);
            feedbackChild.complete(null);assertEquals(2,scope.resultFuture(source).join());verify(callback).onResult(true,2);
        }
    }
    @Test void singleItemlessUsesOriginalErrorAndFalseResult()throws Exception{
        ItemStack empty=mock(ItemStack.class);when(empty.isEmpty()).thenReturn(true);LivingEntity target=target("empty",empty);
        try(var ticks=owned();var scope=CarpetAsyncCommandResults.open()){
            invoke(List.of(target));assertEquals(0,scope.resultFuture(source).join());verify(callback).onResult(false,0);
            assertEquals("commands.enchant.failed.itemless",key(failures.getFirst()));assertTrue(messages.isEmpty());
        }
    }
    @Test void singleNonLivingUsesOriginalErrorAndMixedCollectionCountsOnlyChanged()throws Exception{
        Entity nonLiving=mock(Entity.class);bind(nonLiving);when(nonLiving.getDisplayName()).thenReturn(Component.literal("nonliving"));
        try(var ticks=owned();var scope=CarpetAsyncCommandResults.open()){
            invoke(List.of(nonLiving));assertEquals(0,scope.resultFuture(source).join());assertEquals("commands.enchant.failed.entity",key(failures.getFirst()));
        }
        failures.clear();ItemStack item=item("changed");LivingEntity changed=target("changed",item);
        try(var ticks=owned();var helpers=helpers(item);var names=fullname();var scope=CarpetAsyncCommandResults.open()){
            assertEquals(2,invoke(List.of(nonLiving,changed)));assertEquals(1,scope.resultFuture(source).join());assertEquals("commands.enchant.success.single",key(messages.getFirst()));
        }
    }
    @Test void guestOnlyFailureKeepsRawParentFailureAndRealResultAfterNativeChild()throws Exception{
        ItemStack first=item("first"),second=item("second");LivingEntity a=target("first",first),b=target("second",second);
        var guest=new CompletableFuture<Void>();var child=new CompletableFuture<Void>();var scopeResult=new AtomicReference<CompletableFuture<Integer>>();
        doAnswer(call->{ScarpetNativeWork.record(guest);ScarpetNativeWork.record(child);return null;}).when(first).enchant(holder,100);
        try(var ticks=owned();var helpers=helpers(first,second);var names=fullname();var scope=CarpetAsyncCommandResults.open()){
            var raw=ScarpetNativeWork.observeNative(sourceEntity,()->{try{invoke(List.of(a,b));scopeResult.set(scope.resultFuture(source));}catch(Exception e){throw new RuntimeException(e);}return null;});
            Throwable failure=new IllegalStateException("guest");Method mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);guest.completeExceptionally(failure);
            verify(b,never()).getMainHandItem();child.complete(null);assertEquals(2,scopeResult.get().join());
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,raw::join)));verify(callback).onResult(true,2);
        }
    }
    @Test void nativeFailureStopsLaterTargetAndSuccessFeedback()throws Exception{
        ItemStack first=item("first"),second=item("second");LivingEntity a=target("first",first),b=target("second",second);var child=new CompletableFuture<Void>();
        doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(first).enchant(holder,100);
        try(var ticks=owned();var helpers=helpers(first,second);var names=fullname();var scope=CarpetAsyncCommandResults.open()){
            var raw=ScarpetNativeWork.observeNative(sourceEntity,()->{try{invoke(List.of(a,b));}catch(Exception e){throw new RuntimeException(e);}return null;});
            child.completeExceptionally(new IllegalStateException("native"));assertEquals(0,scope.resultFuture(source).join());
            verify(b,never()).getMainHandItem();assertTrue(messages.isEmpty());assertTrue(failures.isEmpty());verify(callback).onResult(false,0);
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,raw::join)));
        }
    }
    @Test void cancellationCannotFinishActualCommandOrCallbackChild()throws Exception{
        ItemStack item=item("first");LivingEntity target=target("first",item);var child=new CompletableFuture<Void>();var callbackChild=new CompletableFuture<Void>();
        doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(item).enchant(holder,100);doAnswer(call->{ScarpetNativeWork.record(callbackChild);return null;}).when(callback).onResult(true,1);
        try(var ticks=owned();var helpers=helpers(item);var names=fullname();var scope=CarpetAsyncCommandResults.open()){
            invoke(List.of(target));scope.resultFuture(source).cancel(false);assertFalse(scope.completionFuture().isDone());child.complete(null);
            assertFalse(scope.completionFuture().isDone());assertFalse(ScarpetNativeWork.whenIdle(server).isDone());callbackChild.complete(null);
            scope.completionFuture().join();ScarpetNativeWork.whenIdle(server).join();verify(callback).onResult(true,1);
        }
    }
    @Test void foreignActualSchedulerRunsTargetAndSourceFeedbackOnTheirRealOwners()throws Exception{
        ItemStack item=item("foreign");LivingEntity target=target("foreign",item);var queue=new ArrayDeque<Runnable>();
        for(Entity actor:List.of(target,sourceEntity)){
            org.bukkit.craftbukkit.entity.CraftEntity craft=actor instanceof LivingEntity ? mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class) : mock(org.bukkit.craftbukkit.entity.CraftEntity.class);when(actor.getBukkitEntity()).thenReturn(craft);
            var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);Field field=org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");field.setAccessible(true);field.set(craft,scheduler);
            when(scheduler.schedule(any(),any(),eq(1L))).thenAnswer(call->{Consumer<Entity> task=call.getArgument(0);queue.add(()->{running.set(actor);task.accept(actor);running.set(null);});return true;});
        }
        when(target.getMainHandItem()).thenAnswer(call->{assertSame(target,running.get());return item;});
        doAnswer(call->{assertSame(target,running.get());return null;}).when(item).enchant(holder,100);
        doAnswer(call->{assertSame(sourceEntity,running.get());messages.add(((Supplier<Component>)call.getArgument(0)).get());return null;}).when(source).sendSuccess(any(),eq(true));
        try(var ticks=mockStatic(TickThread.class);var helpers=helpers(item);var names=fullname();var scope=CarpetAsyncCommandResults.open()){
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call->running.get()==call.getArgument(0));
            invoke(List.of(target));assertTrue(messages.isEmpty());while(!queue.isEmpty())queue.remove().run();
            assertEquals(1,scope.resultFuture(source).join());verify(callback).onResult(true,1);assertEquals(1,messages.size());
        }
    }
}

