// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.event.bot.BotRemoveEvent;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;

/** Persistent Org player manager. Global work exchanges immutable profiles; actor work owns every live player. */
public final class OrgPlayerManager {
    private static final Map<MinecraftServer, OrgPlayerManager> MANAGERS = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    final OrgPlayerProfileStore store;
    volatile CommandBuildContext access;
    private final Set<String> spawning = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spawningIds=ConcurrentHashMap.newKeySet();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private volatile carpet.script.external.WeakIdentityMap<ServerPlayer, Float> thresholds = new carpet.script.external.WeakIdentityMap<>();
    private final Map<String, Float> permanentThresholds = new ConcurrentHashMap<>();
    private final Map<String, Long> safeRuntimeIntents=new ConcurrentHashMap<>(),safePersistentIntents=new ConcurrentHashMap<>();
    private final Map<UUID, JsonObject> residents = new ConcurrentHashMap<>();
    private final Set<UUID> residentCaptures = ConcurrentHashMap.newKeySet();
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong captures = new AtomicLong();
    private final String captureSession=UUID.randomUUID().toString();
    private volatile ScheduledTask task;
    private volatile boolean closed;
    private volatile boolean autoLoginStarted;
    private String previousResidentTime;
    private static final class Job {
        final String name, kind; final CommandSourceStack source;
        volatile long due; volatile int interval; volatile boolean busy;
        volatile JsonObject profile;
        Job(String name, String kind, CommandSourceStack source, long due, int interval, JsonObject profile) { this.name=name;this.kind=kind;this.source=source;this.due=due;this.interval=interval;this.profile=profile; }
    }
    private OrgPlayerManager(MinecraftServer server, CommandBuildContext access) {
        this.server = server; this.access = access; this.store = new OrgPlayerProfileStore(server.getWorldPath(LevelResource.ROOT));
    }
    public static OrgPlayerManager start(MinecraftServer server, CommandBuildContext access) {
        OrgPlayerManager manager = MANAGERS.computeIfAbsent(server, ignored -> new OrgPlayerManager(server, access));
        manager.access=access;
        synchronized (manager) {
            if (manager.task == null) {
                manager.reloadSafeThresholds();
                manager.task = server.server.getGlobalRegionScheduler().runAtFixedRate(MinecraftInternalPlugin.INSTANCE, ignored -> manager.tickGlobal(), 1L, 1L);
                try { List<String> times=manager.residentTimes();manager.previousResidentTime=times.isEmpty()?null:times.getLast(); } catch(IOException failure){MinecraftServer.LOGGER.warn("Cannot load previous Org resident index",failure);}
            }
        }
        return manager;
    }
    public static OrgPlayerManager start(MinecraftServer server){return start(server,CommandBuildContext.simple(server.registryAccess(),server.getWorldData().enabledFeatures()));}
    static OrgPlayerManager get(MinecraftServer server) { OrgPlayerManager manager = MANAGERS.get(server); return manager==null?start(server):manager; }
    public static void close(MinecraftServer server) {
        OrgPlayerManager manager = MANAGERS.remove(server); if (manager == null) return;
        manager.closed=true; if (manager.task != null) manager.task.cancel(); manager.jobs.clear(); manager.spawning.clear(); manager.spawningIds.clear(); manager.thresholds=new carpet.script.external.WeakIdentityMap<>();
    }
    public static void observe(ServerPlayer player) {
        if (!(player instanceof ServerBot) || !TickThread.isTickThreadFor(player)) return;
        OrgPlayerManager manager=MANAGERS.get(player.level().getServer()); if (manager==null || manager.closed) return;
        manager.thresholds.computeIfAbsent(player,actor->manager.permanentThresholds.getOrDefault(actor.getScoreboardName(),-1F));
        if (player.tickCount % 100 == 0 && manager.residentCaptures.add(player.getUUID()))
            manager.captureIdle((ServerBot) player, false, (owner, profile) -> {
                if (!manager.closed) manager.residents.put(owner.getUUID(), profile);
            }).whenComplete((profile, failure) -> {
                manager.residentCaptures.remove(player.getUUID());
                if (failure != null && !manager.closed) MinecraftServer.LOGGER.warn("Cannot snapshot Org resident {}", player.getScoreboardName(), failure);
            });
    }
    public static float safeThreshold(ServerPlayer player) {
        OrgPlayerManager manager=MANAGERS.get(player.level().getServer()); if(manager==null)return -1F;Float current=manager.thresholds.get(player);return current==null?manager.permanentThresholds.getOrDefault(player.getScoreboardName(),-1F):current;
    }
    JsonObject capture(ServerBot player, boolean nativeState) throws IOException {
        TickThread.ensureTickThread(player,"Org profile capture requires fake owner");
        JsonObject profile=new JsonObject();profile.addProperty("data_version",5);profile.addProperty("_lophine_capture_order",captures.incrementAndGet());profile.addProperty("_lophine_capture_session",captureSession);
        JsonObject pos=new JsonObject();pos.addProperty("x",player.getX());pos.addProperty("y",player.getY());pos.addProperty("z",player.getZ());profile.add("pos",pos);
        JsonObject direction=new JsonObject();direction.addProperty("yaw",player.getYRot());direction.addProperty("pitch",player.getXRot());profile.add("direction",direction);
        profile.addProperty("dimension",player.level().dimension().identifier().toString());profile.addProperty("gamemode",player.gameMode.getGameModeForPlayer().getName());profile.addProperty("flying",player.getAbilities().flying);profile.addProperty("sneaking",player.isShiftKeyDown());
        profile.addProperty("autologin",false);profile.addProperty("annotation","");profile.add("group",new JsonArray());profile.add("startup_action",new JsonArray());
        JsonObject hidden=OrgHiddenPlayerActions.get(player);
        profile.add("script_action",hidden.get("name").getAsString().equals("stop")?OrgFakePlayerActionCodec.write(OrgFakePlayerActions.get(player)):OrgHiddenPlayerActions.save(player));
        CompoundTag pack=OrgHiddenPlayerActions.sanitizeActionPackSnapshot(player,player.carpetActionPack.save()); JsonObject simple=new JsonObject();
        for(Tag tag:pack.getListOrEmpty("actions")) if(tag instanceof CompoundTag action){JsonObject record=new JsonObject();record.addProperty("interval",action.getIntOr("interval",1));record.addProperty("continuous",action.getBooleanOr("continuous",false));simple.add(action.getStringOr("type","").toLowerCase(Locale.ROOT),record);}
        profile.add("simple_action",simple);profile.addProperty("_lophine_action_pack",encode(pack));profile.addProperty("_lophine_uuid",player.getUUID().toString());profile.addProperty("_lophine_name",player.getScoreboardName());
        if(nativeState){
            CompoundTag expected=OrgInventoryTransfers.inventoryTag(player);
            java.util.Optional<CompoundTag> saved;
            if(player.carpetNativePlayer){server.getPlayerList().carpetSaveFakePlayer(player);saved=server.getPlayerList().loadPlayerData(player.nameAndId());}
            else saved=server.getBotList().saveCarpetBotState(player);
            if(saved.isEmpty())throw new IOException("Cannot verify saved fake-player state");
            for(String key:expected.keySet()) if(!java.util.Objects.equals(expected.get(key),saved.get().get(key)))throw new IOException("Fake-player inventory save readback does not match "+key);
            if(saved.get().getIntOr("XpTotal",-1)!=player.totalExperience || saved.get().getIntOr("XpLevel",-1)!=player.experienceLevel)throw new IOException("Fake-player experience save readback does not match");
            Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path state=(player.carpetNativePlayer?server.getPlayerList().playerIo.getPlayerDir().toPath().resolve(player.getStringUUID()+".dat"):server.getBotList().getCarpetBotStatePath(player)).toAbsolutePath().normalize();
            if(!state.startsWith(root))throw new IOException("Fake-player state path leaves its world directory");
            profile.addProperty("_lophine_state_file",root.relativize(state).toString());
            // A manager profile never stores reusable inventory/XP. Spawn reloads the latest
            // real player file, so later consumption cannot be undone by an old saved profile.
            var skins=player.getGameProfile().properties().get("textures");
            for(var skin:skins)if(skin.signature()!=null){JsonArray texture=new JsonArray();texture.add(skin.value());texture.add(skin.signature());profile.add("_lophine_skin",texture);break;}
        }
        if(!profile.has("_lophine_state_file")){
            Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path state=(player.carpetNativePlayer?server.getPlayerList().playerIo.getPlayerDir().toPath().resolve(player.getStringUUID()+".dat"):server.getBotList().getCarpetBotStatePath(player)).toAbsolutePath().normalize();
            if(!state.startsWith(root))throw new IOException("Fake-player state path leaves its world directory");profile.addProperty("_lophine_state_file",root.relativize(state).toString());
        }
        return profile;
    }
    /** Pause every producer before capture; accepted effects and their owner state finish first. */
    private CompletableFuture<JsonObject> captureIdle(ServerBot player, boolean nativeState,
            java.util.function.BiConsumer<ServerBot, JsonObject> afterSnapshot) {
        return captureIdle(player, nativeState, afterSnapshot, false);
    }
    private CompletableFuture<JsonObject> captureIdle(ServerBot player, boolean nativeState,
            java.util.function.BiConsumer<ServerBot, JsonObject> afterSnapshot, boolean mandatory) {
        return OrgMenuNativeEffects.admit(server,()->OrgFakePlayerActions.owned(player, carpet.script.external.ScarpetRuntime.captureNativeContinuation(() -> {
            java.util.function.Supplier<JsonObject> snapshot = carpet.script.external.ScarpetRuntime.captureNativeContinuation(() -> {
            try {
                if (player.isDeadOrDying()) throw new IllegalStateException("Cannot snapshot a dead fake player");
                JsonObject profile = capture(player, nativeState);
                if (afterSnapshot != null) afterSnapshot.accept(player, profile);
                return profile;
            } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            return mandatory ? OrgFakePlayerActions.whenIdleForRemoval(player, snapshot) : OrgFakePlayerActions.whenIdle(player, snapshot);
        })).thenCompose(java.util.function.Function.identity()));
    }
    void save(CommandSourceStack source,String name,String comment,boolean replace){
        ServerPlayer player=server.getPlayerList().getPlayerByName(name);
        if(player instanceof ServerBot bot&&OrgDeferredPlayerCommands.deferIfCausal(bot,()->save(source,name,comment,replace),failure->message(source,"Cannot save profile: "+reason(failure))))return;
        saveAsync(source,name,comment,replace).whenComplete((value,failure)->{if(failure!=null)message(source,"Cannot save profile: "+reason(failure));});
    }
    CompletableFuture<Integer> saveAsync(CommandSourceStack source,String name,String comment,boolean replace){
        return OrgMenuNativeEffects.admit(server,()->TisCommandContinuations.then(filePhase(()->{try{return !replace&&store.names().contains(name);}catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}}),exists->{
            if(exists)return feedback(source,Component.literal("The fake-player profile already exists; use modify resave"),0);
            if(!replace&&GeneralCompatConfig.playerManagerForceComment&&comment.isBlank())return CompletableFuture.failedFuture(new IllegalArgumentException("A comment is required when saving a new fake-player profile"));
            ServerPlayer target=server.getPlayerList().getPlayerByName(name);if(!(target instanceof ServerBot bot)||closed)return CompletableFuture.failedFuture(new IllegalStateException("The selected fake player is unavailable"));
            return TisCommandContinuations.then(captureIdle(bot,true,null),snapshot->TisCommandContinuations.then(filePhase(()->{
                try{if(replace)store.modify(name,previous->{if(previous.has("_lophine_capture_session")&&previous.get("_lophine_capture_session").getAsString().equals(captureSession)&&previous.has("_lophine_capture_order")&&previous.get("_lophine_capture_order").getAsLong()>=snapshot.get("_lophine_capture_order").getAsLong())return previous;for(String field:List.of("annotation","autologin","group","startup_action"))if(previous.has(field))snapshot.add(field,previous.get(field).deepCopy());return snapshot;});
                    else{snapshot.addProperty("annotation",comment.isBlank()?"":comment);store.save(name,snapshot,false);}return 1;
                }catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}
            }),count->feedback(source,translated(replace?"resave":"save",replace?"Updated %s":"Saved %s",name),count)));
        }));
    }
    CompletableFuture<Boolean> spawn(CommandSourceStack source,String name,JsonObject profile,boolean silence){
        String key=name.toLowerCase(Locale.ROOT);if(closed || !spawning.add(key))return CompletableFuture.completedFuture(false);
        Component summoner=source.getPlayer()==null?null:source.getDisplayName().copy();
        java.util.concurrent.atomic.AtomicReference<UUID> reservedId=new java.util.concurrent.atomic.AtomicReference<>();
        var completion=new CompletableFuture<Boolean>();
        carpet.script.external.ScarpetNativeWork.record(completion);carpet.script.external.ScarpetNativeWork.trackNative(server,completion);
        CarpetPlayerBirths.track(server,completion,()->{});
        CompletableFuture.supplyAsync(()->{
            try{
                OrgPlayerProfileStore.validate(profile);
                UUID uuid=profile.has("_lophine_uuid")?UUID.fromString(profile.get("_lophine_uuid").getAsString()):GeneralCompatConfig.fakePlayerUseOfflinePlayerUUID?UUIDUtil.createOfflinePlayerUUID(name):OldUsersConverter.convertMobOwnerIfNecessary(server,name);
                if(uuid==null && GeneralCompatConfig.allowSpawningOfflinePlayers)uuid=UUIDUtil.createOfflinePlayerUUID(name);
                if(uuid==null)throw new IllegalArgumentException("The profile account cannot be resolved");
                if(!spawningIds.add(uuid))throw new IllegalArgumentException("This fake-player UUID is already spawning");reservedId.set(uuid);
                GameProfile gameProfile=new GameProfile(uuid,name);
                if(profile.has("_lophine_skin")){JsonArray texture=profile.getAsJsonArray("_lophine_skin");if(texture.size()!=2)throw new IllegalArgumentException("Invalid saved skin");gameProfile.properties().put("textures",new com.mojang.authlib.properties.Property("textures",texture.get(0).getAsString(),texture.get(1).getAsString()));}
                CompoundTag saved=null;
                JsonObject script=profile.has("script_action")?profile.getAsJsonObject("script_action").deepCopy():new JsonObject();
                boolean hidden=!script.isEmpty()&&OrgHiddenPlayerActions.accepts(script);
                if(hidden)OrgHiddenPlayerActions.validate(script);
                OrgFakePlayerActions.Action action=hidden?OrgFakePlayerActions.Action.simple("stop",List.of()):OrgFakePlayerActionCodec.read(script,access);
                CompoundTag actions=profile.has("_lophine_action_pack")?decode(profile.get("_lophine_action_pack").getAsString()):legacyActions(profile);
                return new Restore(gameProfile,saved,actions,action,hidden?script:null);
            }catch(IOException|com.mojang.brigadier.exceptions.CommandSyntaxException failure){throw new IllegalStateException("Cannot read the latest fake-player state",failure);}
        }).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction((Restore prepared)->OrgPlayerFileLease.withLease(server,prepared.profile.id(),"Org managed fake-player login",carpet.script.external.ScarpetRuntime.captureNativeFunction((OrgPlayerFileLease.Lease fileLease)->{
            if(closed||carpet.script.external.ScarpetNativeWork.isDraining(server))return CompletableFuture.completedFuture(false);
            return OrgPlayerInventoryMenus.recoverOfflineBeforeRead(server,prepared.profile.id(),latestStateFile(profile,prepared.profile)).thenCompose(recovered->CompletableFuture.supplyAsync(()->{
                try{return new Restore(prepared.profile,readLatestState(profile,prepared.profile),prepared.actions,prepared.action,prepared.hidden);}
                catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}
            })).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction((Restore restore)->{
            GameProfile gameProfile=restore.profile;
            ServerLevel world=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(profile.get("dimension").getAsString())));if(world==null)throw new IllegalArgumentException("Saved dimension is unavailable");
            JsonObject pos=profile.getAsJsonObject("pos"),direction=profile.getAsJsonObject("direction");Vec3 position=new Vec3(pos.get("x").getAsDouble(),pos.get("y").getAsDouble(),pos.get("z").getAsDouble());
            int x=BlockPos.containing(position).getX()>>4,z=BlockPos.containing(position).getZ()>>4;
            return CarpetRegionLease.<CompletableFuture<Boolean>>runValue(world,x-2,z-2,x+2,z+2,lease->{
                if(closed || server.getPlayerList().getPlayer(gameProfile.id())!=null || server.getPlayerList().getPlayerByName(name)!=null || server.getBotList().bots.stream().anyMatch(bot->bot.getGameProfile().name().equalsIgnoreCase(name)))return CompletableFuture.completedFuture(false);
                NameAndId identity=new NameAndId(gameProfile);var list=server.getPlayerList();
                if(list.getBans().isBanned(identity))throw new IllegalArgumentException("This profile is banned");
                if((list.isUsingWhitelist()||GeneralCompatConfig.onlyOpCanSpawnRealPlayerInWhitelist)&&list.getWhiteList().isWhiteListed(identity)&&!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source))throw new IllegalArgumentException("Whitelisted players can only be spawned by operators");
                if(!AmsFakePlayers.canSpawn(server,name,source.getBukkitSender()))throw new IllegalArgumentException("Fake-player spawning is unavailable");
                try{
                    CompoundTag saved=restore.saved;
                    CompoundTag actions=restore.actions;
                    ServerBot bot=CarpetPlayerCommand.createOrgManagedPlayer(source,gameProfile,world,position,new Vec2(direction.get("pitch").getAsFloat(),direction.get("yaw").getAsFloat()),GameType.byName(profile.get("gamemode").getAsString()),profile.has("flying")&&profile.get("flying").getAsBoolean(),saved,actions,completion,silence);
                    return server.getBotList().carpetPlacementCompletion(bot).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(joined->{
                        CompletableFuture<Boolean> restored=new CompletableFuture<>();
                        boolean accepted=bot.getBukkitEntity().taskScheduler.schedule(carpet.script.external.ScarpetRuntime.captureNativeConsumer((net.minecraft.world.entity.Entity owner)->{
                            ServerBot actor=(ServerBot)owner;
                            if(closed||actor.isRemoved()||actor.isDeadOrDying()||!server.getBotList().bots.contains(actor)){restored.complete(false);return;}
                            var nativeRestore=carpet.script.external.ScarpetNativeWork.observeNative(actor,()->{
                            try(var acceptedRestore=carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(actor)){
                                actor.setShiftKeyDown(profile.has("sneaking")&&profile.get("sneaking").getAsBoolean());OrgFakePlayerActions.set(actor,restore.action);
                                if(restore.hidden!=null&&!OrgHiddenPlayerActions.load(actor,restore.hidden))throw new IllegalStateException("Validated hidden action was not restored");
                                if(profile.has("startup_action"))for(JsonElement element:profile.getAsJsonArray("startup_action")){
                                    JsonObject startup=element.getAsJsonObject();long delay=startup.has("delay")?startup.get("delay").getAsLong():1;if(delay<0)continue;
                                    JsonObject function=startup.getAsJsonObject("function").deepCopy();startupDelayed(actor,Math.max(1,delay),function);
                                }
                                OrgPlayerSummoner.spawned(actor,summoner,silence);observe(actor);return true;
                            }});
                            carpet.script.external.ScarpetNativeWork.aliasDependency(restored,nativeRestore);
                            nativeRestore.whenComplete((value,problem)->{if(problem==null)restored.complete(value);else restored.completeExceptionally(problem);});
                        }),retired->restored.complete(false),1L);
                        if(!accepted)restored.complete(false);return restored;
                    }));
                }catch(RuntimeException failure){throw new IllegalStateException("Cannot restore fake-player profile",failure);}
            }).thenCompose(java.util.function.Function.identity());
            }));
        })))).whenComplete((success,failure)->{spawning.remove(key);UUID id=reservedId.get();if(id!=null)spawningIds.remove(id);if(failure==null)completion.complete(success);else{completion.completeExceptionally(failure);message(source,"Cannot spawn "+name+": "+failure.getMessage());}});
        var caller=completion.copy();carpet.script.external.ScarpetNativeWork.aliasDependency(caller,completion);return caller;
    }
    Path latestStateFile(JsonObject profile,GameProfile gameProfile){
        Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path file=profile.has("_lophine_state_file")?root.resolve(profile.get("_lophine_state_file").getAsString()).normalize():server.getPlayerList().playerIo.getPlayerDir().toPath().resolve(gameProfile.id()+".dat").toAbsolutePath().normalize();
        if(!file.startsWith(root)||Files.isSymbolicLink(file))throw new IllegalArgumentException("Fake-player state path leaves its world directory");
        return file;
    }
    CompoundTag readLatestState(JsonObject profile,GameProfile gameProfile)throws IOException{
        CompoundTag saved;
        if(profile.has("_lophine_state_file")){
            Path file=latestStateFile(profile,gameProfile);
            if(!Files.isRegularFile(file))throw new IllegalArgumentException("The latest fake-player state file is unavailable");
            saved=NbtIo.readCompressed(file,NbtAccounter.create(64L*1024L*1024L));
        }else saved=server.getPlayerList().loadPlayerData(new NameAndId(gameProfile)).orElse(null);
        if(saved!=null&&saved.read("UUID",UUIDUtil.CODEC).filter(value->!value.equals(gameProfile.id())).isPresent())throw new IllegalArgumentException("The fake-player state UUID differs from its profile");
        return saved;
    }
    private record Restore(GameProfile profile,CompoundTag saved,CompoundTag actions,OrgFakePlayerActions.Action action,JsonObject hidden) {}
    private static CompoundTag legacyActions(JsonObject profile){
        CompoundTag tag=new CompoundTag();tag.putBoolean("sneaking",profile.has("sneaking")&&profile.get("sneaking").getAsBoolean());net.minecraft.nbt.ListTag list=new net.minecraft.nbt.ListTag();
        if(profile.has("simple_action"))for(var entry:profile.getAsJsonObject("simple_action").entrySet()){JsonObject value=entry.getValue().getAsJsonObject();CompoundTag action=new CompoundTag();action.putString("type",entry.getKey().toUpperCase(Locale.ROOT));action.putInt("limit",-1);action.putInt("interval",Math.max(1,value.get("interval").getAsInt()));action.putBoolean("continuous",value.get("continuous").getAsBoolean());list.add(action);}
        tag.put("actions",list);return tag;
    }
    private void startup(ServerBot bot,JsonObject function){
        if(bot.isRemoved()||bot.isDeadOrDying()||closed)return;String value=function.get("value").getAsString();
        if(function.get("type").getAsString().equals("command"))server.getCommands().performPrefixedCommand(bot.createCommandSourceStack(),value);
        else switch(value){case "use"->bot.carpetActionPack.start(CarpetPlayerActionPack.ActionType.USE,CarpetPlayerActionPack.Action.once());case "attack"->bot.carpetActionPack.start(CarpetPlayerActionPack.ActionType.ATTACK,CarpetPlayerActionPack.Action.once());case "kill"->logout(bot,server.createCommandSourceStack(),true);default->throw new IllegalArgumentException("Unknown startup action");}
    }
    static String encode(CompoundTag tag)throws IOException{ByteArrayOutputStream output=new ByteArrayOutputStream();NbtIo.writeCompressed(tag,output);return java.util.Base64.getEncoder().encodeToString(output.toByteArray());}
    static CompoundTag decode(String encoded)throws IOException{byte[] bytes=java.util.Base64.getDecoder().decode(encoded);if(bytes.length>64*1024*1024)throw new IOException("Fake-player snapshot exceeds native bound");return NbtIo.readCompressed(new ByteArrayInputStream(bytes),NbtAccounter.create(64L*1024L*1024L));}
    static void message(CommandSourceStack source,String text){feedback(source,Component.literal(text),null);}
    static <T> CompletableFuture<T> feedback(CommandSourceStack source,Component text,T value){return OrgMenuNativeEffects.admit(source.getServer(),()->TisCommandContinuations.feedback(source,()->{source.sendSuccess(()->text,false);return value;}));}
    static Component translated(String suffix,String fallback,Object... values){String key="carpet-org-addition.command.playerManager."+suffix;return Component.translatableWithFallback(key,OrgRuleTranslations.text(key,fallback),values);}
    private <T> CompletableFuture<T> filePhase(java.util.function.Supplier<T> work){return OrgCommandNativeEffects.file(server,()->TisCommandContinuations.phase(null,work)).thenCompose(java.util.function.Function.identity());}
    private static <T> CompletableFuture<T> failed(String message){return CompletableFuture.failedFuture(new IllegalArgumentException(message));}
    private void tickGlobal(){
        if(closed)return;long now=ticks.incrementAndGet();
        if(!autoLoginStarted&&server.overworld()!=null){autoLoginStarted=true;CommandSourceStack source=server.createCommandSourceStack();CompletableFuture.runAsync(()->{try{for(String name:store.names()){JsonObject profile=store.load(name);if(profile.has("autologin")&&profile.get("autologin").getAsBoolean())spawn(source,name,profile,true);}}catch(IOException failure){MinecraftServer.LOGGER.error("Cannot load Org automatic fake players",failure);}});}
        for(Job job:jobs.values())if(!job.busy&&now>=job.due){job.busy=true;try{executeJob(job,now);}catch(RuntimeException failure){jobs.remove(job.kind+":"+job.name,job);message(job.source,"Schedule stopped: "+failure.getMessage());}}
    }
    private void executeJob(Job job,long now){
        if(job.kind.equals("login")){jobs.remove(job.kind+":"+job.name,job);spawn(job.source,job.name,job.profile,false);return;}
        ServerPlayer player=server.getPlayerList().getPlayerByName(job.name);
        if(job.kind.equals("logout")){jobs.remove(job.kind+":"+job.name,job);if(player instanceof ServerBot bot)logout(bot,job.source,true);return;}
        if(!GeneralCompatConfig.fakePlayerSpawnMemoryLeakFix){jobs.remove(job.kind+":"+job.name,job);if(player==null)spawn(job.source,job.name,job.profile,true);message(job.source,"Relogin stopped: fakePlayerSpawnMemoryLeakFix is disabled");return;}
        if(player instanceof ServerBot bot){
            captureAndRemove(bot, job).whenComplete((removed, failure) -> {
                if(failure!=null){jobs.remove(job.kind+":"+job.name,job);message(job.source,"Relogin stopped: "+reason(failure));}
            });
        }else spawn(job.source,job.name,job.profile,true).whenComplete((success,failure)->{job.due=ticks.get()+job.interval;job.busy=false;if(failure!=null)jobs.remove(job.kind+":"+job.name,job);});
    }
    void schedule(CommandSourceStack source,String name,String kind,long delay,int interval)throws IOException{
        JsonObject profile=kind.equals("logout")?new JsonObject():store.load(name);jobs.put(kind+":"+name,new Job(name,kind,source,ticks.get()+delay,interval,profile));
    }
    void relogin(CommandSourceStack source,String name,int interval){
        ServerPlayer target=server.getPlayerList().getPlayerByName(name);
        if(target instanceof ServerBot bot&&OrgDeferredPlayerCommands.deferIfCausal(bot,()->relogin(source,name,interval),failure->message(source,"Cannot capture relogin state: "+reason(failure))))return;
        reloginAsync(source,name,interval).whenComplete((value,failure)->{if(failure!=null)message(source,"Cannot schedule relogin: "+reason(failure));});
    }
    CompletableFuture<Integer> reloginAsync(CommandSourceStack source,String name,int interval){
        return OrgMenuNativeEffects.admit(server,()->{
            if(!GeneralCompatConfig.fakePlayerSpawnMemoryLeakFix)return feedback(source,translated("schedule.relogin.prerequisite","Enable fakePlayerSpawnMemoryLeakFix before scheduling relogin",Component.literal("/carpet fakePlayerSpawnMemoryLeakFix true")),0);
            Job existing=jobs.get("relogin:"+name);if(existing!=null){existing.interval=interval;existing.due=ticks.get()+interval;return feedback(source,translated("schedule.relogin.modify","Changed %s interval to %s",name,interval),interval);}
            ServerPlayer player=server.getPlayerList().getPlayerByName(name);if(!(player instanceof ServerBot bot))return failed("The selected player must be an online fake player");
            return TisCommandContinuations.then(captureIdle(bot,true,null),profile->{if(closed)return failed("The player manager is closing");jobs.put("relogin:"+name,new Job(name,"relogin",source,ticks.get()+interval,interval,profile));return CompletableFuture.completedFuture(interval);});
        });
    }
    void cancel(CommandSourceStack source,String name){cancelAsync(source,name,null).whenComplete((value,failure)->{if(failure!=null)message(source,reason(failure));});}
    CompletableFuture<Integer> cancelAsync(CommandSourceStack source,String name,String selectedKind){
        return OrgMenuNativeEffects.admit(server,()->{
            var removed=new java.util.ArrayList<Job>();for(Job job:List.copyOf(jobs.values()))if(job.name.equals(name)&&(selectedKind==null||selectedKind.equals(job.kind))&&jobs.remove(job.kind+":"+name,job))removed.add(job);
            if(removed.isEmpty())return failed("No schedule exists for "+name);
            CompletableFuture<Void> before=CompletableFuture.completedFuture(null);for(Job job:removed)if(job.kind.equals("relogin")&&server.getPlayerList().getPlayerByName(name)==null)before=TisCommandContinuations.then(before,ignored->spawn(source,name,job.profile,true).thenAccept(success->{if(!success)throw new IllegalStateException("Cancelled relogin could not restore the offline player");}));
            return TisCommandContinuations.then(before,ignored->feedback(source,translated("schedule.cancel.success","Cancelled schedule for %s",name),removed.size()));
        });
    }
    private CompletableFuture<Boolean> captureAndRemove(ServerBot bot, Job job) {
        return OrgMenuNativeEffects.admit(server,()->OrgFakePlayerActions.owned(bot, carpet.script.external.ScarpetRuntime.captureNativeContinuation(() -> OrgFakePlayerActions.whenIdleForRemoval(bot, () -> {
            if(closed||jobs.get(job.kind+":"+job.name)!=job)return CompletableFuture.completedFuture(false);
            boolean voidStopped=bot.getY()<bot.level().getMinY()-64;if(voidStopped)jobs.remove(job.kind+":"+job.name,job);
            try { job.profile=capture(bot,true); }
            catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}
            return logoutNow(bot, job.source, true,true).thenApply(removed -> {
                if(!removed){jobs.remove(job.kind+":"+job.name,job);message(job.source,"Relogin stopped: fake-player removal was cancelled");return false;}
                if(!closed&&jobs.get(job.kind+":"+job.name)==job){job.due=ticks.get()+2;job.busy=false;}
                else if(!voidStopped&&!closed&&server.getPlayerList().getPlayerByName(job.name)==null)spawn(job.source,job.name,job.profile,true);
                return true;
            });
        }))).thenCompose(java.util.function.Function.identity()).thenCompose(java.util.function.Function.identity()));
    }
    List<String> schedules(){return jobs.values().stream().map(job->job.name+": "+job.kind+", remaining "+Math.max(0,job.due-ticks.get())+"t, interval "+job.interval+"t").sorted().toList();}
    private static CompletableFuture<Boolean> logout(ServerBot bot,CommandSourceStack source,boolean save){
        return logout(bot,source,save,false);
    }
    private static CompletableFuture<Boolean> logout(ServerBot bot,CommandSourceStack source,boolean save,boolean silence){
        var removal=carpet.script.external.ScarpetRuntime.captureNativeContinuation(()->logoutNow(bot,source,save,silence));
        return OrgMenuNativeEffects.admit(bot.level().getServer(),()->OrgFakePlayerActions.owned(bot,carpet.script.external.ScarpetRuntime.captureNativeContinuation(()->OrgFakePlayerActions.whenIdleForRemoval(bot,removal)))
            .thenCompose(java.util.function.Function.identity()).thenCompose(java.util.function.Function.identity()));
    }
    private static CompletableFuture<Boolean> logoutNow(ServerBot bot,CommandSourceStack source,boolean save){
        return logoutNow(bot,source,save,false);
    }
    private static CompletableFuture<Boolean> logoutNow(ServerBot bot,CommandSourceStack source,boolean save,boolean silence){
        TickThread.ensureTickThread(bot,"Fake-player logout requires its owner");
        return OrgNativePlayerMessages.remove(bot,silence,()->bot.level().getServer().getBotList().carpetRemoveBotAsync(bot,BotRemoveEvent.RemoveReason.COMMAND,source.getBukkitSender(),save,false));
    }
    void kill(CommandSourceStack source,String name){killAsync(source,name).whenComplete((value,failure)->{if(failure!=null)message(source,"Cannot log out fake player: "+reason(failure));});}
    CompletableFuture<Boolean> killAsync(CommandSourceStack source,String name){return OrgMenuNativeEffects.admit(server,()->{ServerPlayer player=server.getPlayerList().getPlayerByName(name);return player instanceof ServerBot bot?logout(bot,source,true):failed("The selected player must be an online fake player");});}
    CompletableFuture<Boolean> killSilentAsync(CommandSourceStack source,String name){return OrgMenuNativeEffects.admit(server,()->{ServerPlayer player=server.getPlayerList().getPlayerByName(name);return player instanceof ServerBot bot?logout(bot,source,true,true):failed("The selected player must be an online fake player");});}
    public static boolean reloginKeepTab(ServerBot bot){OrgPlayerManager manager=MANAGERS.get(bot.level().getServer());return manager!=null&&manager.jobs.values().stream().anyMatch(job->job.kind.equals("relogin")&&(job.profile.has("_lophine_uuid")?job.profile.get("_lophine_uuid").getAsString().equals(bot.getUUID().toString()):job.name.equalsIgnoreCase(bot.getScoreboardName())));}
    void batch(CommandSourceStack source,String prefix,int from,int to,String operation,Vec3 position){batchAsync(source,prefix,from,to,operation,position).whenComplete((value,failure)->{if(failure!=null)message(source,"Batch stopped: "+reason(failure));});}
    private record BatchLocation(ServerLevel world,Vec3 position,Vec2 rotation,GameType mode,boolean flying){}
    static String batchName(String prefix,int index){String name=(prefix.endsWith("_")?prefix:prefix+"_")+index;String before=GeneralCompatConfig.fakePlayerNamePrefix,after=GeneralCompatConfig.fakePlayerNameSuffix;if(!before.equals("#none")&&!name.toLowerCase(Locale.ROOT).startsWith(before.toLowerCase(Locale.ROOT)))name=before+name;
        // The pinned Org suffix helper tests startsWith, including its existing quirk.
        if(!after.equals("#none")&&!name.toLowerCase(Locale.ROOT).startsWith(after.toLowerCase(Locale.ROOT)))name+=after;return name;}
    CompletableFuture<Integer> batchAsync(CommandSourceStack source,String prefix,int from,int to,String operation,Vec3 requested){
        return OrgMenuNativeEffects.admit(server,()->{
            int start=Math.min(from,to),end=Math.max(from,to);if((long)end-start+1>256)return failed("A batch may contain at most 256 fake players");
            var names=new java.util.ArrayList<String>();for(int index=start;index<=end;index++)names.add(batchName(prefix,index));
            if(operation.equals("kill")||operation.equals("drop")){
                CompletableFuture<Integer> count=CompletableFuture.completedFuture(0);for(String name:names){ServerPlayer target=server.getPlayerList().getPlayerByName(name);if(!(target instanceof ServerBot bot))continue;
                    count=TisCommandContinuations.then(count,before->(operation.equals("kill")?killSilentAsync(source,name):dropBatch(bot)).thenApply(success->before+(success?1:0)));
                }return count;
            }
            if(names.getFirst().length()>16)return failed("All batch player names exceed 16 characters");
            ServerPlayer sender=source.getPlayer();Vec3 position=requested==null?source.getPosition():requested;
            CompletableFuture<BatchLocation> destination=sender==null?CompletableFuture.completedFuture(new BatchLocation(source.getLevel(),position,Vec2.ZERO,GameType.SURVIVAL,false)):OrgMenuNativeEffects.run(sender,()->new BatchLocation(sender.level(),position,new Vec2(sender.getXRot(),sender.getYRot()),sender.gameMode.getGameModeForPlayer(),sender.getAbilities().flying));
            return TisCommandContinuations.then(destination,location->{CompletableFuture<Integer> count=CompletableFuture.completedFuture(0);for(String name:names){if(name.length()>16||server.getPlayerList().getPlayerByName(name)!=null)continue;
                count=TisCommandContinuations.then(count,before->{var profile=blank(location.world(),location.position(),location.rotation(),location.mode());profile.addProperty("flying",location.flying());profile.addProperty("_lophine_name",name);
                    return TisCommandContinuations.then(spawn(source,name,profile,true),success->{if(!success)return CompletableFuture.completedFuture(before);if(!operation.equals("trial"))return CompletableFuture.completedFuture(before+1);ServerPlayer target=server.getPlayerList().getPlayerByName(name);if(!(target instanceof ServerBot bot))return failed("The trial player disappeared after native placement");return delayed(bot,30,()->logout(bot,source,true,true)).thenApply(removed->{if(!removed)throw new IllegalStateException("Trial-player removal was cancelled");return before+1;});});
                });}
                return TisCommandContinuations.then(count,total->total==0?CompletableFuture.completedFuture(0):TisCommandContinuations.then(OrgCommandNativeEffects.broadcast(server,translated("summon.joined","%s fake players joined",total).copy().withStyle(net.minecraft.ChatFormatting.YELLOW)),ignored->TisCommandContinuations.phase(null,()->{OrgPlayerSummoner.batch(server,source.getDisplayName().copy(),total);return total;})));
            });
        });
    }
    private CompletableFuture<Boolean> dropBatch(ServerBot bot){var done=new java.util.concurrent.atomic.AtomicBoolean();return OrgMenuNativeEffects.run(bot,()->{if(bot.isRemoved()||bot.isDeadOrDying())return null;OrgItemShadowGroups.actor(bot,()->OrgItemShadowGroups.inventory(bot,bot.containerMenu.slots),()->{bot.carpetActionPack.drop(-2,true);done.set(true);return true;},false,owner->!owner.isRemoved()&&!((ServerPlayer)owner).isDeadOrDying(),value->{});return null;}).thenApply(ignored->done.get());}
    private void startupDelayed(ServerBot player,long delay,JsonObject function){
        // Source startup tasks are external jobs after birth, so a kill startup cannot await
        // the same still-admitted birth whose completion would otherwise await this task.
        var actual=new CompletableFuture<Boolean>();carpet.script.external.ScarpetNativeWork.trackNative(server,actual);
        var detached=carpet.script.external.ScarpetRuntime.captureDetachedNativeContinuation(()->CarpetNativeActionContext.with(null,()->closed?CompletableFuture.completedFuture(false):OrgCommandNativeEffects.intent(player,()->{startup(player,function);return true;})));
        try{boolean queued=player.getBukkitEntity().taskScheduler.schedule(owner->{try{detached.get().whenComplete((value,failure)->{if(failure==null)actual.complete(value);else actual.completeExceptionally(failure);});}catch(Throwable failure){actual.completeExceptionally(failure);}},retired->actual.completeExceptionally(new IllegalStateException("Fake player retired before startup")),Math.max(1,delay));if(!queued)actual.completeExceptionally(new IllegalStateException("Startup scheduler retired"));}catch(Throwable failure){actual.completeExceptionally(failure);}
    }
    private <T> CompletableFuture<T> delayed(ServerBot player,long delay,java.util.function.Supplier<CompletableFuture<T>> action){
        return OrgMenuNativeEffects.admit(server,()->{var actual=new CompletableFuture<T>();carpet.script.external.ScarpetNativeWork.record(actual);var captured=carpet.script.external.ScarpetRuntime.captureNativeContinuation(()->OrgCommandNativeEffects.intent(player,action).thenCompose(java.util.function.Function.identity()));
            try{boolean queued=player.getBukkitEntity().taskScheduler.schedule(owner->{try{captured.get().whenComplete((value,failure)->{if(failure==null)actual.complete(value);else actual.completeExceptionally(failure);});}catch(Throwable failure){actual.completeExceptionally(failure);}},retired->actual.completeExceptionally(new IllegalStateException("Fake player retired before delayed native work")),Math.max(1,delay));if(!queued)actual.completeExceptionally(new IllegalStateException("Fake-player scheduler retired"));}catch(Throwable failure){actual.completeExceptionally(failure);}return actual;});
    }
    private static JsonObject blank(ServerLevel world,Vec3 position,Vec2 rotation,GameType mode){
        JsonObject profile=new JsonObject();profile.addProperty("data_version",5);JsonObject pos=new JsonObject();pos.addProperty("x",position.x);pos.addProperty("y",position.y);pos.addProperty("z",position.z);profile.add("pos",pos);JsonObject direction=new JsonObject();direction.addProperty("yaw",rotation.y);direction.addProperty("pitch",rotation.x);profile.add("direction",direction);profile.addProperty("dimension",world.dimension().identifier().toString());profile.addProperty("gamemode",mode.getName());profile.addProperty("flying",false);profile.addProperty("sneaking",false);profile.addProperty("annotation","");profile.addProperty("autologin",false);profile.add("script_action",OrgFakePlayerActionCodec.write(OrgFakePlayerActions.Action.simple("stop",List.of())));profile.add("simple_action",new JsonObject());profile.add("startup_action",new JsonArray());profile.add("group",new JsonArray());return profile;
    }
    List<String> residentTimes()throws IOException{Path grave=OrgWorldFormat.directory(server).resolve("player_data/graveyard");if(!Files.isDirectory(grave))return List.of();try(var files=Files.list(grave)){return files.map(path->path.getFileName().toString()).filter(name->name.startsWith("resident_")&&name.endsWith(".json")).map(name->name.substring(9,name.length()-5)).sorted().toList();}}
    void respawn(CommandSourceStack source,String time)throws IOException{respawnAsync(source,time).whenComplete((value,failure)->{if(failure!=null)message(source,"Cannot restore residents: "+reason(failure));});}
    CompletableFuture<Integer> respawnAsync(CommandSourceStack source,String time){
        return OrgMenuNativeEffects.admit(server,()->TisCommandContinuations.then(filePhase(()->{try{List<String> times=residentTimes();String selected=time==null?previousResidentTime:time;if(selected==null)return new JsonObject();if(!times.contains(selected))throw new IOException("Cannot find that resident snapshot");Path file=OrgWorldFormat.directory(server).resolve("player_data/graveyard/resident_"+selected+".json");return JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("players").deepCopy();}catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}}),profiles->{CompletableFuture<Integer> count=CompletableFuture.completedFuture(0);for(var entry:profiles.entrySet()){String name=entry.getKey();if(server.getPlayerList().getPlayerByName(name)!=null)continue;count=TisCommandContinuations.then(count,before->spawn(source,name,entry.getValue().getAsJsonObject().deepCopy(),true).thenApply(success->before+(success?1:0)));}return count;}));
    }
    public static void retired(ServerPlayer player){OrgPlayerManager manager=MANAGERS.get(player.level().getServer());if(manager==null)return;/* Weak entity keys preserve the native per-object threshold through deferred death RETURN. */if(!manager.closed)manager.residents.remove(player.getUUID());}
    CompletableFuture<JsonObject> captureForShutdown(ServerBot bot){
        return OrgFakePlayerActions.owned(bot,()->OrgFakePlayerActions.whenIdleForRemoval(bot,()->{
            if(bot.isDeadOrDying()){residents.remove(bot.getUUID());return CompletableFuture.<JsonObject>completedFuture(null);}
            // Existing player custody must drain before a release uses an aliased held item.
            // Producer pause remains held; the existing escrow's own owner phases can run.
            return OrgInventoryTransfers.whenAvailable(server,bot.getUUID()).thenCompose(ignored->OrgFakePlayerActions.owned(bot,()->{
                var frozen=OrgShutdownActionSnapshot.freeze(bot);
                return frozen.stop(bot).handle((done,stopFailure)->stopFailure).thenCompose(stopFailure->OrgFakePlayerActions.owned(bot,()->
                    carpet.script.external.ScarpetPlayerInventoryGate.whenIdleForRemoval(bot,()->{
                        try{
                            // Bow release, projectile/native item tail and owner state now ended.
                            JsonObject profile=capture(bot,true);frozen.apply(profile);residents.put(bot.getUUID(),profile);
                            if(stopFailure!=null)MinecraftServer.LOGGER.error("Old fake action stop terminated with failure; latest owned state was preserved for {}",bot.getUUID(),stopFailure);
                            return profile;
                        }catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}
                    })).thenCompose(java.util.function.Function.identity()));
            })).thenCompose(java.util.function.Function.identity());
        })).thenCompose(java.util.function.Function.identity()).thenCompose(java.util.function.Function.identity());
    }
    /** Invoke while region actors still run; await this future only from the shutdown coordinator. */
    public static CompletableFuture<Void> beforeShutdown(MinecraftServer server){
        OrgPlayerManager manager=MANAGERS.get(server);if(manager==null)return CompletableFuture.completedFuture(null);manager.closed=true;if(manager.task!=null)manager.task.cancel();
        CarpetPlayerBirths.beginDrain(server);
        return CarpetPlayerBirths.whenIdle(server).thenCompose(ignoredBirths->manager.carpetCaptureShutdownCohort());
    }
    private CompletableFuture<Void> carpetCaptureShutdownCohort(){
        OrgPlayerManager manager=this;
        var live=List.copyOf(server.getBotList().bots);
        manager.residents.keySet().retainAll(live.stream().map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toSet()));
        var pending=new java.util.ArrayList<CompletableFuture<Void>>();
        for(ServerBot bot:live){
            manager.residents.remove(bot.getUUID()); // Never fall back to an older action snapshot after a failed new save.
            pending.add(manager.captureForShutdown(bot).thenAccept(profile->{}));
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).handle((ignored,failure)->failure).thenAcceptAsync(captureFailure->{
            JsonObject snapshot=new JsonObject();snapshot.addProperty("data_version",3);JsonObject players=new JsonObject();manager.residents.values().forEach(profile->players.add(profile.get("_lophine_name").getAsString(),profile.deepCopy()));snapshot.add("players",players);
            if(players.isEmpty()){if(captureFailure!=null)throw new java.util.concurrent.CompletionException(captureFailure);return;}
            String time=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(java.time.LocalDateTime.now());Path grave=OrgWorldFormat.directory(server).resolve("player_data/graveyard");
            try{atomicText(grave.resolve("resident_"+time+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(snapshot)+"\n");try(var files=Files.list(grave)){var history=files.filter(path->path.getFileName().toString().startsWith("resident_")&&path.getFileName().toString().endsWith(".json")).sorted().toList();for(int index=0;index<history.size()-100;index++)Files.delete(history.get(index));}}
            catch(IOException failure){throw new IllegalStateException("Cannot preserve Org resident profiles",failure);}
            if(captureFailure!=null)throw new java.util.concurrent.CompletionException(captureFailure);
        });
    }
    private static String reason(Throwable failure){while((failure instanceof java.util.concurrent.CompletionException||failure instanceof java.util.concurrent.ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();return String.valueOf(failure.getMessage());}
    void safe(CommandSourceStack source,String name,float threshold,boolean save){safeAsync(source,name,threshold,save).whenComplete((value,failure)->{if(failure!=null)message(source,"Cannot set SafeAFK: "+reason(failure));});}
    private record SafePlan(ServerBot player,String name,Component display,float requested,long intent){}
    CompletableFuture<Integer> safeAsync(CommandSourceStack source,String name,float threshold,boolean save){
        return OrgMenuNativeEffects.admit(server,()->{
            if(!Float.isFinite(threshold))return failed("SafeAFK threshold must be finite");ServerPlayer target=server.getPlayerList().getPlayerByName(name);if(!(target instanceof ServerBot bot))return failed("The selected player must be an online fake player");final float requested=threshold<=0?-1F:threshold;
            return TisCommandContinuations.then(OrgMenuNativeEffects.run(bot,()->{if(bot.isRemoved()||requested>=bot.getMaxHealth())throw new IllegalArgumentException("SafeAFK threshold must be below maximum health");String actualName=bot.getScoreboardName();long intent=captures.incrementAndGet();safeRuntimeIntents.put(actualName,intent);if(save)safePersistentIntents.put(actualName,intent);else thresholds.put(bot,requested);return new SafePlan(bot,actualName,bot.getDisplayName().copy(),requested,intent);}),plan->{
                CompletableFuture<Boolean> changed=!save?CompletableFuture.completedFuture(true):TisCommandContinuations.then(filePhase(()->{try{return saveSafeThreshold(plan.name(),plan.requested(),plan.intent());}catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}}),written->{if(!written)return CompletableFuture.completedFuture(false);ServerPlayer current=server.getPlayerList().getPlayerByName(plan.name());if(!(current instanceof ServerBot))return CompletableFuture.completedFuture(true);return OrgMenuNativeEffects.run(current,()->{if(java.util.Objects.equals(safeRuntimeIntents.get(plan.name()),plan.intent()))thresholds.put(current,plan.requested());return true;});});
                return TisCommandContinuations.then(changed,success->success?feedback(source,translated(save?"safeafk.save":"safeafk.set","SafeAFK %s = %s",plan.display(),plan.requested(),Component.literal("/playerManager safeafk set "+plan.name()+" "+plan.requested()+" true")),(int)plan.requested()):CompletableFuture.completedFuture(0));
            });
        });
    }
    void safeQuery(CommandSourceStack source,String name){safeQueryAsync(source,name).whenComplete((value,failure)->{if(failure!=null)message(source,reason(failure));});}
    private record SafeRow(Component name,float value){}
    CompletableFuture<Integer> safeQueryAsync(CommandSourceStack source,String name){return OrgMenuNativeEffects.admit(server,()->{ServerPlayer player=server.getPlayerList().getPlayerByName(name);if(!(player instanceof ServerBot))return failed("The selected player must be an online fake player");return TisCommandContinuations.then(OrgMenuNativeEffects.run(player,()->new SafeRow(player.getDisplayName().copy(),safeThreshold(player))),row->feedback(source,translated("safeafk.list.each","%s: SafeAFK %s",row.name(),row.value()),(int)row.value()));});}
    void safeList(CommandSourceStack source){safeListAsync(source).whenComplete((value,failure)->{if(failure!=null)message(source,reason(failure));});}
    CompletableFuture<Integer> safeListAsync(CommandSourceStack source){return OrgMenuNativeEffects.admit(server,()->{var rows=new java.util.ArrayList<CompletableFuture<SafeRow>>();for(ServerBot bot:List.copyOf(server.getBotList().bots))rows.add(OrgMenuNativeEffects.run(bot,()->new SafeRow(bot.getDisplayName().copy(),safeThreshold(bot))));return TisCommandContinuations.then(CompletableFuture.allOf(rows.toArray(CompletableFuture[]::new)),ignored->TisCommandContinuations.feedback(source,()->{int count=0;for(var actual:rows){SafeRow row=actual.join();if(row.value()<0)continue;source.sendSuccess(()->translated("safeafk.list.each","%s: SafeAFK %s",row.name(),row.value()),false);count++;}if(count==0)source.sendSuccess(()->translated("safeafk.list.empty","No fake player has SafeAFK enabled"),false);return count;}));});}
    CompletableFuture<Integer> scheduleAsync(CommandSourceStack source,String name,String kind,long delay,int interval){return OrgMenuNativeEffects.admit(server,()->TisCommandContinuations.then(filePhase(()->{try{schedule(source,name,kind,delay,interval);return (int)delay;}catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}}),value->feedback(source,translated("schedule."+kind,"Scheduled %s after %s ticks",name,delay),value)));}
    CompletableFuture<Integer> scheduleListAsync(CommandSourceStack source){return OrgMenuNativeEffects.admit(server,()->{var rows=schedules();return TisCommandContinuations.feedback(source,()->{if(rows.isEmpty())source.sendSuccess(()->translated("schedule.list.empty","No fake-player schedules"),false);else rows.forEach(row->source.sendSuccess(()->Component.literal(row),false));return rows.size();});});}

    private synchronized boolean saveSafeThreshold(String name,float threshold,long intent)throws IOException{
        if(!java.util.Objects.equals(safePersistentIntents.get(name),intent))return false;
        var values=new java.util.Properties();if(Files.exists(safeFile()))try(var reader=Files.newBufferedReader(safeFile())){values.load(reader);}
        if(threshold>0)values.setProperty(name,String.valueOf(threshold));else values.remove(name);
        java.io.StringWriter writer=new java.io.StringWriter();values.store(writer,null);atomicText(safeFile(),writer.toString());
        var verified=new java.util.Properties();try(var reader=Files.newBufferedReader(safeFile())){verified.load(reader);}if(!verified.equals(values))throw new IOException("Cannot verify permanent SafeAFK values");
        if(threshold>0)permanentThresholds.put(name,threshold);else permanentThresholds.remove(name);return true;
    }
    static void atomicText(Path path,String text)throws IOException{
        Files.createDirectories(path.toAbsolutePath().getParent());Path temporary=Files.createTempFile(path.toAbsolutePath().getParent(),"org-manager-",".tmp");
        try{try(var channel=java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)){var bytes=java.nio.ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}Files.move(temporary,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);if(!text.equals(Files.readString(path)))throw new IOException("Cannot verify manager file");}finally{Files.deleteIfExists(temporary);}
    }
    boolean allowStartupCommand(CommandSourceStack source){
        if(!server.isDedicatedServer())return true;
        if(!Commands.hasPermission(Commands.LEVEL_OWNERS).test(source))return false;
        Path config=Path.of("config/carpetorgaddition/carpet-org-addition.json");
        try{if(!Files.isRegularFile(config))return false;JsonObject global=JsonParser.parseString(Files.readString(config)).getAsJsonObject();return global.has("allow_mp_player_startup_cmd")&&global.get("allow_mp_player_startup_cmd").getAsBoolean();}
        catch(IOException|RuntimeException failure){return false;}
    }
    private Path safeFile(){return OrgWorldFormat.directory(server).resolve("safeafk.properties");}
    private synchronized void reloadSafeThresholds(){
        permanentThresholds.clear();if(!Files.exists(safeFile()))return;
        try(var reader=Files.newBufferedReader(safeFile())){var properties=new java.util.Properties();properties.load(reader);for(String name:properties.stringPropertyNames()){float value=Float.parseFloat(properties.getProperty(name));if(Float.isFinite(value)&&value>0)permanentThresholds.put(name,value);}}
        catch(IOException|RuntimeException failure){MinecraftServer.LOGGER.error("Cannot load Org permanent safeAFK thresholds",failure);}
    }
}
