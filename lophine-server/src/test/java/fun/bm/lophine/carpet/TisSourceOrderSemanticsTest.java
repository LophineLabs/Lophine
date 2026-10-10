package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.TickThrottler;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ServerItemCooldowns;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Invokes real production methods to verify the source rule's limited expression changes and callbacks.
 */
public class TisSourceOrderSemanticsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    boolean beforeSpam;
    double beforeDistance;
    boolean beforeLimiter;
    boolean beforeCooldown;

    @BeforeEach
    void setup() {
        beforeSpam = GeneralCompatConfig.antiSpamDisabled;
        beforeDistance = GeneralCompatConfig.xpTrackingDistance;
        beforeCooldown = GeneralCompatConfig.creativeNoItemCooldown;
        beforeLimiter = me.earthme.luminol.config.modules.misc.PaperPacketLimiterConfig.forceDisable;
        me.earthme.luminol.config.modules.misc.PaperPacketLimiterConfig.forceDisable = false;
    }

    @AfterEach
    void restore() {
        GeneralCompatConfig.antiSpamDisabled = beforeSpam;
        GeneralCompatConfig.xpTrackingDistance = beforeDistance;
        GeneralCompatConfig.creativeNoItemCooldown = beforeCooldown;
        me.earthme.luminol.config.modules.misc.PaperPacketLimiterConfig.forceDisable = beforeLimiter;
    }

    private static void set(Object object, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private ServerGamePacketListenerImpl listener() throws Exception {
        var listener = mock(ServerGamePacketListenerImpl.class, CALLS_REAL_METHODS);
        var server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
        listener.player = mock(ServerPlayer.class);
        set(listener, ServerCommonPacketListenerImpl.class, "server", server);
        doNothing().when(listener).disconnectAsync(any(Component.class), any(PlayerKickEvent.Cause.class));
        return listener;
    }

    private static int count(TickThrottler throttler) throws Exception {
        Field field = TickThrottler.class.getDeclaredField("count");
        field.setAccessible(true);
        return ((AtomicInteger) field.get(throttler)).get();
    }

    private static void detect(ServerGamePacketListenerImpl listener, TickThrottler throttler) throws Exception {
        Method method = ServerGamePacketListenerImpl.class.getDeclaredMethod("detectRateSpam", TickThrottler.class, String.class);
        method.setAccessible(true);
        method.invoke(listener, throttler, "message");
    }

    @Test
    void antiSpamRuleSkipsOnlyIncrementAndRetainsExistingCounterThresholdKick() throws Exception {
        GeneralCompatConfig.antiSpamDisabled = true;
        var listener = listener();
        var throttler = new TickThrottler(2, 2);
        throttler.increment();
        detect(listener, throttler);
        assertEquals(2, count(throttler));
        verify(listener).disconnectAsync(any(Component.class), eq(PlayerKickEvent.Cause.SPAM));
    }

    @Test
    void enabledAntiSpamRuleKeepsLowCounterAndDisabledRuleUsesOriginalAtomicIncrement() throws Exception {
        var listener = listener();
        var throttler = new TickThrottler(2, 2);
        GeneralCompatConfig.antiSpamDisabled = true;
        detect(listener, throttler);
        assertEquals(0, count(throttler));
        verify(listener, never()).disconnectAsync(any(Component.class), any(PlayerKickEvent.Cause.class));
        GeneralCompatConfig.antiSpamDisabled = false;
        detect(listener, throttler);
        assertEquals(2, count(throttler));
        verify(listener).disconnectAsync(any(Component.class), eq(PlayerKickEvent.Cause.SPAM));
    }

    @Test
    void creativeDropRetainsOriginalGateAndPluginCallbackEvenWhenIncrementIsSuppressed() throws Exception {
        GeneralCompatConfig.antiSpamDisabled = true;
        var listener = listener();
        var world = mock(ServerLevel.class);
        when(listener.player.level()).thenReturn(world);
        when(world.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
        when(listener.player.hasInfiniteMaterials()).thenReturn(true);
        var menu = mock(InventoryMenu.class);
        set(listener.player, Player.class, "inventoryMenu", menu);
        when(menu.getBukkitView()).thenReturn(mock(org.bukkit.craftbukkit.inventory.CraftInventoryView.class));
        var craft = mock(CraftServer.class, RETURNS_DEEP_STUBS);
        set(listener, ServerCommonPacketListenerImpl.class, "cserver", craft);
        var stack = mock(ItemStack.class);
        when(stack.isItemEnabled(FeatureFlags.DEFAULT_FLAGS)).thenReturn(true);
        when(stack.getCount()).thenReturn(1);
        when(stack.getMaxStackSize()).thenReturn(64);
        var cursor = mock(org.bukkit.inventory.ItemStack.class);
        var throttler = new TickThrottler(2, 2);
        throttler.increment();
        set(listener, ServerGamePacketListenerImpl.class, "dropSpamThrottler", throttler);
        try (var packets = mockStatic(PacketUtils.class); var gate = mockStatic(carpet.script.external.ScarpetPlayerInventoryGate.class); var items = mockStatic(CraftItemStack.class)) {
            items.when(() -> CraftItemStack.asBukkitCopy(stack)).thenReturn(cursor);
            items.when(() -> CraftItemStack.asNMSCopy(cursor)).thenReturn(stack);
            listener.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(-1, stack));
            verify(craft.getPluginManager()).callEvent(any(InventoryCreativeEvent.class));
            verify(listener.player, never()).drop(any(ItemStack.class), anyBoolean(), any());
            assertEquals(2, count(throttler));
            throttler.tick();
            listener.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(-1, stack));
            verify(listener.player).drop(eq(stack), eq(true), eq(net.minecraft.util.Prediction.PREDICTED));
            assertEquals(1, count(throttler));
        }
    }

    private static Player target(ExperienceOrb orb) throws Exception {
        Field field = ExperienceOrb.class.getDeclaredField("followingPlayer");
        field.setAccessible(true);
        return (Player) field.get(orb);
    }

    private static void follow(ExperienceOrb orb) throws Exception {
        Method method = ExperienceOrb.class.getDeclaredMethod("followNearbyPlayer");
        method.setAccessible(true);
        method.invoke(orb);
    }

    @Test
    void zeroXpDistanceStillRunsOriginalForgotTargetCallbackAndClearsTargetWhenAccepted() throws Exception {
        GeneralCompatConfig.xpTrackingDistance = 0;
        var orb = mock(ExperienceOrb.class, CALLS_REAL_METHODS);
        var world = mock(ServerLevel.class);
        when(orb.level()).thenReturn(world);
        var prior = mock(Player.class);
        set(orb, ExperienceOrb.class, "followingPlayer", prior);
        var event = mock(EntityTargetLivingEntityEvent.class);
        try (var callbacks = mockStatic(CraftEventFactory.class)) {
            callbacks.when(() -> CraftEventFactory.callEntityTargetLivingEvent(orb, null, EntityTargetEvent.TargetReason.FORGOT_TARGET)).thenReturn(event);
            follow(orb);
            callbacks.verify(() -> CraftEventFactory.callEntityTargetLivingEvent(orb, null, EntityTargetEvent.TargetReason.FORGOT_TARGET));
            verify(world).getNearestPlayer(orb, 0);
            assertNull(target(orb));
            verify(orb, never()).setDeltaMovement(any());
        }
    }

    @Test
    void zeroXpDistanceRetainsPluginCancellationAndOriginalTargetIdentity() throws Exception {
        GeneralCompatConfig.xpTrackingDistance = 0;
        var orb = mock(ExperienceOrb.class, CALLS_REAL_METHODS);
        var world = mock(ServerLevel.class);
        when(orb.level()).thenReturn(world);
        var prior = mock(Player.class);
        set(orb, ExperienceOrb.class, "followingPlayer", prior);
        var event = mock(EntityTargetLivingEntityEvent.class);
        when(event.isCancelled()).thenReturn(true);
        try (var callbacks = mockStatic(CraftEventFactory.class)) {
            callbacks.when(() -> CraftEventFactory.callEntityTargetLivingEvent(orb, null, EntityTargetEvent.TargetReason.FORGOT_TARGET)).thenReturn(event);
            follow(orb);
            assertSame(prior, target(orb));
            verify(orb, never()).setDeltaMovement(any());
        }
    }

    @Test
    void creativeNoItemCooldownPreservesItemCallbackAndSkipsStateWriteAndStartPacket() throws Exception {
        GeneralCompatConfig.creativeNoItemCooldown = true;
        var player = mock(ServerPlayer.class);
        when(player.isCreative()).thenReturn(true);
        var craft = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
        when(player.getBukkitEntity()).thenReturn(craft);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        var stack = mock(ItemStack.class);
        when(stack.getItem()).thenReturn(net.minecraft.world.item.Items.STONE);
        var cooldowns = new ServerItemCooldowns(player);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class); var items = mockStatic(org.bukkit.craftbukkit.inventory.CraftItemType.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            items.when(() -> org.bukkit.craftbukkit.inventory.CraftItemType.minecraftToBukkit(net.minecraft.world.item.Items.STONE)).thenReturn(org.bukkit.Material.STONE);
            cooldowns.addCooldown(stack, 37);
            verify(manager).callEvent(any(io.papermc.paper.event.player.PlayerItemCooldownEvent.class));
            assertEquals(0, cooldowns.getRemainingCooldown(Identifier.withDefaultNamespace("stone")));
            verifyNoInteractions(player.connection);
        }
    }

    @Test
    void creativeNoItemCooldownPreservesIdentifierGroupCallbackAndCoversDirectNativeOverload() throws Exception {
        GeneralCompatConfig.creativeNoItemCooldown = true;
        var player = mock(ServerPlayer.class);
        when(player.isCreative()).thenReturn(true);
        when(player.getBukkitEntity()).thenReturn(mock(org.bukkit.craftbukkit.entity.CraftPlayer.class));
        player.connection = mock(ServerGamePacketListenerImpl.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        var group = Identifier.withDefaultNamespace("custom_cooldown");
        var cooldowns = new ServerItemCooldowns(player);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            cooldowns.addCooldown(group, 37);
            verify(manager).callEvent(any(io.papermc.paper.event.player.PlayerItemGroupCooldownEvent.class));
            cooldowns.addCooldown(group, 42, false);
            assertEquals(0, cooldowns.getRemainingCooldown(group));
            verifyNoInteractions(player.connection);
            GeneralCompatConfig.creativeNoItemCooldown = false;
            cooldowns.addCooldown(group, 51, false);
            assertEquals(51, cooldowns.getRemainingCooldown(group));
            verify(player.connection).send(any(net.minecraft.network.protocol.game.ClientboundCooldownPacket.class));
        }
    }

    @Test
    void creativeCooldownPluginCancellationStillSendsOriginalCooldownCorrectionPacket() throws Exception {
        GeneralCompatConfig.creativeNoItemCooldown = true;
        var player = mock(ServerPlayer.class);
        when(player.isCreative()).thenReturn(true);
        when(player.getBukkitEntity()).thenReturn(mock(org.bukkit.craftbukkit.entity.CraftPlayer.class));
        player.connection = mock(ServerGamePacketListenerImpl.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        var group = Identifier.withDefaultNamespace("custom_cooldown");
        var cooldowns = new ServerItemCooldowns(player);
        doAnswer(call -> {
            ((io.papermc.paper.event.player.PlayerItemGroupCooldownEvent) call.getArgument(0)).setCancelled(true);
            return null;
        }).when(manager).callEvent(any());
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            cooldowns.addCooldown(group, 37);
            assertEquals(0, cooldowns.getRemainingCooldown(group));
            verify(player.connection).send(any(net.minecraft.network.protocol.game.ClientboundCooldownPacket.class));
        }
    }
}
