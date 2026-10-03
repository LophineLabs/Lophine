// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import java.util.Collection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.protocol.CarpetServerProtocol;

/** The exact server half of Carpet's scShapes packet, shared with addon markers. Colors are RGBA. */
public final class CarpetShapeSender {
    private CarpetShapeSender() {}

    public static CompoundTag box(ServerLevel world, Vec3 from, Vec3 to, int rgba, int fillRgba, float lineWidth, int duration) {
        CompoundTag shape = base(world, "box", rgba, duration);
        shape.put("from", vector(from)); shape.put("to", vector(to));
        shape.putInt("fill", fillRgba); shape.putFloat("line", lineWidth);
        return shape;
    }

    public static CompoundTag text(ServerLevel world, Vec3 position, Component text, int rgba, int duration) {
        CompoundTag shape = base(world, "label", rgba, duration);
        shape.put("pos", vector(position));
        shape.put("text", ComponentSerialization.CODEC.encodeStart(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), text).getOrThrow());
        return shape;
    }

    public static boolean send(ServerPlayer player, Collection<CompoundTag> shapes) {
        if (!CarpetServerProtocol.isValidCarpetPlayer(player)) return false;
        ListTag list = new ListTag();
        for (CompoundTag shape : shapes) {
            list.add(shape.copy());
            if (list.size() == 1000) { CarpetServerProtocol.sendCustomCommand(player, "scShapes", list); list = new ListTag(); }
        }
        if (!list.isEmpty()) CarpetServerProtocol.sendCustomCommand(player, "scShapes", list);
        return true;
    }

    private static CompoundTag base(ServerLevel world, String type, int rgba, int duration) {
        if (duration < 0) throw new IllegalArgumentException("Negative shape duration");
        CompoundTag shape = new CompoundTag();
        shape.putString("shape", type); shape.putString("dim", world.dimension().identifier().toString());
        shape.putInt("color", rgba); shape.putInt("duration", duration);
        return shape;
    }

    private static ListTag vector(Vec3 vector) {
        ListTag result = new ListTag();
        result.add(DoubleTag.valueOf(vector.x)); result.add(DoubleTag.valueOf(vector.y)); result.add(DoubleTag.valueOf(vector.z));
        return result;
    }
}
