package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgExpandedItemPickupTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    @SuppressWarnings("unchecked")
    @Test void crossRegionPickupWaitsForTrueSharedOwnershipAndTheActualNativeItemTail()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var preferences=mockStatic(OrgRulePlayerPreferences.class);var leases=mockStatic(CarpetRegionLease.class)){
            var player=fixture.viewer.player();ServerLevel world=player.level();AABB box=new AABB(15,60,15,16,62,16);when(player.getBoundingBox()).thenReturn(box);preferences.when(()->OrgRulePlayerPreferences.itemPickupRange(player)).thenReturn(3);
            var owns=new AtomicBoolean();fixture.ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),anyInt(),anyInt(),anyInt(),anyInt())).thenAnswer(call->owns.get());
            ItemEntity item=mock(ItemEntity.class);fixture.ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(item))).thenAnswer(call->owns.get());when(world.getEntities(eq(player),any(AABB.class),any())).thenReturn(List.of(item));
            var actualTail=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.record(actualTail);return null;}).when(item).playerTouch(player);
            var request=new CompletableFuture<CompletableFuture<Void>>();var action=new AtomicReference<Function<CarpetRegionLease.Lease<CompletableFuture<Void>>,CompletableFuture<Void>>>();
            leases.when(()->CarpetRegionLease.runLoadedValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any(Function.class))).thenAnswer(call->{action.set(call.getArgument(5));return request;});
            fixture.owner.set(player);OrgExpandedItemPickup.expand(player,box.inflate(1,0.5,1));verify(item,never()).playerTouch(any());var snapshot=ScarpetPlayerInventoryGate.whenIdle(player,()->7);fixture.drain(fixture.viewer);assertFalse(snapshot.isDone());
            owns.set(true);var lease=(CarpetRegionLease.Lease<CompletableFuture<Void>>)mock(CarpetRegionLease.Lease.class);when(lease.ownsAll()).thenReturn(true);request.complete(action.get().apply(lease));verify(item).playerTouch(player);assertFalse(snapshot.isDone());
            actualTail.complete(null);fixture.drain(fixture.viewer);assertEquals(7,snapshot.join());
        }
    }
    @Test void aPlayerMovingOutsideOwnedCurrentPickupBoundsCannotConsumeForeignItems()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var preferences=mockStatic(OrgRulePlayerPreferences.class)){
            var player=fixture.viewer.player();ServerLevel world=player.level();when(player.getBoundingBox()).thenReturn(new AABB(0,60,0,1,62,1));preferences.when(()->OrgRulePlayerPreferences.itemPickupRange(player)).thenReturn(3);
            fixture.ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world),anyInt(),anyInt(),anyInt(),anyInt())).thenReturn(true,false);
            fixture.owner.set(player);OrgExpandedItemPickup.expand(player,new AABB(0,60,0,1,62,1));verify(world,never()).getEntities(eq(player),any(AABB.class),any());
        }
    }
}
