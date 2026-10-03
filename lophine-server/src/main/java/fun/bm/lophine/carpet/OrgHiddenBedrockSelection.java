// SPDX-License-Identifier: MIT
// Org BlockPosTraverser/CylinderBlockPosTraverser and ActionSerializeType source geometry.
package fun.bm.lophine.carpet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

record OrgHiddenBedrockSelection(BlockPos from, BlockPos to, BlockPos center, int radius, int height) {
    OrgHiddenBedrockSelection {
        from = new BlockPos(Math.max(from.getX(),-Level.MAX_LEVEL_SIZE),Math.max(from.getY(),Level.MIN_ENTITY_SPAWN_Y),Math.max(from.getZ(),-Level.MAX_LEVEL_SIZE));
        to = new BlockPos(Math.min(to.getX(),Level.MAX_LEVEL_SIZE),Math.min(to.getY(),Level.MAX_ENTITY_SPAWN_Y),Math.min(to.getZ(),Level.MAX_LEVEL_SIZE));
    }
    static OrgHiddenBedrockSelection cuboid(BlockPos a, BlockPos b) {
        return new OrgHiddenBedrockSelection(new BlockPos(Math.min(a.getX(), b.getX()),Math.min(a.getY(), b.getY()),Math.min(a.getZ(), b.getZ())),
            new BlockPos(Math.max(a.getX(), b.getX()),Math.max(a.getY(), b.getY()),Math.max(a.getZ(), b.getZ())), null, 0, 0);
    }
    static OrgHiddenBedrockSelection cylinder(BlockPos center, int radius, int height) {
        return new OrgHiddenBedrockSelection(center.offset(-radius,0,-radius),center.offset(radius,height-1,radius),center.immutable(),radius,height);
    }
    static OrgHiddenBedrockSelection read(JsonObject object) {
        String type = object.has("region_type") ? object.get("region_type").getAsString() : "cuboid";
        return switch (type) {
            case "cuboid" -> cuboid(position(object.getAsJsonArray("from")),position(object.getAsJsonArray("to")));
            case "cylinder" -> cylinder(position(object.getAsJsonArray("center")),object.get("radius").getAsInt(),object.get("height").getAsInt());
            default -> null; // Source ActionSerializeType.BEDROCK deserializes an unknown region as STOP.
        };
    }
    JsonObject write(boolean ai, boolean recycle) {
        var object = new JsonObject(); object.addProperty("region_type",center == null ? "cuboid" : "cylinder");
        if (center == null) { object.add("from",position(from)); object.add("to",position(to)); }
        else { object.add("center",position(center)); object.addProperty("radius",radius); object.addProperty("height",Math.max(0,to.getY()-from.getY()+1)); }
        object.addProperty("ai",ai); object.addProperty("timed_material_recycling",recycle); return object;
    }
    boolean contains(BlockPos block) {
        return block.getX() >= from.getX() && block.getX() <= to.getX() && block.getY() >= from.getY() && block.getY() <= to.getY()
            && block.getZ() >= from.getZ() && block.getZ() <= to.getZ() && horizontal(block.getX(),block.getZ());
    }
    boolean horizontal(int x, int z) {
        if (center == null) return true;
        double dx = (double)x-center.getX(), dz = (double)z-center.getZ();
        // The source cylinder uses rounded integer X/Z Euclidean distance, including edge cells.
        return Math.round(Math.sqrt(dx*dx+dz*dz)) <= radius;
    }
    AABB box() { return new AABB(from.getX(),from.getY(),from.getZ(),(double)to.getX()+1,(double)to.getY()+1,(double)to.getZ()+1); }
    Iterator<BlockPos> columns() {
        return new Iterator<>() {
            int x=from.getX(), z=from.getZ(); BlockPos next;
            public boolean hasNext() {
                if (next != null) return true;
                while (from.getY() <= to.getY() && x <= to.getX() && z <= to.getZ()) {
                    int currentX=x, currentZ=z;
                    if (++x > to.getX()) { x=from.getX(); z++; }
                    if (horizontal(currentX,currentZ)) { next=new BlockPos(currentX,from.getY(),currentZ); return true; }
                }
                return false;
            }
            public BlockPos next() { if (!hasNext()) throw new NoSuchElementException(); var result=next;next=null;return result; }
        };
    }
    static BlockPos position(JsonArray array) {
        if (array == null) throw new IllegalArgumentException("Missing bedrock position");
        return new BlockPos(array.get(0).getAsInt(),array.get(1).getAsInt(),array.get(2).getAsInt());
    }
    static JsonArray position(BlockPos block) { var array=new JsonArray();array.add(block.getX());array.add(block.getY());array.add(block.getZ());return array; }
}
