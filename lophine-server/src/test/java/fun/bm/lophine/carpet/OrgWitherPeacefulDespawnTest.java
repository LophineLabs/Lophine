package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class OrgWitherPeacefulDespawnTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static WitherBoss wither(boolean persistent, boolean custom, boolean allowed) {
        var world = mock(ServerLevel.class);
        when(world.getDifficulty()).thenReturn(Difficulty.PEACEFUL);
        var type = mock(EntityType.class);
        when(type.isAllowedInPeaceful()).thenReturn(allowed);
        var wither = mock(WitherBoss.class);
        when(wither.level()).thenReturn(world);
        when(wither.getType()).thenReturn(type);
        when(wither.isPersistenceRequired()).thenReturn(persistent);
        when(wither.requiresCustomPersistence()).thenReturn(custom);
        doCallRealMethod().when(wither).checkDespawn();
        return wither;
    }

    @Test
    void namedAndCustomPersistentWithersKeepTheirActualPeacefulBody() {
        boolean old = GeneralCompatConfig.disableMobPeacefulDespawn;
        GeneralCompatConfig.disableMobPeacefulDespawn = true;
        try (var lifetime = mockStatic(TisLifetimeTracker.class)) {
            for (var entity : new WitherBoss[]{wither(true, false, false), wither(false, true, false)}) {
                entity.checkDespawn();
                verify(entity, never()).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);
            }
            lifetime.verifyNoInteractions();
        } finally {
            GeneralCompatConfig.disableMobPeacefulDespawn = old;
        }
    }

    @Test
    void ordinaryWithersStillUseTheRealDifficultyRemovalBranch() {
        boolean old = GeneralCompatConfig.disableMobPeacefulDespawn;
        GeneralCompatConfig.disableMobPeacefulDespawn = true;
        try (var lifetime = mockStatic(TisLifetimeTracker.class)) {
            var entity = wither(false, false, false);
            entity.checkDespawn();
            verify(entity).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);
            lifetime.verify(() -> TisLifetimeTracker.removed(entity, "despawn_difficulty"));
        } finally {
            GeneralCompatConfig.disableMobPeacefulDespawn = old;
        }
    }

    @Test
    void disablingTheRuleRetainsNativeRemovalAndAllowedEntityTypesKeepNativePermission() {
        boolean old = GeneralCompatConfig.disableMobPeacefulDespawn;
        GeneralCompatConfig.disableMobPeacefulDespawn = false;
        try (var lifetime = mockStatic(TisLifetimeTracker.class)) {
            var entity = wither(true, true, false);
            entity.checkDespawn();
            verify(entity).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);
            var allowed = wither(false, false, true);
            allowed.checkDespawn();
            verify(allowed, never()).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);
        } finally {
            GeneralCompatConfig.disableMobPeacefulDespawn = old;
        }
    }
}
