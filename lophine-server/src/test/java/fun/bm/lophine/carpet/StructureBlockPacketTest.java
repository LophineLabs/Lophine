package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureBlockPacketTest {
    private int previousLimit;

    @BeforeEach
    void rememberLimit() {
        this.previousLimit = GeneralCompatConfig.structureBlockLimit;
    }

    @AfterEach
    void restoreLimit() {
        GeneralCompatConfig.structureBlockLimit = this.previousLimit;
    }

    @ParameterizedTest
    @ValueSource(ints = {48, 256})
    void vanillaClientPacketRemainsReadable(final int limit) {
        GeneralCompatConfig.structureBlockLimit = limit;
        FriendlyByteBuf buffer = encodedVanillaPacket();
        try {
            ServerboundSetStructureBlockPacket decoded = ServerboundSetStructureBlockPacket.STREAM_CODEC.decode(buffer);
            assertEquals(new BlockPos(-12, 24, 47), decoded.getOffset());
            assertEquals(new Vec3i(48, 32, 16), decoded.getSize());
            assertEquals("carpet:fixture", decoded.getName());
            assertEquals(123456789L, decoded.getSeed());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void carpetExtensionUsesFullIntegersAndEnforcesConfiguredBounds() {
        GeneralCompatConfig.structureBlockLimit = 256;
        FriendlyByteBuf buffer = encodedVanillaPacket();
        try {
            buffer.writeInt(-512).writeInt(192).writeInt(512);
            buffer.writeInt(1024).writeInt(-1).writeInt(192);
            ServerboundSetStructureBlockPacket decoded = ServerboundSetStructureBlockPacket.STREAM_CODEC.decode(buffer);
            assertEquals(new BlockPos(-256, 192, 256), decoded.getOffset());
            assertEquals(new Vec3i(256, 0, 192), decoded.getSize());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {23, 25})
    void malformedExtensionRemainsForOuterPacketValidation(final int length) {
        FriendlyByteBuf buffer = encodedVanillaPacket();
        try {
            buffer.writeZero(length);
            ServerboundSetStructureBlockPacket.STREAM_CODEC.decode(buffer);
            assertEquals(length, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf encodedVanillaPacket() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ServerboundSetStructureBlockPacket packet = new ServerboundSetStructureBlockPacket(
                new BlockPos(1, 64, 2), StructureBlockEntity.UpdateType.UPDATE_DATA, StructureMode.SAVE, "carpet:fixture",
                new BlockPos(-12, 24, 47), new Vec3i(48, 32, 16), Mirror.NONE, Rotation.NONE, "fixture",
                true, false, false, true, 1.0F, 123456789L
        );
        ServerboundSetStructureBlockPacket.STREAM_CODEC.encode(buffer, packet);
        return buffer;
    }
}
