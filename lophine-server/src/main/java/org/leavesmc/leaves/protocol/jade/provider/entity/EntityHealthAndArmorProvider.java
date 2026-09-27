/*
 * This file is part of Lophine.
 *
 * It implements Jade's server-side absorption value contract for Minecraft 26.2.
 */

package org.leavesmc.leaves.protocol.jade.provider.entity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.EntityAccessor;
import org.leavesmc.leaves.protocol.jade.provider.StreamServerDataProvider;

/**
 * Sends the absorption amount that Jade uses when drawing entity health.
 */
public enum EntityHealthAndArmorProvider implements StreamServerDataProvider<EntityAccessor, Float> {
    INSTANCE;

    private static final Identifier UID = JadeProtocol.mc_id("entity_health");

    @Override
    public @Nullable Float streamData(EntityAccessor accessor) {
        if (!(accessor.getEntity() instanceof LivingEntity living)) {
            return null;
        }
        return Math.max(0.0F, living.getAbsorptionAmount());
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Float> streamCodec() {
        return ByteBufCodecs.FLOAT.cast();
    }

    @Override
    public Identifier getUid() {
        return UID;
    }

    @Override
    public int getDefaultPriority() {
        return -8000;
    }
}
