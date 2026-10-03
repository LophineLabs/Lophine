package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetRuntime;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.ServerBot;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;

class OrgActionScopeSourceTest {
    @Test void realAutomaticRestockAndBrokenGuardSkipInventoryReadsInsideOrgActionOnly() {
        var player=mock(ServerBot.class);var before=mock(ItemStack.class);var after=mock(ItemStack.class);boolean old=GeneralCompatConfig.fakePlayerAutoRestock;GeneralCompatConfig.fakePlayerAutoRestock=true;
        try {
            OrgGameplayHelper.withOrgAction(()->{assertFalse(OrgFakePlayerInventory.restock(player,before,after,InteractionHand.MAIN_HAND));OrgFakePlayerInventory.broken(player,before,EquipmentSlot.MAINHAND);});
            verifyNoInteractions(player,before,after);assertFalse(OrgGameplayHelper.insideOrgAction());
        } finally { GeneralCompatConfig.fakePlayerAutoRestock=old; }
    }
    @Test void actualNativeContinuationCapturesActionAndRestoresDifferentScopeAfterFailure() {
        var failure=new IllegalStateException("native action failure");var nativeBody=OrgGameplayHelper.withOrgAction(()->ScarpetRuntime.<Boolean>captureNativeContinuation(()->{assertTrue(OrgGameplayHelper.insideOrgAction());throw failure;}));
        assertFalse(OrgGameplayHelper.insideOrgAction());assertSame(failure,assertThrows(IllegalStateException.class,nativeBody::get));assertFalse(OrgGameplayHelper.insideOrgAction());
        var unrelated=ScarpetRuntime.captureNativeContinuation(OrgGameplayHelper::insideOrgAction);assertFalse(OrgGameplayHelper.withOrgAction(unrelated));assertFalse(OrgGameplayHelper.insideOrgAction());
    }
    @Test void interpreterInheritsRealActionWhileDetachedNewCommandDoesNot() throws Exception {
        var runtime=ScarpetRuntime.of(mock(net.minecraft.server.MinecraftServer.class));
        var value=OrgGameplayHelper.withOrgAction(()->runtime.submit(()->{assertTrue(OrgGameplayHelper.insideOrgAction());return ScarpetRuntime.captureNativeContinuation(OrgGameplayHelper::insideOrgAction).get();}));
        assertTrue(value.get(3,TimeUnit.SECONDS));assertFalse(OrgGameplayHelper.insideOrgAction());
        var detached=OrgGameplayHelper.withOrgAction(()->ScarpetRuntime.captureDetachedNativeContinuation(OrgGameplayHelper::insideOrgAction));assertFalse(detached.get());
    }
}
