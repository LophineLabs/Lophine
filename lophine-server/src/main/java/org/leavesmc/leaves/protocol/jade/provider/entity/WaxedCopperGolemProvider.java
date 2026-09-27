/*
 * This file is part of Lophine.
 *
 * It implements Jade's waxed Copper Golem marker using the Minecraft server APIs.
 */

package org.leavesmc.leaves.protocol.jade.provider.entity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.EntityAccessor;
import org.leavesmc.leaves.protocol.jade.provider.StreamServerDataProvider;

/**
 * Sends the presence marker Jade expects for a waxed Copper Golem.
 */
public enum WaxedCopperGolemProvider implements StreamServerDataProvider<EntityAccessor, Unit> {
    INSTANCE;

    private static final Identifier UID = JadeProtocol.mc_id("waxed");

    @Override
    public @Nullable Unit streamData(EntityAccessor accessor) {
        if (!(accessor.getEntity() instanceof CopperGolem golem)) {
            return null;
        }
        return golem.nextWeatheringTick == CopperGolem.IGNORE_WEATHERING_TICK ? Unit.INSTANCE : null;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Unit> streamCodec() {
        return Unit.STREAM_CODEC.cast();
    }

    @Override
    public Identifier getUid() {
        return UID;
    }
}
