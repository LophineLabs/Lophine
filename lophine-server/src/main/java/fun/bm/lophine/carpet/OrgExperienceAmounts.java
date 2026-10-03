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
        int level = Math.max(0, Math.min(player.experienceLevel, MAX_EFFECTIVE_LEVEL));
        int partial = level == MAX_EFFECTIVE_LEVEL ? 0 : Math.max(0, (int) Math.floor(player.experienceProgress * player.getXpNeededForNextLevel()));
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
