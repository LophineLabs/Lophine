package fun.bm.lophine.carpet;

import net.minecraft.server.level.ServerPlayer;

import java.math.BigInteger;

/**
 * Constant-time, overflow-safe equivalent of Org's piecewise XP amount formulas.
 */
public final class OrgExperienceAmounts {
    public static final int MAX_EFFECTIVE_LEVEL = 238609312;
    public static final BigInteger MAX_TOTAL = forLevel(MAX_EFFECTIVE_LEVEL);

    private OrgExperienceAmounts() {
    }

    public static BigInteger forLevel(int level) {
        if (level < 0 || level > MAX_EFFECTIVE_LEVEL)
            throw new IllegalArgumentException("Experience level is outside the finite vanilla range");
        BigInteger value = BigInteger.valueOf(level);
        BigInteger square = value.multiply(value);
        if (level <= 16) return square.add(value.multiply(BigInteger.valueOf(6)));
        if (level <= 31)
            return square.multiply(BigInteger.valueOf(5)).subtract(value.multiply(BigInteger.valueOf(81))).add(BigInteger.valueOf(720)).divide(BigInteger.TWO);
        return square.multiply(BigInteger.valueOf(9)).subtract(value.multiply(BigInteger.valueOf(325))).add(BigInteger.valueOf(4440)).divide(BigInteger.TWO);
    }

    public static BigInteger upgrade(int current, int target) {
        return forLevel(target).subtract(forLevel(current));
    }

    public static BigInteger read(ServerPlayer player) {
        return read(player.experienceLevel, player.experienceProgress);
    }

    public static BigInteger read(int rawLevel, float progress) {
        int level = Math.max(0, Math.min(rawLevel, MAX_EFFECTIVE_LEVEL));
        long needed = level >= 30 ? 112L + (level - 30L) * 9 : level >= 15 ? 37L + (level - 15L) * 5 : 7L + level * 2L;
        // Vanilla stores whole points as a float fraction. Flooring its round-off
        // can turn nine points into eight on every transfer or offline reload.
        long partial = level == MAX_EFFECTIVE_LEVEL ? 0 : Math.max(0L, Math.round((double) progress * needed));
        return forLevel(level).add(BigInteger.valueOf(partial));
    }

    public static void write(ServerPlayer player, BigInteger points) {
        if (points.signum() < 0 || points.compareTo(MAX_TOTAL) > 0)
            throw new IllegalArgumentException("Experience amount is outside the finite vanilla range");
        int low = 0;
        int high = MAX_EFFECTIVE_LEVEL;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (forLevel(middle).compareTo(points) <= 0) low = middle;
            else high = middle - 1;
        }
        player.setExperienceLevels(low);
        player.setExperiencePoints(points.subtract(forLevel(low)).intValueExact());
        player.totalExperience = points.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }
}
