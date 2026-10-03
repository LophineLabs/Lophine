// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

/** Native attribution reads are captured on actual entity owners and restored for the explosion replay. */
public final class ScarpetAttribution {
    public record Data(ServerLevel world, BlockPos position, Vec3 exactPosition, Component displayName, ItemStack mainHand,
                       ItemStack weapon, boolean inWater, boolean silent, boolean creative, float luck, net.minecraft.world.scores.Team nativeTeam, boolean friendlyFire,
                       Set<String> bukkitTeamEntries, boolean bukkitFriendlyFire, String scoreboardName) {}
    public static final class Token {
        private final Map<Entity, Data> entities;
        private final Explosion resolvedExplosion;
        private final LivingEntity explosionCausing;
        private Token(Map<Entity, Data> entities) { this(entities, null, null); }
        private Token(Map<Entity, Data> entities, Explosion resolvedExplosion, LivingEntity causing) {
            this.entities = Collections.unmodifiableMap(new IdentityHashMap<>(entities));
            this.resolvedExplosion = resolvedExplosion; this.explosionCausing = causing;
        }
        public Data get(Entity entity) { return entities.get(entity); }
        public Collection<Data> values() { return entities.values(); }
    }
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private ScarpetAttribution() {}
    public static Token capture() { return CURRENT.get(); }
    public static <T> T with(Token token, Supplier<T> operation) {
        Token previous = CURRENT.get(); if (token == null) CURRENT.remove(); else CURRENT.set(token);
        try { return operation.get(); } finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static void with(Token token, Runnable operation) { with(token, () -> { operation.run(); return null; }); }
    public static CompletableFuture<Token> snapshot(Collection<? extends Entity> entities) { return snapshot(entities, false); }
    private static CompletableFuture<Token> snapshot(Collection<? extends Entity> entities, boolean teams) {
        Map<Entity, Data> captured = Collections.synchronizedMap(new IdentityHashMap<>());
        List<CompletableFuture<?>> pending = new ArrayList<>();
        Set<Entity> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entity entity : entities) if (entity != null && unique.add(entity)) pending.add(ScarpetExplosionActors.entity(entity, () -> {
            ItemStack main = entity instanceof LivingEntity living ? living.getMainHandItem().copy() : ItemStack.EMPTY;
            ItemStack weapon = entity.getWeaponItem();
            net.minecraft.world.scores.Team nativeTeam = teams ? entity.getTeam() : null;
            org.bukkit.scoreboard.Team bukkitTeam = null;
            if (teams && entity instanceof net.minecraft.server.level.ServerPlayer player) bukkitTeam = player.getBukkitEntity().getScoreboard().getPlayerTeam(player.getBukkitEntity());
            else if (teams) {
                var craft = ((ServerLevel)entity.level()).getCraftServer();
                bukkitTeam = craft.getScoreboardManager().getMainScoreboard().getPlayerTeam(craft.getOfflinePlayer(entity.getScoreboardName()));
            }
            captured.put(entity, new Data((ServerLevel)entity.level(), entity.blockPosition().immutable(), entity.position(), entity.getDisplayName().copy(), main,
                weapon == null ? null : weapon.copy(), entity.isInWater(), entity.isSilent(), entity instanceof Player player && player.getAbilities().instabuild,
                entity instanceof Player player ? player.getLuck() : 0, nativeTeam, nativeTeam == null || nativeTeam.isAllowFriendlyFire(),
                bukkitTeam == null ? Set.of() : Set.copyOf(bukkitTeam.getEntries()), bukkitTeam == null || bukkitTeam.allowFriendlyFire(), entity.getScoreboardName())); return null;
        }));
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenApply(ignored -> new Token(captured));
    }
    /** Resolves the current virtual causing source on the actual direct source owner, then captures current metadata. */
    public static CompletableFuture<Token> refreshExplosion(Explosion explosion) {
        Entity direct = explosion.getDirectSourceEntity();
        CompletableFuture<LivingEntity> causing = direct == null ? CompletableFuture.completedFuture(explosion.getIndirectSourceEntity())
            : ScarpetExplosionActors.entity(direct, explosion::getIndirectSourceEntity);
        return causing.thenCompose(indirect -> {
            List<Entity> related = new ArrayList<>(); if (direct != null) related.add(direct); if (indirect != null) related.add(indirect);
            if (explosion instanceof net.minecraft.world.level.ServerExplosion nativeExplosion) {
                Entity originalCause = nativeExplosion.getDamageSource().getEntity(); if (originalCause != null) related.add(originalCause);
                Entity damageDirect = nativeExplosion.getDamageSource().getDirectEntity(); if (damageDirect != null) related.add(damageDirect);
            }
            return snapshot(related).thenApply(token -> new Token(token.entities, explosion, indirect));
        });
    }
    /** Exact pinned Entity/Player team algorithms, with each real team's data captured on its entity owner. */
    public static CompletableFuture<Boolean> teamsAllowDamage(Entity source, Entity target) {
        if (source == null) return CompletableFuture.completedFuture(true);
        if (TickThread.isTickThreadFor(source) && TickThread.isTickThreadFor(target)) return CompletableFuture.completedFuture(source.doTeamsAllowDamage(target));
        return snapshot(List.of(source, target), true).thenApply(token -> {
            Data from = token.get(source), to = token.get(target);
            if (source instanceof Player) return to.bukkitFriendlyFire() || !to.bukkitTeamEntries().contains(from.scoreboardName());
            return from.nativeTeam() == null || from.nativeTeam() != to.nativeTeam() || from.friendlyFire();
        });
    }
    public static Data data(Entity entity) { Token token = CURRENT.get(); return token == null ? null : token.get(entity); }
    /** Null means no captured value; callers must use their actual owner or defer, never assume dry. */
    public static Boolean inWater(Entity entity) { Data data = data(entity); return data == null ? null : data.inWater(); }
    public static Boolean silent(Entity entity) { Data data = data(entity); return data == null ? null : data.silent(); }
    /** A captured virtual source was resolved on the original direct entity's actual owner. */
    public static LivingEntity explosionCausing(Explosion explosion) {
        Entity direct = explosion.getDirectSourceEntity();
        if (direct == null || TickThread.isTickThreadFor(direct)) return Explosion.getIndirectSourceEntity(direct);
        Token token = CURRENT.get();
        if (token != null && token.resolvedExplosion == explosion) return token.explosionCausing;
        throw new IllegalStateException("Explosion causing source was not resolved by its actual owner");
    }
    public static ItemStack explosionTool(Explosion explosion) {
        LivingEntity owner = explosion.getIndirectSourceEntity();
        if (owner == null) return ItemStack.EMPTY;
        if (TickThread.isTickThreadFor(owner)) return owner.getMainHandItem();
        Data captured = data(owner);
        if (captured == null) throw new IllegalStateException("Explosion attribution was not captured by its actual tool owner");
        return captured.mainHand().copy();
    }
}
