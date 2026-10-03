/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.protocol.servux.litematics.utils;

import com.google.common.collect.ImmutableList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import org.leavesmc.leaves.util.TagFactory;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

public class EntityUtils {

    @Nullable
    private static Entity createEntityFromNBTSingle(CompoundTag nbt, Level world) {
        try {
            Optional<Entity> optional = EntityType.create(TagFactory.input(nbt), world, new EntitySpawnRequest(EntitySpawnReason.LOAD, false));

            if (optional.isPresent()) {
                Entity entity = optional.get();
                entity.setUUID(UUID.randomUUID());
                return entity;
            }
        } catch (Exception ignore) {
        }

        return null;
    }

    /**
     * Note: This does NOT spawn any of the entities in the world!
     *
     * @param nbt   ()
     * @param world ()
     * @return ()
     */
    @Nullable
    public static Entity createEntityAndPassengersFromNBT(CompoundTag nbt, Level world) {
        Entity entity = createEntityFromNBTSingle(nbt, world);

        if (entity == null) {
            return null;
        }
        if (nbt.contains("Passengers")) {
            ListTag tagList = nbt.getListOrEmpty("Passengers");

            for (int i = 0; i < tagList.size(); ++i) {
                Entity passenger = createEntityAndPassengersFromNBT(tagList.getCompoundOrEmpty(i), world);

                if (passenger != null) {
                    passenger.startRiding(entity, true, true);
                }
            }
        }

        return entity;
    }

    public static void spawnEntityAndPassengersInWorld(Entity entity, Level world) {
        if (world instanceof net.minecraft.server.level.ServerLevel serverWorld) {
            carpetSpawnEntityAndPassengersNativeAsync(entity, serverWorld);
            return;
        }
        ImmutableList<Entity> passengers = entity.passengers;
        if (world.addFreshEntity(entity) && !passengers.isEmpty()) {
            for (Entity passenger : passengers) {
                passenger.snapTo(
                        entity.getX(),
                        entity.getY() + entity.getPassengerRidingPosition(passenger).y(),
                        entity.getZ(),
                        passenger.getYRot(), passenger.getXRot()
                );
                setEntityRotations(passenger, passenger.getYRot(), passenger.getXRot());
                spawnEntityAndPassengersInWorld(passenger, world);
            }
        }
    }

    /** Native root-add result governs all passengers; no queued false can discard the passenger tail. */
    public static java.util.concurrent.CompletableFuture<Void> carpetSpawnEntityAndPassengersNativeAsync(Entity entity, net.minecraft.server.level.ServerLevel originalWorld) {
        var actual = new java.util.concurrent.CompletableFuture<Void>();
        carpet.script.external.ScarpetNativeWork.record(actual);
        carpet.script.external.ScarpetNativeWork.trackNative(originalWorld.getServer(), actual);
        try {
            var body = carpetSpawnTree(entity, originalWorld);
            carpet.script.external.ScarpetNativeWork.aliasDependency(actual, body);
            body.whenComplete((ignored, failure) -> { if (failure == null) actual.complete(null); else actual.completeExceptionally(failure); });
        } catch (Throwable failure) { actual.completeExceptionally(failure); }
        return actual;
    }

    private static <T> java.util.concurrent.CompletableFuture<T> carpetActor(Entity entity, java.util.function.Supplier<T> operation) {
        var actual = carpet.script.external.ScarpetExplosionActors.entity(entity, () ->
            carpet.script.external.ScarpetNativeWork.recoverGuestValue(carpet.script.external.ScarpetNativeWork.observeNative(entity, operation)))
            .thenCompose(value -> value);
        carpet.script.external.ScarpetNativeWork.record(actual); return actual;
    }

    private static java.util.concurrent.CompletableFuture<Void> carpetSpawnTree(Entity entity, net.minecraft.server.level.ServerLevel originalWorld) {
        return carpetActor(entity, () -> entity.passengers).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(passengers ->
            carpetActor(entity, () -> entity.blockPosition().immutable())
                .thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(origin ->
                    fun.bm.lophine.carpet.OrgBlockDropRouting.worldNative(originalWorld, origin,
                        () -> originalWorld.carpetAddFreshEntityNativeAsync(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT))))
                .thenCompose(value -> value).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(added -> {
                    if (!added || passengers.isEmpty()) return java.util.concurrent.CompletableFuture.completedFuture(null);
                    java.util.concurrent.CompletableFuture<Void> tail = java.util.concurrent.CompletableFuture.completedFuture(null);
                    for (Entity passenger : passengers) tail = tail.thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(ignored ->
                        carpetActor(entity, () -> new net.minecraft.world.phys.Vec3(entity.getX(), entity.getY() + entity.getPassengerRidingPosition(passenger).y(), entity.getZ()))
                            .thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(position -> carpetActor(passenger, () -> {
                                passenger.snapTo(position.x, position.y, position.z, passenger.getYRot(), passenger.getXRot());
                                setEntityRotations(passenger, passenger.getYRot(), passenger.getXRot());
                                carpet.script.external.ScarpetRetiredActors.capture(passenger);
                                return null;
                            }))).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(ignoredPosition -> carpetSpawnTree(passenger, originalWorld)))));
                    carpet.script.external.ScarpetNativeWork.record(tail); return tail;
                }))));
    }

    public static void setEntityRotations(Entity entity, float yaw, float pitch) {
        entity.setYRot(yaw);
        entity.yRotO = yaw;

        entity.setXRot(pitch);
        entity.xRotO = pitch;

        if (entity instanceof LivingEntity livingBase) {
            livingBase.yHeadRot = yaw;
            livingBase.yBodyRot = yaw;
            livingBase.yHeadRotO = yaw;
            livingBase.yBodyRotO = yaw;
        }
    }
}
