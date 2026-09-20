package io.anonymous.anonymous.utils;

import io.anonymous.anonymous.data.RegionFile;

import java.io.IOException;

@FunctionalInterface
public interface RegionFileFactory {
    RegionFile newFile(RegionCreatorInfo info) throws IOException;
}