// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from fabric-carpet f358000b175ddbcf1dd0bc59641c715fb0545664, including TIS initialized TNT metadata.
package fun.bm.lophine.carpet;

import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CarpetExplosionLogger {
    private static long lastTime = Long.MIN_VALUE;
    private static int number;
    private final Vec3 position;
    private final float power;
    private final boolean fire;
    private final Explosion.BlockInteraction destruction;
    private final Vec3 primedPosition;
    private final Vec3 primedMotion;
    private final Map<Impact, Integer> impacts = new LinkedHashMap<>();
    private boolean reported;

    public CarpetExplosionLogger(Vec3 position, float power, boolean fire, Explosion.BlockInteraction destruction, Entity source) {
        this.position = position;
        this.power = power;
        this.fire = fire;
        this.destruction = destruction;
        boolean ownsSource = source != null && ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(source);
        primedPosition = ownsSource && source instanceof PrimedTnt tnt ? tnt.carpetExplosionPrimedPosition : null;
        primedMotion = ownsSource && source instanceof PrimedTnt tnt ? tnt.carpetExplosionPrimedMotion : null;
    }

    public static void registerLogger() {
        CarpetLoggerProtocol.registerLogger("explosions", "brief", List.of("brief", "full"), true);
    }

    record TickNumber(long time, int number, boolean first) {
    }

    static synchronized TickNumber next(long time) {
        boolean first = time != lastTime;
        if (first) {
            lastTime = time;
            number = 0;
        }
        return new TickNumber(time, ++number, first);
    }

    public static synchronized void reset() {
        lastTime = Long.MIN_VALUE;
        number = 0;
    }

    public void impacted(Entity entity, Vec3 previousMotion) {
        Impact impact = new Impact(entity.position(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), entity.getDeltaMovement().subtract(previousMotion));
        impacts.merge(impact, 1, Integer::sum);
    }

    public void finish(boolean affectsBlocks) {
        if (reported) return;
        reported = true;
        TickNumber tick = next(CarpetServerClock.gameTime());
        CarpetLoggerProtocol.log("explosions", option -> {
            var output = new ArrayList<Component>();
            if (tick.first) output.add(Component.literal("tick : " + tick.time).withStyle(ChatFormatting.LIGHT_PURPLE));
            output.add(Component.literal("#" + tick.number + " ->").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(CarpetTrajectoryLogger.coordinatesText(position, ChatFormatting.GREEN, true)));
            if ("brief".equals(option)) output.set(output.size() - 1, output.getLast().copy()
                    .append(Component.literal(affectsBlocks ? "  (affects blocks)" : "  (doesn't affect blocks)").withStyle(ChatFormatting.LIGHT_PURPLE)));
            if ("full".equals(option)) {
                output.add(Component.literal("  affects blocks: " + affectsBlocks));
                output.add(Component.literal("  creates fire: " + fire));
                output.add(Component.literal("  power: " + power));
                output.add(Component.literal("  destruction: " + destruction.name()));
                output.add(Component.literal("  affected entities:" + (impacts.isEmpty() ? " None" : "")));
                impacts.forEach((impact, count) -> output.add(Component.literal(position.equals(impact.position) ? "  - TNT" : "  - ")
                        .append(CarpetTrajectoryLogger.coordinatesText(impact.position, position.equals(impact.position) ? ChatFormatting.RED : ChatFormatting.YELLOW, true))
                        .append(" dV").append(CarpetTrajectoryLogger.coordinatesText(impact.acceleration, ChatFormatting.LIGHT_PURPLE, true))
                        .append(" " + impact.type + (count > 1 ? " (" + count + ")" : ""))));
            }
            if (primedPosition != null && primedMotion != null) {
                double angle = initializedAngle(primedMotion);
                String coords = CarpetTrajectoryLogger.coordinates(primedPosition, true);
                var tnt = Component.literal("[TNT]").withStyle(style -> style.withColor(ChatFormatting.RED)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Initialized Data\n- Position: " + coords + "\n- Angle: " + angle)))
                        .withClickEvent(new ClickEvent.SuggestCommand(coords + " " + angle)));
                if ("brief".equals(option))
                    output.set(output.size() - 1, output.getLast().copy().append(" ").append(tnt));
                else output.add(Component.literal("  ").append(tnt));
            }
            return output;
        });
    }

    static double initializedAngle(Vec3 velocity) {
        if (velocity.z != 0.0) {
            double angle = Math.atan(velocity.x / velocity.z);
            return velocity.z > 0.0 ? angle + Math.PI : velocity.x > 0.0 ? angle + Math.PI * 2.0 : angle;
        }
        return velocity.x > 0.0 ? Math.PI * 1.5 : Math.PI * 0.5;
    }

    private record Impact(Vec3 position, String type, Vec3 acceleration) {
    }
}
