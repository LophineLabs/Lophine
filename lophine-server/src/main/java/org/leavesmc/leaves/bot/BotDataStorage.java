/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.bot;

import com.mojang.logging.LogUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.util.TagUtil;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class BotDataStorage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final File botDir;
    private final File botListFile;

    private final CompoundTag savedBotList;

    public BotDataStorage(LevelStorageSource.@NotNull LevelStorageAccess session, String dataDir, String listFileName) {
        this.botDir = session.getLevelPath(new LevelResource(dataDir)).toFile();
        this.botListFile = session.getLevelPath(new LevelResource(listFileName)).toFile();
        this.botDir.mkdirs();

        this.savedBotList = new CompoundTag();
        if (this.botListFile.exists() && this.botListFile.isFile()) {
            try {
                Optional.of(NbtIo.readCompressed(this.botListFile.toPath(), NbtAccounter.unlimitedHeap())).ifPresent(tag -> {
                    for (Map.Entry<String, Tag> entry : tag.entrySet()) {
                        savedBotList.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                    }
                });
            } catch (Exception exception) {
                BotDataStorage.LOGGER.warn("Failed to load player data list");
            }
        }
    }

    public synchronized void save(Player player) {
        try {
            CompoundTag nbt = TagUtil.saveEntityWithoutId(player);
            File file = new File(this.botDir, player.getStringUUID() + ".dat");

            writeAtomic(nbt, file.toPath());
        } catch (Exception exception) {
            BotDataStorage.LOGGER.warn("Failed to save fakeplayer data for {}", player.getScoreboardName(), exception);
            return;
        }

        if (player instanceof ServerBot bot) {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("name", bot.createState.fullName());
            nbt.store("uuid", UUIDUtil.CODEC, bot.getUUID());
            nbt.putBoolean("resume", bot.resume);
            this.savedBotList.put(bot.createState.fullName().toLowerCase(Locale.ROOT), nbt);
            this.saveBotList();
        }
    }

    public synchronized Optional<ValueInput> load(@NotNull ServerBot bot, ProblemReporter reporter) {
        return this.load(bot.nameAndId().name(), bot.nameAndId().id().toString()).map(nbt -> {
            ValueInput valueInput = TagValueInput.create(reporter, bot.registryAccess(), nbt);
            bot.load(valueInput);
            return valueInput;
        });
    }

    public synchronized void removeSavedData(String name) {
        this.savedBotList.remove(name.toLowerCase(Locale.ROOT));
        this.saveBotList();
    }

    private Optional<CompoundTag> load(String name, String uuid) {
        File file = new File(this.botDir, uuid + ".dat");
        if (!file.exists() || !file.isFile()) {
            LOGGER.warn("Failed to load bot {}, the file {} DOES NOT EXIST!", name, file);
            return Optional.empty();
        }
        try {
            Optional<CompoundTag> optional = Optional.of(NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()));
            if (!file.delete()) {
                throw new IOException("Failed to delete fakeplayer data");
            }
            this.removeSavedData(name);
            return optional;
        } catch (Exception exception) {
            BotDataStorage.LOGGER.warn("Failed to load fakeplayer data for {}", name);
        }
        return Optional.empty();
    }

    public java.nio.file.Path statePath(UUID uuid) {
        return new File(this.botDir, uuid + ".dat").toPath();
    }

    public synchronized Optional<CompoundTag> read(String uuid) {
        File file = new File(this.botDir, uuid + ".dat");
        if (file.exists() && file.isFile()) {
            try {
                return Optional.of(NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()));
            } catch (Exception exception) {
                BotDataStorage.LOGGER.warn("Failed to read fakeplayer data for {}", uuid);
            }
        }
        return Optional.empty();
    }

    private void saveBotList() {
        try {
            writeAtomic(this.savedBotList, this.botListFile.toPath());
        } catch (Exception exception) {
            BotDataStorage.LOGGER.warn("Failed to save player data list");
        }
    }

    private static void writeAtomic(CompoundTag nbt, java.nio.file.Path target) throws IOException {
        java.nio.file.Path staging = java.nio.file.Files.createTempFile(target.getParent(), "carpet-bot-", ".dat.tmp");
        try {
            NbtIo.writeCompressed(nbt, staging);
            try (var channel = java.nio.channels.FileChannel.open(staging, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                java.nio.file.Files.move(staging, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                java.nio.file.Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(staging);
        }
    }

    public synchronized Optional<CompoundTag> readCarpetSavedState(ServerBot bot) {
        try {
            CompoundTag savedIndex = NbtIo.readCompressed(this.botListFile.toPath(), NbtAccounter.unlimitedHeap());
            CompoundTag entry = savedIndex.getCompoundOrEmpty(bot.createState.fullName().toLowerCase(Locale.ROOT));
            if (!entry.read("uuid", UUIDUtil.CODEC).filter(bot.getUUID()::equals).isPresent()) return Optional.empty();
            return this.read(bot.getStringUUID());
        } catch (IOException exception) {
            LOGGER.warn("Failed to verify fakeplayer index for {}", bot.getScoreboardName(), exception);
            return Optional.empty();
        }
    }

    public synchronized CompoundTag getSavedBotList() {
        return savedBotList.copy();
    }

    public synchronized UUID getUUIDFromLower(String lowerName) {
        return savedBotList.getCompoundOrEmpty(lowerName).read("uuid", UUIDUtil.CODEC).orElseThrow();
    }

    public synchronized String getNameFromLower(String lowerName) {
        return savedBotList.getCompoundOrEmpty(lowerName).getString("name").orElseThrow();
    }
}
