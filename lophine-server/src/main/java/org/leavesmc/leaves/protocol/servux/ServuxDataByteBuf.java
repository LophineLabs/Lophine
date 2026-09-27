/*
 * This file is part of Lophine.
 *
 * Implements Servux 26.2's length-prefixed, gzip-compressed NBT payload format.
 */

package org.leavesmc.leaves.protocol.servux;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class ServuxDataByteBuf {
    private static final int MAX_COMPRESSED_SIZE = 16 * 1024 * 1024;

    private ServuxDataByteBuf() {
    }

    public static void write(FriendlyByteBuf buffer, CompoundTag tag) {
        try {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, compressed);
            byte[] bytes = compressed.toByteArray();
            buffer.writeInt(bytes.length);
            buffer.writeBytes(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode Servux NBT data", exception);
        }
    }

    public static CompoundTag read(FriendlyByteBuf buffer, long maxUncompressedSize) {
        byte[] bytes = readBytes(buffer);
        try {
            long limit = maxUncompressedSize < 0 ? Long.MAX_VALUE : maxUncompressedSize;
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes), new NbtAccounter(limit, 512));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not decode Servux NBT data", exception);
        }
    }

    public static void skip(FriendlyByteBuf buffer) {
        readBytes(buffer);
    }

    private static byte[] readBytes(FriendlyByteBuf buffer) {
        int length = buffer.readInt();
        if (length < 0 || length > MAX_COMPRESSED_SIZE || length > buffer.readableBytes()) {
            throw new IllegalArgumentException("Invalid Servux NBT payload length: " + length);
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return bytes;
    }
}
