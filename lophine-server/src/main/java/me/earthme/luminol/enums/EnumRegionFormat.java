package me.earthme.luminol.enums;

import abomination.LinearRegionFile;
import me.earthme.luminol.config.modules.function.RegionFormatConfig;
import me.earthme.luminol.data.BufferedLinearRegionFile;
import me.earthme.luminol.utils.IRegionCreateFunction;
import net.minecraft.world.level.chunk.storage.RegionFile;

public enum EnumRegionFormat {
    MCA("mca", (info) -> new RegionFile(info.info(), info.filePath(), info.folder(), info.sync())),
    LINEAR_V2("linear", (info) -> new LinearRegionFile(info.info(), info.filePath(), info.folder(), info.sync(), RegionFormatConfig.linearCompressionLevel)),
    B_LINEAR("b_linear", (info) -> new BufferedLinearRegionFile(info.filePath(), RegionFormatConfig.linearCompressionLevel, RegionFormatConfig.blinearFlusher));

    private final String argument;
    private final IRegionCreateFunction creator;

    EnumRegionFormat(String argument, IRegionCreateFunction creator) {
        this.argument = argument;
        this.creator = creator;
    }

    public IRegionCreateFunction getCreator() {
        return this.creator;
    }

    public String getArgument() {
        return this.argument;
    }
}