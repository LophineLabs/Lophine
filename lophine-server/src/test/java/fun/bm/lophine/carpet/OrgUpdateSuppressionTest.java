package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgUpdateSuppressionTest {
    private final String original = GeneralCompatConfig.CCEUpdateSuppression;

    @AfterEach
    void restore() {
        GeneralCompatConfig.CCEUpdateSuppression = original;
    }

    @Test
    void falseDoesNotTreatTheLiteralNameAsASuppressor() {
        GeneralCompatConfig.CCEUpdateSuppression = "false";
        assertFalse(OrgUpdateSuppression.namedSuppressor("false"));
        assertFalse(OrgUpdateSuppression.namedSuppressor("updateSuppression"));
    }

    @Test
    void trueRecognizesBothUpstreamNamesAndOnlyThoseNames() {
        GeneralCompatConfig.CCEUpdateSuppression = "true";
        assertTrue(OrgUpdateSuppression.namedSuppressor("更新抑制器"));
        assertTrue(OrgUpdateSuppression.namedSuppressor("UpDaTeSuPpReSsIoN"));
        assertFalse(OrgUpdateSuppression.namedSuppressor("true"));
        assertFalse(OrgUpdateSuppression.namedSuppressor(null));
    }

    @Test
    void customStringMatchesTheBlockNameCaseInsensitively() {
        GeneralCompatConfig.CCEUpdateSuppression = "named example";
        assertTrue(OrgUpdateSuppression.namedSuppressor("NAMED EXAMPLE"));
        assertFalse(OrgUpdateSuppression.namedSuppressor("updateSuppression"));
        GeneralCompatConfig.CCEUpdateSuppression = "False";
        assertTrue(OrgUpdateSuppression.namedSuppressor("false"));
    }
}
