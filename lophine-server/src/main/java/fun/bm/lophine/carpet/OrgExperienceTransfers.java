package fun.bm.lophine.carpet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.leavesmc.leaves.bot.ServerBot;

/** Persistent actor transactions for Org's cross-region experience transfer command. */
public final class OrgExperienceTransfers {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final NamespacedKey DEBIT = new NamespacedKey("lophine", "carpet_org_xp_debit");
    private static final NamespacedKey CREDIT = new NamespacedKey("lophine", "carpet_org_xp_credit");
    private static final NamespacedKey CREDIT_ESCROW = new NamespacedKey("lophine", "carpet_org_xp_credit_escrow");
    private static final Map<MinecraftServer, Coordinator> COORDINATORS = new WeakHashMap<>();
    private static final Map<MinecraftServer, LoadFailure> LOAD_FAILURES = new WeakHashMap<>();
    private static final long LOAD_RETRY_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60L);
    private static final SimpleCommandExceptionType SELF_OR_FAKE = new SimpleCommandExceptionType(Component.literal("The source player must be yourself or a fake player"));

    private OrgExperienceTransfers() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var modes = Commands.argument("to", EntityArgument.player());
        for (String mode : java.util.List.of("all", "half")) {
            modes.then(Commands.literal(mode).executes(context -> request(context.getSource(), EntityArgument.getPlayer(context, "from"), EntityArgument.getPlayer(context, "to"), mode, 0)));
        }
        for (String mode : java.util.List.of("points", "level", "upgrade", "upgradeto")) {
            String argument = mode.equals("points") ? "number" : "level";
            modes.then(Commands.literal(mode).then(Commands.argument(argument, IntegerArgumentType.integer(1)).executes(context ->
                request(context.getSource(), EntityArgument.getPlayer(context, "from"), EntityArgument.getPlayer(context, "to"), mode, IntegerArgumentType.getInteger(context, argument)))));
        }
        dispatcher.register(Commands.literal("xpTransfer")
            .requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandXpTransfer))
            .then(Commands.argument("from", EntityArgument.player()).then(modes)));
    }

    private static synchronized Coordinator coordinator(MinecraftServer server) {
        Coordinator existing = COORDINATORS.get(server);
        if (existing != null) return existing;
        long now = System.nanoTime();
        LoadFailure previous = LOAD_FAILURES.get(server);
        if (previous != null && now - previous.lastAttempt < LOAD_RETRY_NANOS) {
            throw new IllegalStateException("The pending experience transfer ledger cannot be loaded; retrying in one minute");
        }
        try {
            Coordinator created = new Coordinator(server);
            COORDINATORS.put(server, created);
            LOAD_FAILURES.remove(server);
            return created;
        } catch (RuntimeException exception) {
            LOAD_FAILURES.put(server, new LoadFailure(now));
            if (previous == null) {
                com.mojang.logging.LogUtils.getLogger().error("Cannot load the Carpet experience transfer ledger; preserving the file and retrying once per minute", exception);
            }
            throw exception;
        }
    }

    private record LoadFailure(long lastAttempt) {}

    /** Read-only/offline actors wait outside their UUID file lease for actual XP custody. */
    public static java.util.concurrent.CompletableFuture<Void> whenAvailable(MinecraftServer server,UUID player){
        try{return coordinator(server).available(player);}catch(RuntimeException failure){return java.util.concurrent.CompletableFuture.failedFuture(failure);}
    }

    /** Finder's hidden offline TAKE action; the source is the real native UUID file. */
    public static java.util.concurrent.CompletableFuture<Boolean> takeOffline(ServerPlayer viewer,UUID player){
        // Enroll the true admission lifetime before a foreign viewer owner is queued.
        // Its result can later be passive durable funds; only real owner/file steps stay enrolled.
        var admitted=new java.util.concurrent.CompletableFuture<java.util.concurrent.CompletableFuture<Boolean>>();
        carpet.script.external.ScarpetNativeWork.trackNative(viewer.level().getServer(),admitted);
        var admission=OrgFakePlayerActions.owned(viewer,()->{
            if(!OrgHiddenPlayerActions.enabled()||!OrgUtilityCommands.permitted(viewer.createCommandSourceStack(),GeneralCompatConfig.commandFinder)
                ||!OrgUtilityCommands.permitted(viewer.createCommandSourceStack(),GeneralCompatConfig.commandXpTransfer)
                ||carpet.script.external.ScarpetNativeWork.isDraining(viewer.level().getServer())||viewer.getUUID().equals(player))return java.util.concurrent.CompletableFuture.completedFuture(false);
            Coordinator owner=coordinator(viewer.level().getServer());OfflineClaim claim=owner.claimOffline(player,viewer.getUUID());
            if(claim==null)return java.util.concurrent.CompletableFuture.completedFuture(false);
            var actual=new java.util.concurrent.CompletableFuture<Void>();carpet.script.external.ScarpetNativeWork.trackNative(owner.server,actual);
            var result=new java.util.concurrent.CompletableFuture<Boolean>();
            OrgPlayerFileLease.withLease(owner.server,player,"offline experience debit",lease->
                OrgOfflineInventorySessions.recoverBeforeRead(owner.server,player).thenCompose(ignored->owner.readOffline(player)).thenCompose(before->{
                    if(owner.online(player))throw new IllegalStateException("The experience source is online; use its actual player actor");
                    BigInteger amount=offlineAmount(before);if(amount.signum()==0){owner.releaseClaim(claim);result.complete(false);return java.util.concurrent.CompletableFuture.completedFuture(null);}
                    Transfer transfer=owner.prepareOffline(claim,amount,before);transfer.completed.whenComplete((ignored,failure)->{if(failure==null)result.complete(!transfer.phase.equals("cancelled"));else result.completeExceptionally(failure);});
                    return owner.debitOffline(transfer).thenRun(()->owner.dispatch(transfer.to));
                }))
                .whenComplete((ignored,failure)->{
                    if(failure==null)actual.complete(null);else{owner.releaseClaim(claim);actual.completeExceptionally(failure);result.completeExceptionally(failure);}
                });
            return result.copy();
        });
        admission.whenComplete((value,failure)->{if(failure==null)admitted.complete(value);else admitted.completeExceptionally(failure);});
        return admitted.thenCompose(value->value).copy();
    }
    /** Caller owns the native first-read UUID lease; only unfinished file debits run here. */
    public static java.util.concurrent.CompletableFuture<Void> recoverOfflineBeforeRead(MinecraftServer server,UUID player){
        try{return recoverOfflineBeforeRead(server,player,coordinator(server).nativePath(player));}
        catch(RuntimeException failure){return java.util.concurrent.CompletableFuture.failedFuture(failure);}
    }
    /** The existing UUID loan owns this exact Native/legacy state file through its read and join. */
    public static java.util.concurrent.CompletableFuture<Void> recoverOfflineBeforeRead(MinecraftServer server,UUID player,Path actualStateFile){
        try{
            Coordinator owner=coordinator(server);Transfer transfer=owner.offlineSource(player);
            var debit=transfer==null?java.util.concurrent.CompletableFuture.<Void>completedFuture(null):owner.debitOffline(transfer).thenRun(()->owner.dispatch(transfer.to));
            var actual=debit.thenCompose(ignored->owner.recoverFileBeforeRead(player,actualStateFile));
            return carpet.script.external.ScarpetNativeWork.trackNative(server,actual);
        }catch(RuntimeException failure){return java.util.concurrent.CompletableFuture.failedFuture(failure);}
    }
    private static BigInteger offlineAmount(CompoundTag data){
        int level=data.getIntOr("XpLevel",0);float progress=data.getFloatOr("XpP",0);if(level<0||!Float.isFinite(progress)||progress<0||progress>1)throw new IllegalArgumentException("Malformed offline experience state");
        level=Math.min(level,OrgExperienceAmounts.MAX_EFFECTIVE_LEVEL);int needed=level>=30?9*level-158:level>=15?5*level-38:2*level+7;
        int partial=level==OrgExperienceAmounts.MAX_EFFECTIVE_LEVEL?0:Math.max(0,(int)Math.floor(progress*needed));return OrgExperienceAmounts.forLevel(level).add(BigInteger.valueOf(partial));
    }
    private static final class OfflineClaim {
        final UUID id=UUID.randomUUID(),from,to;final java.util.concurrent.CompletableFuture<Void> completed=new java.util.concurrent.CompletableFuture<>(),sourceReady=new java.util.concurrent.CompletableFuture<>();
        OfflineClaim(UUID from,UUID to){this.from=from;this.to=to;}
    }
    @FunctionalInterface interface OfflineReader { CompoundTag read(Path file)throws IOException; }

    public static void tick(ServerPlayer player) {
        if (!carpet.script.external.ScarpetNativeWork.isDraining(player.level().getServer()) && player.level().getGameTime() % 20L != 0L) return;
        Coordinator coordinator;
        synchronized (OrgExperienceTransfers.class) { coordinator = COORDINATORS.get(player.level().getServer()); }
        if (coordinator == null) {
            Path file = player.level().getServer().getWorldPath(LevelResource.ROOT).resolve("carpet-org-experience-transfers.json");
            if (!Files.isRegularFile(file)) return;
            try { coordinator = coordinator(player.level().getServer()); }
            catch (RuntimeException exception) { return; }
        }
        coordinator.process(player);
    }

    private static int request(CommandSourceStack source, ServerPlayer from, ServerPlayer to, String mode, int value) throws CommandSyntaxException {
        if (source.getPlayerOrException() != from && !(from instanceof ServerBot)) throw SELF_OR_FAKE.create();
        return OrgCommandNativeEffects.command(source, 1, () -> transferNative(source, from, to, mode, value));
    }

    private record XpSnapshot(UUID id, Component name, String logName, int level, BigInteger total) {
        static XpSnapshot capture(ServerPlayer player) {
            if (player.isRemoved() || player.isDeadOrDying()) throw new IllegalStateException("The experience participant is unavailable");
            return new XpSnapshot(player.getUUID(), player.getDisplayName().copy(), player.getScoreboardName(), player.experienceLevel, OrgExperienceAmounts.read(player));
        }
    }
    private record CommandPlan(Coordinator owner, Transfer transfer, XpSnapshot from, int fromAfter, XpSnapshot to, BigInteger amount) {}
    private static java.util.concurrent.CompletableFuture<Integer> transferNative(CommandSourceStack source, ServerPlayer from, ServerPlayer to, String mode, int value) {
        var held = new java.util.concurrent.atomic.AtomicReference<Transfer>();
        var holder = new java.util.concurrent.atomic.AtomicReference<Coordinator>();
        var prepared = TisCommandContinuations.then(OrgMenuNativeEffects.run(to, () -> XpSnapshot.capture(to)), destination ->
            OrgMenuNativeEffects.run(from, () -> {
                XpSnapshot payer = XpSnapshot.capture(from);
                BigInteger amount = switch (mode) {
                    case "all" -> payer.total();
                    case "half" -> payer.total().divide(BigInteger.TWO);
                    case "points" -> BigInteger.valueOf(value);
                    case "level" -> OrgExperienceAmounts.forLevel(value);
                    case "upgrade" -> OrgExperienceAmounts.upgrade(destination.level(), Math.addExact(destination.level(), value));
                    case "upgradeto" -> OrgExperienceAmounts.upgrade(destination.level(), value);
                    default -> throw new IllegalArgumentException("Unknown transfer mode");
                };
                if (amount.signum() < 0 || amount.compareTo(payer.total()) > 0) throw new IllegalArgumentException("Insufficient experience or negative upgrade amount");
                if (!payer.id().equals(destination.id()) && destination.total().add(amount).compareTo(OrgExperienceAmounts.MAX_TOTAL) > 0)
                    throw new IllegalArgumentException("The destination would exceed the finite vanilla experience range");
                if (payer.id().equals(destination.id()) || amount.signum() == 0) return new CommandPlan(null, null, payer, payer.level(), destination, amount);
                Coordinator owner = coordinator(source.getServer()); holder.set(owner);
                Transfer transfer;
                synchronized (owner) {
                    transfer = owner.prepare(from, destination.id(), amount); transfer.commandHeld = true; held.set(transfer);
                    owner.debit(from, transfer);
                }
                return new CommandPlan(owner, transfer, payer, from.experienceLevel, destination, amount);
            }));
        var delivered = TisCommandContinuations.then(prepared, plan -> {
            java.util.concurrent.CompletableFuture<Integer> credited = plan.transfer() == null
                ? java.util.concurrent.CompletableFuture.completedFuture(plan.to().level())
                : OrgMenuNativeEffects.run(to, () -> {
                    if (to.isRemoved() || to.isDeadOrDying() || !to.getUUID().equals(plan.to().id())) throw new IllegalStateException("The experience destination is unavailable");
                    synchronized (plan.owner()) {
                        if (plan.owner().transfers.get(plan.transfer().id) != plan.transfer() || !plan.transfer().phase.equals("debited")) throw new IllegalStateException("The experience transfer custody changed before credit");
                        plan.owner().credit(to, plan.transfer());
                        if (plan.owner().transfers.containsKey(plan.transfer().id)) throw new IllegalStateException("The incoming experience remains in durable escrow");
                    }
                    return to.experienceLevel;
                });
            return TisCommandContinuations.then(credited, targetAfter -> TisCommandContinuations.feedback(source, () -> {
                String prefix = "carpet-org-addition.command.xpTransfer.";
                String kind = mode.equals("all") || mode.equals("half") ? mode : "point";
                Component hover = Component.literal(String.format(java.util.Locale.ROOT, OrgRuleTranslations.text(prefix + "upgrade", "%s (+%s) [%s->%s]"),
                    plan.to().name().getString(), targetAfter - plan.to().level(), plan.to().level(), targetAfter)).withStyle(net.minecraft.ChatFormatting.GREEN)
                    .append(Component.literal("\n")).append(Component.literal(String.format(java.util.Locale.ROOT, OrgRuleTranslations.text(prefix + "degrade", "%s (-%s) [%s->%s]"),
                        plan.from().name().getString(), plan.from().level() - plan.fromAfter(), plan.from().level(), plan.fromAfter())).withStyle(net.minecraft.ChatFormatting.RED));
                Component message = Component.literal(String.format(java.util.Locale.ROOT, OrgRuleTranslations.text(prefix + kind, "Transfer experience from %1$s to %3$s, %2$s in total"),
                    plan.from().name().getString(), plan.amount().toString(), plan.to().name().getString())).withStyle(style -> style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(hover)));
                source.sendSuccess(() -> message, false);
                com.mojang.logging.LogUtils.getLogger().info("{} transferred {} experience points from {} to {}", source.getTextName(), plan.amount(),
                    source.getEntity() == from ? "themself" : plan.from().logName(), source.getEntity() == to ? "themself" : plan.to().logName());
                return plan.amount().intValue();
            }));
        });
        delivered.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((count, failure) -> {
            Coordinator owner = holder.get(); Transfer transfer = held.get();
            if (owner != null && transfer != null) synchronized (owner) { transfer.commandHeld = false; }
        }));
        return delivered;
    }

    private record State(int level, float progress, int total) {
        static State capture(ServerPlayer player) { return new State(player.experienceLevel, player.experienceProgress, player.totalExperience); }
        void apply(ServerPlayer player) {
            player.setExperienceLevels(this.level);
            player.experienceProgress = this.progress;
            player.totalExperience = this.total;
            player.connection.send(new ClientboundSetExperiencePacket(this.progress, this.total, this.level));
        }
        boolean matches(CompoundTag data) {
            return data.getInt("XpLevel").orElse(-1) == this.level && data.getInt("XpTotal").orElse(-1) == this.total
                && Float.floatToIntBits(data.getFloat("XpP").orElse(Float.NaN)) == Float.floatToIntBits(this.progress);
        }
    }

    private static final class Transfer {
        UUID id;
        UUID from;
        UUID to;
        BigInteger amount;
        String phase;
        State sourceBefore;
        State sourceAfter;
        State targetBefore;
        State targetAfter;
        transient java.util.concurrent.CompletableFuture<Void> completed=new java.util.concurrent.CompletableFuture<>();
        transient boolean commandHeld;
        boolean offline;
        String offlineBefore,offlineAfter;
        transient boolean offlineDebitKnown;
        transient java.util.concurrent.CompletableFuture<Void> offlineFileReady=new java.util.concurrent.CompletableFuture<>();
        String sourceStateFile,targetStateFile;
    }

    private record CreditEscrow(String id, BigInteger amount) {}

    private static final class Coordinator {
        private final MinecraftServer server;
        private final Path file;
        private final LinkedHashMap<UUID, Transfer> transfers = new LinkedHashMap<>();
        private final java.util.Set<UUID> reportedFailures = new java.util.HashSet<>();
        private final Map<UUID,OfflineClaim> offlineClaims=new HashMap<>();
        private final Map<UUID,java.util.concurrent.CompletableFuture<Void>> offlineCreditSteps=new java.util.concurrent.ConcurrentHashMap<>();
        private java.util.concurrent.Executor offlineFiles=java.util.concurrent.ForkJoinPool.commonPool();
        private OfflineReader offlineReader=path->net.minecraft.nbt.NbtIo.readCompressed(path,net.minecraft.nbt.NbtAccounter.create(64L*1024*1024));

        Coordinator(MinecraftServer server) {
            this.server = server;
            this.file = server.getWorldPath(LevelResource.ROOT).resolve("carpet-org-experience-transfers.json");
            if (Files.isRegularFile(this.file)) {
                try {
                    Transfer[] saved = JSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8), Transfer[].class);
                    if (saved == null) throw new IllegalArgumentException("An experience transaction ledger must be a JSON array");
                    java.util.Set<UUID> participants = new java.util.HashSet<>();
                    for (Transfer transfer : saved) {
                        if (transfer == null || transfer.id == null || transfer.from == null || transfer.to == null || transfer.amount == null
                            || transfer.from.equals(transfer.to) || transfer.amount.signum() <= 0 || transfer.amount.compareTo(OrgExperienceAmounts.MAX_TOTAL) > 0
                            || transfer.phase == null || !(java.util.Set.of("created", "debited").contains(transfer.phase)||transfer.offline&&transfer.phase.equals("cancelled"))
                            || transfer.offline&&(transfer.offlineBefore==null||transfer.offlineAfter==null)
                            || this.transfers.containsKey(transfer.id) || !participants.add(transfer.from) || !participants.add(transfer.to)) {
                            throw new IllegalArgumentException("Invalid persisted experience transaction");
                        }
                        this.transfers.put(transfer.id, transfer);
                        if(transfer.completed==null)transfer.completed=new java.util.concurrent.CompletableFuture<>();
                        transfer.offlineDebitKnown=transfer.offline&&transfer.phase.equals("debited");
                        if(transfer.offlineFileReady==null)transfer.offlineFileReady=new java.util.concurrent.CompletableFuture<>();
                        if(transfer.phase.equals("debited"))transfer.offlineFileReady.complete(null);
                        if(transfer.sourceStateFile!=null)statePath(transfer.from,transfer.sourceStateFile);
                        if(transfer.targetStateFile!=null)statePath(transfer.to,transfer.targetStateFile);
                        if(transfer.offline){
                            CompoundTag before=OrgPlayerManager.decode(transfer.offlineBefore),after=OrgPlayerManager.decode(transfer.offlineAfter),expected=before.copy();expected.putInt("XpLevel",0);expected.putFloat("XpP",0);expected.putInt("XpTotal",0);var values=expected.getCompoundOrEmpty("BukkitValues").copy();values.putString(DEBIT.toString(),transfer.id.toString());expected.put("BukkitValues",values);
                            if(!expected.equals(after)||!offlineAmount(before).equals(transfer.amount))throw new IllegalArgumentException("Offline XP source plan does not match its exact debit");
                        }
                    }
                } catch (IOException | RuntimeException exception) {
                    throw new IllegalStateException("Cannot load the pending Carpet experience transfers", exception);
                }
            }
            // Existing paid/planned source rows recover independently of a source login.
            // The native first-read path can also run the same idempotent file step while
            // already holding its own UUID lease; the queued startup job then sees it done.
            for(Transfer transfer:List.copyOf(transfers.values()))if(transfer.offline&&!transfer.phase.equals("debited")){
                var actual=OrgPlayerFileLease.withLease(server,transfer.from,"offline XP startup recovery",lease->debitOffline(transfer)).thenRun(()->dispatch(transfer.to));
                carpet.script.external.ScarpetNativeWork.trackNative(server,actual);
            }
        }

        synchronized OfflineClaim claimOffline(UUID from,UUID to){
            if(offlineClaims.containsKey(from)||offlineClaims.containsKey(to))return null;
            for(Transfer transfer:transfers.values())if(java.util.Set.of(transfer.from,transfer.to).contains(from)||java.util.Set.of(transfer.from,transfer.to).contains(to))return null;
            var claim=new OfflineClaim(from,to);offlineClaims.put(from,claim);offlineClaims.put(to,claim);return claim;
        }
        synchronized void releaseClaim(OfflineClaim claim){offlineClaims.remove(claim.from,claim);offlineClaims.remove(claim.to,claim);claim.sourceReady.complete(null);claim.completed.complete(null);}
        synchronized Transfer prepareOffline(OfflineClaim claim,BigInteger amount,CompoundTag before){
            if(offlineClaims.get(claim.from)!=claim||offlineClaims.get(claim.to)!=claim)throw new IllegalStateException("Offline experience admission identity changed");
            Transfer transfer=new Transfer();transfer.id=claim.id;transfer.from=claim.from;transfer.to=claim.to;transfer.amount=amount;transfer.phase="created";transfer.offline=true;
            transfer.sourceStateFile=relativePath(claim.from,nativePath(claim.from));
            CompoundTag after=before.copy();after.putInt("XpLevel",0);after.putFloat("XpP",0);after.putInt("XpTotal",0);var values=after.getCompoundOrEmpty("BukkitValues").copy();values.putString(DEBIT.toString(),transfer.id.toString());after.put("BukkitValues",values);
            try{transfer.offlineBefore=OrgPlayerManager.encode(before);transfer.offlineAfter=OrgPlayerManager.encode(after);}catch(IOException failure){throw new IllegalStateException("Cannot encode the offline XP source custody",failure);}
            transfers.put(transfer.id,transfer);offlineClaims.remove(claim.from,claim);offlineClaims.remove(claim.to,claim);transfer.completed.whenComplete((ignored,failure)->{if(failure==null)claim.completed.complete(null);else claim.completed.completeExceptionally(failure);});
            transfer.offlineFileReady.whenComplete((ignored,failure)->{if(failure==null)claim.sourceReady.complete(null);else claim.sourceReady.completeExceptionally(failure);});
            // This row stays held on an unknown ledger write. File debit's retry proves
            // the complete prepared row durable before touching the source file.
            try{save();}catch(RuntimeException failure){reportOffline(transfer,failure);}return transfer;
        }
        synchronized Transfer offlineSource(UUID player){for(Transfer transfer:transfers.values())if(transfer.offline&&transfer.from.equals(player)&&!transfer.phase.equals("debited"))return transfer;return null;}
        boolean online(UUID player){return server.getPlayerList().getPlayer(player)!=null||server.getBotList()!=null&&server.getBotList().getBot(player)!=null;}
        Path nativePath(UUID player){
            java.io.File directory=server.getPlayerList().playerIo.getPlayerDir();Path configured=directory==null?server.getWorldPath(LevelResource.PLAYER_DATA_DIR):directory.toPath();
            return (configured==null?server.getWorldPath(LevelResource.ROOT).resolve("playerdata"):configured).resolve(player+".dat").toAbsolutePath().normalize();
        }
        Path liveStatePath(ServerPlayer player){
            Path actual=player instanceof ServerBot bot&&!bot.carpetNativePlayer?server.getBotList().getCarpetBotStatePath(bot):nativePath(player.getUUID());
            if(actual==null)actual=server.getWorldPath(LevelResource.ROOT).resolve("fakeplayerdata").resolve(player.getUUID()+".dat");return statePath(player.getUUID(),relativePath(player.getUUID(),actual));
        }
        String relativePath(UUID player,Path path){
            Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(),actual=path.toAbsolutePath().normalize();
            if(!actual.startsWith(root)||!actual.getFileName().toString().equals(player+".dat"))throw new IllegalArgumentException("XP custody file leaves its UUID world state");
            for(Path parent=actual;parent!=null&&!parent.equals(root);parent=parent.getParent())if(Files.isSymbolicLink(parent))throw new IllegalArgumentException("XP custody file is a symbolic link");return root.relativize(actual).toString();
        }
        Path statePath(UUID player,String relative){
            Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(),actual=root.resolve(relative).toAbsolutePath().normalize();relativePath(player,actual);
            if(!List.of(nativePath(player).getParent(),root.resolve("fakeplayerdata"),root.resolve("resume_fakeplayerdata")).contains(actual.getParent()))throw new IllegalArgumentException("XP custody file is not a native player/bot storage path");return actual;
        }
        Path offlinePath(UUID player)throws IOException{
            Path root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(),path=server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize().resolve(player+".dat");
            if(!path.startsWith(root))throw new IOException("Offline XP source leaves its native world");for(Path parent=path;parent!=null&&!parent.equals(root);parent=parent.getParent())if(Files.isSymbolicLink(parent))throw new IOException("Offline XP source is a symbolic link");return path;
        }
        java.util.concurrent.CompletableFuture<CompoundTag> readOffline(UUID player){return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{
            CompoundTag data=offlineReader.read(offlinePath(player));
            if(data.contains("UUID")&&!net.minecraft.core.UUIDUtil.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,data.get("UUID")).getOrThrow().equals(player))throw new IOException("Offline XP file UUID mismatch");return data;
        }catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}},offlineFiles);}
        java.util.concurrent.CompletableFuture<Void> debitOffline(Transfer transfer){
            return java.util.concurrent.CompletableFuture.runAsync(()->{
                try{
                    synchronized(this){if(!transfers.containsKey(transfer.id))return;save();if(transfer.phase.equals("debited")){transfer.offlineDebitKnown=true;return;}if(transfer.phase.equals("cancelled")){retire(transfer);transfer.completed.complete(null);return;}}
                    CompoundTag before=OrgPlayerManager.decode(transfer.offlineBefore),after=OrgPlayerManager.decode(transfer.offlineAfter);Path path=offlinePath(transfer.from);CompoundTag current=offlineReader.read(path);
                    boolean receipt=transfer.id.toString().equals(current.getCompoundOrEmpty("BukkitValues").getStringOr(DEBIT.toString(),""));
                    if(!receipt){
                        if(!current.equals(before)){synchronized(this){transfer.phase="cancelled";save();retire(transfer);transfer.completed.complete(null);}return;}
                        Path temporary=Files.createTempFile(path.getParent(),"offline-xp-",".tmp");try{
                            net.minecraft.nbt.NbtIo.writeCompressed(after,temporary);try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
                            try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(java.nio.file.AtomicMoveNotSupportedException unsupported){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
                            if(!after.equals(offlineReader.read(path)))throw new IOException("Offline XP debit readback is unknown");
                        }finally{try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
                    }
                    // The private debit marker proves an earlier completed source clear,
                    // even if a later legitimate native save now contains new XP/items.
                    synchronized(this){transfer.phase="debited";save();transfer.offlineDebitKnown=true;}
                }catch(IOException|RuntimeException failure){reportOffline(transfer,failure);throw new java.util.concurrent.CompletionException(failure);}
            },offlineFiles).exceptionallyCompose(failure->java.util.concurrent.CompletableFuture.runAsync(()->{},java.util.concurrent.CompletableFuture.delayedExecutor(1,java.util.concurrent.TimeUnit.SECONDS,offlineFiles)).thenCompose(ignored->debitOffline(transfer)))
                .whenComplete((ignored,failure)->{if(failure==null)transfer.offlineFileReady.complete(null);});
        }
        private synchronized void reportOffline(Transfer transfer,Throwable failure){if(reportedFailures.add(transfer.id))com.mojang.logging.LogUtils.getLogger().error("Offline XP transfer {} preserves file/player custody until real readback recovers",transfer.id,failure);}

        private synchronized Transfer prepare(ServerPlayer player, UUID to, BigInteger amount) {
            if(offlineClaims.containsKey(player.getUUID())||offlineClaims.containsKey(to))throw new IllegalStateException("Offline experience preparation owns one of these participants");
            for (Transfer transfer : transfers.values()) {
                if (transfer.from.equals(player.getUUID()) || transfer.to.equals(player.getUUID()) || transfer.from.equals(to) || transfer.to.equals(to)) {
                    throw new IllegalStateException("A pending transfer already involves one of these players");
                }
            }
            Transfer transfer = new Transfer();
            transfer.id = UUID.randomUUID(); transfer.from = player.getUUID(); transfer.to = to;
            transfer.sourceStateFile=relativePath(player.getUUID(),liveStatePath(player));
            transfer.amount = amount; transfer.phase = "created"; transfer.sourceBefore = State.capture(player);
            transfers.put(transfer.id, transfer);
            try { save(); } catch (RuntimeException exception) { transfers.remove(transfer.id); throw exception; }
            return transfer;
        }
        synchronized void begin(ServerPlayer player, UUID to, BigInteger amount) {
            Transfer transfer = prepare(player, to, amount);
            debit(player, transfer);
            dispatch(to);
        }
        synchronized java.util.concurrent.CompletableFuture<Void> available(UUID player){
            var pending=new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
            for(Transfer transfer:transfers.values())if(player.equals(transfer.from)||player.equals(transfer.to))pending.add(player.equals(transfer.from)?transfer.offlineFileReady:transfer.completed);
            OfflineClaim claim=offlineClaims.get(player);if(claim!=null)pending.add(player.equals(claim.from)?claim.sourceReady:claim.completed);var step=offlineCreditSteps.get(player);if(step!=null)pending.add(step);
            return java.util.concurrent.CompletableFuture.allOf(pending.toArray(java.util.concurrent.CompletableFuture[]::new));
        }

        synchronized void process(ServerPlayer player) {
            if (player.isRemoved() || player.isDeadOrDying()) return;
            for (Transfer transfer : new ArrayList<>(transfers.values())) {
                if (transfer.commandHeld) continue;
                try {
                    if(transfer.offline){if(transfer.phase.equals("debited")&&transfer.offlineDebitKnown&&transfer.to.equals(player.getUUID()))queueOfflineCredit(player,transfer);continue;}
                    if (transfer.phase.equals("created") && transfer.from.equals(player.getUUID())) {
                        debit(player, transfer);
                        dispatch(transfer.to);
                    } else if (transfer.phase.equals("debited") && transfer.to.equals(player.getUUID())) credit(player, transfer);
                } catch (RuntimeException exception) {
                    if (reportedFailures.add(transfer.id)) {
                        com.mojang.logging.LogUtils.getLogger().error("Pending Carpet experience transfer {} cannot be committed", transfer.id, exception);
                        player.sendSystemMessage(Component.literal("The pending experience transfer could not be saved; it will be retried"));
                    }
                }
            }
        }
        private void queueOfflineCredit(ServerPlayer player,Transfer transfer){
            var step=new java.util.concurrent.CompletableFuture<Void>();if(offlineCreditSteps.putIfAbsent(player.getUUID(),step)!=null)return;
            carpet.script.external.ScarpetNativeWork.trackNative(server,step);
            boolean queued=player.getBukkitEntity().taskScheduler.schedule(owner->{
                if(owner!=player||player.isRemoved()||player.isDeadOrDying()){
                    offlineCreditSteps.remove(player.getUUID(),step);step.complete(null);return;
                }
                try{
                java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> run=()->{
                    carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(player,step);
                    return carpet.script.external.ScarpetNativeWork.<Void>observeNative(player,()->{
                        try(var accepted=carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)){synchronized(this){if(transfers.containsKey(transfer.id)&&transfer.phase.equals("debited"))credit(player,transfer);}}return null;
                    });
                };
                var actual=player.carpetActionPack==null?carpet.script.external.ScarpetPlayerInventoryGate.whenIdle(player,run):OrgFakePlayerActions.whenIdle(player,run);
                actual.thenCompose(value->value).whenComplete((ignored,failure)->{
                    offlineCreditSteps.remove(player.getUUID(),step);if(failure==null)step.complete(null);else{reportOffline(transfer,failure);step.completeExceptionally(failure);}
                    synchronized(this){if(!transfers.containsKey(transfer.id)){if(failure==null)transfer.completed.complete(null);else transfer.completed.completeExceptionally(failure);}}
                });
                }catch(Throwable failure){offlineCreditSteps.remove(player.getUUID(),step);reportOffline(transfer,failure);step.completeExceptionally(failure);}
            },retired->{offlineCreditSteps.remove(player.getUUID(),step);step.completeExceptionally(new IllegalStateException("Offline XP destination retired before actual credit"));},1L);
            if(!queued){offlineCreditSteps.remove(player.getUUID(),step);step.completeExceptionally(new IllegalStateException("Offline XP destination retired before actual credit"));}
        }

        private void debit(ServerPlayer player, Transfer transfer) {
            String id = transfer.id.toString();
            var pdc = player.getBukkitEntity().getPersistentDataContainer();
            String previous = pdc.get(DEBIT, PersistentDataType.STRING);
            if (id.equals(previous)) {
                // A live receipt with an unreadable save is not proof of a durable debit.
                if (!persist(player, DEBIT, id, State.capture(player))) throw new IllegalStateException("The source debit remains in escrow until player storage can be verified");
                transfer.phase = "debited"; save(); transfer.offlineFileReady.complete(null); return;
            }
            BigInteger existing = OrgExperienceAmounts.read(player);
            if (existing.compareTo(transfer.amount) < 0) throw new IllegalStateException("Pending transfer requires more experience than the source player has");
            State before = State.capture(player);
            OrgExperienceAmounts.write(player, existing.subtract(transfer.amount));
            transfer.sourceBefore = before; transfer.sourceAfter = State.capture(player);
            // Persist the exact planned player state before committing it to player storage.
            try { save(); } catch (RuntimeException exception) { before.apply(player); throw exception; }
            pdc.set(DEBIT, PersistentDataType.STRING, id);
            if (!persist(player, DEBIT, id, transfer.sourceAfter)) {
                // The file may already contain this debit. Keep the paid amount detached from spendable XP.
                throw new IllegalStateException("The source debit remains in escrow until player storage can be verified");
            }
            transfer.phase = "debited";
            save();
            transfer.offlineFileReady.complete(null);
        }

        private void credit(ServerPlayer player, Transfer transfer) {
            String state=relativePath(player.getUUID(),liveStatePath(player));
            if(transfer.targetStateFile!=null&&!transfer.targetStateFile.equals(state))throw new IllegalStateException("This UUID actor uses a different file than the XP recipient custody");
            if(transfer.targetStateFile==null){transfer.targetStateFile=state;save();}
            String id = transfer.id.toString();
            var pdc = player.getBukkitEntity().getPersistentDataContainer();
            String previous = pdc.get(CREDIT, PersistentDataType.STRING);
            if (id.equals(previous)) {
                if (!persist(player, CREDIT, id, State.capture(player))) throw new IllegalStateException("Cannot verify the destination credit receipt");
                retire(transfer); return;
            }
            String heldMarker = id + ":held";
            String held = pdc.get(CREDIT_ESCROW, PersistentDataType.STRING);
            if (heldMarker.equals(previous)) {
                CreditEscrow escrow = JSON.fromJson(held, CreditEscrow.class);
                if (escrow == null || !id.equals(escrow.id) || !transfer.amount.equals(escrow.amount)) throw new IllegalStateException("Malformed destination XP escrow");
            } else {
                if (held != null) throw new IllegalStateException("Another XP credit already occupies the destination escrow");
                held = JSON.toJson(new CreditEscrow(id, transfer.amount));
                pdc.set(CREDIT_ESCROW, PersistentDataType.STRING, held);
                pdc.set(CREDIT, PersistentDataType.STRING, heldMarker);
            }
            // The incoming amount is stored outside spendable XP. IO failures never expose it to enchanting or orb drops.
            if (!persist(player, CREDIT, heldMarker, State.capture(player))) throw new IllegalStateException("Cannot verify destination XP escrow custody");
            BigInteger combined = OrgExperienceAmounts.read(player).add(transfer.amount);
            if (combined.compareTo(OrgExperienceAmounts.MAX_TOTAL) > 0) return;
            State before = State.capture(player);
            OrgExperienceAmounts.write(player, combined);
            transfer.targetBefore = before; transfer.targetAfter = State.capture(player);
            try { save(); } catch (RuntimeException exception) { before.apply(player); throw exception; }
            pdc.remove(CREDIT_ESCROW);
            pdc.set(CREDIT, PersistentDataType.STRING, id);
            boolean verified;
            try { verified = persist(player, CREDIT, id, transfer.targetAfter); }
            catch (RuntimeException exception) { holdCredit(player, before, heldMarker, held); throw exception; }
            if (!verified) {
                holdCredit(player, before, heldMarker, held);
                throw new IllegalStateException("Destination XP release remains held until storage can be verified");
            }
            retire(transfer);
            player.sendSystemMessage(Component.literal("Received " + transfer.amount + " experience points"));
        }

        private void holdCredit(ServerPlayer player, State spendableBefore, String heldMarker, String held) {
            // The SAME actor has not returned to gameplay. Incoming funds are returned to the already durable held amount.
            spendableBefore.apply(player);
            var pdc = player.getBukkitEntity().getPersistentDataContainer();
            pdc.set(CREDIT_ESCROW, PersistentDataType.STRING, held);
            pdc.set(CREDIT, PersistentDataType.STRING, heldMarker);
            // The next actor verifies held custody BEFORE another release attempt. No unverified compensating save is assumed successful.
        }

        private void retire(Transfer transfer) {
            transfers.remove(transfer.id);
            try { save(); } catch (RuntimeException exception) { transfers.put(transfer.id, transfer); throw exception; }
            if(!transfer.offline)transfer.completed.complete(null);
        }

        java.util.concurrent.CompletableFuture<Void> recoverFileBeforeRead(UUID player,Path requested){
            Path file=statePath(player,relativePath(player,requested));
            var actual=new java.util.concurrent.CompletableFuture<Void>();
            carpet.script.external.ScarpetNativeWork.trackNative(server,actual);
            retryFileRecovery(player,file,actual);return actual.copy();
        }
        private void retryFileRecovery(UUID player,Path file,java.util.concurrent.CompletableFuture<Void> actual){
            java.util.concurrent.CompletableFuture.runAsync(()->{
                try{normalizeFileBeforeRead(player,file);}
                catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}
            },offlineFiles).whenComplete((ignored,failure)->{
                if(failure==null){actual.complete(null);return;}
                Transfer pending; synchronized(this){pending=transfers.values().stream().filter(value->value.from.equals(player)||value.to.equals(player)).findFirst().orElse(null);}
                if(pending!=null)reportOffline(pending,failure);
                java.util.concurrent.CompletableFuture.delayedExecutor(1,java.util.concurrent.TimeUnit.SECONDS,offlineFiles).execute(()->retryFileRecovery(player,file,actual));
            });
        }
        private void normalizeFileBeforeRead(UUID player,Path file)throws IOException{
            Transfer transfer;boolean source;
            synchronized(this){
                transfer=transfers.values().stream().filter(value->
                    value.from.equals(player)&&!value.offline&&value.phase.equals("created")&&(value.sourceStateFile==null||statePath(player,value.sourceStateFile).equals(file))
                    ||value.to.equals(player)&&value.phase.equals("debited")&&(value.targetStateFile==null||statePath(player,value.targetStateFile).equals(file)))
                    .findFirst().orElse(null);
                if(transfer==null)return;source=transfer.from.equals(player);
            }
            CompoundTag before=offlineReader.read(file);validateNativeXpFile(player,before);CompoundTag after=before.copy(),values=after.getCompoundOrEmpty("BukkitValues").copy();String id=transfer.id.toString();
            if(source){
                boolean receipt=id.equals(values.getStringOr(DEBIT.toString(),""));
                if(!receipt){
                    if(transfer.sourceAfter==null||transfer.sourceBefore==null||!transfer.sourceBefore.matches(before)){
                        // No debit receipt and the real file moved before debit: cancel the
                        // unpaid row. Never restore a historical source inventory or XP image.
                        synchronized(this){if(transfers.get(transfer.id)==transfer){retire(transfer);transfer.offlineFileReady.complete(null);}}return;
                    }
                    writeXp(after,transfer.sourceAfter);values.putString(DEBIT.toString(),id);after.put("BukkitValues",values);writeNativeFile(file,before,after);
                }else{
                    if(transfer.sourceAfter==null)throw new IOException("The XP source receipt has no durable debit plan");
                    // A later legitimate whole native save can change current XP while retaining this paid marker.
                    // Reading and comparing the complete latest native tag proves its state, never restores the plan.
                    if(!before.equals(offlineReader.read(file)))throw new IOException("XP source receipt readback changed");
                }
                synchronized(this){if(transfers.get(transfer.id)==transfer){transfer.sourceStateFile=relativePath(player,file);transfer.phase="debited";save();transfer.offlineFileReady.complete(null);}}
                dispatch(transfer.to);return;
            }
            String marker=values.getStringOr(CREDIT.toString(),"");String held=values.getString(CREDIT_ESCROW.toString()).orElse(null);
            if(id.equals(marker)){
                if(transfer.targetBefore==null||transfer.targetAfter==null)throw new IOException("The XP credit receipt has no durable release plan");
                // This exact private id is written only after the planned incoming amount
                // was added. Preserve the latest real XP (including later legal spending),
                // prove the complete file again and retire durable incoming custody before join.
                values.remove(CREDIT_ESCROW.toString());after.put("BukkitValues",values);writeNativeFile(file,before,after);
                synchronized(this){if(transfers.get(transfer.id)==transfer){transfer.targetStateFile=relativePath(player,file);retire(transfer);transfer.completed.complete(null);}}
                return;
            }
            if(held!=null){CreditEscrow escrow=JSON.fromJson(held,CreditEscrow.class);if(escrow==null||!id.equals(escrow.id)||!transfer.amount.equals(escrow.amount))throw new IOException("Another or malformed XP custody occupies this native file");}
            values.putString(CREDIT.toString(),id+":held");values.putString(CREDIT_ESCROW.toString(),JSON.toJson(new CreditEscrow(id,transfer.amount)));after.put("BukkitValues",values);
            // Only incoming custody is changed. Current native spendable XP/items/PDC and
            // every unrelated field are preserved; no fake SP or historical compensation.
            writeNativeFile(file,before,after);
            synchronized(this){if(transfers.get(transfer.id)==transfer){transfer.targetStateFile=relativePath(player,file);save();}}
        }
        private static void validateNativeXpFile(UUID player,CompoundTag tag)throws IOException{
            if(tag.contains("UUID")&&!net.minecraft.core.UUIDUtil.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,tag.get("UUID")).getOrThrow().equals(player))throw new IOException("XP custody file UUID mismatch");
            if(tag.getIntOr("XpTotal",0)<0)throw new IOException("Negative native XP total");offlineAmount(tag);
        }
        private static void writeXp(CompoundTag tag,State state){tag.putInt("XpLevel",state.level);tag.putFloat("XpP",state.progress);tag.putInt("XpTotal",state.total);}
        private void writeNativeFile(Path file,CompoundTag expected,CompoundTag updated)throws IOException{
            if(!expected.equals(offlineReader.read(file)))throw new IOException("Native XP file changed before custody publication");
            if(!expected.equals(updated)){
                Path temporary=Files.createTempFile(file.getParent(),"native-xp-custody-",".tmp");try{
                    net.minecraft.nbt.NbtIo.writeCompressed(updated,temporary);try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
                    try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(java.nio.file.AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
                }finally{try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
            }
            if(!updated.equals(offlineReader.read(file)))throw new IOException("Native XP custody readback is unknown");
        }

        private java.util.concurrent.CompletableFuture<Void> dispatch(UUID playerId) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if(player==null&&server.getBotList()!=null)player=server.getBotList().getBot(playerId);
            // A confirmed offline destination has durable custody, not an unfinished native job.
            if(player==null)return java.util.concurrent.CompletableFuture.completedFuture(null);
            ServerPlayer expected=player;var actual=new java.util.concurrent.CompletableFuture<Void>();
            carpet.script.external.ScarpetNativeWork.trackNative(server,actual);
            boolean queued=expected.getBukkitEntity().taskScheduler.schedule(owned->{
                try{
                    if(owned!=expected||expected.isRemoved()||expected.isDeadOrDying()){actual.complete(null);return;}
                    process(expected); // Offline credit enrolls its next true step before this dispatch can end.
                    actual.complete(null);
                }catch(Throwable failure){actual.completeExceptionally(failure);}
            },retired->actual.complete(null),1L);
            if(!queued)actual.complete(null);
            return actual;
        }

        private boolean persist(ServerPlayer player, NamespacedKey marker, String id, State state) {
            String expectedHeld = player.getBukkitEntity().getPersistentDataContainer().get(CREDIT_ESCROW, PersistentDataType.STRING);
            Optional<CompoundTag> result;
            if (player instanceof ServerBot bot&&!bot.carpetNativePlayer) result = server.getBotList().saveCarpetBotState(bot);
            else {
                if(player instanceof ServerBot bot)server.getPlayerList().carpetSaveFakePlayer(bot);else server.getPlayerList().playerIo.save(player);
                result = server.getPlayerList().playerIo.load(player.nameAndId());
            }
            return result.filter(state::matches).flatMap(tag -> tag.getCompound("BukkitValues"))
                .filter(values -> java.util.Objects.equals(expectedHeld, values.getString(CREDIT_ESCROW.toString()).orElse(null)))
                .flatMap(values -> values.getString(marker.toString())).filter(id::equals).isPresent();
        }

        private static void restoreMarker(ServerPlayer player, NamespacedKey key, String previous) {
            if (previous == null) player.getBukkitEntity().getPersistentDataContainer().remove(key);
            else player.getBukkitEntity().getPersistentDataContainer().set(key, PersistentDataType.STRING, previous);
        }

        private void save() {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            byte[] serialized = JSON.toJson(transfers.values()).getBytes(StandardCharsets.UTF_8);
            try {
                Files.createDirectories(file.getParent());
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    ByteBuffer data = ByteBuffer.wrap(serialized);
                    while (data.hasRemaining()) output.write(data);
                    output.force(true);
                }
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
                if(!java.util.Arrays.equals(serialized,Files.readAllBytes(file)))throw new IOException("Experience ledger readback is unknown");
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot save the pending Carpet experience transfers", exception);
            }
        }
    }
}
