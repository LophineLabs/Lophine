package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.*;

class CarpetFlyAndBlueSourceTest {
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static float invoke(Object target,Class<?> type,String name,Class<?>[] parameters,Object... arguments) {
        try {var method=type.getDeclaredMethod(name,parameters);method.setAccessible(true);return (Float)method.invoke(target,arguments);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static Player player(Abilities abilities){return mock(Player.class,call->{
        if(call.getMethod().getName().equals("getFlyingSpeed"))return 0.25F;
        if(call.getMethod().getName().equals("getAbilities"))return abilities;
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
    });}
    @Test void realDefaultFlightHeadsPreserveNativeResultWithoutReadingAbilityInjectionPredicates(){
        double oldSpeed=GeneralCompatConfig.creativeFlySpeed,oldDrag=GeneralCompatConfig.creativeFlyDrag;
        try {
            GeneralCompatConfig.creativeFlySpeed=1.0D;GeneralCompatConfig.creativeFlyDrag=0.09D;var player=player(new Abilities());
            assertEquals(0.25F,invoke(player,LivingEntity.class,"getFrictionInfluencedSpeed",new Class<?>[]{float.class},0.6F));
            assertEquals(0.91F,invoke(player,LivingEntity.class,"carpetAirDrag",new Class<?>[0]));verify(player,never()).getAbilities();verify(player,times(1)).onGround();
        } finally {GeneralCompatConfig.creativeFlySpeed=oldSpeed;GeneralCompatConfig.creativeFlyDrag=oldDrag;}
    }
    @Test void actualFlyingAndGroundedBranchesUseOriginalFloatCastAndNativeFallback(){
        double oldSpeed=GeneralCompatConfig.creativeFlySpeed,oldDrag=GeneralCompatConfig.creativeFlyDrag;
        try {
            GeneralCompatConfig.creativeFlySpeed=2.5D;GeneralCompatConfig.creativeFlyDrag=0.333333333333D;var abilities=new Abilities();abilities.flying=true;var player=player(abilities);
            assertEquals(0.625F,invoke(player,LivingEntity.class,"getFrictionInfluencedSpeed",new Class<?>[]{float.class},0.6F));assertEquals((float)(1.0D-GeneralCompatConfig.creativeFlyDrag),invoke(player,LivingEntity.class,"carpetAirDrag",new Class<?>[0]));
            when(player.onGround()).thenReturn(true);assertEquals(0.91F,invoke(player,LivingEntity.class,"carpetAirDrag",new Class<?>[0]));
        } finally {GeneralCompatConfig.creativeFlySpeed=oldSpeed;GeneralCompatConfig.creativeFlyDrag=oldDrag;}
    }
    @Test void realBlueRollReadsRuleBeforeSingleOriginalRandomDrawAndKeepsSourceDivision(){
        boolean old=GeneralCompatConfig.moreBlueSkulls;
        try {
            var wither=mock(WitherBoss.class);var random=mock(RandomSource.class);var field=Entity.class.getDeclaredField("random");field.setAccessible(true);field.set(wither,random);
            GeneralCompatConfig.moreBlueSkulls=true;when(random.nextFloat()).thenAnswer(call->{GeneralCompatConfig.moreBlueSkulls=false;return 0.1234567F;});
            assertEquals(0.1234567F/100.0F,invoke(wither,WitherBoss.class,"carpetBlueSkullRoll",new Class<?>[0]));
            when(random.nextFloat()).thenReturn(0.1234567F);assertEquals(0.1234567F,invoke(wither,WitherBoss.class,"carpetBlueSkullRoll",new Class<?>[0]));verify(random,times(2)).nextFloat();
        } catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        finally {GeneralCompatConfig.moreBlueSkulls=old;}
    }
}
