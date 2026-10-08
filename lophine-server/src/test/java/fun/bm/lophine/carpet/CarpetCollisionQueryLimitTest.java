package fun.bm.lophine.carpet;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class CarpetCollisionQueryLimitTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void actualPositiveRuleUsesBoundedNativeQueryAndDoesNotCountPassengersPastItsCap() throws Exception {
        run(2, 4, 4, false);
    }

    @Test
    void actualBoundedCrammingAndPushCountMatchPinnedSource() throws Exception {
        run(2, 4, 0, true);
    }

    @Test
    void actualHigherCollisionLimitSetsQueryBoundToTheCollisionCount() throws Exception {
        run(6, 4, 0, true);
    }

    private void run(int rule, int cramming, int passengers, boolean damages) throws Exception {
        var living = mock(LivingEntity.class, CALLS_REAL_METHODS);
        var world = mock(ServerLevel.class);
        doReturn(world).when(living).level();
        doReturn(true).when(living).isPushable();
        doReturn(null).when(living).getTeam();
        var box = new AABB(0, 0, 0, 1, 1, 1);
        doReturn(box).when(living).getBoundingBox();
        var game = mock(GameRules.class);
        when(world.getGameRules()).thenReturn(game);
        when(game.get(GameRules.MAX_ENTITY_CRAMMING)).thenReturn(cramming);
        var random = mock(net.minecraft.util.RandomSource.class);
        when(random.nextInt(4)).thenReturn(0);
        var randomField = Entity.class.getDeclaredField("random");
        randomField.setAccessible(true);
        randomField.set(living, random);
        var source = mock(net.minecraft.world.damagesource.DamageSource.class);
        var sources = mock(net.minecraft.world.damagesource.DamageSources.class);
        doReturn(sources).when(living).damageSources();
        when(sources.cramming()).thenReturn(source);
        doReturn(true).when(living).hurtServer(world, source, 6F);
        var candidates = new ArrayList<Entity>();
        for (int i = 0; i < 10; i++) {
            var entity = mock(Entity.class);
            when(entity.isPassenger()).thenReturn(i < passengers);
            candidates.add(entity);
        }
        var bound = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            int limit = call.getArgument(4);
            bound.set(limit);
            List<Entity> into = call.getArgument(3);
            into.addAll(candidates.subList(0, Math.min(limit, candidates.size())));
            return null;
        }).when(world).getEntities(any(net.minecraft.world.level.entity.EntityTypeTest.class), eq(box), any(java.util.function.Predicate.class), anyList(), anyInt());
        var pushed = new ArrayList<Entity>();
        var push = LivingEntity.class.getDeclaredMethod("doPush", Entity.class);
        push.setAccessible(true);
        push.invoke(doAnswer(call -> {
            pushed.add(call.getArgument(0));
            return null;
        }).when(living), any(Entity.class));
        var method = LivingEntity.class.getDeclaredMethod("pushEntities");
        method.setAccessible(true);
        int previous = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.maxEntityCollisions;
        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.maxEntityCollisions = rule;
        try (var selectors = mockStatic(EntitySelector.class)) {
            selectors.when(() -> EntitySelector.pushableBy(living)).thenReturn((java.util.function.Predicate<Entity>) candidate -> true);
            method.invoke(living);
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.maxEntityCollisions = previous;
        }
        assertEquals(Math.max(rule, cramming), bound.get());
        assertEquals(candidates.subList(0, rule), pushed);
        verify(world, never()).getPushableEntities(any(), any());
        verify(living, damages ? times(1) : never()).hurtServer(world, source, 6F);
    }
}
