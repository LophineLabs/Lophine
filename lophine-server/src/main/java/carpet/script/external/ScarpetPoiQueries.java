// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.value.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;

/** Each POI file is loaded by its actual owner at task-queue MAX level; no FULL generation ticket. */
public final class ScarpetPoiQueries {
    private record Match(String type, int occupied, BlockPos position) {
        Value value() { return ListValue.of(StringValue.of(type), NumericValue.of(occupied), ValueConversions.of(position)); }
    }
    private ScarpetPoiQueries() {}
    private static Match capture(ServerLevel world, PoiRecord poi) {
        return new Match(NBTSerializableValue.nameFromResource(world.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getKey(poi.getPoiType().value())),
            poi.getPoiType().value().maxTickets() - Vanilla.PoiRecord_getFreeTickets(poi), poi.getPos().immutable());
    }
    public static Value point(ServerLevel world, BlockPos position) {
        return ScarpetRuntime.atBlock(world, position, () -> {
            PoiManager manager = world.getPoiManager();
            var type = manager.getType(position);
            if (type.isEmpty()) return Value.NULL;
            PoiRecord poi = manager.getInChunk(holder -> holder.value() == type.get().value(), new ChunkPos(position.getX() >> 4, position.getZ() >> 4), PoiManager.Occupancy.ANY)
                .filter(record -> record.getPos().equals(position)).findFirst().orElse(null);
            if (poi == null) return Value.NULL;
            Match match = capture(world, poi);
            return ListValue.of(StringValue.of(match.type()), NumericValue.of(match.occupied()));
        });
    }
    public static Value range(ServerLevel world, BlockPos position, int radius, Predicate<Holder<PoiType>> types, PoiManager.Occupancy occupancy, boolean column) {
        if (radius < 0) return ListValue.of();
        int minX = (position.getX() - radius) >> 4, maxX = (position.getX() + radius) >> 4;
        int minZ = (position.getZ() - radius) >> 4, maxZ = (position.getZ() + radius) >> 4;
        double maximum = (double)radius * (double)radius;
        List<CompletableFuture<List<Match>>> jobs = new ArrayList<>();
        for (int z = minZ; z <= maxZ; ++z) for (int x = minX; x <= maxX; ++x) {
            ChunkPos chunk = new ChunkPos(x, z);
            jobs.add(ScarpetRuntime.atBlockFuture(world, new BlockPos(x << 4, position.getY(), z << 4), () -> world.getPoiManager().getInChunk(types, chunk, occupancy)
                .filter(poi -> Math.abs((long)poi.getPos().getX() - position.getX()) <= radius && Math.abs((long)poi.getPos().getZ() - position.getZ()) <= radius)
                .filter(poi -> column || poi.getPos().distSqr(position) <= maximum).map(poi -> capture(world, poi)).toList()));
        }
        ScarpetRuntime.await(CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)));
        List<Match> combined = new ArrayList<>();
        for (var job : jobs) combined.addAll(job.getNow(List.of()));
        combined.sort(Comparator.comparingDouble(match -> match.position().distSqr(position)));
        return ListValue.wrap(combined.stream().map(Match::value));
    }
}
