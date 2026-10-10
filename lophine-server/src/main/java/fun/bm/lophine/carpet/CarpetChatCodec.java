package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.Utf8String;
import net.minecraft.network.codec.StreamCodec;

import java.util.function.BooleanSupplier;

public final class CarpetChatCodec {
    private CarpetChatCodec() {
    }

    public static StreamCodec<ByteBuf, String> chat(StreamCodec<ByteBuf, String> original) {
        return dynamic(original, () -> GeneralCompatConfig.chatMessageLengthLimitUnlocked, 32000);
    }

    public static StreamCodec<ByteBuf, String> command(StreamCodec<ByteBuf, String> original) {
        return dynamic(original, () -> GeneralCompatConfig.chatMessageLengthLimitUnlocked
                || me.earthme.luminol.config.modules.fixes.LongCommandSupportConfig.enabled, 32767);
    }

    private static StreamCodec<ByteBuf, String> dynamic(StreamCodec<ByteBuf, String> original, BooleanSupplier enabled, int limit) {
        return new StreamCodec<>() {
            @Override
            public String decode(ByteBuf input) {
                return enabled.getAsBoolean() ? Utf8String.read(input, limit) : original.decode(input);
            }

            @Override
            public void encode(ByteBuf output, String value) {
                if (enabled.getAsBoolean()) Utf8String.write(output, value, limit);
                else original.encode(output, value);
            }
        };
    }
}
