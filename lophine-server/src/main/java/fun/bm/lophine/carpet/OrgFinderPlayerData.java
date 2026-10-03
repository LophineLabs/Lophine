// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1 (26.3-snapshot-9 -> 26.3).
package fun.bm.lophine.carpet;

import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.storage.FileNameDateFormatter;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Finder alone owns backup/upgrade/save; ordinary offline snapshots remain readonly.
 */
final class OrgFinderPlayerData {
    private static final Map<MinecraftServer, Flags> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Flags {
        final Set<UUID> corrupted = ConcurrentHashMap.newKeySet(), backed = ConcurrentHashMap.newKeySet(), invalid = ConcurrentHashMap.newKeySet();
    }

    private static Flags flags(MinecraftServer server) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(server, ignored -> new Flags());
        }
    }

    static void close(MinecraftServer server) {
        STATES.remove(server);
    }

    static boolean corrupted(MinecraftServer server, UUID uuid) {
        return flags(server).corrupted.contains(uuid);
    }

    static boolean backed(MinecraftServer server, UUID uuid) {
        return flags(server).backed.contains(uuid);
    }

    static boolean invalid(MinecraftServer server, UUID uuid) {
        return flags(server).invalid.contains(uuid);
    }

    private static void markCorrupted(MinecraftServer server, UUID uuid) {
        if (flags(server).corrupted.add(uuid))
            MinecraftServer.LOGGER.warn("Unable to read player data from file for UUID {}", uuid);
    }

    static Reader reader(MinecraftServer server, BooleanSupplier cancelled) {
        return new Reader(server, cancelled, DEFAULT_IO);
    }

    interface FileIo {
        CompoundTag read(Path file) throws IOException;

        void copy(Path source, Path destination) throws IOException;
    }

    static final FileIo DEFAULT_IO = new FileIo() {
        public CompoundTag read(Path file) throws IOException {
            return NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024L * 1024L));
        }

        public void copy(Path source, Path destination) throws IOException {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    };

    static final class Reader {
        final MinecraftServer server;
        final BooleanSupplier cancelled;
        final FileIo io;
        final Flags flags;
        final Path directory, backups;
        private Path dated;

        Reader(MinecraftServer server, BooleanSupplier cancelled, FileIo io) {
            this.server = server;
            this.cancelled = cancelled;
            this.io = io;
            this.flags = flags(server);
            directory = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();
            backups = OrgWorldFormat.directory(server).resolve("backups/playerdata");
        }

        CompletableFuture<OrgOfflinePlayerSnapshots.Snapshot> readForFinder(UUID uuid) {
            return OrgMenuNativeEffects.admit(server, () -> {
                checkCancelled();
                if (flags.invalid.contains(uuid)) return CompletableFuture.completedFuture(null);
                var work = CompletableFuture.allOf(OrgInventoryTransfers.whenAvailable(server, uuid), OrgExperienceTransfers.whenAvailable(server, uuid))
                        .thenCompose(ignored -> OrgPlayerFileLease.withLease(server, uuid, "Finder native player-data backup/upgrade", lease -> {
                            checkCancelled();
                            return OrgInventoryTransfers.withNativePlayerData(server, uuid, custody -> CompletableFuture.supplyAsync(() -> {
                                checkCancelled();
                                if (online(uuid)) return null;
                                Path file = file(uuid);
                                try {
                                    return io.read(file);
                                } catch (IOException failure) {
                                    throw new CompletionException(failure);
                                }
                            }).thenCompose(raw -> {
                                if (raw == null) return CompletableFuture.completedFuture(null);
                                checkCancelled();
                                if (flags.corrupted.contains(uuid) || NbtUtils.getDataVersion(raw, -1) < currentVersion())
                                    return repair(uuid, raw, custody);
                                return decode(uuid, raw, true);
                            }));
                        }));
                return work.handle((snapshot, failure) -> {
                    if (failure == null) return snapshot;
                    Throwable cause = unwrap(failure);
                    if (!(cause instanceof CancellationException)) {
                        markCorrupted(server, uuid);
                        MinecraftServer.LOGGER.error("Unable to read player data from file for file {}", uuid + ".dat", cause);
                    }
                    throw new CompletionException(cause);
                });
            });
        }

        private CompletableFuture<OrgOfflinePlayerSnapshots.Snapshot> repair(UUID uuid, CompoundTag raw, OrgInventoryTransfers.NativePlayerData custody) {
            if (online(uuid)) return CompletableFuture.completedFuture(null);
            Path file = file(uuid);
            try {
                checkCancelled();
                backup(file, uuid);
            } catch (CancellationException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                flags.invalid.add(uuid);
                MinecraftServer.LOGGER.warn("Player data has expired: {}", uuid, failure);
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.supplyAsync(() -> {
                checkCancelled();
                if (online(uuid)) throw new IllegalStateException("Finder cannot upgrade an online owner");
                CompoundTag desired = normalize(uuid, raw, false);
                NbtUtils.addCurrentDataVersion(desired);
                return desired;
            }).thenCompose(desired -> OrgOfflinePlayerSnapshots.parse(server, uuid, desired).thenCompose(validated -> {
                checkCancelled();
                try {
                    // Vanilla player save retains its immediate .dat_old image as well as the dated backup.
                    Path old = file.resolveSibling(uuid + ".dat_old");
                    safe(old);
                    io.copy(file, old);
                    if (Files.mismatch(file, old) != -1) throw new IOException("Native .dat_old backup did not match");
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
                String relative = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().relativize(file).toString();
                return custody.replace(relative, raw, desired).thenCompose(ignored -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return io.read(file);
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                })).thenCompose(saved -> {
                    checkCancelled();
                    return decode(uuid, saved, true);
                });
            }));
        }

        private CompletableFuture<OrgOfflinePlayerSnapshots.Snapshot> decode(UUID uuid, CompoundTag raw, boolean reportErrors) {
            return CompletableFuture.supplyAsync(() -> {
                        checkCancelled();
                        return normalize(uuid, raw, reportErrors);
                    })
                    .thenCompose(data -> OrgOfflinePlayerSnapshots.parse(server, uuid, data)).thenApply(snapshot -> {
                        checkCancelled();
                        if (online(uuid)) throw new IllegalStateException("Finder file owner became online");
                        return snapshot;
                    });
        }

        /**
         * The source's native player/container load accepts codec partials and last duplicate slot wins.
         * Private native pointer/custody data instead keeps strict readonly decoding and unchanged assets.
         */
        private CompoundTag normalize(UUID uuid, CompoundTag raw, boolean reportErrors) {
            CompoundTag data = ca.spottedleaf.dataconverter.minecraft.MCDataConverter.convertTag(ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry.PLAYER, net.minecraft.util.datafix.DataFixTypes.PLAYER, raw.copy(), NbtUtils.getDataVersion(raw), ca.spottedleaf.dataconverter.minecraft.util.Version.getCurrentVersion());
            if (data.contains("CarpetOrgEscrowShadows") || data.contains("CarpetOrgEscrowCursor") || data.contains(OrgOfflineInventorySessions.CUSTODY))
                return data;
            var values = data.getCompoundOrEmpty("BukkitValues");
            if (values.contains("lophine:carpet_org_inventory_hold") || values.contains("lophine:carpet_org_xp_credit_escrow"))
                return data;
            var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            slots(uuid, data, "Inventory", Inventory.INVENTORY_SIZE, ops, reportErrors);
            int enderSize = 9 * fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.effectiveEnderChestRows();
            slots(uuid, data, "EnderItems", Math.max(27, enderSize), ops, reportErrors);
            if (data.contains("equipment")) {
                EntityEquipment equipment = EntityEquipment.CODEC.parse(ops, data.get("equipment")).resultOrPartial(error -> {
                    if (reportErrors) markCorrupted(server, uuid);
                }).orElseGet(EntityEquipment::new);
                data.put("equipment", EntityEquipment.CODEC.encodeStart(ops, equipment).getOrThrow());
            }
            return data;
        }

        private void slots(UUID uuid, CompoundTag data, String key, int size, DynamicOps<Tag> ops, boolean reportErrors) {
            var values = new TreeMap<Integer, ItemStackWithSlot>();
            for (Tag tag : data.getListOrEmpty(key))
                ItemStackWithSlot.CODEC.parse(ops, tag).resultOrPartial(error -> {
                    if (reportErrors) markCorrupted(server, uuid);
                }).ifPresent(slot -> {
                    if (slot.isValidInContainer(size)) values.put(slot.slot(), slot);
                });
            if (data.contains(key))
                data.put(key, ItemStackWithSlot.CODEC.listOf().encodeStart(ops, new ArrayList<>(values.values())).getOrThrow());
        }

        private void backup(Path file, UUID uuid) {
            try {
                safe(backups);
                Files.createDirectories(backups);
                if (flags.backed.contains(uuid))
                    throw new IllegalStateException("Player data already required repair in this session");
                List<Path> directories;
                try (var stream = Files.list(backups)) {
                    directories = stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).filter(path -> validVersionDirectory(path.getFileName().toString())).sorted().toList();
                }
                if (!directories.isEmpty()) {
                    Path previous = directories.getLast().resolve(file.getFileName());
                    if (Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS) && Files.mismatch(previous, file) == -1) {
                        flags.backed.add(uuid);
                        throw new IllegalStateException("Identical player data already exists in the latest backup");
                    }
                }
                if (dated == null)
                    dated = backups.resolve(LocalDateTime.now().format(FileNameDateFormatter.FORMATTER) + "_" + currentVersion());
                safe(dated);
                Files.createDirectories(dated);
                Path copy = dated.resolve(file.getFileName());
                safe(copy);
                io.copy(file, copy);
                // Upstream IOUtils.copyFile swallowed IOException; the adapter verifies bytes before mutation.
                if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(file, copy) != -1)
                    throw new IOException("Finder backup bytes did not match");
                flags.backed.add(uuid);
                flags.corrupted.remove(uuid);
            } catch (IOException failure) {
                throw new IllegalStateException("Finder native player-data backup failed", failure);
            }
        }

        private Path file(UUID uuid) {
            Path file = directory.resolve(uuid + ".dat");
            safe(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("Native player-data file is unavailable");
            return file;
        }

        private void safe(Path target) {
            Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(), path = target.toAbsolutePath().normalize();
            if (!path.startsWith(root) || path.equals(root))
                throw new IllegalArgumentException("Finder data path leaves its world");
            for (Path current = path; current != null && !current.equals(root); current = current.getParent())
                if (Files.isSymbolicLink(current))
                    throw new IllegalArgumentException("Finder data path is a symbolic link");
        }

        private boolean online(UUID uuid) {
            return server.getPlayerList().getPlayer(uuid) != null || server.getBotList() != null && server.getBotList().getBot(uuid) != null;
        }

        private void checkCancelled() {
            if (cancelled.getAsBoolean() || carpet.script.external.ScarpetNativeWork.isDraining(server))
                throw new CancellationException("Finder cancelled");
        }
    }

    static int currentVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private static boolean validVersionDirectory(String name) {
        String suffix = "_" + currentVersion();
        if (!name.endsWith(suffix)) return false;
        try {
            FileNameDateFormatter.FORMATTER.parse(name.substring(0, name.length() - suffix.length()));
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }

    static void start(MinecraftServer server) {
        OrgCommandNativeEffects.file(server, () -> {
            try {
                cleanExpiredBackups(OrgWorldFormat.directory(server).resolve("backups/playerdata"), LocalDateTime.now());
            } catch (IOException failure) {
                MinecraftServer.LOGGER.warn("Cannot clean Org Finder player-data backups", failure);
            }
            return null;
        });
    }

    static void cleanExpiredBackups(Path backups, LocalDateTime now) throws IOException {
        if (Files.isSymbolicLink(backups)) throw new IOException("Finder backups are a symbolic link");
        Files.createDirectories(backups);
        List<Path> directories;
        try (var stream = Files.list(backups)) {
            directories = stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList();
        }
        for (Path directory : directories) {
            try {
                String[] parts = directory.getFileName().toString().split("_");
                if (parts.length != 3) continue;
                LocalDateTime date = LocalDateTime.from(FileNameDateFormatter.FORMATTER.parse(parts[0] + "_" + parts[1]));
                if (ChronoUnit.DAYS.between(date, now) <= 30) continue;
                try (var stream = Files.list(directory)) {
                    for (Path file : stream.toList()) {
                        String[] names = file.getFileName().toString().split("\\.");
                        if (names.length != 2 || !names[1].equals("dat")) continue;
                        try {
                            UUID.fromString(names[0]);
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }
                        // Only direct UUID.dat leaves are eligible. Preserve subdirectories and symbolic links.
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(directory);
            } catch (RuntimeException | IOException failure) {
                MinecraftServer.LOGGER.warn("Error processing Finder backup file: {}", directory.getFileName(), failure);
            }
        }
    }
}
