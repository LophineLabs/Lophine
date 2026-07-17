package me.earthme.luminol.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

public class EntityMoveOutOfRegionException extends RuntimeException {
    private final Entity entity;
    private final Vec3 movement;
    private final MoverType moverType;

    public EntityMoveOutOfRegionException(Entity entity, Vec3 movement, MoverType moverType) {
        this.entity = entity;
        this.movement = movement;
        this.moverType = moverType;
    }


    public Entity getEntity() {
        return this.entity;
    }

    public Vec3 getMovement() {
        return this.movement;
    }

    public MoverType getMoverType() {
        return this.moverType;
    }
}
