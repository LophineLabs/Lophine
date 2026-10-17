/*
 * This file is part of Lophine.
 * Portions derived from Leaves (https://github.com/LeavesMC/Leaves).
 * This file is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This file is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this file. If not, see <https://www.gnu.org/licenses/>.
 */

package fun.bm.lophine.carpet;

import com.mojang.authlib.GameProfile;
import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.*;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

import static net.minecraft.server.MinecraftServer.getServer;

public class CarpetFakePlayer extends ServerPlayer {
    public boolean carpetShadow;
    public boolean resume;
    public boolean handsBusy;
    public volatile boolean carpetPlacementReady;
    private boolean carpetRestoreAfterDeath;
    private CompoundTag carpetShutdownActions;
    private int autoFishCooldown;
    private static final int AUTO_FISH_RECAST_DELAY = 4, AUTO_FISH_ENTITY_DELAY = 20;
    final java.util.concurrent.CompletableFuture<CarpetFakePlayer> carpetActualPlacementFuture = new java.util.concurrent.CompletableFuture<>();
    public final java.util.concurrent.CompletableFuture<CarpetFakePlayer> carpetPlacementFuture = new java.util.concurrent.CompletableFuture<>();
    public Runnable carpetPlacementInitializer = () -> {
    };

    public CarpetFakePlayer(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile, ClientInformation.createDefault());
        this.entityData.set(Player.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
    }

    public boolean isFakePlayer() {
        return true;
    }

    public void tick() {
        if (this.isRemoved() || this.hasDisconnected() || !this.isAlive()) return;
        if (fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerHeal && this.level().getGameTime() % 40L == 0L) {
            this.heal(1.0F);
            this.getFoodData().eat(1, 0.0F);
        }
        this.joining = false;
        super.tick();
        if (this.isRemoved() || this.hasDisconnected()) return;
        if (!FakePlayerCompatConfig.fakePlayerTicksLikeRealPlayer) this.doTick();
    }

    public void doTick() {
        if (this.isRemoved() || this.hasDisconnected()) return;
        if (!this.isAlive()) {
            this.die(this.damageSources().generic());
            return;
        }
        super.doTick();
        if (this.isRemoved() || this.hasDisconnected()) return;
        this.updateItemInHand(InteractionHand.MAIN_HAND);
        this.updateItemInHand(InteractionHand.OFF_HAND);
        tickAutoFish();
    }

    public void networkTick() {
        if (carpet.script.external.ScarpetNativeWork.isDraining(this.level().getServer()) || CarpetPlayerBirths.playerPending(this))
            return;
        if (FakePlayerCompatConfig.fakePlayerTicksLikeRealPlayer) {
            this.carpetActionPack.onUpdate();
            if (this.isRemoved() || this.hasDisconnected()) return;
            this.doTick();
        }
    }

    public void addAdditionalSaveData(ValueOutput nbt) {
        super.addAdditionalSaveData(nbt);
        nbt.putBoolean("isShiftKeyDown", this.isShiftKeyDown());
        nbt.putBoolean("carpetRestoreAfterDeath", this.carpetRestoreAfterDeath);
        nbt.putBoolean("carpetShadow", this.carpetShadow);
        nbt.store("carpetPlayerActions", CompoundTag.CODEC,
                this.carpetShutdownActions == null ? this.carpetActionPack.save() : this.carpetShutdownActions.copy());
    }

    void preserveShutdownActions(CompoundTag actions) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Shutdown action metadata requires its owner");
        if (!carpet.script.external.ScarpetNativeWork.isDraining(this.level().getServer()))
            throw new IllegalStateException("Resumable shutdown actions can only be frozen while draining");
        this.carpetShutdownActions = actions.copy();
    }

    public void readAdditionalSaveData(ValueInput nbt) {
        super.readAdditionalSaveData(nbt);
        this.setShiftKeyDown(nbt.getBooleanOr("isShiftKeyDown", false));
        this.carpetShadow = nbt.getBooleanOr("carpetShadow", this.carpetShadow);
        nbt.read("carpetPlayerActions", CompoundTag.CODEC).ifPresent(this.carpetActionPack::load);
        if (nbt.getBooleanOr("carpetRestoreAfterDeath", false)) {
            this.setHealth(this.getMaxHealth());
            this.deathTime = 0;
            this.setRemainingFireTicks(0);
            this.fallDistance = 0.0F;
            this.carpetRestoreAfterDeath = false;
        }
    }

    @Override
    public InteractionResult interact(Player viewer, InteractionHand hand, Vec3 location) {
        if (FakePlayerCompatConfig.openFakePlayerInventory && viewer instanceof ServerPlayer player && player.getMainHandItem().isEmpty()) {
            OrgPlayerInventoryMenus.openAsync(player, this, false);
            return InteractionResult.SUCCESS;
        }
        return super.interact(viewer, hand, location);
    }

    @Override
    public void onItemPickup(ItemEntity item) {
        super.onItemPickup(item);
        this.updateItemInHand(InteractionHand.MAIN_HAND);
    }

    public void updateItemInHand(InteractionHand hand) {
        ItemStack item = this.getItemInHand(hand);
        if (!item.isEmpty()) OrgFakePlayerInventory.restock(this, item, item, hand);
        this.detectEquipmentUpdates();
    }

    public void renderInfo() {
        this.level().getServer().getPlayerList().getPlayers().forEach(this::sendPlayerInfo);
    }

    private void sendPacket(Packet<?> packet) {
        this.level().getServer().getPlayerList().getPlayers().forEach(p -> p.connection.send(packet));
    }

    public void faceLocation(Vec3 position) {
        this.look(position.subtract(this.position()), false);
    }

    public void look(Vec3 direction, boolean keepYaw) {
        double horizontal = direction.horizontalDistance();
        float yaw = keepYaw || horizontal == 0.0 ? this.getYHeadRot() : (float) Math.toDegrees(Mth.atan2(-direction.x, direction.z));
        float pitch = (float) Math.toDegrees(Mth.atan2(-direction.y, horizontal));
        this.setRot(yaw, pitch);
        if (!keepYaw) this.sendPacket(new ClientboundRotateHeadPacket(this, (byte) (yaw * 256 / 360f)));
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        if (this.isSpectator() || this.keepLevel
                || level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY)
                || OrgFakePlayerInventory.shouldKeepInventory(this, this.getLastDamageSource())) return 0;
        return Math.min(this.experienceLevel * 7, 100);
    }

    public boolean allowsListing() {
        return fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.allowListingFakePlayers;
    }

    public void carpetSetProfile(GameProfile profile) {
        if (!profile.id().equals(this.getUUID()))
            throw new IllegalArgumentException("A fake player's profile must retain its UUID");
        this.gameProfile = profile;
    }

    public boolean canSimulateMovement() {
        return true;
    }

    public void removeVehicle() {
        super.removeVehicle();
        this.handsBusy = false;
    }

    public void rideTick() {
        super.rideTick();
        this.handsBusy = false;
        if (this.getControlledVehicle() instanceof AbstractBoat abstractBoat) {
            Input input = this.getLastClientInput();
            abstractBoat.setInput(input.left(), input.right(), input.forward(), input.backward());
            this.handsBusy = this.handsBusy | (input.left() || input.right() || input.forward() || input.backward());
        }
    }

    public void attack(@NotNull Entity target) {
        super.attack(target);
        this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
    }

    public void sendPlayerInfo(ServerPlayer player) {
        player.connection.send(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), List.of(this)));
    }

    public void removeTab() {
        this.sendPacket(new ClientboundPlayerInfoRemovePacket(List.of(this.getUUID())));
    }

    public EntityHitResult getEntityHitResult() {
        return this.getEntityHitResult(null);
    }

    public EntityHitResult getEntityHitResult(Predicate<? super Entity> predicate) {
        EntityHitResult result = this.pick(this, this.entityInteractionRange());
        if (result != null && (predicate == null || predicate.test(result.getEntity()))) {
            return result;
        }
        return null;
    }

    public BlockHitResult getBlockHitResult() {
        return (BlockHitResult) this.pick(this.blockInteractionRange(), 1.0f, false);
    }

    private EntityHitResult pick(Entity entity, double maxDistance) {
        double d = maxDistance;
        double d1 = Mth.square(maxDistance);
        Vec3 vec3 = entity.getEyePosition(1.0f);
        HitResult hitResult = entity.pick(maxDistance, 1.0f, false);
        double d2 = hitResult.getLocation().distanceToSqr(vec3);
        if (hitResult.getType() != HitResult.Type.MISS) {
            d1 = d2;
            d = Math.sqrt(d2);
        }

        Vec3 viewStart = entity.getViewVector(1.0f);
        Vec3 viewEnd = vec3.add(viewStart.x * d, viewStart.y * d, viewStart.z * d);
        AABB aABB = entity.getBoundingBox().expandTowards(viewStart.scale(d)).inflate(1.0, 1.0, 1.0);
        return ProjectileUtil.getEntityHitResult(entity, vec3, viewEnd, aABB, EntitySelector.CAN_BE_PICKED, d1);
    }

    public void dropAll(boolean death) {
        NonNullList<ItemStack> items = this.getInventory().getNonEquipmentItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack itemStack = items.get(i);
            if (!itemStack.isEmpty()) {
                if (!death || !net.minecraft.world.item.enchantment.EnchantmentHelper.has(itemStack, net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                    ItemEntity dropped = this.drop(itemStack, false, net.minecraft.util.Prediction.PREDICTED, death, false, null);
                    if (dropped != null && fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerDropsNotDespawning)
                        dropped.setUnlimitedLifetime();
                }
                items.set(i, ItemStack.EMPTY);
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack itemStack;
            if (!(itemStack = this.equipment.get(slot)).isEmpty()) {
                if (!death || !net.minecraft.world.item.enchantment.EnchantmentHelper.has(itemStack, net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                    this.drop(itemStack, false, net.minecraft.util.Prediction.PREDICTED, death, false, null);
                }
                this.equipment.set(slot, ItemStack.EMPTY);
            }
        }
        this.detectEquipmentUpdates();
    }

    private void tickAutoFish() {
        if (!FakePlayerCompatConfig.fakePlayerAutoFish) {
            this.autoFishCooldown = 0;
            return;
        }

        if (this.autoFishCooldown > 0) {
            this.autoFishCooldown--;
            return;
        }

        ItemStack mainHand = this.getMainHandItem();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof FishingRodItem)) {
            return;
        }

        FishingHook fishingHook = this.fishing;
        if (fishingHook != null) {
            if (fishingHook.currentState == FishingHook.FishHookState.HOOKED_IN_ENTITY) {
                mainHand.use(this.level(), this, InteractionHand.MAIN_HAND);
                this.autoFishCooldown = AUTO_FISH_ENTITY_DELAY;
                return;
            }

            if (fishingHook.nibble > 0) {
                mainHand.use(this.level(), this, InteractionHand.MAIN_HAND);
                this.autoFishCooldown = AUTO_FISH_RECAST_DELAY;
            }
            return;
        }

        mainHand.use(this.level(), this, InteractionHand.MAIN_HAND);
        this.autoFishCooldown = AUTO_FISH_RECAST_DELAY;
    }

    public boolean startRiding(@NotNull Entity vehicle, boolean force, boolean sendGameEvent) {
        if (super.startRiding(vehicle, force, sendGameEvent)) {
            if (vehicle.getControllingPassenger() == this) { // see net.minecraft.server.networkServerGamePacketListenerImpl#handleMoveVehicle
                this.setDeltaMovement(Vec3.ZERO);
                this.setYRot(vehicle.yRotO);
            }
            return true;
        } else {
            return false;
        }
    }

    public void die(@NotNull DamageSource damageSource) {
        var observed = carpet.script.external.ScarpetNativeWork.observeNative(this, () -> {
            var originalDeath = carpet.script.external.ScarpetRuntime.captureNativeContinuation(() ->
                    carpet.script.external.ScarpetExplosionActors.entity(this, () ->
                            carpet.script.external.ScarpetNativeWork.recoverGuestValue(carpet.script.external.ScarpetNativeWork.<Void>observeNative(this, () -> {
                                super.die(damageSource);
                                return null;
                            }))).thenCompose(value -> value));
            var afterDeath = carpet.script.external.ScarpetRuntime.captureNativeContinuation(() ->
                    carpet.script.external.ScarpetExplosionActors.entity(this, () -> {
                        // Paper revives the player when PlayerDeathEvent is cancelled.
                        if (this.getHealth() > 0.0F || this.isRemoved())
                            return java.util.concurrent.CompletableFuture.completedFuture(false);
                        try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(this)) {
                            this.setHealth(20.0F);
                            this.foodData = new net.minecraft.world.food.FoodData();
                            // The true SP body already performed death drops/XP. The disconnect saves its final inventory once.
                            this.carpetRestoreAfterDeath = true;
                            return getServer().getCarpetFakePlayers().removeAsync(this, CarpetFakePlayerManager.RemoveReason.DEATH, null, true, false);
                        }
                    }).thenCompose(value -> value));
            var actual = carpet.script.external.ScarpetNativeDeaths.shakeOff(this)
                    .thenCompose(ignored -> originalDeath.get()).thenCompose(ignored -> afterDeath.get());
            carpet.script.external.ScarpetNativeWork.record(actual);
            return actual;
        });
        var actual = carpet.script.external.ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value -> value);
        carpet.script.external.ScarpetNativeWork.aliasDependency(actual, observed);
        carpet.script.external.ScarpetNativeWork.record(actual);
        carpet.script.external.ScarpetNativeWork.trackNative(getServer(), actual);
    }
}
