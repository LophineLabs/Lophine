package fun.bm.lophine.utils.markdown;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.HSVLike;

/**
 * Color ramps used by {@code {name|scale}} placeholders. Keep aligned with
 * {@link io.papermc.paper.threadedregions.commands.CommandUtil}.
 */
public final class MarkdownColorScales {

    public static TextColor byName(String scale, double value) {
        return switch (scale) {
            case "tps" -> tps(value);
            case "mspt" -> mspt(value);
            case "util" -> util(value);
            case "mem" -> mem(value);
            default -> null;
        };
    }

    public static TextColor tps(double tps) {
        final double difference = Math.min(Math.abs(20.0 - tps), 20.0);
        final double coordinate;
        if (difference <= 2.0) {
            coordinate = 70.0 + ((140.0 - 70.0) / (0.0 - 2.0)) * (difference - 2.0);
        } else if (difference <= 5.0) {
            coordinate = 30.0 + ((70.0 - 30.0) / (2.0 - 5.0)) * (difference - 5.0);
        } else if (difference <= 10.0) {
            coordinate = 10.0 + ((30.0 - 10.0) / (5.0 - 10.0)) * (difference - 10.0);
        } else {
            coordinate = ((10.0 - 0.0) / (10.0 - 20.0)) * (difference - 20.0);
        }
        return hsv(coordinate);
    }

    public static TextColor mspt(double mspt) {
        final double clamped = Math.min(Math.abs(mspt), 50.0);
        final double coordinate;
        if (clamped <= 15.0) {
            coordinate = 130.0 + ((140.0 - 130.0) / (0.0 - 15.0)) * (clamped - 15.0);
        } else if (clamped <= 25.0) {
            coordinate = 90.0 + ((130.0 - 90.0) / (15.0 - 25.0)) * (clamped - 25.0);
        } else if (clamped <= 35.0) {
            coordinate = 30.0 + ((90.0 - 30.0) / (25.0 - 35.0)) * (clamped - 35.0);
        } else if (clamped <= 40.0) {
            coordinate = 15.0 + ((30.0 - 15.0) / (35.0 - 40.0)) * (clamped - 40.0);
        } else {
            coordinate = ((15.0 - 0.0) / (40.0 - 50.0)) * (clamped - 50.0);
        }
        return hsv(coordinate);
    }

    public static TextColor util(double util) {
        return mspt(util * 50.0);
    }

    public static TextColor mem(double percent) {
        if (percent < 60.0) {
            return util(0.0);
        }
        if (percent < 85.0) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    private static TextColor hsv(double hueDegrees) {
        return TextColor.color(HSVLike.hsvLike((float) (hueDegrees / 360.0), 85.0f / 100.0f, 80.0f / 100.0f));
    }
}
