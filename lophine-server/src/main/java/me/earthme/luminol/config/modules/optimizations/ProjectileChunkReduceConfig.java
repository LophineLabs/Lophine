package me.earthme.luminol.config.modules.optimizations;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "projectile")
public class ProjectileChunkReduceConfig implements IConfigModule {
    @ConfigInfo(name = "max-loads-per-tick", comments = "Controls how many chunks are allowed to be sync loaded by projectiles in a tick.")
    public static int maxProjectileLoadsPerTick;
    @ConfigInfo(name = "max-loads-per-projectile", comments = "Controls how many chunks a projectile can load in its lifetime before it gets automatically removed.")
    public static int maxProjectileLoadsPerProjectile;
}