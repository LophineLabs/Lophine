// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.leavesmc.leaves.util.UpdateSuppressionException;

public final class AmsUpdateSuppressor {
    private static volatile boolean forceMode;
    private static volatile boolean unreadableFile;
    private static volatile MinecraftServer loadedServer;
    private static final Suppression FAILURE = new Suppression();

    private static final class Suppression extends RuntimeException {
        private Suppression() { super("AMS Throwable Suppression"); }
    }
    private AmsUpdateSuppressor() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("amsUpdateSuppressionCrashFixForceMode")
            .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
            .then(Commands.argument("mode",BoolArgumentType.bool()).executes(context->AmsNativeCommandEffects.command(context,ignored->{
                forceMode=BoolArgumentType.getBool(context,"mode");boolean captured=forceMode;var source=context.getSource();
                AmsNativeCommandEffects.reply(source,()->source.sendSuccess(()->AmsTranslations.message(source,"command.amsUpdateSuppressionCrashFixForceMode."+(captured?"force_mode":"lazy_mode")),false));
                AmsNativeCommandEffects.effect(()->saveForceMode(source.getServer()));return 1;
            }))));
    }
    public static java.util.concurrent.CompletableFuture<Void> saveForceMode(MinecraftServer server){
        if(unreadableFile){var failed=new java.util.concurrent.CompletableFuture<Void>(){@Override public boolean cancel(boolean interrupt){return false;}};carpet.script.external.ScarpetNativeWork.record(failed);carpet.script.external.ScarpetNativeWork.trackNative(server,failed);failed.completeExceptionally(new IllegalStateException("Original AMS suppression force file is unreadable"));return failed;}
        var json=new com.google.gson.JsonObject();json.addProperty("amsUpdateSuppressionCrashFixForceMode",forceMode);
        return AmsManagementSettings.save(server,"amsUpdateSuppressionCrashFixForceMode",json);
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("carpetamsaddition/amsUpdateSuppressionCrashFixForceMode.json");
    }

    public static void load(MinecraftServer server) {
        if (loadedServer == server) return;
        loadedServer = server;
        forceMode = false;unreadableFile=false;
            Path file = file(server);
            if (!Files.isRegularFile(file)) return;
            try {
                var object = com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (object.has("amsUpdateSuppressionCrashFixForceMode")) forceMode = object.get("amsUpdateSuppressionCrashFixForceMode").getAsBoolean();
            } catch (IOException | RuntimeException failure) {
                unreadableFile=true;
                org.slf4j.LoggerFactory.getLogger("Carpet AMS").error("Cannot load suppression force mode", failure);
            }
    }

    public static void neighborChanged(BlockState state, Level level, BlockPos pos, Block source) {
        String selected = GeneralCompatConfig.customBlockUpdateSuppressor;
        if (selected.equals("none")) return;
        if (forceMode) GeneralCompatConfig.amsUpdateSuppressionCrashFix = "true";
        if (!selected.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) return;
        if (GeneralCompatConfig.mergedUpdateSuppressionCrashEnabled()) throw new UpdateSuppressionException(pos, level, source, null, FAILURE);
        throw FAILURE;
    }
}
