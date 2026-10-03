package carpet.script.external;
import java.lang.reflect.*;import java.util.*;
import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerLevel;import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;import net.minecraft.world.level.ServerLevelAccessor;import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
public class AmsPreventEndSpikeTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @AfterEach void reset(){GeneralCompatConfig.preventEndSpikeRespawn="false";}
 void place(String mode,boolean beam,boolean blocks,boolean crystal)throws Exception{
  GeneralCompatConfig.preventEndSpikeRespawn=mode;WorldGenLevel level=mock(WorldGenLevel.class);ServerLevel world=mock(ServerLevel.class);
  when(level.getLevel()).thenReturn(world);when(level.getMinY()).thenReturn(65);when(world.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
  when(level.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());var random=mock(RandomSource.class);
  try(var lifetime=mockStatic(fun.bm.lophine.carpet.TisLifetimeTracker.class);var entities=mockConstruction(EndCrystal.class,(value,context)->when(value.blockPosition()).thenReturn(new BlockPos(0,69,0)))){
   var feature=new EndSpikeFeature(List.of(),true,beam?Optional.of(new BlockPos(0,128,0)):Optional.empty());
   Method method=EndSpikeFeature.class.getDeclaredMethod("placeSpike",ServerLevelAccessor.class,RandomSource.class,EndSpikeFeature.EndSpike.class);method.setAccessible(true);
   method.invoke(feature,level,random,new EndSpikeFeature.EndSpike(0,0,1,68,true));
   if(blocks)verify(level,atLeastOnce()).setBlockAndUpdate(any(),any());else verify(level,never()).setBlockAndUpdate(any(),any());
   assertEquals(crystal?1:0,entities.constructed().size());
   if(crystal){verify(level).addFreshEntity(entities.constructed().getFirst());verify(entities.constructed().getFirst()).setBeamTarget(beam?new BlockPos(0,128,0):null);}
   else verify(level,never()).addFreshEntity(any());
  }
 }
 @Test void falseKeepsNativeBlocksAndCrystalOnInitialGeneration()throws Exception{place("false",false,true,true);}
 @Test void truePreventsInitialBlocksAndCrystalWithoutRequiringRespawnBeam()throws Exception{place("true",false,false,false);}
 @Test void keepEndCrystalSuppressesRespawnBlocksAndKeepsActualCrystal()throws Exception{place("keepEndCrystal",true,false,true);}
 @Test void nativeStringBindingAcceptsSourceModeAndRejectsWrongCaseAndUnknownValue(){
  var binding=fun.bm.lophine.carpet.CarpetRuleRegistry.get("preventEndSpikeRespawn");assertEquals(String.class,binding.field().getType());assertEquals("keepEndCrystal",binding.parse("keepEndCrystal"));
  assertThrows(IllegalArgumentException.class,()->binding.parse("keependcrystal"));assertThrows(IllegalArgumentException.class,()->binding.parse("unknown"));
 }
}
