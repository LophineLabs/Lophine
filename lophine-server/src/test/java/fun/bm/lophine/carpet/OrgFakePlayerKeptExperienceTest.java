package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.ServerBot;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class OrgFakePlayerKeptExperienceTest {
    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private static ServerBot bot(boolean nativePlayer) throws Exception {
        var bot = mock(ServerBot.class);
        var world = mock(ServerLevel.class);
        when(bot.level()).thenReturn(world);
        bot.carpetNativePlayer = nativePlayer;
        bot.experienceLevel = 10;
        when(bot.getSoundSource()).thenReturn(net.minecraft.sounds.SoundSource.PLAYERS);
        Method base = ServerBot.class.getDeclaredMethod("getBaseExperienceReward", ServerLevel.class);
        base.setAccessible(true);
        when((Integer) base.invoke(bot, world)).thenCallRealMethod();
        when(bot.getExperienceReward(world, null)).thenCallRealMethod();
        when(bot.getExpReward(world, null)).thenCallRealMethod();
        Method always = Player.class.getDeclaredMethod("isAlwaysExperienceDropper");
        always.setAccessible(true);
        when((Boolean) always.invoke(bot)).thenCallRealMethod();
        return bot;
    }

    private static int reward(ServerBot bot) {
        return bot.getExpReward(bot.level(), null);
    }

    @Test
    void nativeKeptDeathCreatesAnActualDeathEventWithZeroRewardAndNoFalseDropFlagAssumption() throws Exception {
        boolean prior = GeneralCompatConfig.fakePlayerKeepInventory;
        String condition = GeneralCompatConfig.fakePlayerKeepInventoryCondition;
        GeneralCompatConfig.fakePlayerKeepInventory = true;
        GeneralCompatConfig.fakePlayerKeepInventoryCondition = "unconditional";
        try (var types = mockStatic(org.bukkit.craftbukkit.damage.CraftDamageType.class)) {
            var bot = bot(true);
            var source = mock(DamageSource.class);
            when(source.typeHolder()).thenReturn(Holder.direct(new DamageType("test", 0F)));
            when(bot.getLastDamageSource()).thenReturn(source);
            boolean keep = OrgFakePlayerInventory.shouldKeepInventory(bot, source);
            assertTrue(keep);
            bot.keepLevel = keep;
            var bukkit = mock(org.leavesmc.leaves.entity.bot.CraftBot.class);
            when(bot.getBukkitEntity()).thenReturn(bukkit);
            var bukkitMax = mock(org.bukkit.attribute.AttributeInstance.class);
            when(bukkitMax.getValue()).thenReturn(20D);
            when(bukkit.getAttribute(any(org.bukkit.attribute.Attribute.class))).thenReturn(bukkitMax);
            var max = mock(AttributeInstance.class);
            when(max.getValue()).thenReturn(20D);
            when(bot.getAttribute(Attributes.MAX_HEALTH)).thenReturn(max);
            types.when(() -> org.bukkit.craftbukkit.damage.CraftDamageType.minecraftHolderToBukkit(any())).thenReturn(null);
            var event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerDeathEvent(bot, source, new java.util.ArrayList<>(), net.kyori.adventure.text.Component.empty(), true, keep);
            assertTrue(event.getKeepInventory());
            assertTrue(event.getKeepLevel());
            assertTrue(event.shouldDropExperience());
            assertEquals(0, event.getDroppedExp());
            assertEquals(0, bot.expToDrop);
        } finally {
            GeneralCompatConfig.fakePlayerKeepInventory = prior;
            GeneralCompatConfig.fakePlayerKeepInventoryCondition = condition;
        }
    }

    @Test
    void nativeAndLegacyRewardsRespectTheExactEnabledConditionAndDisabledBoundaries() throws Exception {
        boolean prior = GeneralCompatConfig.fakePlayerKeepInventory;
        String condition = GeneralCompatConfig.fakePlayerKeepInventoryCondition;
        try {
            for (boolean nativePlayer : new boolean[]{false, true}) {
                var bot = bot(nativePlayer);
                GeneralCompatConfig.fakePlayerKeepInventory = false;
                bot.keepLevel = false;
                assertEquals(70, reward(bot));
                bot.keepLevel = true;
                assertEquals(nativePlayer ? 0 : 70, reward(bot));
                bot.keepLevel = false;
                GeneralCompatConfig.fakePlayerKeepInventory = true;
                GeneralCompatConfig.fakePlayerKeepInventoryCondition = "unconditional";
                assertEquals(0, reward(bot));
                GeneralCompatConfig.fakePlayerKeepInventoryCondition = "killed_by_player_or_the_void";
                var source = mock(DamageSource.class);
                when(bot.getLastDamageSource()).thenReturn(source);
                assertEquals(70, reward(bot));
                when(source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)).thenReturn(true);
                assertEquals(0, reward(bot));
                when(source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)).thenReturn(false);
                when(source.getDirectEntity()).thenReturn(mock(ServerPlayer.class));
                assertEquals(0, reward(bot));
                when(source.getDirectEntity()).thenReturn(null);
                when(source.getEntity()).thenReturn(mock(ServerPlayer.class));
                assertEquals(0, reward(bot));
                when(source.getEntity()).thenReturn(null);
                when(bot.getKillCredit()).thenReturn(mock(ServerPlayer.class));
                assertEquals(0, reward(bot));
                when(bot.getKillCredit()).thenReturn(null);
                GeneralCompatConfig.fakePlayerKeepInventory = false;
                assertEquals(70, reward(bot));
            }
        } finally {
            GeneralCompatConfig.fakePlayerKeepInventory = prior;
            GeneralCompatConfig.fakePlayerKeepInventoryCondition = condition;
        }
    }

    @Test
    void spectatorRewardAndVanillaLevelCapRemainIntact() throws Exception {
        boolean prior = GeneralCompatConfig.fakePlayerKeepInventory;
        GeneralCompatConfig.fakePlayerKeepInventory = false;
        try {
            var bot = bot(false);
            bot.experienceLevel = 30;
            assertEquals(100, reward(bot));
            when(bot.isSpectator()).thenReturn(true);
            assertEquals(0, reward(bot));
        } finally {
            GeneralCompatConfig.fakePlayerKeepInventory = prior;
        }
    }
}
