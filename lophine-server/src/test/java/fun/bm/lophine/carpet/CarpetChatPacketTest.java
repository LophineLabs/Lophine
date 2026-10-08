package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import me.earthme.luminol.config.modules.fixes.LongCommandSupportConfig;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.BitSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CarpetChatPacketTest {
    private boolean previousRule;
    private boolean previousLongCommands;

    @BeforeEach
    void rememberRules() {
        this.previousRule = GeneralCompatConfig.chatMessageLengthLimitUnlocked;
        this.previousLongCommands = LongCommandSupportConfig.enabled;
        GeneralCompatConfig.chatMessageLengthLimitUnlocked = false;
        LongCommandSupportConfig.enabled = false;
    }

    @AfterEach
    void restoreRules() {
        GeneralCompatConfig.chatMessageLengthLimitUnlocked = this.previousRule;
        LongCommandSupportConfig.enabled = this.previousLongCommands;
    }

    @Test
    void vanillaChatLimitRemainsAndChangesWithoutReinitializingTheCodec() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerboundChatPacket.STREAM_CODEC.encode(buffer, chat("a".repeat(256)));
            assertEquals(256, ServerboundChatPacket.STREAM_CODEC.decode(buffer).message().length());
            assertThrows(EncoderException.class, () -> ServerboundChatPacket.STREAM_CODEC.encode(buffer, chat("a".repeat(257))));
            buffer.clear();
            GeneralCompatConfig.chatMessageLengthLimitUnlocked = true;
            ServerboundChatPacket.STREAM_CODEC.encode(buffer, chat("a".repeat(32000)));
            assertEquals(32000, ServerboundChatPacket.STREAM_CODEC.decode(buffer).message().length());
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            assertThrows(EncoderException.class, () -> ServerboundChatPacket.STREAM_CODEC.encode(buffer, chat("a".repeat(32001))));
        } finally {
            buffer.release();
        }
    }

    @Test
    void outgoingSignedMessageBodyPreservesLongUnicodeContent() {
        GeneralCompatConfig.chatMessageLengthLimitUnlocked = true;
        var packed = new SignedMessageBody.Packed("测试".repeat(1000), Instant.EPOCH, 7L, LastSeenMessages.Packed.EMPTY);
        var buffer = Unpooled.buffer();
        try {
            SignedMessageBody.Packed.STREAM_CODEC.encode(buffer, packed);
            assertEquals(packed, SignedMessageBody.Packed.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void bothSignedAndUnsignedCommandsAcceptLongInputWhenEnabled() {
        GeneralCompatConfig.chatMessageLengthLimitUnlocked = true;
        String command = "say " + "x".repeat(1024);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var unsigned = new ServerboundChatCommandPacket(command);
            ServerboundChatCommandPacket.STREAM_CODEC.encode(buffer, unsigned);
            assertEquals(unsigned, ServerboundChatCommandPacket.STREAM_CODEC.decode(buffer));
            buffer.clear();
            var signed = new ServerboundChatCommandSignedPacket(command, Instant.EPOCH, 1L, ArgumentSignatures.EMPTY, lastSeen());
            ServerboundChatCommandSignedPacket.STREAM_CODEC.encode(buffer, signed);
            assertEquals(signed, ServerboundChatCommandSignedPacket.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static LastSeenMessages.Update lastSeen() {
        return new LastSeenMessages.Update(0, new BitSet(), (byte) 0);
    }

    private static ServerboundChatPacket chat(String text) {
        return new ServerboundChatPacket(text, Instant.EPOCH, 0L, Optional.empty(), lastSeen());
    }
}
