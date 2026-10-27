package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "projectile")
public class ProjectileChunkReduceConfig {
    @ConfigInfo(name = "max-loads-per-tick")
    public static final WorldValues.Key<Integer> maxProjectileLoadsPerTick = WorldValues.key(0);
    @ConfigInfo(name = "max-loads-per-projectile")
    public static final WorldValues.Key<Integer> maxProjectileLoadsPerProjectile = WorldValues.key(0);
}
