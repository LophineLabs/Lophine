package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.CarpetRegionLease;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TisDragonDeathNativeTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var head = net.minecraft.world.item.Items.DRAGON_HEAD;
        try {
            head.builtInRegistryHolder().components();
        } catch (NullPointerException unbound) {
            head.builtInRegistryHolder().bindComponents(net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
        }
        var config = new io.papermc.paper.configuration.GlobalConfiguration();
        config.misc = config.new Misc();
        try (var global = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)) {
            global.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);
            try {
                Class.forName("net.minecraft.network.Connection");
            } catch (ClassNotFoundException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerLevel original = mock(ServerLevel.class);
    private final ServerLevel changed = mock(ServerLevel.class);
    private final EnderDragonFight fight = mock(EnderDragonFight.class);
    private final EnderDragonPart part = mock(EnderDragonPart.class);
    private final RandomSource random = mock(RandomSource.class);
    private final List<String> order = new ArrayList<>();
    private final UUID uuid = UUID.randomUUID();
    private final AtomicInteger time = new AtomicInteger(199);
    private CompletableFuture<Void> eightChild, twentyChild, headChild, portalChild, moveChild;
    private final EnderDragon dragon = mock(EnderDragon.class, call -> {
        String name = call.getMethod().getName();
        if (name.equals("carpetDeathAwardUnit")) {
            assertSame(original, call.getArgument(0));
            assertEquals(new Vec3(0, 64, 0), call.getArgument(1));
            assertNull(call.getArgument(2));
            int amount = call.getArgument(3);
            order.add("xp" + amount);
            var child = amount == 8 ? eightChild : twentyChild;
            if (child != null) ScarpetNativeWork.record(child);
            return 0;
        }
        if (name.equals("move")) {
            order.add("move");
            if (moveChild != null) ScarpetNativeWork.record(moveChild);
            return null;
        }
        if (name.equals("spawnAtLocation")) {
            assertSame(original, call.getArgument(0));
            assertSame(net.minecraft.world.item.Items.DRAGON_HEAD, ((net.minecraft.world.item.ItemStack) call.getArgument(1)).getItem());
            order.add("head");
            if (headChild != null) ScarpetNativeWork.record(headChild);
            return null;
        }
        if (name.equals("remove")) {
            order.add("remove");
            return null;
        }
        if (name.equals("gameEvent")) {
            order.add("event");
            return null;
        }
        if (name.equals("carpetDeathAdvance")) {
            int value = (int) call.callRealMethod();
            time.set(value);
            order.add("advance" + value);
            return value;
        }
        if (name.equals("tickDeath") || name.startsWith("carpetDeath")) return CALLS_REAL_METHODS.answer(call);
        return RETURNS_DEFAULTS.answer(call);
    });

    private static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        f.set(object, value);
    }

    @BeforeEach
    void setup() throws Exception {
        when(original.getServer()).thenReturn(server);
        when(changed.getServer()).thenReturn(server);
        when(dragon.level()).thenReturn(original);
        when(dragon.position()).thenReturn(new Vec3(0, 64, 0));
        when(dragon.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
        when(dragon.getX()).thenReturn(0D);
        when(dragon.getY()).thenReturn(64D);
        when(dragon.getZ()).thenReturn(0D);
        when(dragon.getUUID()).thenReturn(uuid);
        when(dragon.getHealth()).thenReturn(1F);
        when(dragon.getMaxHealth()).thenReturn(200F);
        when(dragon.getSubEntities()).thenReturn(new EnderDragonPart[]{part});
        when(part.level()).thenReturn(original);
        when(part.blockPosition()).thenReturn(BlockPos.ZERO);
        when(part.position()).thenReturn(new Vec3(3, 64, 0));
        doAnswer(call -> {
            order.add("part-old");
            return null;
        }).when(part).setOldPosAndRot();
        doAnswer(call -> {
            assertEquals(new Vec3(3, 64 + (double) .1F, 0), call.getArgument(0));
            order.add("part-pos");
            return null;
        }).when(part).setPos(any(Vec3.class));
        field(dragon, EnderDragon.class, "dragonDeathTime", 199);
        field(dragon, Entity.class, "random", random);
        dragon.expToDrop = 100;
        fight.level = original;
        fight.origin = new BlockPos(96, 0, 0);
        when(fight.carpetDeathMatches(uuid)).thenAnswer(call -> {
            order.add("match");
            return true;
        });
        doAnswer(call -> {
            assertEquals(.005F, (Float) call.getArgument(0));
            order.add("progress");
            return null;
        }).when(fight).carpetDeathUpdateProgress(anyFloat());
        doAnswer(call -> {
            order.add("name");
            return null;
        }).when(fight).carpetDeathUpdateName(any());
        when(fight.hasPreviouslyKilledDragon()).thenAnswer(call -> {
            order.add("previous");
            return false;
        });
        doAnswer(call -> {
            order.add("zero");
            return null;
        }).when(fight).carpetDeathZeroProgress();
        doAnswer(call -> {
            order.add("hide");
            return null;
        }).when(fight).carpetDeathHide();
        when(fight.carpetDeathPortalPosition()).thenReturn(new BlockPos(96, 64, 0));
        when(fight.carpetDeathGatewayPosition()).thenReturn(null);
        when(fight.carpetDeathTakeGateway()).thenAnswer(call -> {
            order.add("gateway-empty");
            return null;
        });
        when(fight.carpetDeathPodiumPosition()).thenReturn(new BlockPos(96, 64, 0));
        when(fight.carpetDeathPreparePortal()).thenAnswer(call -> {
            order.add("portal-prepare");
            return null;
        });
        when(fight.carpetDeathPlacePortal(any())).thenAnswer(call -> {
            order.add("portal-place");
            if (portalChild != null) ScarpetNativeWork.record(portalChild);
            return false;
        });
        doAnswer(call -> {
            order.add("light");
            return null;
        }).when(fight).carpetDeathLightPortal();
        var egg = mock(io.papermc.paper.event.block.DragonEggFormEvent.class);
        when(egg.callEvent()).thenAnswer(call -> {
            order.add("egg-event");
            return false;
        });
        when(fight.carpetDeathPrepareEgg()).thenAnswer(call -> {
            order.add("egg-prepare");
            return egg;
        });
        doAnswer(call -> {
            order.add("killed");
            return null;
        }).when(fight).carpetDeathFinishKilled();
    }

    private final class Owners implements AutoCloseable {
        final MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final MockedStatic<CarpetRegionLease> leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();

        @SuppressWarnings({"rawtypes", "unchecked"})
        Owners() {
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(any(ServerLevel.class), any(BlockPos.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(any(ServerLevel.class), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
            leases.when(() -> CarpetRegionLease.runValue(any(), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class)))
                    .thenAnswer(call -> CompletableFuture.completedFuture(((Function) call.getArgument(5)).apply(null)));
        }

        public void close() {
            leases.close();
            ticks.close();
        }
    }

    private CompletableFuture<Void> tick() {
        return ScarpetNativeWork.observeNative(dragon, () -> {
            try {
                Method method = EnderDragon.class.getDeclaredMethod("tickDeath");
                method.setAccessible(true);
                method.invoke(dragon);
                return null;
            } catch (ReflectiveOperationException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private void drop(boolean value) throws Exception {
        field(dragon, EnderDragon.class, "carpetDropDragonHead", value);
    }

    private void withFight() throws Exception {
        field(dragon, EnderDragon.class, "dragonFight", fight);
    }

    @Test
    void actualDeathXpEightAndTwentyWaitNativeChildrenBeforeMoveHeadAndRemoval() throws Exception {
        drop(true);
        eightChild = new CompletableFuture<>();
        twentyChild = new CompletableFuture<>();
        headChild = new CompletableFuture<>();
        try (var owners = new Owners()) {
            var raw = tick();
            assertEquals(List.of("advance200", "xp8"), order);
            assertFalse(raw.isDone());
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            eightChild.complete(null);
            assertEquals(List.of("advance200", "xp8", "move", "part-old", "part-pos", "xp20"), order);
            twentyChild.complete(null);
            if (raw.isCompletedExceptionally()) raw.join();
            assertEquals("head", order.getLast());
            assertFalse(order.contains("remove"));
            headChild.complete(null);
            raw.join();
            assertEquals(List.of("advance200", "xp8", "move", "part-old", "part-pos", "xp20", "head", "remove", "event"), order);
            ScarpetNativeWork.whenIdle(server).join();
        }
    }

    @Test
    void guestOnlyHeadFailureKeepsRawParentWhileActualRemovalAndEventContinue() throws Exception {
        drop(true);
        headChild = new CompletableFuture<>();
        try (var owners = new Owners()) {
            var raw = tick();
            Throwable failure = new IllegalStateException("guest-head");
            Method mark = ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure", Throwable.class);
            mark.setAccessible(true);
            mark.invoke(null, failure);
            headChild.completeExceptionally(failure);
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, raw::join)));
            assertEquals(List.of("remove", "event"), order.subList(order.size() - 2, order.size()));
        }
    }

    @Test
    void trueXpFailureStopsMoveAndTrueHeadFailureStopsRemovalEvent() throws Exception {
        eightChild = new CompletableFuture<>();
        try (var owners = new Owners()) {
            var raw = tick();
            eightChild.completeExceptionally(new IllegalStateException("xp-native"));
            assertThrows(CompletionException.class, raw::join);
            assertEquals(List.of("advance200", "xp8"), order);
        }
        order.clear();
        eightChild = null;
        field(dragon, EnderDragon.class, "dragonDeathTime", 199);
        drop(true);
        headChild = new CompletableFuture<>();
        try (var owners = new Owners()) {
            var raw = tick();
            headChild.completeExceptionally(new IllegalStateException("head-native"));
            assertThrows(CompletionException.class, raw::join);
            assertFalse(order.contains("remove"));
            assertFalse(order.contains("event"));
        }
    }

    @Test
    void sourceFightProgressPortalFalseEggFalseAndFinalStateArePreserved() throws Exception {
        withFight();
        try (var owners = new Owners()) {
            tick().join();
            assertEquals(List.of("match", "progress", "name", "advance200", "previous", "xp8", "move", "part-old", "part-pos", "xp20", "match", "zero", "hide", "portal-prepare", "portal-place", "gateway-empty", "egg-prepare", "egg-event", "killed", "remove", "event"), order);
            verify(fight, never()).carpetDeathLightPortal();
            verify(fight, never()).updateDragon(any());
            verify(fight, never()).setDragonKilled(any());
        }
    }

    @Test
    void portalPlacementChildrenPrecedeLightGatewayEggAndNativeFailureBlocksThem() throws Exception {
        withFight();
        portalChild = new CompletableFuture<>();
        when(fight.carpetDeathPlacePortal(any())).thenAnswer(call -> {
            order.add("portal-place");
            ScarpetNativeWork.record(portalChild);
            return true;
        });
        try (var owners = new Owners()) {
            var raw = tick();
            assertEquals("portal-place", order.getLast());
            portalChild.complete(null);
            raw.join();
            assertTrue(order.indexOf("light") < order.indexOf("gateway-empty"));
        }
        order.clear();
        field(dragon, EnderDragon.class, "dragonDeathTime", 199);
        portalChild = new CompletableFuture<>();
        try (var owners = new Owners()) {
            var raw = tick();
            portalChild.completeExceptionally(new IllegalStateException("portal native"));
            assertThrows(CompletionException.class, raw::join);
            assertFalse(order.contains("light"));
            assertFalse(order.contains("gateway-empty"));
            assertFalse(order.contains("remove"));
        }
    }

    @Test
    void originalWorldAndRandomThreeFloatsRemainThroughDeferredXp() throws Exception {
        eightChild = new CompletableFuture<>();
        when(random.nextFloat()).thenReturn(.25F, .5F, .75F);
        try (var owners = new Owners()) {
            var raw = tick();
            verify(random, times(3)).nextFloat();
            verify(original).addParticle(eq(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER), eq(-2D), eq(66D), eq(2D), eq(0D), eq(0D), eq(0D));
            when(dragon.level()).thenReturn(changed);
            eightChild.complete(null);
            raw.join();
            verify(changed, never()).addParticle(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
            assertEquals("event", order.getLast());
        }
    }

    @Test
    void secondActualTickDoesNotAdvanceAgainWhileNativeMoveStillPending() throws Exception {
        moveChild = new CompletableFuture<>();
        field(dragon, EnderDragon.class, "dragonDeathTime", 149);
        try (var owners = new Owners()) {
            var first = tick();
            var second = tick();
            assertEquals(List.of("advance150", "move"), order);
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            moveChild.complete(null);
            first.join();
            second.join();
            assertEquals(List.of("advance150", "move", "part-old", "part-pos"), order);
        }
    }

    @Test
    void realXpUnitKeepsNativeRandomMergeIdentityAndOriginalFalseAddResult() throws Exception {
        var global = new io.papermc.paper.configuration.GlobalConfiguration();
        global.misc = global.new Misc();
        var nativeRandom = mock(RandomSource.class);
        when(original.getRandom()).thenReturn(nativeRandom);
        when(nativeRandom.nextInt(anyInt())).thenReturn(0);
        boolean previous = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.experienceOrbMerge;
        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.experienceOrbMerge = false;
        try (var config = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class); var created = mockConstruction(ExperienceOrb.class, (orb, context) -> {
            assertSame(original, context.arguments().get(0));
            assertEquals(Vec3.ZERO, context.arguments().get(2));
            assertEquals(7, context.arguments().get(3));
            assertSame(dragon, context.arguments().get(6));
        })) {
            config.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(global);
            when(original.getEntities(any(net.minecraft.world.level.entity.EntityTypeTest.class), any(net.minecraft.world.phys.AABB.class), any(Predicate.class))).thenReturn(List.of());
            when(original.addFreshEntity(any())).thenReturn(false);
            assertEquals(1, ExperienceOrb.carpetAwardUnit(original, new Vec3(0, 64, 0), Vec3.ZERO, 8, org.bukkit.entity.ExperienceOrb.SpawnReason.ENTITY_DEATH, null, dragon));
            verify(nativeRandom).nextInt(40);
            assertEquals(1, created.constructed().size());
            verify(original).addFreshEntity(created.constructed().getFirst());
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.experienceOrbMerge = previous;
        }
    }

    @Test
    void sourceSoundUsesActualFarRecipientAndWaitsRealNetworkReceiptBeforeMovement() throws Exception {
        field(dragon, EnderDragon.class, "dragonDeathTime", 0);
        var player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(changed);
        when(player.blockPosition()).thenReturn(new BlockPos(1000, 64, 0));
        when(player.getX()).thenReturn(1000D);
        when(player.getZ()).thenReturn(0D);
        var listener = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        player.connection = listener;
        var connection = mock(net.minecraft.network.Connection.class);
        field(listener, net.minecraft.server.network.ServerCommonPacketListenerImpl.class, "connection", connection);
        connection.channel = mock(io.netty.channel.Channel.class);
        var closing = mock(io.netty.channel.ChannelFuture.class);
        when(connection.channel.closeFuture()).thenReturn(closing);
        var receipt = new AtomicReference<io.netty.channel.ChannelFutureListener>();
        var packet = new AtomicReference<net.minecraft.network.protocol.game.ClientboundLevelEventPacket>();
        doAnswer(call -> {
            order.add("send");
            packet.set(call.getArgument(0));
            receipt.set(call.getArgument(1));
            return null;
        }).when(listener).send(any(net.minecraft.network.protocol.game.ClientboundLevelEventPacket.class), any(io.netty.channel.ChannelFutureListener.class));
        var craft = mock(org.bukkit.craftbukkit.CraftServer.class);
        when(craft.getViewDistance()).thenReturn(10);
        when(original.getCraftServer()).thenReturn(craft);
        when(original.getPlayersForGlobalSoundGamerule()).thenReturn(List.of(player));
        when(original.getGlobalSoundRangeSquared(any())).thenReturn(40000D);
        var rules = mock(net.minecraft.world.level.gamerules.GameRules.class);
        when(original.getGameRules()).thenReturn(rules);
        when(rules.get(net.minecraft.world.level.gamerules.GameRules.GLOBAL_SOUND_EVENTS)).thenReturn(true);
        try (var owners = new Owners()) {
            var raw = tick();
            assertEquals("send", order.getLast());
            assertFalse(order.contains("move"));
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            assertEquals(new BlockPos(840, 64, 0), packet.get().getPos());
            var sent = mock(io.netty.channel.ChannelFuture.class);
            when(sent.isSuccess()).thenReturn(true);
            receipt.get().operationComplete(sent);
            raw.join();
            assertEquals(List.of("move", "part-old", "part-pos"), order.subList(order.size() - 3, order.size()));
            verify(closing).removeListener(any(io.netty.channel.ChannelFutureListener.class));
        }
    }

    @Test
    void actualForeignPartOwnerWaitsBeforeTwentyPercentAwardAndRemoval() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var running = new AtomicReference<Entity>(dragon);
        var craft = mock(org.bukkit.craftbukkit.entity.CraftComplexPart.class);
        when(part.getBukkitEntity()).thenReturn(craft);
        var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
        field(craft, org.bukkit.craftbukkit.entity.CraftEntity.class, "taskScheduler", scheduler);
        when(scheduler.schedule(any(), any(), eq(1L))).thenAnswer(call -> {
            Consumer<Entity> task = call.getArgument(0);
            queue.add(() -> {
                running.set(part);
                task.accept(part);
                running.set(dragon);
            });
            return true;
        });
        doAnswer(call -> {
            assertSame(part, running.get());
            order.add("part-old");
            return null;
        }).when(part).setOldPosAndRot();
        try (var owners = new Owners()) {
            owners.ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> call.getArgument(0) != part || running.get() == part);
            var raw = tick();
            assertEquals(List.of("advance200", "xp8", "move"), order);
            assertFalse(raw.isDone());
            while (!queue.isEmpty()) queue.remove().run();
            raw.join();
            assertTrue(order.indexOf("part-pos") < order.indexOf("xp20"));
            assertEquals("event", order.getLast());
        }
    }
}

