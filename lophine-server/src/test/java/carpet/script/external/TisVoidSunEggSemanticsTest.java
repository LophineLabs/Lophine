package carpet.script.external;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.CraftWorld;
import org.junit.jupiter.api.*;

/** Actual NMS bodies verify source relative coordinates, skipped native ignition and complete goal timing/RNG. */
public class TisVoidSunEggSemanticsTest {
    @BeforeAll static void bootstrap(){ScarpetLootTablesTest.bootstrap();try{Items.EGG.builtInRegistryHolder().components();}catch(NullPointerException unbound){Items.EGG.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE,64).build());}}
    double priorAltitude;boolean priorSun,priorEgg;
    @BeforeEach void save(){priorAltitude=GeneralCompatConfig.voidRelatedAltitude;priorSun=GeneralCompatConfig.undeadDontBurnInSunlight;priorEgg=GeneralCompatConfig.turtleEggTrampledDisabled;}
    @AfterEach void restore(){GeneralCompatConfig.voidRelatedAltitude=priorAltitude;GeneralCompatConfig.undeadDontBurnInSunlight=priorSun;GeneralCompatConfig.turtleEggTrampledDisabled=priorEgg;}
    private static void field(Object actual,Class<?> type,String name,Object value)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(actual,value);}
    private static int goalCount(RemoveBlockGoal goal)throws Exception{Field field=RemoveBlockGoal.class.getDeclaredField("ticksSinceReachedGoal");field.setAccessible(true);return field.getInt(goal);}
    private static final class VoidFixture implements AutoCloseable{
        final ServerLevel world=mock(ServerLevel.class);final CraftWorld craft=mock(CraftWorld.class);final int[] calls={0};
        final Entity entity=mock(Entity.class,invocation->{if(invocation.getMethod().getName().equals("onBelowWorld")){++calls[0];return null;}return CALLS_REAL_METHODS.answer(invocation);});
        final org.mockito.MockedStatic<fun.bm.lophine.carpet.TisLifetimeTracker> lifetime=mockStatic(fun.bm.lophine.carpet.TisLifetimeTracker.class);
        VoidFixture(int minimum)throws Exception{field(entity,Entity.class,"level",world);when(world.getWorld()).thenReturn(craft);when(world.getMinY()).thenReturn(minimum);when(craft.isVoidDamageEnabled()).thenReturn(true);when(craft.getEnvironment()).thenReturn(org.bukkit.World.Environment.NORMAL);when(craft.getVoidDamageMinBuildHeightOffset()).thenReturn(-64.0);}
        void at(double y){doReturn(y).when(entity).getY();entity.checkBelowWorld();}
        @Override public void close(){lifetime.close();}
    }
    @Test void actualVoidThresholdUsesOriginalMinimumHeightAndRoundedRuleOffset()throws Exception{
        try(var f=new VoidFixture(-64)){GeneralCompatConfig.voidRelatedAltitude=-512.4;f.at(-575.9);f.at(-576);assertEquals(0,f.calls[0]);f.at(-576.1);assertEquals(1,f.calls[0]);}
        try(var f=new VoidFixture(0)){GeneralCompatConfig.voidRelatedAltitude=-512.6;f.at(-513);assertEquals(0,f.calls[0]);f.at(-513.1);assertEquals(1,f.calls[0]);}
    }
    @Test void originalNegativeInfinityRuleRetainsNativeIntegerClampBeforeRelativeCheck()throws Exception{
        try(var f=new VoidFixture(0)){GeneralCompatConfig.voidRelatedAltitude=Double.NEGATIVE_INFINITY;f.at(-2147483647.0);assertEquals(0,f.calls[0]);f.at(-2147483648.0);assertEquals(1,f.calls[0]);}
    }
    @Test void vanillaRuleValuePreservesExistingPaperWorldVoidOffset()throws Exception{
        try(var f=new VoidFixture(-64)){GeneralCompatConfig.voidRelatedAltitude=-64;when(f.craft.getVoidDamageMinBuildHeightOffset()).thenReturn(-100.0);f.at(-164);assertEquals(0,f.calls[0]);f.at(-165);assertEquals(1,f.calls[0]);}
    }
    private static Mob sunMob()throws Exception{
        var mob=mock(Mob.class,CALLS_REAL_METHODS);doReturn(true).when(mob).isAlive();doReturn(true).when(mob).isSunBurnTick();doReturn(ItemStack.EMPTY).when(mob).getItemBySlot(EquipmentSlot.HEAD);field(mob,Entity.class,"remainingFireTicks",-20);doNothing().when(mob).igniteForSeconds(anyFloat());return mob;
    }
    private static void burn(Mob mob)throws Exception{Method method=Mob.class.getDeclaredMethod("burnUndead");method.setAccessible(true);method.invoke(mob);}
    @Test void enabledSunRuleSkipsActualIgnitionForEveryMobThatReachesTheSourceBurnPath()throws Exception{
        GeneralCompatConfig.undeadDontBurnInSunlight=true;var mob=sunMob();burn(mob);verify(mob,never()).igniteForSeconds(anyFloat());assertEquals(-20,mob.getRemainingFireTicks());
    }
    @Test void disabledSunRuleRetainsOriginalEightSecondNativeIgnition()throws Exception{
        GeneralCompatConfig.undeadDontBurnInSunlight=false;var mob=sunMob();burn(mob);verify(mob).igniteForSeconds(8F);
    }
    private static final class GoalFixture{
        final ServerLevel world=mock(ServerLevel.class);final PathfinderMob mob=mock(PathfinderMob.class);final RandomSource random=mock(RandomSource.class);final RemoveBlockGoal goal;
        GoalFixture(Block target)throws Exception{
            when(mob.level()).thenReturn(world);when(mob.blockPosition()).thenReturn(BlockPos.ZERO);when(mob.position()).thenReturn(Vec3.atCenterOf(BlockPos.ZERO.above()));when(mob.getDeltaMovement()).thenReturn(Vec3.ZERO);when(mob.getRandom()).thenReturn(random);
            when(world.getBlockStateIfLoaded(BlockPos.ZERO)).thenReturn(target.defaultBlockState());goal=new RemoveBlockGoal(target,mob,1.0,1);field(goal,MoveToBlockGoal.class,"blockPos",BlockPos.ZERO);field(goal,RemoveBlockGoal.class,"ticksSinceReachedGoal",61);
        }
    }
    @Test void sourceEggGoalReductionRetainsFinalIncrementAndOriginalThreeRandomDraws()throws Exception{
        GeneralCompatConfig.turtleEggTrampledDisabled=true;var f=new GoalFixture(Blocks.TURTLE_EGG);f.goal.tick();assertEquals(32,goalCount(f.goal));verify(f.world,never()).removeBlock(any(),anyBoolean());verify(f.random,times(3)).nextFloat();verify(f.random,never()).nextGaussian();
    }
    @Test void originalGenericRemoveBlockGoalHookIsNotRestrictedToOneBlockType()throws Exception{
        GeneralCompatConfig.turtleEggTrampledDisabled=true;var f=new GoalFixture(Blocks.STONE);f.goal.tick();assertEquals(32,goalCount(f.goal));verify(f.world,never()).removeBlock(any(),anyBoolean());
    }
}
