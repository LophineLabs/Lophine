package me.earthme.luminol.utils;

import net.openhft.affinity.Affinity;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

public class AffinityRunnableWrapper {
    private final BitSet affinity;
    private final String name;

    public AffinityRunnableWrapper(String name, BitSet affinity) {
        this.name = name;
        this.affinity = affinity;
    }

    public Runnable wrap(Runnable original) {
        return () -> {
            Affinity.setAffinity(this.affinity);

            original.run();
        };
    }

    @NotNull
    public String getName() {
        return this.name;
    }

    @NotNull
    public BitSet getAffinity() {
        return this.affinity;
    }
}
