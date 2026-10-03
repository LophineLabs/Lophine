package fun.bm.lophine.carpet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TisSpeedTestCompressionTest {
    @Test
    void skipsOnlyTheMarkedFrameAndPreservesOtherChannels() {
        EmbeddedChannel marked = new EmbeddedChannel(new CompressionEncoder(1));
        EmbeddedChannel independent = new EmbeddedChannel(new CompressionEncoder(1));
        try {
            marked.attr(TisSpeedTestCommand.SKIP_COMPRESSION).set(true);
            assertCompressed(independent);
            assertTrue(marked.writeOutbound(Unpooled.buffer(1024).writeZero(1024)));
            ByteBuf frame = marked.readOutbound();
            try {
                assertEquals(0, VarInt.read(frame));
                assertEquals(1024, frame.readableBytes());
            } finally { frame.release(); }
            assertCompressed(marked);
        } finally {
            marked.finishAndReleaseAll();
            independent.finishAndReleaseAll();
        }
    }

    private static void assertCompressed(EmbeddedChannel channel) {
        assertTrue(channel.writeOutbound(Unpooled.buffer(1024).writeZero(1024)));
        ByteBuf frame = channel.readOutbound();
        try {
            assertEquals(1024, VarInt.read(frame));
            assertTrue(frame.readableBytes() < 1024);
        } finally { frame.release(); }
    }
}
