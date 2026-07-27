package io.anonymous.anonymous.utils;

import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Path;

public record RegionCreatorInfo(RegionStorageInfo info, Path filePath, Path folder, boolean sync) {
}