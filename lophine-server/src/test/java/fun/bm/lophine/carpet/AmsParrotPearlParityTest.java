package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.lang.reflect.*;import java.nio.file.Path;import java.util.function.*;
import net.minecraft.server.level.*;import net.minecraft.world.entity.*;import net.minecraft.world.entity.animal.parrot.Parrot;import net.minecraft.world.entity.ai.goal.*;import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;import net.minecraft.world.item.*;import net.minecraft.world.level.*;import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;

public class AmsParrotPearlParityTest {
 @BeforeAll static void boot(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @TempDir Path directory;
 @AfterEach void reset(){GeneralCompatConfig.breedableParrots="none";GeneralCompatConfig.mitePearl=false;GeneralCompatConfig.notDamageEnderPearl=false;}
 static ItemStack stack(Item item)throws Exception{var constructor=ItemStack.class.getDeclaredConstructor(net.minecraft.core.Holder.class,int.class,net.minecraft.core.component.PatchedDataComponentMap.class);constructor.setAccessible(true);return constructor.newInstance(item.builtInRegistryHolder(),1,new net.minecraft.core.component.PatchedDataComponentMap(net.minecraft.core.component.DataComponentMap.EMPTY));}
 @Test void actualParrotUsesOriginalFullItemRegistryIdAndKeepsNoneDefault()throws Exception{
  var parrot=mock(Parrot.class,CALLS_REAL_METHODS);var apple=stack(Items.APPLE);GeneralCompatConfig.breedableParrots="minecraft:apple";assertTrue(parrot.isFood(apple));assertFalse(parrot.isFood(stack(Items.GOLDEN_APPLE)));GeneralCompatConfig.breedableParrots="apple";assertFalse(parrot.isFood(apple));GeneralCompatConfig.breedableParrots="none";assertFalse(parrot.isFood(apple));
 }
 @Test void actualGoalRegistrationAddsOriginalBreedGoalToTargetSelectorOnly()throws Exception{
  var parrot=mock(Parrot.class,CALLS_REAL_METHODS);var world=mock(ServerLevel.class);doReturn(world).when(parrot).level();var config=mock(io.papermc.paper.configuration.WorldConfiguration.class);config.entities=mock(io.papermc.paper.configuration.WorldConfiguration.Entities.class);config.entities.behavior=mock(io.papermc.paper.configuration.WorldConfiguration.Entities.Behavior.class);when(world.paperConfig()).thenReturn(config);var navigation=mock(FlyingPathNavigation.class);doReturn(navigation).when(parrot).getNavigation();var goals=mock(GoalSelector.class);var targets=mock(GoalSelector.class);for(var entry:java.util.Map.of("goalSelector",goals,"targetSelector",targets).entrySet()){Field field=Mob.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(parrot,entry.getValue());}
  GeneralCompatConfig.breedableParrots="minecraft:apple";var method=Parrot.class.getDeclaredMethod("registerGoals");method.setAccessible(true);method.invoke(parrot);verify(targets).addGoal(eq(1),isA(BreedGoal.class));verify(goals,never()).addGoal(eq(1),isA(BreedGoal.class));
 }
 @Test void actualFoliaPearlTeleportProducerRetainsOriginalWorldRandomDrawWhenEnabled()throws Exception{pearl(true);}
 @Test void actualFoliaPearlTeleportProducerKeepsDefaultChanceAndOriginalDraw()throws Exception{pearl(false);}
 void pearl(boolean enabled)throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   GeneralCompatConfig.mitePearl=enabled;GeneralCompatConfig.notDamageEnderPearl=true;var source=f.sourcePlayer;when(source.isAlive()).thenReturn(true);when(source.position()).thenReturn(Vec3.ZERO);when(source.getDeltaMovement()).thenReturn(Vec3.ZERO);var random=mock(net.minecraft.util.RandomSource.class);when(f.world.getRandom()).thenReturn(random);when(random.nextFloat()).thenAnswer(call->{assertSame(source,f.current);f.order.add("original world rng");return .9F;});when(f.world.isSpawningMonsters()).thenReturn(false);
   doAnswer(call->{assertSame(source,f.current);Consumer<Entity> completed=call.getArgument(7);completed.accept(source);return true;}).when(source).teleportAsync(eq(f.world),eq(Vec3.ZERO),isNull(),isNull(),eq(Vec3.ZERO),eq(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL),anyLong(),any());
   var method=net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl.class.getDeclaredMethod("attemptTeleport",Entity.class,ServerLevel.class,Vec3.class);method.setAccessible(true);method.invoke(null,source,f.world,Vec3.ZERO);f.drain();assertEquals(java.util.List.of("original world rng"),f.order);verify(random).nextFloat();verify(f.world,times(enabled?1:0)).isSpawningMonsters();
  }
 }
}
