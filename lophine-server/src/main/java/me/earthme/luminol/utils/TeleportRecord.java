package me.earthme.luminol.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

import java.lang.ref.WeakReference;

public record TeleportRecord(WeakReference<ServerLevel> lastLevel, Vec3 lastPos, float yaw, float pitch) {
    @Contract("_, _, _, _ -> new")
    public static @NonNull TeleportRecord create(ServerLevel lastLevel, Vec3 lastPos, float yaw, float pitch) {
        return new TeleportRecord(new WeakReference<>(lastLevel), lastPos, yaw, pitch);
    }
}
