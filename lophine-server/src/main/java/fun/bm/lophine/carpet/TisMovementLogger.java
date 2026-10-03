// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TisMovementLogger {
    private static final Map<UUID, Subscriber> SUBSCRIBERS = new ConcurrentHashMap<>();

    private TisMovementLogger() {
    }

    public static void registerLogger() {
        CarpetLoggerProtocol.registerLogger("movement", "", List.of("non_zero:@a[distance=..10]", "@s",
                "non_zero:@e[type=creeper,distance=..5]", "Steve"), false);
    }

    public static boolean canSubscribe(net.minecraft.commands.CommandSourceStack source) {
        return CarpetCommandPermissions.canUse(source, GeneralCompatConfig.loggerMovement);
    }

    public static void globalTick(MinecraftServer server) {
        if (!CarpetLoggerProtocol.hasSubscribers("movement")) {
            SUBSCRIBERS.clear();
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (CarpetLoggerProtocol.subscriptions(player.getScoreboardName()).containsKey("movement")) {
                player.getBukkitEntity().taskScheduler.schedule(entity -> capture((ServerPlayer) entity),
                        retired -> SUBSCRIBERS.remove(player.getUUID()), 1L);
            } else SUBSCRIBERS.remove(player.getUUID());
        }
    }

    public static void capture(ServerPlayer player) {
        UUID id = player.getUUID();
        String option = CarpetLoggerProtocol.subscriptions(player.getScoreboardName()).get("movement");
        var source = player.createCommandSourceStack();
        if (option == null || !canSubscribe(source)) {
            SUBSCRIBERS.remove(id);
            return;
        }
        String filter = option;
        if (filter.isEmpty()) filter = player.getGameProfile().name();
        boolean nonZero = filter.startsWith("non_zero:");
        if (nonZero) filter = filter.substring("non_zero:".length());
        try {
            EntitySelector selector = new EntitySelectorParser(new StringReader(filter),
                    Commands.LEVEL_GAMEMASTERS.check(source.permissions())).parse();
            SUBSCRIBERS.put(id, new Subscriber(player, player.getScoreboardName(), option, nonZero, selector,
                    source.getPosition(), player.level(), id, source.enabledFeatures()));
        } catch (CommandSyntaxException invalid) {
            SUBSCRIBERS.remove(id);
        }
    }

    public static Tracker begin(Entity entity, MoverType type, Vec3 original) {
        if (SUBSCRIBERS.isEmpty() || !(entity.level() instanceof ServerLevel) || !TickThread.isTickThreadFor(entity))
            return null;
        return new Tracker(entity, type, original);
    }

    public static void remove(ServerPlayer player) {
        SUBSCRIBERS.remove(player.getUUID());
    }

    public static void reset() {
        SUBSCRIBERS.clear();
    }

    private record Subscriber(ServerPlayer player, String name, String option, boolean nonZero, EntitySelector selector,
                              Vec3 position, ServerLevel level, UUID id, FeatureFlagSet enabledFeatures) {
    }

    private record Modification(String reason, Vec3 before, Vec3 after) {
    }

    public static final class Tracker {
        private final Entity entity;
        private final ServerLevel world;
        private final Vec3 originalPosition;
        private final MoverType type;
        private final Vec3 originalMovement;
        private Vec3 movement;
        private final List<Modification> changes = new ArrayList<>();

        private Tracker(Entity entity, MoverType type, Vec3 original) {
            this.entity = entity;
            this.world = (ServerLevel) entity.level();
            this.originalPosition = entity.position();
            this.type = type;
            this.originalMovement = original;
            this.movement = original;
        }

        public void modified(String reason, Vec3 value) {
            if (movement.subtract(value).length() >= 1.0e-12) {
                changes.add(new Modification(reason, movement, value));
                movement = value;
            }
        }

        public void report() {
            if (!TickThread.isTickThreadFor(entity)) {
                entity.getBukkitEntity().taskScheduler.schedule(ignored -> report(), null, 1L);
                return;
            }
            List<Component> output = null;
            for (Subscriber subscriber : SUBSCRIBERS.values()) {
                if (!subscriber.option.equals(CarpetLoggerProtocol.subscriptions(subscriber.name).get("movement")))
                    continue;
                if (subscriber.nonZero && movement.lengthSqr() == 0.0) continue;
                if (!subscriber.selector.carpetMatchesMovement(entity, subscriber.position, subscriber.level,
                        subscriber.id, subscriber.enabledFeatures)) continue;
                if (output == null) output = message();
                List<Component> immutable = output;
                subscriber.player.getBukkitEntity().taskScheduler.schedule(recipient -> {
                    for (Component line : immutable) ((ServerPlayer) recipient).sendSystemMessage(line);
                }, null, 1L);
            }
        }

        private List<Component> message() {
            List<Component> result = new ArrayList<>();
            Component name = entity.getName().copy().withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(entity.getStringUUID())))
                    .withClickEvent(new ClickEvent.SuggestCommand("/tp " + entity.getStringUUID())));
            result.add(Component.empty());
            result.add(Component.literal("[Movement] ").withStyle(ChatFormatting.GRAY).append(name)
                    .append(" requested ").append(vector(originalMovement)).append(" @ ").append(vector(originalPosition)));
            result.add(Component.literal("  " + type.name().toLowerCase(Locale.ROOT) + " tick " + world.getGameTime()
                    + " " + world.dimension().identifier() + " region "
                    + io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion().id).withStyle(ChatFormatting.GRAY));
            for (Modification change : changes) {
                Component arrow = Component.literal(" -> ").withStyle(style -> style.withColor(ChatFormatting.GRAY)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Delta: " + change.after.subtract(change.before)))));
                result.add(Component.literal("  - ").append(vector(change.before)).append(arrow).append(vector(change.after))
                        .append(Component.literal(" due to " + change.reason).withStyle(ChatFormatting.GRAY)));
            }
            result.add(Component.literal("Result: ").withStyle(ChatFormatting.GRAY).append(name).append(" moved ")
                    .append(vector(movement)).append(" @ ").append(vector(entity.position())));
            return List.copyOf(result);
        }

        private static Component vector(Vec3 value) {
            return Component.literal(String.format(Locale.ROOT, "[%.8f, %.8f, %.8f]", value.x, value.y, value.z))
                    .withStyle(style -> style.withColor(ChatFormatting.YELLOW)
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(value.toString()))));
        }
    }
}
