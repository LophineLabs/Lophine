package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.configuration.type.number.IntOr;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarpetTntSourceOrderTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    private static ServerLevel world() throws Exception {
        var world = mock(ServerLevel.class);
        var spigot = mock(org.spigotmc.SpigotWorldConfig.class);
        var field = Level.class.getField("spigotConfig"); field.setAccessible(true); field.set(world, spigot);
        var paper = mock(WorldConfiguration.class);
        paper.entities = mock(WorldConfiguration.Entities.class);
        paper.entities.spawning = mock(WorldConfiguration.Entities.Spawning.class);
        paper.entities.spawning.despawnTime = new Reference2ObjectOpenHashMap<>();
        paper.fixes = mock(WorldConfiguration.Fixes.class);
        paper.fixes.tntEntityHeightNerf = IntOr.Disabled.DISABLED;
        when(world.paperConfig()).thenReturn(paper);
        when(world.getServer()).thenReturn(mock(MinecraftServer.class));
        return world;
    }

    private static PrimedTnt tickBody(ServerLevel world, AtomicReference<Vec3> motion) throws Exception {
        var tnt = mock(PrimedTnt.class, call -> Set.of("applyGravity", "applyEffectsFromBlocks", "updateFluidInteraction", "setFuse", "setRequiresPrecisePosition").contains(call.getMethod().getName())
            ? null : call.getMethod().getName().equals("getAirDrag") ? 1.0F : CALLS_REAL_METHODS.answer(call));
        doReturn(world).when(tnt).level();
        doReturn(80).when(tnt).getFuse();
        doReturn(new AABB(0, 0, 0, 1, 1, 1)).when(tnt).getBoundingBox();
        doReturn(0D).when(tnt).getX(); doReturn(0D).when(tnt).getY(); doReturn(0D).when(tnt).getZ();
        doAnswer(call -> motion.get()).when(tnt).getDeltaMovement();
        doAnswer(call -> { motion.set(call.getArgument(0)); return null; }).when(tnt).setDeltaMovement(any(Vec3.class));
        doNothing().when(tnt).move(any(MoverType.class), any(Vec3.class));
        set(tnt, "carpetMergedTnt", 1);
        return tnt;
    }
    private static void set(PrimedTnt tnt, String name, Object value) throws Exception {
        var field = PrimedTnt.class.getDeclaredField(name); field.setAccessible(true); field.set(tnt, value);
    }
    private static Object get(PrimedTnt tnt, String name) throws Exception {
        var field = PrimedTnt.class.getDeclaredField(name); field.setAccessible(true); return field.get(tnt);
    }

    @Test void actualConstructorAppliesMomentumRemovalAfterHardcodedAngle() throws Exception {
        boolean oldMomentum = GeneralCompatConfig.tntPrimerMomentumRemoved;
        double oldAngle = GeneralCompatConfig.hardcodeTNTangle;
        try {
            var world = world();
            GeneralCompatConfig.hardcodeTNTangle = 1.234D;
            GeneralCompatConfig.tntPrimerMomentumRemoved = false;
            var angled = new PrimedTnt(world, 0, 64, 0, null);
            assertEquals(new Vec3(-Math.sin(1.234D) * 0.02D, 0.2D, -Math.cos(1.234D) * 0.02D), angled.getDeltaMovement());
            GeneralCompatConfig.tntPrimerMomentumRemoved = true;
            var removed = new PrimedTnt(world, 0, 64, 0, null);
            assertEquals(new Vec3(0D, 0.20000000298023224D, 0D), removed.getDeltaMovement());
            assertEquals(removed.getDeltaMovement(), removed.carpetExplosionPrimedMotion);
        } finally { GeneralCompatConfig.tntPrimerMomentumRemoved = oldMomentum; GeneralCompatConfig.hardcodeTNTangle = oldAngle; }
    }

    @Test void actualTickRetainsMovementEligibilityWhileRuleIsDisabledAndMergesOnlyAtGroundDamping() throws Exception {
        boolean old = GeneralCompatConfig.mergeTNT;
        try (var ticks = mockStatic(TickThread.class)) {
            var world = world(); var motion = new AtomicReference<>(new Vec3(1, 0, 0)); var tnt = tickBody(world, motion);
            var other = mock(PrimedTnt.class); when(other.getFuse()).thenReturn(80); when(other.getDeltaMovement()).thenReturn(Vec3.ZERO);
            set(other, "carpetMergedTnt", 3); ticks.when(() -> TickThread.isTickThreadFor(other)).thenReturn(true);
            when(world.getEntities(tnt, tnt.getBoundingBox())).thenReturn(List.of(other));
            doReturn(false).when(tnt).onGround(); GeneralCompatConfig.mergeTNT = false;
            tnt.tick(); assertEquals(true, get(tnt, "carpetTntMergeEligible"));
            motion.set(Vec3.ZERO); GeneralCompatConfig.mergeTNT = true;
            tnt.tick(); verify(world, never()).getEntities(any(Entity.class), any(AABB.class)); verify(other, never()).discard();
            doReturn(true).when(tnt).onGround(); tnt.tick();
            verify(other).discard(); assertEquals(4, get(tnt, "carpetMergedTnt")); assertEquals(false, get(tnt, "carpetTntMergeEligible"));
        } finally { GeneralCompatConfig.mergeTNT = old; }
    }

    @Test void actualMergeChecksCandidateOwnershipBeforeMutableEntityState() throws Exception {
        boolean old = GeneralCompatConfig.mergeTNT;
        try (var ticks = mockStatic(TickThread.class)) {
            var world = world(); var tnt = tickBody(world, new AtomicReference<>(Vec3.ZERO)); var other = mock(PrimedTnt.class);
            doReturn(true).when(tnt).onGround(); set(tnt, "carpetTntMergeEligible", true); GeneralCompatConfig.mergeTNT = true;
            when(world.getEntities(tnt, tnt.getBoundingBox())).thenReturn(List.of(other));
            ticks.when(() -> TickThread.isTickThreadFor(other)).thenReturn(false); tnt.tick();
            verify(other, never()).isRemoved(); verify(other, never()).getX(); verify(other, never()).getDeltaMovement(); verify(other, never()).discard();
        } finally { GeneralCompatConfig.mergeTNT = old; }
    }
}
