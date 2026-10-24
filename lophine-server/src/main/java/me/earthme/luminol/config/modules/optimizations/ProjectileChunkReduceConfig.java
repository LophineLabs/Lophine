package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.config.WorldValues;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "projectile")
public class ProjectileChunkReduceConfig {
    @ConfigInfo(name = "max-loads-per-tick", isWorldConfig = true)
    public static int maxProjectileLoadsPerTick;
    @ConfigInfo(name = "max-loads-per-projectile", isWorldConfig = true)
    public static int maxProjectileLoadsPerProjectile;

    public static int maxProjectileLoadsPerTick() {
        return WorldValues.get(maxProjectileLoadsPerTick);
    }

    public static int maxProjectileLoadsPerProjectile() {
        return WorldValues.get(maxProjectileLoadsPerProjectile);
    }
}