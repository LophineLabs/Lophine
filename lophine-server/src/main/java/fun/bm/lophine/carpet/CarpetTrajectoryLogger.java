// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from fabric-carpet f358000, carpet.logging.logHelpers.TrajectoryLogHelper.
package fun.bm.lophine.carpet;

import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.world.phys.Vec3;

/** Owned by one projectile or falling block, with one terminal report. */
public final class CarpetTrajectoryLogger {
    private final String name;
    private final List<Vec3> positions = new ArrayList<>();
    private final List<Vec3> motions = new ArrayList<>();
    private boolean finished;
    private final net.minecraft.world.entity.Entity entity;
    private TisProjectileVisualizer.Hit hit;
    private boolean createdVisualizers;

    public CarpetTrajectoryLogger(String name) {
        this.name = name;
        this.entity = TisProjectileVisualizer.takeEntity(name);
        TisProjectileVisualizer.bind(this.entity, this);
    }

    public void hit(net.minecraft.world.phys.HitResult result) { if (!finished) hit = TisProjectileVisualizer.Hit.capture(result); }

    public void tick(Vec3 position, Vec3 motion) {
        if (!finished) {
            positions.add(position);
            motions.add(motion);
        }
    }

    public void finish() {
        if (finished) return;
        finished = true;
        if (TisProjectileVisualizer.isVisualizer(entity)) { positions.clear(); motions.clear(); return; }
        if (entity != null && "projectiles".equals(name)) TisProjectileVisualizer.clear();
        CarpetLoggerProtocol.log(name, option -> {
            List<Component> output = new ArrayList<>();
            if ("brief".equals(option)) {
                output.add(Component.empty());
                var line = Component.empty();
                for (int i = 0; i < positions.size(); i++) {
                    Vec3 pos = positions.get(i), mot = motions.get(i);
                    String hover = String.format(Locale.ROOT, "Tick: %d\nx: %f\ny: %f\nz: %f\n------------\nmx: %f\nmy: %f\nmz: %f",
                        i, pos.x(), pos.y(), pos.z(), mot.x(), mot.y(), mot.z());
                    line.append(Component.literal("  x").withStyle(style -> style.withColor(ChatFormatting.WHITE)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)))));
                    if ((i + 1) % 20 == 0 || i == positions.size() - 1) {
                        output.add(line);
                        line = Component.empty();
                    }
                }
            } else if ("full".equals(option)) {
                output.add(Component.literal("---------"));
                for (int i = 0; i < positions.size(); i++) {
                    output.add(Component.literal(String.format(Locale.ROOT, "tick: %3d pos", i))
                        .append(coordinatesText(positions.get(i), ChatFormatting.WHITE, false))
                        .append("   mot").append(coordinatesText(motions.get(i), ChatFormatting.WHITE, false)));
                }
            }
            if (hit != null && "brief".equals(option)) {
                var marker = Component.literal(" x").withStyle(style -> style.withColor(ChatFormatting.GRAY)
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(hit.text()))));
                if (output.isEmpty()) output.add(marker);
                else output.set(output.size() - 1, output.getLast().copy().append(marker));
            } else if (hit != null && "full".equals(option)) output.add(Component.literal(hit.text()));
            if ("visualize".equals(option) && entity != null && entity.level() instanceof net.minecraft.server.level.ServerLevel world) {
                if (fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.visualizeProjectileLoggerEnabled) {
                    output.add(Component.literal("Visualized projectile trajectory: " + positions.size() + " ticks"));
                    if (!createdVisualizers) {
                        TisProjectileVisualizer.visualize(world, List.copyOf(positions), hit == null ? null : hit.position());
                        createdVisualizers = true;
                    }
                } else output.add(Component.literal("Projectile visualization is disabled; enable visualizeProjectileLoggerEnabled")
                    .withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand("/carpet visualizeProjectileLoggerEnabled true"))));
            }
            return output;
        });
        positions.clear();
        motions.clear();
    }

    public static String coordinates(Vec3 vector, boolean full) {
        return String.format(Locale.ROOT, full ? "[ %f, %f, %f ]" : "[ %.1f, %.1f, %.1f ]", vector.x(), vector.y(), vector.z());
    }

    public static Component coordinatesText(Vec3 vector, ChatFormatting color, boolean full) {
        if (full) return Component.literal(coordinates(vector, true)).withStyle(color);
        var message = Component.literal("[ ").withStyle(color);
        double[] values = {vector.x(), vector.y(), vector.z()};
        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            String prefix = i == 0 ? "" : ", ";
            message.append(Component.literal(prefix + String.format(Locale.ROOT, "%.1f", value)).withStyle(style ->
                style.withColor(color).withClickEvent(new ClickEvent.SuggestCommand(Double.toString(value)))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(Double.toString(value))))));
        }
        return message.append(" ]");
    }
}
