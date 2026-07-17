/**
 * This file is a part of Leaf(https://github.com/Winds-Studio/Leaf/blob/ver/1.21.8/leaf-server/src/main/java/org/dreeam/leaf/world/biome/PositionalBiomeGetter.java)
 * Original license: https://github.com/Winds-Studio/Leaf/blob/ver/1.21.8/LICENSE.md
 */
package org.dreeam.leaf.world.biome;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.function.Function;
import java.util.function.Supplier;

public class PositionalBiomeGetter implements Supplier<Holder<Biome>> {

    private final Function<BlockPos, Holder<Biome>> biomeGetter;
    private final BlockPos.MutableBlockPos pos;
    private int nextX, nextY, nextZ;
    private volatile Holder<Biome> curBiome;

    public PositionalBiomeGetter(Function<BlockPos, Holder<Biome>> biomeGetter, BlockPos.MutableBlockPos pos) {
        this.biomeGetter = biomeGetter;
        this.pos = pos;
    }

    public void update(int nextX, int nextY, int nextZ) {
        this.nextX = nextX;
        this.nextY = nextY;
        this.nextZ = nextZ;
        this.curBiome = null;
    }

    @Override
    public Holder<Biome> get() {
        Holder<Biome> biome = curBiome;
        if (biome == null) {
            curBiome = biome = biomeGetter.apply(pos.set(nextX, nextY, nextZ));
        }
        return biome;
    }
}