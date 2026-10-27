package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "projectile")
public class ProjectileChunkReduceConfig {
    @ConfigInfo(name = "max-loads-per-tick", isWorldConfig = true)
    public static int maxProjectileLoadsPerTick;
    public static final WorldValues.Key<Integer> MAX_PROJECTILE_LOADS_PER_TICK = WorldValues.key();
    @ConfigInfo(name = "max-loads-per-projectile", isWorldConfig = true)
    public static int maxProjectileLoadsPerProjectile;
    public static final WorldValues.Key<Integer> MAX_PROJECTILE_LOADS_PER_PROJECTILE = WorldValues.key();

    public static int maxProjectileLoadsPerTick() {
        return WorldValues.get(MAX_PROJECTILE_LOADS_PER_TICK, maxProjectileLoadsPerTick);
    }

    public static int maxProjectileLoadsPerProjectile() {
        return WorldValues.get(MAX_PROJECTILE_LOADS_PER_PROJECTILE, maxProjectileLoadsPerProjectile);
    }
}