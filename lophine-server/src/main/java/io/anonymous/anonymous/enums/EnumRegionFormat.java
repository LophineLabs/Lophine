package io.anonymous.anonymous.enums;

import abomination.LinearRegionFile;
import me.earthme.luminol.config.modules.function.RegionFormatConfig;
import io.anonymous.anonymous.data.BufferedLinearRegionFile;
import io.anonymous.anonymous.utils.RegionFileFactory;
import net.minecraft.world.level.chunk.storage.RegionFile;

public enum EnumRegionFormat {
    MCA("mca", (info) -> new RegionFile(info.info(), info.filePath(), info.folder(), info.sync())),
    LINEAR_V2("linear", (info) -> new LinearRegionFile(info.info(), info.filePath(), info.folder(), info.sync(), RegionFormatConfig.linearCompressionLevel)),
    B_LINEAR("b_linear", (info) -> new BufferedLinearRegionFile(info.filePath(), RegionFormatConfig.linearCompressionLevel, RegionFormatConfig.blinearFlusher));

    private final String argument;
    private final RegionFileFactory creator;

    EnumRegionFormat(String argument, RegionFileFactory creator) {
        this.argument = argument;
        this.creator = creator;
    }

    public RegionFileFactory getCreator() {
        return this.creator;
    }

    public String getArgument() {
        return this.argument;
    }
}