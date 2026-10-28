package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "projectile")
public class ProjectileChunkReduceConfig {
    @ConfigInfo(name = "max-loads-per-tick")
    public static final WorldValue<Integer> maxProjectileLoadsPerTick = WorldValues.key(0);
    @ConfigInfo(name = "max-loads-per-projectile")
    public static final WorldValue<Integer> maxProjectileLoadsPerProjectile = WorldValues.key(0);
}
