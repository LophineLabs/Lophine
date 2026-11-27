// SPDX-License-Identifier: LGPL-3.0-or-later
// Adapted from Carpet TIS Addition v1.84.0.
package fun.bm.lophine.carpet.tis;

import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import org.bukkit.event.entity.EntityRemoveEvent;

import java.util.List;

public final class TisEntityLifecycleLoggers {
    private TisEntityLifecycleLoggers() {
    }

    public static void registerLoggers() {
        var events = List.of("create", "die", "despawn", "all");
        CarpetLoggerProtocol.registerLogger("item", "die", events, false);
        CarpetLoggerProtocol.registerLogger("xporb", "die", events, false);
        CarpetLoggerProtocol.registerLogger("phantom", "spawning", List.of("spawning"), false);
    }

    public static void created(Entity entity) {
        logEntity(entity, "create", "created");
    }

    public static void removed(Entity entity, Entity.RemovalReason reason, EntityRemoveEvent.Cause cause) {
        if (cause == EntityRemoveEvent.Cause.DESPAWN) {
            logEntity(entity, "despawn", "despawned");
        } else if (reason == Entity.RemovalReason.KILLED || cause == EntityRemoveEvent.Cause.DEATH
                || cause == EntityRemoveEvent.Cause.EXPLODE || cause == EntityRemoveEvent.Cause.OUT_OF_WORLD) {
            logEntity(entity, "die", "died");
        }
    }

    private static void logEntity(Entity entity, String option, String event) {
        String logger = entity instanceof ItemEntity ? "item" : entity instanceof ExperienceOrb ? "xporb" : null;
        if (logger == null || !CarpetLoggerProtocol.hasSubscribers(logger, selected -> accepts(selected, option))) return;
        Component label = entity instanceof ItemEntity item ? item.getItem().getDisplayName()
                : Component.translatable(entity.getType().getDescriptionId()).append(" (" + ((ExperienceOrb) entity).getValue() + " XP)");
        Component line = Component.literal("[" + logger + "] ").append(label).append(" " + event + " @ ")
                .append(entity.blockPosition().toShortString());
        CarpetLoggerProtocol.log(logger, selected -> accepts(selected, option) ? List.of(line) : List.of());
    }

    public static void phantoms(ServerPlayer player, int amount) {
        if (!CarpetLoggerProtocol.hasSubscribers("phantom", selected -> accepts(selected, "spawning"))) return;
        var line = TisTranslations.text("logger.phantom.summon", player.getDisplayName(), amount, Component.translatable("entity.minecraft.phantom"));
        CarpetLoggerProtocol.log("phantom", selected -> accepts(selected, "spawning") ? List.of(line) : List.of());
    }

    static boolean accepts(String selected, String event) {
        if (selected.equals("all")) return true;
        for (String option : selected.split("[,+]")) {
            if (option.strip().equals(event)) return true;
        }
        return false;
    }
}
