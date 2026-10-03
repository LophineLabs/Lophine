// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;

/** AMS command-owned values, published as immutable snapshots for protocol readers. */
public final class AmsManagementSettings {
    static final Set<String> ANTI_FIRE = ConcurrentHashMap.newKeySet();
    static final Set<String> MOVABLE = ConcurrentHashMap.newKeySet();
    static final Map<BlockState, Float> HARDNESS = new ConcurrentHashMap<>();
    static final Map<BlockState, Float> RESISTANCE = new ConcurrentHashMap<>();
    static final Map<String, Integer> PERMISSIONS = new ConcurrentHashMap<>();
    static final Map<String, UUID> LEADERS = new ConcurrentHashMap<>();
    static final Map<UUID, String> POSES = new ConcurrentHashMap<>();
    static final Set<UUID> NO_PORTAL = ConcurrentHashMap.newKeySet();
    static volatile boolean noPortalGlobal, anvilDisabled;
    private static final java.util.List<String> POSE_SUGGESTIONS = java.util.List.of("spin_attack", "swimming", "sleeping", "fall_flying", "standing", "crouching", "dying");
    private static final Map<String, Pose> POSE_NAMES = Map.of("spin_attack", Pose.SPIN_ATTACK, "swimming", Pose.SWIMMING,
        "sleeping", Pose.SLEEPING, "fall_flying", Pose.FALL_FLYING, "standing", Pose.STANDING, "crouching", Pose.CROUCHING, "dying", Pose.DYING);
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("AMS-management-storage").factory());
    private static final Set<String> UNREADABLE = ConcurrentHashMap.newKeySet();
    private static volatile MinecraftServer loaded;
    private AmsManagementSettings() {}
    public static Map<BlockState, Float> hardnessSnapshot() { return Map.copyOf(HARDNESS); }
    public static Map<UUID, String> poseSnapshot() { return Map.copyOf(POSES); }
    public static boolean antiFire(ItemStack stack) { return enabled(GeneralCompatConfig.commandCustomAntiFireItems) && ANTI_FIRE.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); }
    public static boolean anvilDisabled() { return enabled(GeneralCompatConfig.commandAnvilInteractionDisabled) && anvilDisabled; }
    public static boolean customMovable(BlockState state) { return enabled(GeneralCompatConfig.commandCustomMovableBlock) && MOVABLE.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()); }
    public static boolean enabledHardness() { return enabled(GeneralCompatConfig.commandCustomBlockHardness); }
    public static boolean hasHardness(BlockState state) { return enabledHardness() && HARDNESS.containsKey(state.getBlock().defaultBlockState()); }
    public static float hardness(BlockState state, float original) { return enabled(GeneralCompatConfig.commandCustomBlockHardness) ? HARDNESS.getOrDefault(state.getBlock().defaultBlockState(), original) : original; }
    public static float resistance(BlockState state, float original) { return enabled(GeneralCompatConfig.commandCustomBlockBlastResistance) && GeneralCompatConfig.enhancedWorldEater == -1 ? RESISTANCE.getOrDefault(state.getBlock().defaultBlockState(), original) : original; }
    public static Pose pose(Entity entity, Pose original) {
        if (!enabled(GeneralCompatConfig.commandSetPlayerPose) || !(entity instanceof Player)) return original;
        String pose = POSES.get(entity.getUUID()); return pose == null ? original : POSE_NAMES.getOrDefault(pose, original);
    }
    public static boolean noPortal(Entity entity) { return enabled(GeneralCompatConfig.commandPlayerNoNetherPortalTeleport) && entity instanceof Player && (noPortalGlobal || NO_PORTAL.contains(entity.getUUID())); }
    static boolean enabled(String rule) { return !"false".equalsIgnoreCase(rule); }
    static java.util.List<String> poseNames() { return POSE_SUGGESTIONS; }
    static Path file(MinecraftServer server, String name) { return server.getWorldPath(LevelResource.ROOT).resolve("carpetamsaddition/" + name + ".json"); }
    public static synchronized void load(MinecraftServer server) {
        if (loaded == server) return;
        loaded = server; UNREADABLE.clear();
        ANTI_FIRE.clear(); MOVABLE.clear(); HARDNESS.clear(); RESISTANCE.clear(); PERMISSIONS.clear(); LEADERS.clear(); POSES.clear(); NO_PORTAL.clear(); noPortalGlobal = false; anvilDisabled = false;
        loadSet(server, "custom_anti_fire_items", ANTI_FIRE);
        loadSet(server, "custom_movable_block", MOVABLE);
        loadBlocks(server, "custom_block_hardness", HARDNESS);
        loadBlocks(server, "custom_block_blast_resistance", RESISTANCE);
        read(server, "custom_command_permission_level", json -> { var parsed = new java.util.HashMap<String,Integer>(); json.getAsJsonObject().entrySet().forEach(e -> parsed.put(e.getKey(), e.getValue().getAsInt())); PERMISSIONS.putAll(parsed); });
        read(server, "leader", json -> { var parsed = new java.util.HashMap<String,UUID>(); json.getAsJsonObject().entrySet().forEach(e -> parsed.put(e.getKey(), UUID.fromString(e.getValue().getAsString()))); LEADERS.putAll(parsed); });
    }
    private static void loadSet(MinecraftServer server, String name, Set<String> target) { read(server, name, json -> { var parsed = new java.util.HashSet<String>(); json.getAsJsonArray().forEach(e -> parsed.add(e.getAsString())); target.addAll(parsed); }); }
    private static void loadBlocks(MinecraftServer server, String name, Map<BlockState,Float> target) {
        read(server, name, json -> { var parsed = new java.util.HashMap<BlockState,Float>(); json.getAsJsonObject().entrySet().forEach(e -> {
            float value = e.getValue().getAsFloat();
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite block value");
            Identifier id = Identifier.parse(e.getKey());
            var block = BuiltInRegistries.BLOCK.get(id);
            if (block.isPresent()) parsed.put(block.get().value().defaultBlockState(), value);
        }); target.putAll(parsed); });
    }
    private static void read(MinecraftServer server, String name, java.util.function.Consumer<JsonElement> apply) {
        Path path = file(server, name);
        if (!java.nio.file.Files.exists(path)) return;
        try { apply.accept(JsonParser.parseString(java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception failure) { UNREADABLE.add(name); org.slf4j.LoggerFactory.getLogger("AMS-management").error("Cannot read {}. Original file retained", path, failure); }
    }
    static synchronized java.util.concurrent.CompletableFuture<Void> saveSet(MinecraftServer server, String name, Set<String> values) {
        JsonArray result = new JsonArray(); values.stream().sorted().forEach(result::add); return save(server, name, result);
    }
    static synchronized java.util.concurrent.CompletableFuture<Void> saveBlocks(MinecraftServer server, String name, Map<BlockState,Float> values) {
        JsonObject result = new JsonObject(); values.forEach((state, value) -> result.addProperty(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), value)); return save(server, name, result);
    }
    static synchronized java.util.concurrent.CompletableFuture<Void> saveLeaders(MinecraftServer server) {
        JsonObject json=new JsonObject(); LEADERS.forEach((name,id) -> json.addProperty(name,id.toString())); return save(server,"leader",json);
    }
    static synchronized java.util.concurrent.CompletableFuture<Void> savePermissions(MinecraftServer server) {
        JsonObject json=new JsonObject(); PERMISSIONS.forEach(json::addProperty); return save(server,"custom_command_permission_level",json);
    }
    static java.util.concurrent.CompletableFuture<Void> save(MinecraftServer server,String name,JsonElement contents) {
        var actual=new java.util.concurrent.CompletableFuture<Void>(){@Override public boolean cancel(boolean interrupt){return false;}};
        carpet.script.external.ScarpetNativeWork.record(actual);carpet.script.external.ScarpetNativeWork.trackNative(server,actual);AmsNativeCommandEffects.receipt(actual);
        if(UNREADABLE.contains(name)){actual.completeExceptionally(new IllegalStateException("Original AMS data file is unreadable: "+name));return actual;}
        try{
        String snapshot=new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(contents);Path path=file(server,name).toAbsolutePath();
        IO.execute(()->{
            Path temporary=null;Throwable problem=null;
            try{
                java.nio.file.Files.createDirectories(path.getParent());temporary=java.nio.file.Files.createTempFile(path.getParent(),name+"-",".tmp");
                java.nio.file.Files.writeString(temporary,snapshot,java.nio.charset.StandardCharsets.UTF_8);
                try(var channel=java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
                try{java.nio.file.Files.move(temporary,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
                catch(java.nio.file.AtomicMoveNotSupportedException missing){java.nio.file.Files.move(temporary,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            }catch(Throwable failure){org.slf4j.LoggerFactory.getLogger("AMS-management").error("Cannot save {}",path,failure);problem=failure;}
            finally{if(temporary!=null)try{java.nio.file.Files.deleteIfExists(temporary);}catch(java.io.IOException cleanup){if(problem==null)problem=cleanup;else problem.addSuppressed(cleanup);}}
            if(problem==null)actual.complete(null);else actual.completeExceptionally(problem);
        });}catch(Throwable failure){actual.completeExceptionally(failure);}
        return actual;
    }
    public static void flushAtShutdown() {
        try { IO.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception failure) { org.slf4j.LoggerFactory.getLogger("AMS-management").error("AMS management data flush failed", failure); }
    }
}
