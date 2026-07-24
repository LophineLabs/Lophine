package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "old-feature")
public class OldFeatureConfig implements IConfigModule {
    @ConfigInfo(name = "spawn_invulnerable_time")
    public static boolean spawnInvulnerableTime = false;

    @ConfigInfo(name = "old_zombie_reinforcement")
    public static boolean oldZombieReinforcement = false;

    @ConfigInfo(name = "old_leader_zombie_health")
    public static boolean oldLeaderZombieHealth = false;

    @ConfigInfo(name = "old_explosion_damage_calculator")
    public static boolean oldExplosionDamageCalculator = false;

    @ConfigInfo(name = "old_raid_behavior")
    public static boolean oldRaidBehavior = false;

    @ConfigInfo(name = "villager-void-trade")
    public static boolean villagerVoidTrade = false;
}