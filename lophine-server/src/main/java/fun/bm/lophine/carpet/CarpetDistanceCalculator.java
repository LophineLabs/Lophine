package fun.bm.lophine.carpet;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CarpetDistanceCalculator {
    private static final Map<String, Vec3> START_POINTS = new ConcurrentHashMap<>();

    private CarpetDistanceCalculator() {
    }

    public static boolean hasStartingPoint(CommandSourceStack source) {
        return START_POINTS.containsKey(source.getTextName());
    }

    public static int setStart(CommandSourceStack source, Vec3 pos) {
        START_POINTS.put(source.getTextName(), pos);
        source.sendSuccess(() -> Component.literal("Initial point set to: " + coordinates(pos)), false);
        return 1;
    }

    public static int setEnd(CommandSourceStack source, Vec3 pos) {
        Vec3 start = START_POINTS.get(source.getTextName());
        if (start == null) {
            source.sendSuccess(() -> Component.literal("There was no initial point for " + source.getTextName()), false);
            setStart(source, pos);
            return 0;
        }
        return distance(source, start, pos);
    }

    public static int distance(CommandSourceStack source, Vec3 from, Vec3 to) {
        // Carpet computes these differences in float precision.
        double dx = Math.abs((float) from.x - (float) to.x);
        double dy = Math.abs((float) from.y - (float) to.y);
        double dz = Math.abs((float) from.z - (float) to.z);
        source.sendSuccess(() -> Component.literal("Distance between " + coordinates(from) + " and " + coordinates(to) + ":"), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, " - Spherical: %.2f", Math.sqrt(dx * dx + dy * dy + dz * dz))), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, " - Cylindrical: %.2f", Math.sqrt(dx * dx + dz * dz))), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, " - Manhattan: %.1f", dx + dy + dz)), false);
        return 1;
    }

    private static String coordinates(Vec3 pos) {
        return String.format(Locale.ROOT, "[%.2f, %.2f, %.2f]", pos.x, pos.y, pos.z);
    }
}
