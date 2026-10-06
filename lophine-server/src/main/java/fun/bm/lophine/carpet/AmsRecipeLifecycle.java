// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet AMS Addition 750310179368b2569dd6121a2769b2fb1bbc7343 RecipeRuleHelper.
package fun.bm.lophine.carpet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The real resource reload finishes before reading and granting the resulting AMS recipes.
 */
public final class AmsRecipeLifecycle {
    private AmsRecipeLifecycle() {
    }

    public static CompletableFuture<Void> changed(MinecraftServer server) {
        if (server == null || !server.isRunning()) return CompletableFuture.completedFuture(null);
        return AmsNativeCommandEffects.nativeReceipt(server, () -> AmsNativeCommandEffects.then(
                AmsNativeCommandEffects.global(server, () -> server.reloadResources(List.copyOf(server.getPackRepository().getSelectedIds()))).thenCompose(value -> value),
                ignored -> AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(server, () -> new Grants(recipes(server), List.copyOf(server.getPlayerList().getPlayers()))), grants -> nextRecipe(server, grants.recipes().iterator(), grants.players()))));
    }

    public static CompletableFuture<Void> loggedIn(MinecraftServer server, ServerPlayer player) {
        if (server == null || !server.isRunning() || fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.recipeRuleValues().stream().noneMatch(Boolean::booleanValue))
            return CompletableFuture.completedFuture(null);
        return AmsNativeCommandEffects.nativeReceipt(server, () -> AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(server, () -> recipes(server)),
                all -> nextRecipe(server, all.iterator(), List.of(player))));
    }

    private record Grants(List<RecipeHolder<?>> recipes, List<ServerPlayer> players) {
    }

    private static List<RecipeHolder<?>> recipes(MinecraftServer server) {
        return server.getRecipeManager().recipes.values().stream().filter(recipe -> recipe.id().identifier().getNamespace().equals("carpetamsaddition")).toList();
    }

    private static CompletableFuture<Void> nextRecipe(MinecraftServer server, Iterator<RecipeHolder<?>> recipes, List<ServerPlayer> players) {
        return AmsNativeCommandEffects.sequence(server, recipes, recipe -> nextPlayer(server, players.iterator(), recipe));
    }

    private static CompletableFuture<Void> nextPlayer(MinecraftServer server, Iterator<ServerPlayer> players, RecipeHolder<?> recipe) {
        return AmsNativeCommandEffects.sequence(server, players, player ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(player, () -> player.getRecipeBook().contains(recipe.id())), known ->
                        known ? CompletableFuture.completedFuture(0) : AmsNativeCommandEffects.owned(player, () -> player.getRecipeBook().carpetAddRecipeNative(recipe, player)).thenCompose(value -> value)));
    }
}
