package fun.bm.lophine.carpet.config.modules;

import fun.bm.lophine.carpet.CarpetProtocalDataBase;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

import java.util.List;

@ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "general", directory = {"carpet"})
public class GeneralCompatConfig implements IConfigModule {
    @ConfigInfo(name = "language")
    public static String language = "en_us";

    @ConfigInfo(name = "amsUpdateSuppressionCrashFix")
    public static boolean amsUpdateSuppressionCrashFix = false;

    @ConfigInfo(name = "yeetUpdateSuppressionCrash")
    public static boolean yeetUpdateSuppressionCrash = false;

    @ConfigInfo(name = "dustTrapdoorReintroduced")
    public static boolean dustTrapdoorReintroduced = false;

    @ConfigInfo(name = "shulkerBoxCCEReintroduced")
    public static boolean shulkerBoxCCEReintroduced = false;

    @ConfigInfo(name = "instantBlockUpdaterReintroduced")
    public static boolean instantBlockUpdaterReintroduced = false;

    @ConfigInfo(name = "commandTick")
    public static boolean commandTick = false;

    @ConfigInfo(name = "creativeNoClip")
    public static boolean creativeNoClip = false;

    @ConfigInfo(name = "optimizedDragonRespawn")
    public static boolean optimizedDragonRespawn = false;

    @ConfigInfo(name = "antiSpamDisabled")
    public static boolean antiSpamDisabled = false;

    @ConfigInfo(name = "blockPlacementIgnoreEntity")
    public static boolean blockPlacementIgnoreEntity = false;

    @ConfigInfo(name = "creativeOpenContainerForcibly")
    public static boolean creativeOpenContainerForcibly = false;

    @ConfigInfo(name = "creativeOneHitKill")
    public static boolean creativeOneHitKill = false;

    @ConfigInfo(name = "observerNoDetection")
    public static boolean observerNoDetection = false;

    @ConfigInfo(name = "bambooModelNoOffset")
    public static boolean bambooModelNoOffset = false;

    @ConfigInfo(name = "creativeNoItemCooldown")
    public static boolean creativeNoItemCooldown = false;

    @ConfigInfo(name = "ctrlQCraftingFix")
    public static boolean ctrlQCraftingFix = false;

    @ConfigInfo(name = "carpetAlwaysSetDefault")
    public static boolean carpetAlwaysSetDefault = false;

    @ConfigInfo(name = "placementRotationFix")
    public static boolean placementRotationFix = false;

    @ConfigInfo(name = "tntDoNotUpdate")
    public static boolean tntDoNotUpdate = false;

    @ConfigInfo(name = "totallyNoBlockUpdate")
    public static boolean totallyNoBlockUpdate = false;

    @ConfigInfo(name = "tiscmNetworkProtocol")
    public static boolean tiscmNetworkProtocol = false;

    @ConfigInfo(name = "hopperNoItemCost")
    public static boolean hopperNoItemCost = false;

    @ConfigInfo(name = "explosionNoBlockDamage")
    public static boolean explosionNoBlockDamage = false;

    @ConfigInfo(name = "noCreeperBlockBreaking")
    public static boolean noCreeperBlockBreaking = false;

    @ConfigInfo(name = "noGhastBlockBreaking")
    public static boolean noGhastBlockBreaking = false;

    @ConfigInfo(name = "disableBlazeFire")
    public static boolean disableBlazeFire = false;

    @ConfigInfo(name = "disableGhastFire")
    public static boolean disableGhastFire = false;

    @ConfigInfo(name = "optimizedTNTHighPriority")
    public static boolean optimizedTNTHighPriority = false;

    @ConfigInfo(name = "tntPrimerMomentumRemoved")
    public static boolean tntPrimerMomentumRemoved = false;

    @ConfigInfo(name = "tntIgnoreRedstoneSignal")
    public static boolean tntIgnoreRedstoneSignal = false;

    @ConfigInfo(name = "tntDupingFix")
    public static boolean tntDupingFix = false;

    @ConfigInfo(name = "interactionUpdates")
    public static boolean interactionUpdates = true;

    @ConfigInfo(name = "xpNoCooldown")
    public static boolean xpNoCooldown = false;

    @ConfigInfo(name = "powerfulExpMending")
    public static boolean powerfulExpMending = false;

    @ConfigInfo(name = "clientSettingsLostOnRespawnFix")
    public static boolean clientSettingsLostOnRespawnFix = false;

    @ConfigInfo(name = "sensibleEnderman")
    public static boolean sensibleEnderman = false;

    @ConfigInfo(name = "entityInstantDeathRemoval")
    public static boolean entityInstantDeathRemoval = false;

    @ConfigInfo(name = "farmlandTrampledDisabled")
    public static boolean farmlandTrampledDisabled = false;

    @ConfigInfo(name = "shulkerGolem")
    public static boolean shulkerGolem = false;

    @ConfigInfo(name = "preventEndSpikeRespawn")
    public static boolean preventEndSpikeRespawn = false;

    @ConfigInfo(name = "yeetOutOfOrderChatKick")
    public static boolean yeetOutOfOrderChatKick = false;

    @ConfigInfo(name = "betterCraftableBoneBlock")
    public static boolean betterCraftableBoneBlock = false;

    @ConfigInfo(name = "betterCraftableDispenser")
    public static boolean betterCraftableDispenser = false;

    @ConfigInfo(name = "viewDistance")
    public static int viewDistance = 12;

    @ConfigInfo(name = "tickCommandPermission")
    public static int tickCommandPermission = 3;

    @ConfigInfo(name = "tickFreezeCommandToggleable")
    public static boolean tickFreezeCommandToggleable = false;

    @ConfigInfo(name = "syncServerMsptMetricsData")
    public static boolean syncServerMsptMetricsData = false;

    @ConfigInfo(name = "simpleInGameCalculator")
    public static boolean simpleInGameCalculator = false;

    @ConfigInfo(name = "microTiming")
    public static boolean microTiming = false;

    @ConfigInfo(name = "fastRedstoneDust")
    public static boolean fastRedstoneDust = false;

    @ConfigInfo(name = "lagFreeSpawning")
    public static boolean lagFreeSpawning = false;

    @ConfigInfo(name = "optimizedFastEntityMovement")
    public static boolean optimizedFastEntityMovement = false;

    @ConfigInfo(name = "optimizedHardHitBoxEntityCollision")
    public static boolean optimizedHardHitBoxEntityCollision = false;

    @ConfigInfo(name = "tntFuseDuration")
    public static int tntFuseDuration = 80;

    @ConfigInfo(name = "defaultLoggers")
    public static List<String> defaultLoggers = List.of();

    public static boolean mergedUpdateSuppressionCrashEnabled() {
        return amsUpdateSuppressionCrashFix || yeetUpdateSuppressionCrash;
    }

    public static int normalizedTntFuseDuration() {
        return Math.clamp(tntFuseDuration, 0, Short.MAX_VALUE);
    }

    public static int normalizedTickCommandPermission() {
        return Math.clamp(tickCommandPermission, 0, 4);
    }

    @Override
    public void beforeFinalLoad() {
        // send changes to client
        CarpetProtocalDataBase.apply();
    }
}
