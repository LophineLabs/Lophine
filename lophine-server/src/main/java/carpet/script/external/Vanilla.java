package carpet.script.external;

import carpet.script.CarpetScriptServer;
import carpet.script.EntityEventsGroup;
import carpet.script.value.MapValue;
import carpet.script.value.StringValue;
import carpet.script.value.Value;
import com.mojang.brigadier.CommandDispatcher;
import fun.bm.lophine.carpet.CarpetCommandPermissions;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.tags.TagKey;
import net.minecraft.util.TaskChainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.leavesmc.leaves.protocol.CarpetServerProtocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public class Vanilla {
    public static void MinecraftServer_forceTick(MinecraftServer server, BooleanSupplier sup) {
        ScarpetRuntime.awaitNextTick(server);
    }

    public static void ChunkMap_relightChunk(ChunkMap chunkMap, ChunkPos pos) {
        //((ThreadedAnvilChunkStorageInterface) chunkMap).relightChunk(pos);
    }

    public static Map<String, Integer> ChunkMap_regenerateChunkRegion(ChunkMap chunkMap, List<ChunkPos> requestedChunks) {
        return Map.of(); //return ((ThreadedAnvilChunkStorageInterface) chunkMap).regenerateChunkRegion(requestedChunks);
    }

    public static int NaturalSpawner_MAGIC_NUMBER() {
        return 17 * 17;
    }

    public static PotentialCalculator SpawnState_getPotentialCalculator(NaturalSpawner.SpawnState spawnState) {
        return spawnState.carpetGetPotentialCalculator();
    }

    public static void Objective_setCriterion(Objective objective, ObjectiveCriteria criterion) {
        objective.carpetSetCriterion(criterion);
    }

    public static Map<ObjectiveCriteria, List<Objective>> Scoreboard_getObjectivesByCriterion(Scoreboard scoreboard) {
        return scoreboard.carpetGetObjectivesByCriterion();
    }

    public static Long2ObjectOpenHashMap<List<Ticket>> ChunkTicketManager_getTicketsByPosition(DistanceManager ticketManager) {
        return copyTickets(ticketManager);
    }

    public static CompoundTag BlockInput_getTag(BlockInput blockInput) {
        return blockInput.carpetGetTag();
    }

    public static CarpetScriptServer MinecraftServer_getScriptServer(MinecraftServer server) {
        return ScarpetRuntime.of(server).scriptServer();
    }

    public static Biome.ClimateSettings Biome_getClimateSettings(Biome biome) {
        return biome.climateSettings;
    }

    public static ThreadLocal<Boolean> skipGenerationChecks(ServerLevel level) { // not sure does vanilla care at all - needs checking
        return ScarpetRuntime.SKIP_GENERATION_CHECKS;
    }

    public static void sendScarpetShapesDataToPlayer(ServerPlayer player, Tag data) { // dont forget to add the packet to vanilla packed handler and call ShapesRenderer.addShape to handle on client
        CarpetServerProtocol.sendCustomCommand(player, "scShapes", data);
    }

    public static PermissionSet MinecraftServer_getRunPermissionLevel(MinecraftServer server) {
        return ScarpetRuntime.of(server).runPermission();
    }

    public static void CommandSourceStack_setupPrivates(CommandSourceStack css, boolean bl, CommandResultCallback commandResultCallback, EntityAnchorArgument.Anchor anchor, CommandSigningContext commandSigningContext, TaskChainer taskChainer) {
        css.carpetSetupPrivates(bl, commandResultCallback, anchor, commandSigningContext, taskChainer);
    }

    ;

    public static int[] MinecraftServer_getReleaseTarget(MinecraftServer server) {
        String version = net.minecraft.SharedConstants.getCurrentVersion().name();
        java.util.regex.Matcher parts = java.util.regex.Pattern.compile("([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?").matcher(version);
        if (!parts.find()) return new int[]{0, 0, 0};
        return new int[]{Integer.parseInt(parts.group(1)), Integer.parseInt(parts.group(2)), parts.group(3) == null ? 0 : Integer.parseInt(parts.group(3))};
    }

    public static boolean isDevelopmentEnvironment() {
        return false;
    }

    public static MapValue getServerMods(MinecraftServer server) {
        Map<Value, Value> ret = new HashMap<>();
        platformVersions().forEach((name, version) -> ret.put(new StringValue(name), new StringValue(version)));
        return MapValue.wrap(ret);
    }

    public static Map<MemoryModuleType<?>, MemorySlot<?>> Brain_getMemories(Brain brain) {
        return brain.carpetGetMemories();
    }

    public static LevelStorageSource.LevelStorageAccess MinecraftServer_storageSource(MinecraftServer server) {
        return server.carpetGetStorageSource();
    }

    public static BlockPos ServerPlayerGameMode_getCurrentBlockPosition(ServerPlayerGameMode gameMode) {
        return gameMode.carpetGetBreakingBlock();
    }

    public static int ServerPlayerGameMode_getCurrentBlockBreakingProgress(ServerPlayerGameMode gameMode) {
        return gameMode.carpetGetBreakingProgress();
    }

    public static void ServerPlayerGameMode_setBlockBreakingProgress(ServerPlayerGameMode gameMode, int progress) {
        gameMode.carpetSetBreakingProgress(progress);
    }

    public static boolean ServerPlayer_isInvalidEntityObject(ServerPlayer player) {
        return player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player;
    }

    public static GoalSelector Mob_getAI(Mob mob, boolean target) {
        return target ? mob.targetSelector : mob.getGoalSelector();
    }

    public static Map<String, Goal> Mob_getTemporaryTasks(Mob mob) {
        return mob.carpetTemporaryTasks;
    }

    public static void Mob_setPersistence(Mob mob, boolean what) {
        mob.persistenceRequired = what;
    }

    public static EntityEventsGroup Entity_getEventContainer(Entity entity) {
        return entity.carpetGetEventContainer();
    }

    public static boolean Entity_isPermanentVehicle(Entity entity) {
        return entity.carpetPermanentVehicle;
    }

    public static void Entity_setPermanentVehicle(Entity entity, boolean permanent) {
        entity.carpetPermanentVehicle = permanent;
    }

    public static int Entity_getPortalTimer(Entity entity) {
        return entity.portalProcess == null ? 0 : entity.portalProcess.getPortalTime();
    }

    public static void Entity_setPortalTimer(Entity entity, int amount) {
        entity.carpetSetPortalTime(amount);
    }

    public static int Entity_getPublicNetherPortalCooldown(Entity entity) {
        return entity.getPortalCooldown();
    }

    public static void Entity_setPublicNetherPortalCooldown(Entity entity, int what) {
        entity.setPortalCooldown(what);
    }

    public static int ItemEntity_getPickupDelay(ItemEntity entity) {
        return entity.pickupDelay;
    }

    public static boolean LivingEntity_isJumping(LivingEntity entity) {
        return entity.isJumping();
    }

    public static void LivingEntity_setJumping(LivingEntity entity) {
        entity.carpetJumpFromGround();
    }

    public static Container AbstractHorse_getInventory(AbstractHorse horse) {
        return horse.inventory;
    }

    public static DataSlot AbstractContainerMenu_getDataSlot(AbstractContainerMenu handler, int index) {
        return handler.dataSlots.get(index);
    }

    public static void CommandDispatcher_unregisterCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        ScarpetRuntime.atGlobal(net.minecraft.server.MinecraftServer.getServer(), () -> {
            dispatcher.getRoot().removeCommand(name);
            return null;
        });
    }

    public static boolean MinecraftServer_doScriptsAutoload(MinecraftServer server) {
        return GeneralCompatConfig.scriptsAutoload;
    }

    public static void MinecraftServer_notifyPlayersCommandsChanged(MinecraftServer server) {
        notifyPlayers(server);
    }

    public static boolean ScriptServer_scriptOptimizations(MinecraftServer scriptServer) {
        return GeneralCompatConfig.scriptsOptimization;
    }

    public static boolean ScriptServer_scriptDebugging(MinecraftServer server) {
        return GeneralCompatConfig.scriptsDebugging;
    }

    public static boolean ServerPlayer_canScriptACE(CommandSourceStack player) {
        return CarpetCommandPermissions.canUse(player, GeneralCompatConfig.commandScriptACE);
    }

    public static boolean ServerPlayer_canScriptGeneral(CommandSourceStack player) {
        return CarpetCommandPermissions.canUse(player, GeneralCompatConfig.commandScript);
    }

    public static int PoiRecord_getFreeTickets(PoiRecord record) {
        return record.carpetGetFreeTickets();
    }

    public static void PoiRecord_callAcquireTicket(PoiRecord record) {
        record.carpetAcquireTicket();
    }

    public static double FoodData_getExhaustion(FoodData foodData) {
        return foodData.exhaustionLevel;
    }

    public static void FoodData_setExhaustion(FoodData foodData, float exhaustion) {
        foodData.exhaustionLevel = exhaustion;
    }

    public record BlockPredicatePayload(BlockState state, TagKey<Block> tagKey, Map<Value, Value> properties,
                                        CompoundTag tag) {
        public static BlockPredicatePayload of(Predicate<BlockInWorld> blockPredicate) {
            return ((net.minecraft.commands.arguments.blocks.BlockPredicateArgument.Result) blockPredicate).carpetPayload();
        }
    }

    private static Long2ObjectOpenHashMap<List<Ticket>> copyTickets(DistanceManager manager) {
        Long2ObjectOpenHashMap<List<Ticket>> copy = new Long2ObjectOpenHashMap<>();
        manager.moonrise$getChunkHolderManager().getTicketsCopy().forEach((key, tickets) -> copy.put(key, List.copyOf(tickets)));
        return copy;
    }

    public static Map<String, String> platformVersions() {
        Map<String, String> versions = new HashMap<>();
        versions.put("minecraft", net.minecraft.SharedConstants.getCurrentVersion().name());
        versions.put("carpet", Carpet.getCarpetVersion());
        versions.put("lophine", org.bukkit.Bukkit.getVersion());
        for (org.bukkit.plugin.Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins())
            versions.put(plugin.getName().toLowerCase(java.util.Locale.ROOT), plugin.getPluginMeta().getVersion());
        return Map.copyOf(versions);
    }

    private static void notifyPlayers(MinecraftServer server) {
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers())
                player.getBukkitEntity().taskScheduler.schedule(owned ->
                        server.getCommands().sendCommands((ServerPlayer) owned), null, 1L);
        });
    }
}
