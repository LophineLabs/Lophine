package me.earthme.luminol.commands;

import fun.bm.lophine.command.tps.TpsCommand;
import me.earthme.luminol.commands.bar.BarCommand;

public class CommandRegister {
    /**
     * Register commands after the configuration has been loaded.
     * This method is called once the configuration and the Paper built-in commands are fully
     * loaded, so it can register commands that depend on the complete configuration, or that
     * must be registered after all other built-in commands have been registered.
     */
    public static void register() {
        new BarCommand().register();
        new TpsCommand().register();
    }
}
