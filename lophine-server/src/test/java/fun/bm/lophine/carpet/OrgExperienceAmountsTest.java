package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class OrgExperienceAmountsTest {
    @Test
    void levelBoundariesMatchVanilla() {
        assertEquals(BigInteger.ZERO, OrgExperienceAmounts.forLevel(0));
        assertEquals(BigInteger.valueOf(352), OrgExperienceAmounts.forLevel(16));
        assertEquals(BigInteger.valueOf(394), OrgExperienceAmounts.forLevel(17));
        assertEquals(BigInteger.valueOf(1507), OrgExperienceAmounts.forLevel(31));
        assertEquals(BigInteger.valueOf(1628), OrgExperienceAmounts.forLevel(32));
    }

    @Test
    void adjacentLevelsMatchActualVanillaIncrementFormula() {
        for (int level : new int[]{0, 1, 15, 16, 17, 29, 30, 31, 32, 100, 10000, OrgExperienceAmounts.MAX_EFFECTIVE_LEVEL - 1}) {
            long next = level >= 30 ? 9L * level - 158 : level >= 15 ? 5L * level - 38 : 2L * level + 7;
            assertEquals(BigInteger.valueOf(next), OrgExperienceAmounts.upgrade(level, level + 1), "level " + level);
        }
    }

    @Test
    void totalsMatchSummationAcrossAllThreeVanillaIntervals() {
        long accumulated = 0;
        for (int level = 0; level < 10000; level++) {
            assertEquals(BigInteger.valueOf(accumulated), OrgExperienceAmounts.forLevel(level), "level " + level);
            accumulated += level >= 30 ? 9L * level - 158 : level >= 15 ? 5L * level - 38 : 2L * level + 7;
        }
    }

    @Test
    void rejectsNonFiniteLevelAndPreservesSignedUpgradeDifference() {
        assertThrows(IllegalArgumentException.class, () -> OrgExperienceAmounts.forLevel(-1));
        assertThrows(IllegalArgumentException.class, () -> OrgExperienceAmounts.forLevel(OrgExperienceAmounts.MAX_EFFECTIVE_LEVEL + 1));
        assertEquals(BigInteger.valueOf(-235), OrgExperienceAmounts.upgrade(20, 15));
        assertTrue(OrgExperienceAmounts.MAX_TOTAL.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) < 0);
        assertTrue(OrgExperienceAmounts.MAX_TOTAL.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0);
    }
}
