package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "old-feature")
public class OldFeatureConfig {
    @ConfigInfo(name = "spawn_invulnerable_time", isWorldConfig = true)
    public static boolean spawnInvulnerableTime = false;
    public static final WorldValues.Key<Boolean> SPAWN_INVULNERABLE_TIME = WorldValues.key();

    @ConfigInfo(name = "old_zombie_reinforcement", isWorldConfig = true)
    public static boolean oldZombieReinforcement = false;
    public static final WorldValues.Key<Boolean> OLD_ZOMBIE_REINFORCEMENT = WorldValues.key();

    @ConfigInfo(name = "old_leader_zombie_health", isWorldConfig = true)
    public static boolean oldLeaderZombieHealth = false;
    public static final WorldValues.Key<Boolean> OLD_LEADER_ZOMBIE_HEALTH = WorldValues.key();

    @ConfigInfo(name = "old_explosion_damage_calculator", isWorldConfig = true)
    public static boolean oldExplosionDamageCalculator = false;
    public static final WorldValues.Key<Boolean> OLD_EXPLOSION_DAMAGE_CALCULATOR = WorldValues.key();

    @ConfigInfo(name = "old_raid_behavior", isWorldConfig = true)
    public static boolean oldRaidBehavior = false;
    public static final WorldValues.Key<Boolean> OLD_RAID_BEHAVIOR = WorldValues.key();

    @ConfigInfo(name = "villager-void-trade", isWorldConfig = true)
    public static boolean villagerVoidTrade = false;
    public static final WorldValues.Key<Boolean> VILLAGER_VOID_TRADE = WorldValues.key();

    @ConfigInfo(name = "should-generate-end-ring", isWorldConfig = true)
    public static boolean shouldGenerateEndRing = false;
    public static final WorldValues.Key<Boolean> SHOULD_GENERATE_END_RING = WorldValues.key();

    public static boolean spawnInvulnerableTime() {
        return WorldValues.get(SPAWN_INVULNERABLE_TIME, spawnInvulnerableTime);
    }

    public static boolean oldZombieReinforcement() {
        return WorldValues.get(OLD_ZOMBIE_REINFORCEMENT, oldZombieReinforcement);
    }

    public static boolean oldLeaderZombieHealth() {
        return WorldValues.get(OLD_LEADER_ZOMBIE_HEALTH, oldLeaderZombieHealth);
    }

    public static boolean oldExplosionDamageCalculator() {
        return WorldValues.get(OLD_EXPLOSION_DAMAGE_CALCULATOR, oldExplosionDamageCalculator);
    }

    public static boolean oldRaidBehavior() {
        return WorldValues.get(OLD_RAID_BEHAVIOR, oldRaidBehavior);
    }

    public static boolean villagerVoidTrade() {
        return WorldValues.get(VILLAGER_VOID_TRADE, villagerVoidTrade);
    }

    public static boolean shouldGenerateEndRing() {
        return WorldValues.get(SHOULD_GENERATE_END_RING, shouldGenerateEndRing);
    }
}