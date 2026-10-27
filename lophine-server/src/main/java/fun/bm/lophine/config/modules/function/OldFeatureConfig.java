package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "old-feature")
public class OldFeatureConfig {
    @ConfigInfo(name = "spawn_invulnerable_time", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> spawnInvulnerableTime = WorldValues.key(false);

    @ConfigInfo(name = "old_zombie_reinforcement", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> oldZombieReinforcement = WorldValues.key(false);

    @ConfigInfo(name = "old_leader_zombie_health", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> oldLeaderZombieHealth = WorldValues.key(false);

    @ConfigInfo(name = "old_explosion_damage_calculator", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> oldExplosionDamageCalculator = WorldValues.key(false);

    @ConfigInfo(name = "old_raid_behavior", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> oldRaidBehavior = WorldValues.key(false);

    @ConfigInfo(name = "villager-void-trade", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> villagerVoidTrade = WorldValues.key(false);

    @ConfigInfo(name = "should-generate-end-ring", isWorldConfig = true)
    public static final WorldValues.Key<Boolean> shouldGenerateEndRing = WorldValues.key(false);

    public static boolean spawnInvulnerableTime() {
        return spawnInvulnerableTime.get();
    }

    public static boolean oldZombieReinforcement() {
        return oldZombieReinforcement.get();
    }

    public static boolean oldLeaderZombieHealth() {
        return oldLeaderZombieHealth.get();
    }

    public static boolean oldExplosionDamageCalculator() {
        return oldExplosionDamageCalculator.get();
    }

    public static boolean oldRaidBehavior() {
        return oldRaidBehavior.get();
    }

    public static boolean villagerVoidTrade() {
        return villagerVoidTrade.get();
    }

    public static boolean shouldGenerateEndRing() {
        return shouldGenerateEndRing.get();
    }
}
