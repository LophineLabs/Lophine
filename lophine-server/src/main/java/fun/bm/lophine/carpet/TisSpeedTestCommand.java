// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.tiscm.TISCMProtocol;
import fun.bm.lophine.protocol.tiscm.TISCMProtocol.S2CPacket;
import io.netty.util.AttributeKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TisSpeedTestCommand {
    public static final AttributeKey<Boolean> SKIP_COMPRESSION = AttributeKey.valueOf("lophine.tis.speedtest.skipCompression");
    private static final int SIZE_PER_PACKET = 16 * 1024;
    private static final byte[] BUFFER = new byte[SIZE_PER_PACKET - 60];
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger MAGIC_COUNTER = new AtomicInteger();

    static {
        new Random(42).nextBytes(BUFFER);
    }

    private TisSpeedTestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("speedtest")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandSpeedTest))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("download")
                        .executes(context -> transfer(context.getSource(), true, Math.min(10, GeneralCompatConfig.speedTestCommandMaxTestSize)))
                        .then(Commands.argument("size_mib", IntegerArgumentType.integer(1))
                                .executes(context -> transfer(context.getSource(), true, IntegerArgumentType.getInteger(context, "size_mib")))))
                .then(Commands.literal("upload")
                        .executes(context -> transfer(context.getSource(), false, Math.min(10, GeneralCompatConfig.speedTestCommandMaxTestSize)))
                        .then(Commands.argument("size_mib", IntegerArgumentType.integer(1))
                                .executes(context -> transfer(context.getSource(), false, IntegerArgumentType.getInteger(context, "size_mib")))))
                .then(Commands.literal("ping").executes(context -> ping(context.getSource(), 3, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(context -> ping(context.getSource(), IntegerArgumentType.getInteger(context, "count"), 1))
                                .then(Commands.argument("interval", DoubleArgumentType.doubleArg(0, 10))
                                        .executes(context -> ping(context.getSource(), IntegerArgumentType.getInteger(context, "count"),
                                                DoubleArgumentType.getDouble(context, "interval"))))))
                .then(Commands.literal("abort").executes(context -> abort(context.getSource()))));
    }

    private static int help(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        long supported = List.of(S2CPacket.SPEED_TEST_DOWNLOAD_PAYLOAD, S2CPacket.SPEED_TEST_UPLOAD_REQUEST,
                S2CPacket.SPEED_TEST_PING, S2CPacket.SPEED_TEST_ABORT).stream().filter(packet -> TISCMProtocol.supports(player, packet)).count();
        tell(source, "/speedtest download|upload [size_mib], ping [count] [interval_s], abort; maximum "
                + GeneralCompatConfig.speedTestCommandMaxTestSize + " MiB; client support: "
                + (supported == 4 ? "yes" : supported == 0 ? "no" : "partial"), false);
        return 0;
    }

    private static boolean check(CommandSourceStack source, ServerPlayer player, S2CPacket packet) {
        if (!GeneralCompatConfig.tiscmNetworkProtocol) {
            tell(source, "TISCM network protocol is disabled", false);
            return false;
        }
        if (!TISCMProtocol.supports(player, packet)) {
            tell(source, "Client does not support this TISCM speed test", false);
            return false;
        }
        return true;
    }

    private static int transfer(CommandSourceStack source, boolean download, int sizeMiB) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (sizeMiB < 1 || sizeMiB > GeneralCompatConfig.speedTestCommandMaxTestSize || (long) sizeMiB * 64L > Integer.MAX_VALUE) {
            tell(source, "Test size exceeds speedTestCommandMaxTestSize or packet count limit", false);
            return 0;
        }
        if (!check(source, player, download ? S2CPacket.SPEED_TEST_DOWNLOAD_PAYLOAD : S2CPacket.SPEED_TEST_UPLOAD_REQUEST))
            return 0;
        Transfer session = new Transfer(source, player, download, sizeMiB);
        if (!claim(session)) return 0;
        tell(source, "Starting " + session.kind() + " test: " + sizeMiB + " MiB", false);
        if (download) {
            for (int i = 0; i < 3; ++i) session.sendOne();
        } else if (!TISCMProtocol.send(player, S2CPacket.SPEED_TEST_UPLOAD_REQUEST, nbt -> nbt.putInt("size_mb", sizeMiB), null)) {
            session.abort("client support changed");
        }
        return 1;
    }

    private static int ping(CommandSourceStack source, int count, double interval) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!check(source, player, S2CPacket.SPEED_TEST_PING)) return 0;
        Pinger session = new Pinger(source, player, count, interval);
        if (!claim(session)) return 0;
        tell(source, "Starting ping test: " + count + " pings, interval " + Math.round(interval * 1000) + " ms", false);
        Thread.ofVirtual().name("TISCM-SpeedTestPinger").start(session::run);
        return 1;
    }

    private static boolean claim(Session session) {
        if (SESSIONS.putIfAbsent(session.id, session) == null) return true;
        tell(session.source, "A speed test is already running", false);
        return false;
    }

    private static int abort(CommandSourceStack source) throws CommandSyntaxException {
        Session session = SESSIONS.get(source.getPlayerOrException().getUUID());
        if (session == null) {
            tell(source, "No speed test is running", false);
            return 0;
        }
        session.abort("command");
        return 1;
    }

    public static void disconnected(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) session.abort("disconnected");
    }

    public static void reset() {
        for (Session session : List.copyOf(SESSIONS.values())) session.abort("server closed");
    }

    // Invoked on the network thread: these paths only touch atomic session data and immutable identity.
    public static void handleUpload(ServerPlayer player, CompoundTag payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session instanceof Transfer transfer && !transfer.download) {
            byte[] bytes = payload.getByteArray("buf").orElse(null);
            if (bytes == null || bytes.length != BUFFER.length) transfer.abort("invalid upload payload size");
            else transfer.completedOne();
        }
    }

    public static void handlePing(ServerPlayer player, CompoundTag payload) {
        String type = payload.getStringOr("type", "");
        if ("ping".equals(type)) {
            TISCMProtocol.send(player, S2CPacket.SPEED_TEST_PING, nbt -> {
                nbt.merge(payload);
                nbt.putString("type", "pong");
            });
        } else if ("pong".equals(type)) {
            Session session = SESSIONS.get(player.getUUID());
            if (session != null) session.pong(payload);
        }
    }

    public static boolean isDownloadPacket(Packet<?> packet) {
        return packet instanceof ClientboundCustomPayloadPacket custom
                && custom.payload() instanceof TISCMProtocol.TISCMPayload tis
                && "speed_test_download_payload".equals(tis.packetId());
    }

    private static void tell(CommandSourceStack source, String message, boolean actionBar) {
        ServerPlayer recipient = source.getPlayer();
        if (recipient == null) source.sendSuccess(() -> Component.literal(message), false);
        else recipient.getBukkitEntity().taskScheduler.schedule(entity -> ((ServerPlayer) entity)
                .sendSystemMessage(Component.literal(message), actionBar), null, 1L);
    }

    private record PendingPing(long sentAt, CompletableFuture<Long> result) {
    }

    private abstract static class Session {
        final CommandSourceStack source;
        final ServerPlayer player;
        final UUID id;
        final long started = System.nanoTime();
        final AtomicBoolean done = new AtomicBoolean();
        final Map<Long, PendingPing> pendingPings = new ConcurrentHashMap<>();
        final CompletableFuture<Void> aborted = new CompletableFuture<>();

        Session(CommandSourceStack source, ServerPlayer player) {
            this.source = source;
            this.player = player;
            this.id = player.getUUID();
        }

        abstract String kind();

        abstract void report(boolean cancelled, String reason);

        void finish(boolean cancelled, String reason) {
            if (!done.compareAndSet(false, true)) return;
            SESSIONS.remove(id, this);
            aborted.complete(null);
            for (PendingPing ping : pendingPings.values()) ping.result.complete(-1L);
            pendingPings.clear();
            report(cancelled, reason);
        }

        void abort(String reason) {
            if (done.get()) return;
            TISCMProtocol.send(player, S2CPacket.SPEED_TEST_ABORT, nbt -> {
            }, null);
            finish(true, reason);
        }

        CompletableFuture<Long> sendPing() {
            CompletableFuture<Long> result = new CompletableFuture<>();
            if (done.get()) {
                result.complete(-1L);
                return result;
            }
            long magic = Integer.toUnsignedLong(MAGIC_COUNTER.getAndIncrement()) | (long) ThreadLocalRandom.current().nextInt() << 32;
            long timestamp = System.nanoTime();
            pendingPings.put(magic, new PendingPing(timestamp, result));
            if (done.get() || !TISCMProtocol.send(player, S2CPacket.SPEED_TEST_PING, nbt -> {
                nbt.putString("type", "ping");
                nbt.putLong("magic", magic);
                nbt.putLong("timestamp", timestamp);
            }, null)) {
                pendingPings.remove(magic);
                result.complete(-1L);
                abort("client does not support ping acknowledgement");
            }
            return result;
        }

        void pong(CompoundTag payload) {
            PendingPing ping = pendingPings.remove(payload.getLongOr("magic", 0L));
            if (ping == null || payload.getLongOr("timestamp", Long.MIN_VALUE) != ping.sentAt) {
                abort("bad pong");
                return;
            }
            ping.result.complete(System.nanoTime() - ping.sentAt);
        }
    }

    private static final class Transfer extends Session {
        final boolean download;
        final int packetCount;
        final long totalSize;
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicLong lastProgress = new AtomicLong();

        Transfer(CommandSourceStack source, ServerPlayer player, boolean download, int sizeMiB) {
            super(source, player);
            this.download = download;
            this.packetCount = sizeMiB * 64;
            this.totalSize = (long) sizeMiB << 20;
        }

        @Override
        String kind() {
            return download ? "download" : "upload";
        }

        void sendOne() {
            if (done.get()) return;
            if (sent.getAndIncrement() >= packetCount) return;
            if (!TISCMProtocol.send(player, S2CPacket.SPEED_TEST_DOWNLOAD_PAYLOAD, nbt -> nbt.putByteArray("buf", BUFFER), future -> {
                if (future.isSuccess()) completedOne();
                else abort("network write failed");
            })) abort("client support changed");
        }

        void completedOne() {
            if (done.get()) return;
            int count = completed.incrementAndGet();
            long now = System.nanoTime();
            long previous = lastProgress.get();
            if (now - previous > TimeUnit.SECONDS.toNanos(1) && lastProgress.compareAndSet(previous, now)) {
                tell(source, String.format(Locale.ROOT, "%s %.1f%% %.2f MiB/s", kind(), 100.0 * count / packetCount,
                        (double) count * SIZE_PER_PACKET / (1 << 20) / Math.max(1e-9, (now - started) / 1e9)), true);
            }
            if (count >= packetCount) {
                finish(false, "done");
                return;
            }
            if (!download) return;
            if (count % 16 == 0 || count == packetCount - 1) {
                sendPing().thenAccept(cost -> {
                    if (cost >= 0 && !done.get()) sendOne();
                });
            } else sendOne();
        }

        @Override
        void report(boolean cancelled, String reason) {
            long bytes = Math.min(packetCount, completed.get()) * (long) SIZE_PER_PACKET;
            double seconds = Math.max(1e-9, (System.nanoTime() - started) / 1e9);
            tell(source, String.format(Locale.ROOT, "%s %s: %.2f/%.2f MiB in %.3f s, %.2f MiB/s (%.2f Mbps)%s",
                    kind(), cancelled ? "aborted" : "done", bytes / (double) (1 << 20), totalSize / (double) (1 << 20), seconds,
                    bytes / (double) (1 << 20) / seconds, bytes * 8e-6 / seconds, cancelled ? " - " + reason : ""), false);
        }
    }

    private static final class Pinger extends Session {
        final int count;
        final long intervalNs;
        int received;
        long totalPingNs;

        Pinger(CommandSourceStack source, ServerPlayer player, int count, double interval) {
            super(source, player);
            this.count = count;
            this.intervalNs = (long) (interval * 1e9);
        }

        @Override
        String kind() {
            return "ping";
        }

        void run() {
            try {
                for (int i = 1; i <= count && !done.get(); ++i) {
                    long cost = sendPing().get();
                    if (cost < 0 || done.get()) break;
                    synchronized (this) {
                        ++received;
                        totalPingNs += cost;
                    }
                    tell(source, String.format(Locale.ROOT, "Ping %d: %.1f ms", i, cost / 1e6), true);
                    long wait = i < count ? Math.max(0L, intervalNs - cost) : 0L;
                    if (wait > 0) {
                        try {
                            aborted.get(wait, TimeUnit.NANOSECONDS);
                            break;
                        } catch (TimeoutException elapsed) {
                        }
                    }
                }
                finish(false, "done");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                abort("interrupted");
            } catch (ExecutionException failure) {
                abort("ping failed");
            }
        }

        @Override
        synchronized void report(boolean cancelled, String reason) {
            tell(source, String.format(Locale.ROOT, "Ping %s: %d received, average %.2f ms%s", cancelled ? "aborted" : "done",
                    received, received == 0 ? -1.0 : totalPingNs / (double) received / 1e6, cancelled ? " - " + reason : ""), false);
        }
    }
}
