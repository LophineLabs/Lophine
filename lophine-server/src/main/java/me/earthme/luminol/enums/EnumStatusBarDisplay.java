package me.earthme.luminol.enums;

import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;

public enum EnumStatusBarDisplay {
    BOSS_BAR,
    ACTION_BAR,
    TAB_LIST;

    @Contract(pure = true)
    public static @Nullable EnumStatusBarDisplay fromOrdinal(int ordinal) {
        EnumStatusBarDisplay[] values = values();

        if (ordinal < 0 || ordinal >= values.length) {
            return null;
        }

        return values[ordinal];
    }
}
