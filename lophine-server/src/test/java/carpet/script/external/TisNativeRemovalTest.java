package carpet.script.external;

import fun.bm.lophine.carpet.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TisNativeRemovalTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private final MinecraftServer server=mock(MinecraftServer.class);
    private final ServerLevel world=mock(ServerLevel.class);
    private final ServerPlayer player=mock(ServerPlayer.class);
    private final List<String> order=new ArrayList<>();private boolean previous;
    @BeforeEach void setup(){previous=GeneralCompatConfig.creativeHitRemoveEntity;GeneralCompatConfig.creativeHitRemoveEntity=true;when(world.getServer()).thenReturn(server);bind(player);when(player.isCreative()).thenReturn(true);when(player.position()).thenReturn(Vec3.ZERO);}
    @AfterEach void restore(){GeneralCompatConfig.creativeHitRemoveEntity=previous;}
    private void bind(Entity entity){when(entity.level()).thenReturn(world);when(entity.blockPosition()).thenReturn(BlockPos.ZERO);when(entity.position()).thenReturn(Vec3.ZERO);}
    private <T extends Entity>T entity(Class<T> type,String name){T entity=mock(type);bind(entity);when(entity.isSpectator()).thenAnswer(call->{order.add(name+":filter");return false;});doAnswer(call->{order.add(name+":remove");return null;}).when(entity).carpetDiscardCleanly();return entity;}
    private org.mockito.MockedStatic<TickThread> owner(){var ticks=mockStatic(TickThread.class);ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);ticks.when(()->TickThread.isTickThreadFor(any(ServerLevel.class),any(BlockPos.class))).thenReturn(true);return ticks;}
    private org.mockito.MockedStatic<CarpetRegionLease> leases(){var lease=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();lease.when(()->CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any(Function.class))).thenAnswer(call->CompletableFuture.completedFuture(((Function)call.getArgument(5)).apply(null)));lease.when(()->CarpetRegionLease.runLoadedValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any(Function.class))).thenAnswer(call->CompletableFuture.completedFuture(((Function)call.getArgument(5)).apply(null)));return lease;}
    private void sword(boolean value){ItemStack item=mock(ItemStack.class);when(item.is(ItemTags.SWORDS)).thenReturn(value);when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(item);when(player.onGround()).thenReturn(true);}
    @Test void originalQueueFiltersAllInputsThenRootAndAppendedDragonPartsInSourceOrder(){
        EnderDragon dragon=entity(EnderDragon.class,"dragon");Entity other=entity(Entity.class,"other");EnderDragonPart first=entity(EnderDragonPart.class,"part1"),second=entity(EnderDragonPart.class,"part2");
        when(dragon.getSubEntities()).thenAnswer(call->{assertTrue(dragon.carpetCleanRemoval);order.add("parts");return new EnderDragonPart[]{first,second};});
        try(var ticks=owner()){
            assertEquals(4,ScarpetNativeEntityRemoval.removeAll(List.of(dragon,other)).join());
            assertEquals(List.of("dragon:filter","other:filter","parts","dragon:remove","other:remove","part1:remove","part2:remove"),order);
        }
    }
    @Test void originalQueueRetainsRepeatedPartIdentityAndDoesNotInventRemovedGuard(){
        EnderDragon dragon=entity(EnderDragon.class,"dragon");EnderDragonPart part=entity(EnderDragonPart.class,"part");when(dragon.getSubEntities()).thenReturn(new EnderDragonPart[]{part});when(part.isRemoved()).thenReturn(true);
        try(var ticks=owner()){
            assertEquals(3,ScarpetNativeEntityRemoval.removeAll(List.of(dragon,part)).join());verify(part,times(2)).carpetDiscardCleanly();
        }
    }
    @Test void actualCreativeSweepSelectsBeforeRemovingPrimaryFirstAndUsesOriginalWorldSound(){
        Entity primary=entity(Entity.class,"primary"),nearby=entity(Entity.class,"nearby");when(primary.getBoundingBox()).thenReturn(new AABB(0,0,0,1,1,1));when(world.getEntitiesOfClass(eq(Entity.class),any(AABB.class))).thenReturn(List.of(primary,player,nearby));
        sword(true);var child=new CompletableFuture<Void>();ServerLevel changedWorld=mock(ServerLevel.class);
        doAnswer(call->{order.add("primary:remove");when(player.level()).thenReturn(changedWorld);ScarpetNativeWork.record(child);return null;}).when(primary).carpetDiscardCleanly();
        doAnswer(call->{order.add("sound");return null;}).when(world).playSound(isNull(),anyDouble(),anyDouble(),anyDouble(),any(net.minecraft.sounds.SoundEvent.class),any(),eq(.7F),eq(.9F));
        try(var ticks=owner();var held=leases()){
            var actual=ScarpetNativeAttackHeads.creative(player,primary);verify(nearby,never()).carpetDiscardCleanly();verify(world,never()).playSound(isNull(),anyDouble(),anyDouble(),anyDouble(),any(net.minecraft.sounds.SoundEvent.class),any(),anyFloat(),anyFloat());
            child.complete(null);assertTrue(actual.join());assertTrue(order.indexOf("nearby:filter")<order.indexOf("primary:remove"));assertTrue(order.indexOf("primary:remove")<order.indexOf("nearby:remove"));assertEquals("sound",order.getLast());
            verify(changedWorld,never()).playSound(isNull(),anyDouble(),anyDouble(),anyDouble(),any(net.minecraft.sounds.SoundEvent.class),any(),anyFloat(),anyFloat());
        }
    }
    @Test void unsweepingSourceStillEvaluatesOriginalOnGroundRead(){
        Entity primary=entity(Entity.class,"primary");sword(false);
        try(var ticks=owner();var held=leases()){assertTrue(ScarpetNativeAttackHeads.creative(player,primary).join());verify(player).onGround();verify(player,never()).isSprinting();}
    }
    @Test void guestOnlyFailureKeepsRawParentAndProceedsToOriginalNextRemovalAfterNativeChild()throws Exception{
        Entity first=entity(Entity.class,"first"),second=entity(Entity.class,"second");var guest=new CompletableFuture<Void>();var child=new CompletableFuture<Void>();var actual=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Integer>>();
        doAnswer(call->{ScarpetNativeWork.record(guest);ScarpetNativeWork.record(child);return null;}).when(first).carpetDiscardCleanly();
        try(var ticks=owner()){
            var raw=ScarpetNativeWork.observeNative(null,()->{actual.set(ScarpetNativeEntityRemoval.removeAll(List.of(first,second)));return null;});
            Throwable failure=new IllegalStateException("guest");Method mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);guest.completeExceptionally(failure);
            verify(second,never()).carpetDiscardCleanly();child.complete(null);assertEquals(2,actual.get().join());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,raw::join)));
        }
    }
    @Test void nativeRemovalFailureBlocksLaterQueueAndSuccessTail(){
        Entity first=entity(Entity.class,"first"),second=entity(Entity.class,"second");var child=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(first).carpetDiscardCleanly();
        try(var ticks=owner()){
            var actual=ScarpetNativeEntityRemoval.removeAll(List.of(first,second));child.completeExceptionally(new IllegalStateException("native"));assertThrows(CompletionException.class,actual::join);verify(second,never()).carpetDiscardCleanly();
        }
    }
    @Test void realRemoveEntityProducerCountsDragonPartsAndWaitsSourceAndCallbackChildren()throws Exception{
        EnderDragon dragon=entity(EnderDragon.class,"dragon");EnderDragonPart part=entity(EnderDragonPart.class,"part");when(dragon.getSubEntities()).thenReturn(new EnderDragonPart[]{part});
        CommandSourceStack source=mock(CommandSourceStack.class);CommandResultCallback callback=mock(CommandResultCallback.class);when(source.getEntity()).thenReturn(player);when(source.getServer()).thenReturn(server);when(source.callback()).thenReturn(callback);
        var sourceChild=new CompletableFuture<Void>();var callbackChild=new CompletableFuture<Void>();var message=new java.util.concurrent.atomic.AtomicReference<net.minecraft.network.chat.Component>();
        doAnswer(call->{message.set(((Supplier<net.minecraft.network.chat.Component>)call.getArgument(0)).get());ScarpetNativeWork.record(sourceChild);return null;}).when(source).sendSuccess(any(),eq(true));doAnswer(call->{ScarpetNativeWork.record(callbackChild);return null;}).when(callback).onResult(true,2);
        try(var ticks=owner();var scope=CarpetAsyncCommandResults.open()){
            Method method=TisUtilityCommands.class.getDeclaredMethod("removeEntities",CommandSourceStack.class,Collection.class);method.setAccessible(true);assertEquals(1,method.invoke(null,source,List.of(dragon)));
            assertFalse(scope.resultFuture(source).isDone());verifyNoInteractions(callback);sourceChild.complete(null);verify(callback).onResult(true,2);assertFalse(scope.resultFuture(source).isDone());callbackChild.complete(null);assertEquals(2,scope.resultFuture(source).join());
            var content=(net.minecraft.network.chat.contents.TranslatableContents)message.get().getContents();assertEquals("carpettisaddition.command.removeentity.success",content.getKey());assertEquals(2,content.getArgs()[0]);
        }
    }
}
