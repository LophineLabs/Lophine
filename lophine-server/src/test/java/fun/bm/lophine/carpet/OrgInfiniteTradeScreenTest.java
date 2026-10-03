package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Org resets existing sold-out trades at native screen HEAD even when the actual screen is cancelled. */
class OrgInfiniteTradeScreenTest {
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static MerchantOffer soldOut(){return new MerchantOffer(new ItemCost(Items.EMERALD,1),java.util.Optional.empty(),new ItemStack(Items.DIAMOND),7,7,4,0.1f);}
    @Test void enablingAfterTradesSellOutRestocksBeforeTheActualCancelledScreenProvider(){
        boolean original=GeneralCompatConfig.villagerInfiniteTrade;GeneralCompatConfig.villagerInfiniteTrade=true;
        try{
            var first=soldOut();var second=soldOut();var offers=new MerchantOffers();offers.add(first);offers.add(second);
            var merchant=mock(Merchant.class,CALLS_REAL_METHODS);when(merchant.getOffers()).thenReturn(offers);var player=mock(ServerPlayer.class);
            when(player.openMenu(any(MenuProvider.class))).thenAnswer(call->{assertEquals(0,first.getUses());assertEquals(0,second.getUses());return java.util.OptionalInt.empty();});
            merchant.openTradingScreen(player,Component.literal("Trades"),3);assertFalse(first.isOutOfStock());assertFalse(second.isOutOfStock());
            first.increaseUses();assertEquals(0,first.getUses());verify(player,never()).sendMerchantOffers(anyInt(),any(MerchantOffers.class),anyInt(),anyInt(),anyBoolean(),anyBoolean());
        }finally{GeneralCompatConfig.villagerInfiniteTrade=original;}
    }
    @Test void disabledRulePreservesNativeSoldOutUsesWhenTheScreenIsCancelled(){
        boolean original=GeneralCompatConfig.villagerInfiniteTrade;GeneralCompatConfig.villagerInfiniteTrade=false;
        try{
            var offer=soldOut();var offers=new MerchantOffers();offers.add(offer);var merchant=mock(Merchant.class,CALLS_REAL_METHODS);when(merchant.getOffers()).thenReturn(offers);var player=mock(ServerPlayer.class);when(player.openMenu(any(MenuProvider.class))).thenReturn(java.util.OptionalInt.empty());
            merchant.openTradingScreen(player,Component.literal("Trades"),3);assertEquals(7,offer.getUses());assertTrue(offer.isOutOfStock());
        }finally{GeneralCompatConfig.villagerInfiniteTrade=original;}
    }
}
