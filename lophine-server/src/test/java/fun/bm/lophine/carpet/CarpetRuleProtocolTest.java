package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.ServerBuildInfo;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.protocol.CarpetServerProtocol;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CarpetRuleProtocolTest {
    @BeforeAll
    static void provideBuildMetadata() throws ClassNotFoundException {
        ServerBuildInfo info = mock(ServerBuildInfo.class);
        when(info.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE)).thenReturn("test");
        try (MockedStatic<ServerBuildInfo> metadata = mockStatic(ServerBuildInfo.class)) {
            metadata.when(ServerBuildInfo::buildInfo).thenReturn(info);
            Class.forName(CarpetServerProtocol.class.getName());
        }
    }

    @AfterEach
    void clearRules() {
        CarpetServerProtocol.CarpetRules.clear();
    }

    @Test
    void identicalNamesFromDifferentManagersRemainDistinct() {
        CarpetServerProtocol.CarpetRules.beginBatch();
        try {
            CarpetServerProtocol.CarpetRules.clear();
            CarpetServerProtocol.CarpetRules.register(CarpetServerProtocol.CarpetRule.of("carpet", "structureBlockLimit", 96));
            CarpetServerProtocol.CarpetRules.register(CarpetServerProtocol.CarpetRule.of("carpettisaddition", "structureBlockLimit", 192));
            assertEquals(Map.of("carpet:structureBlockLimit", "96", "carpettisaddition:structureBlockLimit", "192"), serializedRules());
        } finally {
            CarpetServerProtocol.CarpetRules.endBatch();
        }
    }

    @Test
    void migratedMetadataPublishesNewRulesAndRefreshesTheirValues() throws ReflectiveOperationException {
        double previousDistance = GeneralCompatConfig.maxBlockPlaceDistance;
        Method register = CarpetProtocalDataBase.class.getDeclaredMethod("registerMigratedRules");
        register.setAccessible(true);
        CarpetServerProtocol.CarpetRules.beginBatch();
        try {
            CarpetServerProtocol.CarpetRules.clear();
            register.invoke(null);
            Map<String, String> initial = serializedRules();
            assertEquals("48", initial.get("carpet:structureBlockLimit"));
            assertEquals("-1.0", initial.get("carpet:spawnBabyProbably"));
            assertEquals("false", initial.get("carpet:largeShulkerBox"));
            GeneralCompatConfig.maxBlockPlaceDistance = 8.5D;
            register.invoke(null);
            assertEquals("8.5", serializedRules().get("carpet:maxBlockPlaceDistance"));
        } finally {
            GeneralCompatConfig.maxBlockPlaceDistance = previousDistance;
            CarpetServerProtocol.CarpetRules.endBatch();
        }
    }

    private static Map<String, String> serializedRules() {
        CompoundTag data = new CompoundTag();
        CarpetServerProtocol.CarpetRules.write(data);
        CompoundTag rules = data.getCompoundOrEmpty("Rules");
        Map<String, String> result = new HashMap<>();
        for (String key : rules.keySet()) {
            CompoundTag rule = rules.getCompoundOrEmpty(key);
            result.put(rule.getString("Manager").orElseThrow() + ":" + rule.getString("Rule").orElseThrow(), rule.getString("Value").orElseThrow());
        }
        return result;
    }
}
