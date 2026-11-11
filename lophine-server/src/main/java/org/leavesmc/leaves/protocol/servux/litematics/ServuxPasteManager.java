// SPDX-License-Identifier: LGPL-3.0-only
package org.leavesmc.leaves.protocol.servux.litematics;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.CarpetRegionLease;
import fun.bm.lophine.config.modules.function.protocol.ServuxProtocolConfig;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;
import org.leavesmc.leaves.protocol.servux.ServuxDataByteBuf;
import org.leavesmc.leaves.protocol.servux.ServuxProtocol;
import org.leavesmc.leaves.protocol.servux.litematics.placement.SchematicPlacement;
import org.leavesmc.leaves.protocol.servux.litematics.utils.ReplaceBehavior;
import org.leavesmc.leaves.protocol.servux.litematics.utils.SchematicPlacingUtils;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Background preparation never accesses mutable world state; all placement runs on the current region owner. */
public final class ServuxPasteManager {
    private static final int MAX_JOBS = 4;
    private static final int MAX_PREPARED_SECTIONS = 16;
    private static final long RESERVE_NANOS = 2_000_000L;
    private static final Map<MinecraftServer, State> STATES = new ConcurrentHashMap<>();
    private static final AtomicInteger PAUSED_SECTIONS = new AtomicInteger();
    private static final ExecutorService PREPARER = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_JOBS * (MAX_PREPARED_SECTIONS + 1)), task -> Thread.ofPlatform().daemon(true).name("Servux paste preparation").unstarted(task),
            new ThreadPoolExecutor.AbortPolicy());

    private ServuxPasteManager() { }

    /** The caller retains its original buffer; this method owns only its retained read-only slice. */
    public static void receive(ServerPlayer player, FriendlyByteBuf packet) {
        FriendlyByteBuf owned = new FriendlyByteBuf(packet.retainedSlice().asReadOnly());
        onPlayerThread(player, owner -> receiveOwned(owner, owned), owned::release);
    }

    private static void receiveOwned(ServerPlayer player, FriendlyByteBuf owned) {
        Job job;
        try { job = admit(player); }
        catch (RuntimeException failure) { owned.release(); throw failure; }
        if (job == null) { owned.release(); return; }
        try {
            PREPARER.execute(() -> job.read(owned));
        } catch (RuntimeException failure) {
            owned.release();
            synchronized (job) { job.preparing = false; }
            job.fail(failure);
            return;
        }
        job.message("Servux: queued for background preparation", NamedTextColor.YELLOW);
    }

    private static void onPlayerThread(ServerPlayer player, Consumer<ServerPlayer> action, Runnable discarded) {
        Consumer<ServerPlayer> run = current -> {
            if (current.hasDisconnected()) { discarded.run(); return; }
            try { action.accept(current); }
            catch (RuntimeException failure) { ServuxProtocol.LOGGER.warn("Cannot admit Servux paste for {}", current.getUUID(), failure); }
        };
        if (TickThread.isTickThreadFor(player)) {
            run.accept(player);
            return;
        }
        boolean scheduled;
        try {
            scheduled = player.getBukkitEntity().taskScheduler.schedule(run, ignored -> discarded.run(), 1L);
        } catch (RuntimeException failure) {
            discarded.run();
            ServuxProtocol.LOGGER.warn("Cannot schedule Servux paste admission for {}", player.getUUID(), failure);
            return;
        }
        if (!scheduled) discarded.run();
    }

    private static Job admit(ServerPlayer player) {
        return admit(player, player.level());
    }

    private static Job admit(ServerPlayer player, ServerLevel world) {
        TickThread.ensureTickThread(player, "Servux paste admission must own its player");
        if (world.getServer() != player.level().getServer()) throw new IllegalArgumentException("Paste world belongs to another server");
        if (!ServuxProtocolConfig.litematicsEnabled || !ServuxLitematicsProtocol.hasPermission(player) || !player.isCreative()
                || ScarpetNativeWork.isDraining(player.level().getServer())) {
            player.getBukkitEntity().sendActionBar(Component.text("Servux paste requires permission and Creative Mode", NamedTextColor.RED));
            return null;
        }
        State state = STATES.computeIfAbsent(player.level().getServer(), ignored -> new State());
        Job job = new Job(state, player, world);
        synchronized (state) {
            if (state.closed || state.jobs.size() >= MAX_JOBS || state.jobs.containsKey(player.getUUID())) {
                player.getBukkitEntity().sendActionBar(Component.text("Servux paste queue is full, or your previous paste is still active", NamedTextColor.RED));
                return null;
            }
            state.jobs.put(job.id, job);
        }
        return job;
    }

    public static void receiveTag(ServerPlayer player, net.minecraft.nbt.CompoundTag tag) {
        // Preserve the direct-call entry point without compressing or parsing NBT on the region thread.
        var snapshot = tag.copy();
        onPlayerThread(player, owner -> receiveTagOwned(owner, snapshot), () -> { });
    }

    private static void receiveTagOwned(ServerPlayer player, net.minecraft.nbt.CompoundTag snapshot) {
        Job job = admit(player);
        if (job == null) return;
        try {
            PREPARER.execute(() -> {
                var packet = new FriendlyByteBuf(Unpooled.buffer());
                try { ServuxDataByteBuf.write(packet, snapshot); }
                catch (Throwable failure) {
                    packet.release();
                    synchronized (job) { job.preparing = false; }
                    job.fail(failure);
                    return;
                }
                job.read(packet);
            });
        } catch (RuntimeException failure) {
            synchronized (job) { job.preparing = false; }
            job.fail(failure);
        }
    }

    public static void pastePrepared(ServerLevel world, ReplaceBehavior replace, ServerPlayer player, SchematicPlacement placement) {
        onPlayerThread(player, owner -> pastePreparedOwned(world, replace, owner, placement), () -> { });
    }

    private static void pastePreparedOwned(ServerLevel world, ReplaceBehavior replace, ServerPlayer player, SchematicPlacement placement) {
        Job job = admit(player, world);
        if (job == null) return;
        try {
            PREPARER.execute(() -> {
                try {
                    job.replace = replace;
                    job.installPlan(ServuxPastePlan.create(placement, job.minY, job.maxY, job.stopping::get));
                } catch (Throwable failure) { job.fail(failure); }
                finally {
                    synchronized (job) { job.preparing = false; }
                    job.pump();
                }
            });
        } catch (RuntimeException failure) {
            synchronized (job) { job.preparing = false; }
            job.fail(failure);
        }
    }

    public static boolean isPaused(Level level, BlockPos pos) {
        return isPaused(level, pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    public static boolean isPaused(Level level, int x, int y, int z) {
        if (PAUSED_SECTIONS.get() == 0 || !(level instanceof ServerLevel world)) return false;
        State state = STATES.get(world.getServer());
        Map<Long, Job> sections = state == null ? null : state.paused.get(world);
        return sections != null && sections.containsKey(SectionPos.asLong(x, y, z));
    }

    /** Called once at the end of each region tick; the deadline includes all jobs in that region. */
    public static void tick(ServerLevel world, long scheduledEnd) {
        if (PAUSED_SECTIONS.get() == 0) return;
        State state = STATES.get(world.getServer());
        if (state == null || state.ready.isEmpty()) return;
        if (state.closed || ScarpetNativeWork.isDraining(world.getServer())) return;
        long now = System.nanoTime();
        double configured = ServuxProtocolConfig.pasteMaxMillisPerTick;
        if (!Double.isFinite(configured) || configured <= 0.0D) configured = 8.0D;
        long available = scheduledEnd - now - RESERVE_NANOS;
        if (available <= 0L) return;
        long deadline = now + Math.min(available, (long) (Math.min(configured, 10.0D) * 1_000_000.0D));
        int skipped = 0, limit = Math.max(1, state.ready.size());
        while (System.nanoTime() < deadline && skipped < limit) {
            Batch batch = state.ready.poll();
            if (batch == null) break;
            batch.queued.set(false);
            var section = batch.prepared.section();
            if (batch.job.world != world || !TickThread.isTickThreadFor(world, section.x - 1, section.z - 1, section.x + 1, section.z + 1)) {
                batch.offer();
                skipped++;
                continue;
            }
            skipped = 0;
            batch.run(deadline);
        }
    }

    public static void maintain(MinecraftServer server) {
        State state = STATES.get(server);
        if (state == null) return;
        if (ScarpetNativeWork.isDraining(server)) {
            shutdown(server);
            return;
        }
        if (!ServuxProtocolConfig.litematicsEnabled) {
            for (Job job : List.copyOf(state.jobs.values())) job.cancel("Servux paste protocol was disabled");
            return;
        }
        long now = System.nanoTime();
        for (Job job : List.copyOf(state.jobs.values())) {
            if (now - job.lastMessage < TimeUnit.SECONDS.toNanos(1)) continue;
            job.lastMessage = now;
            job.progress();
        }
    }

    public static void cancel(ServerPlayer player) {
        cancelAdmitted(player);
        if (!TickThread.isTickThreadFor(player)) onPlayerThread(player, ServuxPasteManager::cancelAdmitted, () -> { });
    }

    private static void cancelAdmitted(ServerPlayer player) {
        State state = STATES.get(player.level().getServer());
        Job job = state == null ? null : state.jobs.get(player.getUUID());
        if (job != null && job.player == player) job.cancel("Servux paste cancelled");
    }

    /** Cancel unstarted work before native draining; admitted slice tails keep their real completion. */
    public static void shutdown(MinecraftServer server) {
        State state = STATES.get(server);
        if (state == null) return;
        state.closed = true;
        for (Job job : List.copyOf(state.jobs.values())) job.cancel("Servux paste cancelled during shutdown");
    }

    private static final class State {
        volatile boolean closed;
        final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
        final Map<ServerLevel, ConcurrentMap<Long, Job>> paused = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Batch> ready = new ConcurrentLinkedQueue<>();
    }

    private static final class Job {
        final State state;
        final ServerPlayer player;
        final UUID id;
        final ServerLevel world;
        final int minY, maxY;
        final long nbtLimit, started = System.nanoTime();
        final AtomicBoolean stopping = new AtomicBoolean();
        final AtomicBoolean cleaned = new AtomicBoolean();
        final AtomicLong blocks = new AtomicLong();
        final AtomicLong bulkSkippedAir = new AtomicLong();
        final Set<Batch> batches = ConcurrentHashMap.newKeySet();
        // Each bitmap is written by that section's current owner; phase completion publishes it to the fluid pass.
        final ConcurrentMap<Long, BitSet> fluidBases = new ConcurrentHashMap<>();
        volatile Path cache;
        volatile ServuxPastePlan plan;
        volatile ReplaceBehavior replace;
        volatile long lastMessage;
        volatile String failure;
        private boolean preparing = true;
        private int phase, next, completed, inFlight;
        private int claimedSections;

        Job(State state, ServerPlayer player, ServerLevel world) {
            this.state = state; this.player = player; this.id = player.getUUID(); this.world = world;
            this.minY = world.getMinY(); this.maxY = world.getMaxY() + 1; // The world API includes maxY; the paste plan excludes it.
            this.nbtLimit = ServuxProtocolConfig.litematicsMaxNbtSize;
        }

        void read(FriendlyByteBuf packet) {
            try {
                try {
                    if (stopping.get()) return;
                    Path directory = world.getServer().getServerDirectory().resolve("cache/servux-pastes");
                    Files.createDirectories(directory);
                    cache = Files.createTempFile(directory, "paste-", ".bin");
                    try (var channel = FileChannel.open(cache, StandardOpenOption.WRITE)) {
                        var bytes = packet.nioBuffer();
                        while (bytes.hasRemaining()) channel.write(bytes);
                    }
                } finally { packet.release(); }
                if (stopping.get()) return;
                var input = new FriendlyByteBuf(Unpooled.wrappedBuffer(Files.readAllBytes(cache)));
                net.minecraft.nbt.CompoundTag tag;
                try { tag = ServuxDataByteBuf.read(input, nbtLimit); }
                finally { input.release(); }
                if (!tag.getStringOr("Task", "").equals("LitematicaPaste"))
                    throw new IllegalArgumentException("Upload is not a Litematica paste");
                replace = ReplaceBehavior.fromStringStatic(tag.getStringOr("ReplaceMode", ReplaceBehavior.NONE.name()));
                ServuxPastePlan prepared = ServuxPastePlan.create(SchematicPlacement.createFromNbt(tag), minY, maxY, stopping::get);
                installPlan(prepared);
            } catch (Throwable problem) {
                if (!stopping.get()) fail(problem);
            } finally {
                synchronized (this) { preparing = false; }
                pump();
            }
        }

        void installPlan(ServuxPastePlan prepared) {
            synchronized (state) {
                if (stopping.get() || state.closed) return;
                var claimed = state.paused.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>());
                for (var section : prepared.sections)
                    if (claimed.containsKey(section.key())) throw new IllegalStateException("Paste overlaps another active paste");
                plan = prepared;
                for (var section : prepared.sections) claimed.put(section.key(), this);
                claimedSections = prepared.sections.size();
                PAUSED_SECTIONS.addAndGet(claimedSections);
            }
            if (prepared.heightClipped) {
                ServuxProtocol.LOGGER.warn("Servux paste {} extends outside build height Y={}..{}; out-of-range positions will be clipped", id, minY, maxY - 1);
                message("Servux: schematic exceeds world height; only Y=" + minY + ".." + (maxY - 1) + " will be pasted", NamedTextColor.YELLOW);
            }
        }

        void pump() {
            List<Work> work = new ArrayList<>();
            boolean finish;
            synchronized (this) {
                if (!stopping.get() && !preparing && plan != null) {
                    while (phase < plan.passes() && completed == plan.sections.size() && inFlight == 0) {
                        phase++; next = completed = 0;
                    }
                    int limit = Math.clamp(ServuxProtocolConfig.pasteConcurrentSections, 1, MAX_PREPARED_SECTIONS);
                    while (phase < plan.passes() && inFlight < limit && next < plan.sections.size()) {
                        work.add(new Work(plan.sections.get(next++), phase));
                        inFlight++;
                    }
                }
                finish = !preparing && inFlight == 0 && (stopping.get() || plan == null || phase == plan.passes());
            }
            for (Work item : work) {
                try { PREPARER.execute(() -> prepare(item)); }
                catch (RuntimeException problem) { fail(problem); sectionDone(); }
            }
            if (finish) cleanup();
        }

        void prepare(Work work) {
            try {
                if (stopping.get()) { sectionDone(); return; }
                var prepared = plan.preparePass(work.section, work.phase, replace, stopping::get);
                if (prepared.empty() || stopping.get()) { sectionDone(); return; }
                Batch batch = new Batch(this, prepared);
                batches.add(batch);
                if (stopping.get()) batch.cancel();
                var section = prepared.section();
                var admission = CarpetRegionLease.<CompletableFuture<Void>>runValue(world,
                        section.x - 1, section.z - 1, section.x + 1, section.z + 1, area -> {
                            batch.offer();
                            return batch.done;
                        });
                batch.admission = admission;
                if (stopping.get()) batch.cancel();
                var lease = admission.thenCompose(value -> value);
                lease.whenComplete((ignored, problem) -> {
                    state.ready.remove(batch);
                    batches.remove(batch);
                    if (problem != null && !stopping.get()) fail(problem);
                    sectionDone();
                });
            } catch (Throwable problem) {
                if (!stopping.get()) fail(problem);
                sectionDone();
            }
        }

        void sectionDone() {
            synchronized (this) { completed++; inFlight--; }
            pump();
        }

        void fail(Throwable problem) {
            ServuxProtocol.LOGGER.warn("Servux paste failed for {}", id, problem);
            cancel("Servux paste failed: " + problem.getClass().getSimpleName());
        }

        void cancel(String reason) {
            synchronized (this) {
                if (!stopping.get()) { failure = reason; stopping.set(true); }
            }
            for (Batch batch : batches) batch.cancel();
            pump();
        }

        void cleanup() {
            if (!cleaned.compareAndSet(false, true)) return;
            synchronized (state) {
                var claimed = state.paused.get(world);
                if (claimed != null && plan != null) for (var section : plan.sections) claimed.remove(section.key(), this);
                PAUSED_SECTIONS.addAndGet(-claimedSections);
                if (claimed != null && claimed.isEmpty()) state.paused.remove(world, claimed);
                state.jobs.remove(id, this);
                fluidBases.clear();
            }
            Path file = cache;
            if (file != null) Thread.ofVirtual().name("Servux paste cache cleanup").start(() -> {
                try { Files.deleteIfExists(file); }
                catch (java.io.IOException problem) { ServuxProtocol.LOGGER.warn("Cannot remove Servux paste cache {}", file, problem); }
            });
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            boolean heightClipped = plan != null && plan.heightClipped;
            String result = failure != null ? failure : heightClipped ? "completed_with_height_clipping" : "completed";
            ServuxProtocol.LOGGER.info("Servux paste {} finished: result={}, elapsed_ms={}, changed_blocks={}, bulk_skipped_air={}",
                    id, result, elapsedMillis, blocks.get(), bulkSkippedAir.get());
            if (!state.closed) message(failure != null ? failure : "Servux: applied " + blocks.get() + " block changes in "
                    + elapsedMillis + "ms" + (heightClipped ? "; clipped to Y=" + minY + ".." + (maxY - 1) : ""),
                    failure != null ? NamedTextColor.RED : heightClipped ? NamedTextColor.GOLD : NamedTextColor.GREEN);
        }

        void progress() {
            if (stopping.get()) return;
            String text;
            synchronized (this) {
                text = plan == null ? "Servux: caching and preparing schematic" : "Servux: "
                        + Math.min(100, (phase * plan.sections.size() + completed) * 100L / Math.max(1, plan.sections.size() * plan.passes()))
                        + "% | " + blocks.get() + " changes";
            }
            message(text, NamedTextColor.YELLOW);
        }

        void message(String text, NamedTextColor color) {
            try {
                player.getBukkitEntity().getScheduler().run(MinecraftInternalPlugin.INSTANCE, task -> {
                    if (cleaned.get() && color == NamedTextColor.YELLOW) return;
                    if (!player.hasDisconnected()) {
                        if (!cleaned.get() && (!player.isCreative() || !ServuxLitematicsProtocol.hasPermission(player)))
                            cancel("Servux paste permission or Creative Mode was lost");
                        player.getBukkitEntity().sendActionBar(Component.text(text, color));
                    }
                }, null);
            } catch (RuntimeException ignored) { }
        }

        record Work(ServuxPastePlan.Section section, int phase) { }
    }

    private static final class Batch {
        final Job job;
        final ServuxPastePlan.Prepared prepared;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final AtomicBoolean queued = new AtomicBoolean();
        volatile CompletableFuture<?> admission;
        private boolean running;
        private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
        private int cursor;
        private int airCursor;

        Batch(Job job, ServuxPastePlan.Prepared prepared) { this.job = job; this.prepared = prepared; }

        void offer() {
            if (job.stopping.get()) { cancel(); return; }
            if (!done.isDone() && queued.compareAndSet(false, true)) job.state.ready.add(this);
        }

        void cancel() {
            boolean finish;
            synchronized (this) { finish = !running && tail.isDone(); }
            if (finish) {
                done.complete(null);
                var waiting = admission;
                if (waiting != null) waiting.cancel(false);
            }
        }

        void run(long deadline) {
            synchronized (this) {
                if (running || !tail.isDone() || done.isDone()) return;
                running = true;
            }
            CompletableFuture<Void> actual = ScarpetNativeWork.observeNative(null, () -> {
                int phase = phaseAt(cursor);
                while (!job.stopping.get() && cursor < prepared.size() && System.nanoTime() < deadline) {
                    if (phaseAt(cursor) != phase) break;
                    if (cursor < prepared.changes().size() && prepared.changes().get(cursor).kind() == ServuxPastePlan.ChangeKind.AIR_RUN)
                        applyAirRun(prepared.changes().get(cursor), deadline);
                    else apply(cursor++);
                }
                return null;
            });
            ScarpetNativeWork.trackNative(job.world.getServer(), actual);
            synchronized (this) { tail = actual; running = false; }
            actual.whenComplete((ignored, problem) -> {
                if (problem != null) done.completeExceptionally(problem);
                else if (job.stopping.get() || cursor == prepared.size()) done.complete(null);
                else offer();
            });
        }

        private int phaseAt(int index) {
            if (prepared.phase() >= 0) return prepared.phase();
            return index < prepared.changes().size() ? ServuxPastePlan.phase(prepared.changes().get(index).state()) : ServuxPastePlan.PHASES - 1;
        }

        private void applyAirRun(ServuxPastePlan.Change change, long deadline) {
            if (job.replace == ReplaceBehavior.ALL && SchematicPlacingUtils.isUnchangedAirSection(job.world, change.pos())) {
                job.bulkSkippedAir.addAndGet(change.length() - airCursor);
                airCursor = 0;
                cursor++;
                return;
            }
            while (!job.stopping.get() && airCursor < change.length() && System.nanoTime() < deadline) {
                if (SchematicPlacingUtils.placePreparedBlock(job.world, change.positionAt(airCursor++), change.state(), null, job.replace)
                        == SchematicPlacingUtils.PlacementResult.CHANGED) job.blocks.incrementAndGet();
            }
            if (airCursor == change.length()) { airCursor = 0; cursor++; }
        }

        @SuppressWarnings("unchecked")
        private void apply(int index) {
            if (index < prepared.changes().size()) {
                var change = prepared.changes().get(index);
                int local = ((change.pos().getY() & 15) << 8) | ((change.pos().getZ() & 15) << 4) | (change.pos().getX() & 15);
                if (change.kind() == ServuxPastePlan.ChangeKind.ADD_WATER) {
                    BitSet admitted = job.fluidBases.get(prepared.section().key());
                    if (admitted != null && admitted.get(local)
                            && SchematicPlacingUtils.addPreparedWater(job.world, change.pos(), change.state())) job.blocks.incrementAndGet();
                } else {
                    var result = SchematicPlacingUtils.placePreparedBlock(job.world, change.pos(), change.state(), change.nbt(), job.replace);
                    if (result != SchematicPlacingUtils.PlacementResult.SKIPPED && change.kind() == ServuxPastePlan.ChangeKind.DRY_BASE)
                        job.fluidBases.computeIfAbsent(prepared.section().key(), ignored -> new BitSet(4096)).set(local);
                    if (result == SchematicPlacingUtils.PlacementResult.CHANGED) job.blocks.incrementAndGet();
                }
                return;
            }
            index -= prepared.changes().size();
            if (index < prepared.ticks().size()) {
                var saved = prepared.ticks().get(index);
                long delay = Math.max(0L, saved.tick().triggerTick());
                if (saved.block() && job.world.getBlockState(saved.pos()).getBlock() == saved.tick().type())
                    job.world.getBlockTicks().schedule(new ScheduledTick<>((Block) saved.tick().type(), saved.pos(), job.world.getRedstoneGameTime() + delay, saved.tick().priority(), saved.tick().subTickOrder()));
                else if (!saved.block() && job.world.getFluidState(saved.pos()).getType() == saved.tick().type())
                    job.world.getFluidTicks().schedule(new ScheduledTick<>((Fluid) saved.tick().type(), saved.pos(), job.world.getRedstoneGameTime() + delay, saved.tick().priority(), saved.tick().subTickOrder()));
                return;
            }
            var entity = prepared.entities().get(index - prepared.ticks().size());
            SchematicPlacingUtils.placeEntitiesToWorldWithinChunk(job.world, prepared.section().chunk(), List.of(entity.info()),
                    job.plan.placement.getOrigin(), job.plan.placement, entity.placement());
        }
    }
}
