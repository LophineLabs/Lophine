package me.earthme.luminol.utils;

import net.openhft.affinity.Affinity;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.BitSet;

public record AffinityRunnableWrapper(String name, BitSet affinity) {

    @Contract(pure = true)
    public @NonNull Runnable wrap(Runnable original) {
        return () -> {
            Affinity.setAffinity(this.affinity);

            original.run();
        };
    }

    @Override
    @NotNull
    public String name() {
        return this.name;
    }

    @Override
    @NotNull
    public BitSet affinity() {
        return this.affinity;
    }
}
