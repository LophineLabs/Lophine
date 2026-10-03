package fun.bm.lophine.carpet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class CarpetCreativeNoClipTest{
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void actualPortalRejectsCreativeFlightWhenEnabled(){portal(true,true,true,false);}
 @Test void actualPortalAllowsCreativeFlightWhenDisabled(){portal(false,true,true,true);}
 @Test void actualPortalAllowsCreativeOnGroundWhenEnabled(){portal(true,true,false,true);}
 @Test void actualPortalDoesNotTreatSurvivalFlyingAsCreativeNoClip(){portal(true,false,true,true);}
 @Test void productionPlacementPredicateNeverSkipsForNullPlayer(){assertFalse(CarpetCreativeNoClip.active(null));}
 private void portal(boolean enabled,boolean creative,boolean flying,boolean expected){
  var player=mock(ServerPlayer.class,CALLS_REAL_METHODS);doReturn(creative).when(player).isCreative();var ability=new Abilities();ability.flying=flying;doReturn(ability).when(player).getAbilities();doReturn(true).when(player).isAlive();doReturn(false).when(player).isSleeping();doReturn(false).when(player).isPassenger();
  boolean old=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeNoClip;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeNoClip=enabled;
  try{assertEquals(enabled&&creative&&flying,CarpetCreativeNoClip.active(player));assertEquals(expected,player.canUsePortal(false));}
  finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeNoClip=old;}
 }
}
