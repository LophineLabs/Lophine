package fun.bm.lophine.carpet;

import fun.bm.lophine.protocol.tiscm.TISCMProtocol;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TisSpeedTestSessionIdentityTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void oldConnectionsCannotAdvanceOrAbortAReplacementSessionWithTheSameUuid() throws Exception {
        try (var protocol = mockStatic(TISCMProtocol.class)) {
            UUID id = UUID.randomUUID();
            var original = mock(ServerPlayer.class);
            var replacement = mock(ServerPlayer.class);
            when(original.getUUID()).thenReturn(id);
            when(replacement.getUUID()).thenReturn(id);
            var source = mock(CommandSourceStack.class);
            Class<?> sessionType = Class.forName(TisSpeedTestCommand.class.getName() + "$Session");
            Class<?> transferType = Class.forName(TisSpeedTestCommand.class.getName() + "$Transfer");
            var constructor = transferType.getDeclaredConstructor(CommandSourceStack.class, ServerPlayer.class, boolean.class, int.class);
            constructor.setAccessible(true);
            Object current = constructor.newInstance(source, replacement, false, 1);
            var claim = TisSpeedTestCommand.class.getDeclaredMethod("claim", sessionType);
            claim.setAccessible(true);
            assertEquals(true, claim.invoke(null, current));
            var doneField = sessionType.getDeclaredField("done");
            doneField.setAccessible(true);
            var countField = transferType.getDeclaredField("completed");
            countField.setAccessible(true);
            var done = (AtomicBoolean) doneField.get(current);
            var count = (AtomicInteger) countField.get(current);
            try {
                var upload = new CompoundTag();
                upload.putByteArray("buf", new byte[16 * 1024 - 60]);
                TisSpeedTestCommand.handleUpload(original, upload);
                assertEquals(0, count.get());
                var pong = new CompoundTag();
                pong.putString("type", "pong");
                TisSpeedTestCommand.handlePing(original, pong);
                TisSpeedTestCommand.disconnected(original);
                assertFalse(done.get());
                TisSpeedTestCommand.handleUpload(replacement, upload);
                assertEquals(1, count.get());
                TisSpeedTestCommand.disconnected(replacement);
                assertTrue(done.get());
            } finally { TisSpeedTestCommand.reset(); }
        }
    }
}
