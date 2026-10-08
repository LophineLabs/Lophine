package fun.bm.lophine.protocol;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class AmsProtocolWireTest {
    @Test
    void readsOriginalHandshakeWireLayout() {
        UUID player = UUID.randomUUID();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUtf("26.3");
            buffer.writeUUID(player);
            buffer.writeVarInt(2);
            buffer.writeUtf("sync_custom_block_hardness");
            buffer.writeUtf("handshake_s2c");
            var handshake = AmsNetworkProtocol.readClientHandshake(buffer, player);
            assertEquals("26.3", handshake.version());
            assertEquals(Set.of("sync_custom_block_hardness", "handshake_s2c"), handshake.packets());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void bindsHandshakeToConnectionAndBoundsPacketCount() {
        UUID actual = UUID.randomUUID();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int count : new int[]{-1, 129}) {
                buffer.clear();
                buffer.writeUtf("26.3");
                buffer.writeUUID(actual);
                buffer.writeVarInt(count);
                assertNull(AmsNetworkProtocol.readClientHandshake(buffer, actual));
            }
            buffer.clear();
            buffer.writeUtf("26.3");
            buffer.writeUUID(UUID.randomUUID());
            buffer.writeVarInt(0);
            assertNull(AmsNetworkProtocol.readClientHandshake(buffer, actual));
        } finally {
            buffer.release();
        }
    }
}
