package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "old-feature")
public class OldFeatureConfig {
    @ConfigInfo(name = "spawn_invulnerable_time", isWorldConfig = true)
    public static boolean spawnInvulnerableTime = false;

    @ConfigInfo(name = "old_zombie_reinforcement", isWorldConfig = true)
    public static boolean oldZombieReinforcement = false;

    @ConfigInfo(name = "old_leader_zombie_health", isWorldConfig = true)
    public static boolean oldLeaderZombieHealth = false;

    @ConfigInfo(name = "old_explosion_damage_calculator", isWorldConfig = true)
    public static boolean oldExplosionDamageCalculator = false;

    @ConfigInfo(name = "old_raid_behavior", isWorldConfig = true)
    public static boolean oldRaidBehavior = false;

    @ConfigInfo(name = "villager-void-trade", isWorldConfig = true)
    public static boolean villagerVoidTrade = false;

    @ConfigInfo(name = "should-generate-end-ring", isWorldConfig = true)
    public static boolean shouldGenerateEndRing = false;

    public static boolean spawnInvulnerableTime() {
        return WorldValues.get(spawnInvulnerableTime);
    }

    public static boolean oldZombieReinforcement() {
        return WorldValues.get(oldZombieReinforcement);
    }

    public static boolean oldLeaderZombieHealth() {
        return WorldValues.get(oldLeaderZombieHealth);
    }

    public static boolean oldExplosionDamageCalculator() {
        return WorldValues.get(oldExplosionDamageCalculator);
    }

    public static boolean oldRaidBehavior() {
        return WorldValues.get(oldRaidBehavior);
    }

    public static boolean villagerVoidTrade() {
        return WorldValues.get(villagerVoidTrade);
    }

    public static boolean shouldGenerateEndRing() {
        return WorldValues.get(shouldGenerateEndRing);
    }
}