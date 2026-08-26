package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "projectile")
public class ProjectileChunkReduceConfig {
    @ConfigInfo(name = "max-loads-per-tick")
    public static int maxProjectileLoadsPerTick;
    @ConfigInfo(name = "max-loads-per-projectile")
    public static int maxProjectileLoadsPerProjectile;
}