package fun.bm.lophine.carpet;

import carpet.script.external.WeakIdentityMap;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgCustodyIdentityTest {
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }

    private static ServerPlayer player(int id) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        ServerPlayer player = (ServerPlayer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ServerPlayer.class);
        player.setId(id);
        return player;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> WeakIdentityMap<K, V> state(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        return (WeakIdentityMap<K, V>) field.get(null);
    }

    @Test void cursorRecoveryKeepsTheRealPlayerCustodyAcrossNetworkIdReuseAndMutation() throws Exception {
        ServerPlayer previous = player(41), fresh = player(41);
        assertEquals(previous, fresh);
        var oldInput = mock(ValueInput.class); var freshInput = mock(ValueInput.class);
        doReturn(Optional.of(List.of(new ItemStack(Items.DIAMOND, 5)))).when(oldInput).read(eq("CarpetOrgEscrowCursor"), any());
        doReturn(Optional.of(List.of(new ItemStack(Items.EMERALD, 3)))).when(freshInput).read(eq("CarpetOrgEscrowCursor"), any());
        OrgInventoryTransfers.loadCursor(previous, oldInput);
        OrgInventoryTransfers.loadCursor(fresh, freshInput);
        var recovered = OrgCustodyIdentityTest.<ServerPlayer, List<ItemStack>>state(OrgInventoryTransfers.class, "RECOVERED_CURSOR");
        try {
            previous.setId(42);
            assertTrue(recovered.get(previous).getFirst().is(Items.DIAMOND));
            assertEquals(5, recovered.get(previous).getFirst().getCount());
            assertTrue(recovered.get(fresh).getFirst().is(Items.EMERALD));
            recovered.remove(previous);
            assertEquals(3, recovered.get(fresh).getFirst().getCount());
        } finally { recovered.remove(previous); recovered.remove(fresh); }
    }

    @Test void deferredReturnsAndQuarantinedDescriptorsNeverMoveToAReplacementPlayer() throws Exception {
        ServerPlayer previous = player(52), fresh = player(52);
        var returning = OrgCustodyIdentityTest.<ServerPlayer, List<ItemStack>>state(OrgInventoryTransfers.class, "DEFERRED_RETURNS");
        var shadows = OrgCustodyIdentityTest.<ServerPlayer, List<CompoundTag>>state(OrgInventoryTransfers.class, "SHADOW_LOADS");
        var oldItems = List.of(new ItemStack(Items.DIAMOND, 11)); var freshItems = List.of(new ItemStack(Items.EMERALD, 7));
        var oldShadow = new CompoundTag(); oldShadow.putInt("slot", 1);
        var freshShadow = new CompoundTag(); freshShadow.putInt("slot", 4);
        returning.put(previous, oldItems); returning.put(fresh, freshItems);
        shadows.put(previous, List.of(oldShadow)); shadows.put(fresh, List.of(freshShadow));
        try {
            previous.setId(53);
            assertSame(oldItems, returning.get(previous)); assertSame(freshItems, returning.get(fresh));
            assertSame(oldShadow, shadows.get(previous).getFirst()); assertSame(freshShadow, shadows.get(fresh).getFirst());
            returning.remove(previous); shadows.remove(previous);
            assertSame(freshItems, returning.get(fresh)); assertSame(freshShadow, shadows.get(fresh).getFirst());
        } finally { returning.remove(previous); returning.remove(fresh); shadows.remove(previous); shadows.remove(fresh); }
    }

    @Test void pendingBlockDropReceiptsFollowTheExactNativeEntity() throws Exception {
        ServerPlayer previous = player(61), fresh = player(61);
        var pending = OrgCustodyIdentityTest.<Entity, CompletableFuture<Boolean>>state(OrgBlockDropRouting.class, "ADDS");
        var oldAdd = new CompletableFuture<Boolean>(); var freshAdd = new CompletableFuture<Boolean>();
        pending.put(previous, oldAdd); pending.put(fresh, freshAdd);
        try {
            previous.setId(62);
            assertSame(oldAdd, OrgBlockDropRouting.pendingNativeResult(previous));
            assertSame(freshAdd, OrgBlockDropRouting.pendingNativeResult(fresh));
            oldAdd.complete(false); freshAdd.complete(true);
            assertFalse(OrgBlockDropRouting.pendingResult(previous).join());
            assertTrue(OrgBlockDropRouting.pendingResult(fresh).join());
        } finally { pending.remove(previous); pending.remove(fresh); }
    }

    @Test void retiringAnOldPlayerDoesNotRemoveTheReplacementPlayersMailNotice() throws Exception {
        ServerPlayer previous = player(71), fresh = player(71);
        var noticed = OrgCustodyIdentityTest.<ServerPlayer, Boolean>state(OrgMailService.class, "NOTICED");
        noticed.putIfAbsent(previous, true); noticed.putIfAbsent(fresh, true);
        try {
            previous.setId(72); OrgMailService.retired(previous);
            assertNull(noticed.get(previous)); assertEquals(Boolean.TRUE, noticed.get(fresh));
        } finally { noticed.remove(previous); noticed.remove(fresh); }
    }
}
