package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.ServerBuildInfo;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.protocol.CarpetServerProtocol;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetProtocolSessionIdentityTest {
    @BeforeAll
    static void metadata() throws Exception {
        var info = mock(ServerBuildInfo.class);
        when(info.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE)).thenReturn("test");
        try (var metadata = mockStatic(ServerBuildInfo.class)) {
            metadata.when(ServerBuildInfo::buildInfo).thenReturn(info);
            Class.forName(CarpetServerProtocol.class.getName());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void retiredConnectionCannotRemoveOrUseTheNewConnectionsHandshake() throws Exception {
        UUID id = UUID.randomUUID();
        ServerPlayer old = mock(ServerPlayer.class), replacement = mock(ServerPlayer.class);
        when(old.getUUID()).thenReturn(id);
        when(replacement.getUUID()).thenReturn(id);
        var field = CarpetServerProtocol.class.getDeclaredField("clients");
        field.setAccessible(true);
        var clients = (Map<UUID, Object>) field.get(null);
        var constructor = Class.forName(CarpetServerProtocol.class.getName() + "$ClientSession")
                .getDeclaredConstructor(ServerPlayer.class, String.class);
        constructor.setAccessible(true);
        boolean secret = GeneralCompatConfig.superSecretSetting;
        GeneralCompatConfig.superSecretSetting = false;
        try {
            clients.put(id, constructor.newInstance(old, "old"));
            assertTrue(CarpetServerProtocol.isValidCarpetPlayer(old));
            clients.put(id, constructor.newInstance(replacement, "new"));
            CarpetServerProtocol.onPlayerLeave(old);
            assertFalse(CarpetServerProtocol.isValidCarpetPlayer(old));
            assertEquals("vanilla", CarpetServerProtocol.getPlayerStatus(old));
            assertTrue(CarpetServerProtocol.isValidCarpetPlayer(replacement));
            assertEquals("carpet new", CarpetServerProtocol.getPlayerStatus(replacement));
            CarpetServerProtocol.onPlayerLeave(replacement);
            assertFalse(CarpetServerProtocol.isValidCarpetPlayer(replacement));
        } finally {
            clients.remove(id);
            GeneralCompatConfig.superSecretSetting = secret;
        }
    }
}
