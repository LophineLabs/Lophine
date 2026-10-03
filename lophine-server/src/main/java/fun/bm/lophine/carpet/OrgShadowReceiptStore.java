package fun.bm.lophine.carpet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One live group owns one file writer; pending real IO pins that group explicitly.
 */
final class OrgShadowReceiptStore {
    final Path file;
    final Path escrow;
    final UUID group;
    private final ReentrantLock writer = new ReentrantLock();

    OrgShadowReceiptStore(Path world, UUID group) {
        this.group = group;
        this.file = world.toAbsolutePath().normalize().resolve("carpet-org-item-shadow-receipts").resolve(group + ".nbt");
        this.escrow = world.toAbsolutePath().normalize().resolve("carpet-org-inventory-escrow");
    }

    private void checkPath() throws IOException {
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent()) || Files.isSymbolicLink(escrow))
            throw new IOException("Shadow receipt path is a symbolic link");
    }

    CompoundTag read() throws IOException {
        checkPath();
        try {
            CompoundTag record = NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024L * 1024L));
            if (record.getIntOr("format", -1) != 1 || !record.getStringOr("group", "").equals(group.toString()))
                throw new IOException("Malformed shadow receipt identity");
            CompoundTag state = record.getCompound("state").orElseThrow(() -> new IOException("Shadow receipt lacks canonical state"));
            if (!state.getStringOr("id", "").equals(group.toString()) || state.getLongOr("revision", -1) < 0)
                throw new IOException("Malformed canonical shadow revision/identity");
            return state;
        } catch (java.nio.file.NoSuchFileException missing) {
            return null;
        }
    }

    boolean pending(UUID transaction) throws IOException {
        Path ledger = escrow.resolve(transaction + ".nbt");
        try {
            return Files.readAttributes(ledger, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).isRegularFile();
        } catch (java.nio.file.NoSuchFileException missing) {
            return false;
        }
    }

    void write(CompoundTag state) throws IOException {
        if (!writer.tryLock()) throw new IOException("Another real shadow receipt write is pending");
        Path staging = null;
        try {
            checkPath();
            CompoundTag previous = read();
            if (state.equals(previous)) return;
            if (previous != null) {
                long old = previous.getLongOr("revision", -1), next = state.getLongOr("revision", -1);
                if (old > next)
                    throw new IOException("An older snapshot cannot replace a newer durable shadow revision");
                if (old == next) for (String field : java.util.List.of("item", "count", "patch"))
                    if (!Objects.equals(previous.get(field), state.get(field)))
                        throw new IOException("Conflicting durable shadow states at one revision");
            }
            CompoundTag record = new CompoundTag();
            record.putInt("format", 1);
            record.putString("group", group.toString());
            record.put("state", state.copy());
            Files.createDirectories(file.getParent());
            staging = Files.createTempFile(file.getParent(), "shadow-", ".tmp");
            NbtIo.writeCompressed(record, staging);
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!state.equals(read())) throw new IOException("Shadow canonical receipt readback did not match");
        } finally {
            if (staging != null) try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
            }
            writer.unlock();
        }
    }

    void retire() {
        if (!writer.tryLock()) return;
        try {
            checkPath();
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("Unused shadow receipt could not be retired: {}", file, failure);
        } finally {
            writer.unlock();
        }
    }
}
