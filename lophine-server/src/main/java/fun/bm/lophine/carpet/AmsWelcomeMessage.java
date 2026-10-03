// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet AMS Addition welcome messages, revision 750310179368b2569dd6121a2769b2fb1bbc7343.
package fun.bm.lophine.carpet;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import carpet.script.external.ScarpetNativeWork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

public final class AmsWelcomeMessage {
    private static final Object FILE_LOCK = new Object();

    private AmsWelcomeMessage() {
    }

    public static void send(final ServerPlayer player) {
        ScarpetNativeWork.record(sendAsync(player));
    }

    private record Admission(net.minecraft.server.MinecraftServer server, Path file, String instruction, String savePath) {}

    public static CompletableFuture<Void> sendAsync(final ServerPlayer player) {
        return AmsNativeCommandEffects.nativeReceipt(player.carpetSpawnServer(),() -> AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(player, () -> {
            if (!GeneralCompatConfig.welcomeMessage) return null;
            var server=player.level().getServer();
            var path=server.getWorldPath(LevelResource.ROOT).resolve("carpetamsaddition/welcomeMessage.json");
            return new Admission(server,path,serverText("modify_content_in"),serverText("save_path"));
        }), admission -> admission==null ? CompletableFuture.completedFuture(null)
            : AmsNativeCommandEffects.then(read(admission), lines -> sendNext(player,lines.iterator()))));
    }

    private static String serverText(String key) {
        return AmsTranslations.translateText(Component.translatable("carpetamsaddition.rule.welcomeMessage."+key),AmsTranslations.serverLanguage()).getString();
    }

    private static CompletableFuture<List<String>> read(Admission admission) {
        var actual=new CompletableFuture<List<String>>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        ScarpetNativeWork.record(actual);ScarpetNativeWork.trackNative(admission.server(),actual);
        try {
            CompletableFuture.runAsync(() -> {
                try { actual.complete(load(admission)); } catch (Throwable failure) { actual.completeExceptionally(failure); }
            });
        } catch (Throwable failure) { actual.completeExceptionally(failure); }
        return actual;
    }

    private static CompletableFuture<Void> sendNext(ServerPlayer player,java.util.Iterator<String> lines) {
        if (!lines.hasNext()) return CompletableFuture.completedFuture(null);
        String line=lines.next();
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(player,() -> {
            player.sendSystemMessage(Component.literal(line));return (Void)null;
        }), ignored -> sendNext(player,lines));
    }

    private static List<String> load(final Admission admission) {
        final Path file=admission.file();
        synchronized (FILE_LOCK) {
            final ArrayList<String> lines = new ArrayList<>();
            try {
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    final JsonArray defaults = new JsonArray();
                    defaults.add("§3§o " + admission.instruction());
                    defaults.add("§a" + admission.savePath() + "/carpetamsaddition/welcomeMessage.json");
                    final JsonObject object = new JsonObject();
                    object.add("welcomeMessage", defaults);
                    Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(object), StandardCharsets.UTF_8);
                }
                final JsonElement configured = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().get("welcomeMessage");
                if (configured.isJsonArray()) {
                    for (JsonElement line : configured.getAsJsonArray()) {
                        lines.add(line.getAsString());
                    }
                } else {
                    lines.addAll(List.of(configured.getAsString().split("\n")));
                }
                return List.copyOf(lines);
            } catch (Exception exception) {
                LogUtils.getLogger().error("Failed to load AMS welcome message configuration {}", file, exception);
                return List.copyOf(lines);
            }
        }
    }
}
