package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CarpetRuleRegistryTest {
    @Test
    void nativeLimitsKeepTheirActualUpstreamBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("pushLimit").parse("0"));
        assertEquals(1024, CarpetRuleRegistry.get("pushLimit").parse("1024"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("railPowerLimit").parse("1025"));
        assertEquals(1024, CarpetRuleRegistry.get("sculkSensorRange").parse("1024"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("sculkSensorRange").parse("0"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("sculkSensorRange").parse("1025"));
        assertEquals(20_000_000, CarpetRuleRegistry.get("forceloadLimit").parse("20000000"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("forceloadLimit").parse("20000001"));
        assertEquals(32, CarpetRuleRegistry.get("viewDistance").parse("32"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("simulationDistance").parse("33"));
        assertEquals(300, CarpetRuleRegistry.get("blockChunkLoaderRangeController").parse("300"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("blockChunkLoaderRangeController").parse("301"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("easyGetPitcherPod").parse("1"));
    }
    @Test
    void booleanTyposAreRejected() {
        CarpetRuleRegistry.Binding rule = CarpetRuleRegistry.get("optimizedTNT");
        assertEquals(true, rule.parse("true"));
        assertThrows(IllegalArgumentException.class, () -> rule.parse("treu"));
        assertThrows(IllegalArgumentException.class, () -> rule.parse("TRUE"));
    }

    @Test
    void tisNumericalAndModeValidatorsKeepTheirSourceBoundaries() {
        for (String name : java.util.List.of("blockEventPacketRange", "explosionPacketRange", "voidDamageAmount")) {
            assertEquals(0D, CarpetRuleRegistry.get(name).parse("0"));
            assertEquals(Double.POSITIVE_INFINITY, CarpetRuleRegistry.get(name).parse("Infinity"));
            assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get(name).parse("-0.1"));
            assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get(name).parse("NaN"));
        }
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("snowMeltMinLightLevel").parse("-1"));
        assertEquals(-0.5D, CarpetRuleRegistry.get("voidRelatedAltitude").parse("-0.5"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("voidRelatedAltitude").parse("0"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("renewableElytra").parse("1.001"));
        for (String name : java.util.List.of("spawnBabyProbably", "spawnJockeyProbably", "spawnLeaderZombieProbably")) {
            assertEquals(-0.5D, CarpetRuleRegistry.get(name).parse("-0.5"));
            assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get(name).parse("-1.01"));
            assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get(name).parse("NaN"));
        }
        assertEquals(32767, CarpetRuleRegistry.get("tntFuseDuration").parse("32767"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("tntFuseDuration").parse("32768"));
        assertEquals(128D, CarpetRuleRegistry.get("xpTrackingDistance").parse("128"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("xpTrackingDistance").parse("128.01"));
        assertEquals("creative,spectator", CarpetRuleRegistry.get("voidDamageIgnorePlayer").parse("creative,spectator"));
        for (String modes : java.util.List.of("", "notcreative", "creative,garbage", "Creative"))
            assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("voidDamageIgnorePlayer").parse(modes));
        for (String type : java.util.List.of("StackOverflowError", "OutOfMemoryError", "ClassCastException", "IllegalArgumentException", "IllegalStateException")) {
            assertEquals(type, CarpetRuleRegistry.get("updateSuppressionSimulator").parse(type));
            assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("updateSuppressionSimulator").parse(type.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    @Test
    void orgOperationalLimitsRejectValuesTheSourceCannotLoad() {
        assertEquals(256D, CarpetRuleRegistry.get("maxBlockPlaceDistance").parse("256"));
        assertEquals(-1D, CarpetRuleRegistry.get("maxBlockPlaceDistance").parse("-1"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("maxBlockPlaceDistance").parse("256.01"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("maxBlockPlaceDistance").parse("NaN"));
        assertEquals(0L, CarpetRuleRegistry.get("customPiglinBarteringTime").parse("0"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("customPiglinBarteringTime").parse("-2"));
        assertEquals(-1, CarpetRuleRegistry.get("fakePlayerMaxItemOperationCount").parse("-1"));
        assertEquals(Integer.MAX_VALUE, CarpetRuleRegistry.get("fakePlayerMaxItemOperationCount").parse("2147483647"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("fakePlayerMaxItemOperationCount").parse("0"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("fakePlayerMaxItemOperationCount").parse("-2"));
    }

    @Test
    void requestedFileValuesControlCrossRuleValidationInsteadOfOldRuntimeValues() throws Exception {
        boolean oldSync = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.synchronizedLightThread;
        String oldLight = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates;
        boolean oldTnt = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.optimizedTNT;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.synchronizedLightThread = true;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates = "off";
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.optimizedTNT = false;
            var requested = java.util.Map.of(CarpetRuleRegistry.get("synchronizedLightThread").path(), false,
                CarpetRuleRegistry.get("lightUpdates").path(), "off", CarpetRuleRegistry.get("optimizedTNT").path(), true);
            try (var ignored = CarpetRuleRegistry.configurationView(requested::get)) {
                assertEquals("off", CarpetRuleRegistry.get("lightUpdates").parse("off"));
                assertEquals(false, CarpetRuleRegistry.get("synchronizedLightThread").parse("false"));
                assertEquals(1.0D, CarpetRuleRegistry.get("tntRandomRange").parse("1"));
            }
            try (var ignored = CarpetRuleRegistry.configurationView(path -> path.equals(CarpetRuleRegistry.get("synchronizedLightThread").path()) ? true : null)) {
                assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("lightUpdates").parse("off"));
            }
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.synchronizedLightThread = oldSync;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.lightUpdates = oldLight;
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.optimizedTNT = oldTnt;
        }
    }

    @Test
    void enumAndStructureBoundsAreValidated() {
        assertEquals("all", CarpetRuleRegistry.get("thickFungusGrowth").parse("all"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("thickFungusGrowth").parse("anything"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("structureBlockLimit").parse("47"));
    }

    @Test
    void rulesResolveToTheirExistingConfigurationPaths() {
        assertEquals("carpet.general.largeEnderChest", CarpetRuleRegistry.get("largeEnderChest").path());
        assertEquals("carpet.fakeplayer.commandPlayer", CarpetRuleRegistry.get("commandPlayer").path());
    }

    @Test
    void portalAndAnvilLimitsRejectValuesOutsideUpstreamBounds() {
        assertEquals(384, CarpetRuleRegistry.get("netherPortalMaxSize").parse("384"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("netherPortalMaxSize").parse("385"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("netherPortalMaxSize").parse("1"));
        assertEquals(-1, CarpetRuleRegistry.get("setAnvilExperienceConsumptionLimit").parse("-1"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("setAnvilExperienceConsumptionLimit").parse("0"));
        assertThrows(IllegalArgumentException.class, () -> CarpetRuleRegistry.get("setAnvilExperienceConsumptionLimit").parse("10001"));
    }
}
