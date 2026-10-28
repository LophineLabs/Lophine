package fun.bm.lophine.config.modules.function;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "old-feature")
public class OldFeatureConfig {
    @ConfigInfo(name = "spawn_invulnerable_time")
    public static final WorldValue<Boolean> spawnInvulnerableTime = WorldValues.key(false);

    @ConfigInfo(name = "old_zombie_reinforcement")
    public static final WorldValue<Boolean> oldZombieReinforcement = WorldValues.key(false);

    @ConfigInfo(name = "old_leader_zombie_health")
    public static final WorldValue<Boolean> oldLeaderZombieHealth = WorldValues.key(false);

    @ConfigInfo(name = "old_explosion_damage_calculator")
    public static final WorldValue<Boolean> oldExplosionDamageCalculator = WorldValues.key(false);

    @ConfigInfo(name = "old_raid_behavior")
    public static final WorldValue<Boolean> oldRaidBehavior = WorldValues.key(false);

    @ConfigInfo(name = "villager-void-trade")
    public static final WorldValue<Boolean> villagerVoidTrade = WorldValues.key(false);

    @ConfigInfo(name = "should-generate-end-ring")
    public static final WorldValue<Boolean> shouldGenerateEndRing = WorldValues.key(false);
}
