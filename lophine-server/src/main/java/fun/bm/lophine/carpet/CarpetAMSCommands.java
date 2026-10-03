// SPDX-License-Identifier: LGPL-3.0-only
// Server adaptation of Carpet AMS Addition commands, revision 750310179368b2569dd6121a2769b2fb1bbc7343.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CompletableFuture;

public final class CarpetAMSCommands {
    private CarpetAMSCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("getHeldItemID")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandGetHeldItemID))
                .executes(context -> heldItem(context.getSource())));
        dispatcher.register(Commands.literal("getSystemInfo")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandGetSystemInfo))
                .executes(context -> systemInfo(context.getSource())));
        dispatcher.register(Commands.literal("getSaveSize")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandGetSaveSize))
                .executes(context -> saveSize(context.getSource())));
        dispatcher.register(Commands.literal("getPlayerSkull")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandGetPlayerSkull))
                .then(Commands.argument("player", StringArgumentType.string())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getServer().getPlayerNames(), builder))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(context -> skull(context.getSource(), StringArgumentType.getString(context, "player"), IntegerArgumentType.getInteger(context, "count"))))));
        dispatcher.register(Commands.literal("here")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandHere))
                .executes(context -> here(context.getSource())));
        dispatcher.register(Commands.literal("where")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandWhere))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> where(context.getSource(), EntityArgument.getPlayer(context, "player")))));
        dispatcher.register(Commands.literal("goto")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandGoto))
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                        .executes(context -> goTo(context.getSource(), DimensionArgument.getDimension(context, "dimension"), null))
                        .then(Commands.argument("destination", BlockPosArgument.blockPos())
                                .executes(context -> goTo(context.getSource(), DimensionArgument.getDimension(context, "dimension"),
                                        BlockPosArgument.getSpawnablePos(context, "destination"))))));
    }

    private static int heldItem(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return OrgCommandNativeEffects.command(source, 1, () ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(player, () -> {
                    String id = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString().replaceAll(".*?(minecraft:[a-z_]+).*", "$1");
                    Component button = Component.literal(" [C] ").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent.CopyToClipboard(id))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(AmsTranslations.message(source, "command.commandGetHeldItemID.getHeldItemID.copy").withStyle(ChatFormatting.YELLOW))));
                    return Component.literal("<commandGetHeldItemID> ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(id).withStyle(ChatFormatting.GREEN)).append(button);
                }), message -> AmsNativeCommandEffects.then(CarpetMessenger.sendAsync(source, java.util.List.of(message)), ignored -> CompletableFuture.completedFuture(1))));
    }

    private static int skull(CommandSourceStack source, String name, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return OrgCommandNativeEffects.command(source, 1, () -> AmsNativeCommandEffects.owned(player, () -> {
            ItemStack skull = new ItemStack(Items.PLAYER_HEAD, count);
            ResolvableProfile profile = DataComponents.PROFILE.codecOrThrow().parse(net.minecraft.nbt.NbtOps.INSTANCE,
                    net.minecraft.nbt.StringTag.valueOf(name)).getOrThrow();
            skull.set(DataComponents.PROFILE, profile);
            player.addItem(skull);
            return 1;
        }));
    }

    private static int systemInfo(CommandSourceStack source) {
        return OrgCommandNativeEffects.command(source, 1, () -> AmsNativeCommandEffects.then(AmsNativeCommandEffects.source(source, () -> {
            Runtime runtime = Runtime.getRuntime();
            var os = ManagementFactory.getOperatingSystemMXBean();
            return Component.literal("===================================\n")
                    .append(AmsTranslations.message(source, "command.getSystemInfo.os", os.getName() + " - " + os.getVersion())).append("\n")
                    .append(AmsTranslations.message(source, "command.getSystemInfo.os_arch", os.getArch())).append("\n")
                    .append(AmsTranslations.message(source, "command.getSystemInfo.available_processors", String.valueOf(runtime.availableProcessors()))).append("\n")
                    .append(AmsTranslations.message(source, "command.getSystemInfo.max_memory", runtime.maxMemory() / 1024 / 1024 + "MB")).append("\n")
                    .append(AmsTranslations.message(source, "command.getSystemInfo.total_memory", runtime.totalMemory() / 1024 / 1024 + "MB")).append("\n")
                    .append(AmsTranslations.message(source, "command.getSystemInfo.free_memory", runtime.freeMemory() / 1024 / 1024 + "MB")).append("\n")
                    .append("===================================\n").withStyle(ChatFormatting.DARK_AQUA);
        }), message -> AmsNativeCommandEffects.then(CarpetMessenger.sendAsync(source, java.util.List.of(message)), ignored -> CompletableFuture.completedFuture(1))));
    }

    private static int saveSize(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return OrgCommandNativeEffects.command(source, 1, () ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(player, () -> player.level().getServer()), server ->
                        AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(server, () -> server.saveEverything(false, true, true)), saved ->
                                AmsNativeCommandEffects.then(AmsNativeCommandEffects.source(source, () ->
                                        AmsTranslations.message(source, "command.getSaveSize." + (saved ? "save_success_msg" : "save_fail_msg")).withStyle(ChatFormatting.GRAY)), message ->
                                        AmsNativeCommandEffects.then(CarpetMessenger.sendAsync(source, java.util.List.of(message)), reply ->
                                                AmsNativeCommandEffects.then(OrgCommandNativeEffects.file(server, () -> folderSize(server.getWorldPath(LevelResource.ROOT).toFile())), size ->
                                                        AmsNativeCommandEffects.then(AmsNativeCommandEffects.source(source, () -> Component.literal("§e" + AmsTranslations.message(source, "command.getSaveSize.size_msg").getString()
                                                                + " §a§l§n" + String.format("%.3f GB", size / (1024.0 * 1024.0 * 1024.0)))), result ->
                                                                AmsNativeCommandEffects.then(CarpetMessenger.sendAsync(source, java.util.List.of(result)), ignored -> CompletableFuture.completedFuture(1)))))))));
    }

    private static long folderSize(java.io.File folder) {
        long length = 0;
        java.io.File[] files = folder.listFiles();
        if (files != null) for (java.io.File file : files) length += file.isFile() ? file.length() : folderSize(file);
        return length;
    }

    private static int here(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return OrgCommandNativeEffects.command(source, 1, () ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.source(source, () -> location(source, "here", source.getTextName(), source.getLevel(), source.getPosition(), false)), message ->
                        AmsNativeCommandEffects.then(CarpetMessenger.print_server_messageAsync(source.getServer(), message), ignored ->
                                AmsNativeCommandEffects.owned(player, () -> {
                                    player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 600));
                                    return 1;
                                }))));
    }

    private static int where(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer sender = source.getPlayerOrException();
        return OrgCommandNativeEffects.command(source, 1, () ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () ->
                        location(source, "where", target.getGameProfile().name(), (ServerLevel) target.level(), new Vec3(target.getX(), target.getY(), target.getZ()), true)), message ->
                        AmsNativeCommandEffects.then(CarpetMessenger.sendAsync(sender, java.util.List.of(message)), reply ->
                                AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(sender, () -> sender.getGameProfile().name()), senderName ->
                                        AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () -> target.getGameProfile().name()), targetName ->
                                                AmsNativeCommandEffects.then(AmsNativeCommandEffects.source(source, () -> Component.literal(AmsTranslations.message(source, "command.where.who_get_who", senderName, targetName).getString()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)), query ->
                                                        AmsNativeCommandEffects.then(CarpetMessenger.print_server_messageAsync(source.getServer(), query), ignored ->
                                                                AmsNativeCommandEffects.owned(target, () -> {
                                                                    target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 600));
                                                                    return 1;
                                                                }))))))));
    }

    private static MutableComponent location(CommandSourceStack source, String command, String name, ServerLevel level, Vec3 position, boolean where) {
        int x = (int) position.x, y = (int) position.y, z = (int) position.z;
        String current = String.format("%d, %d, %d", x, y, z);
        String other = level.dimension() == Level.NETHER ? String.format("%d, %d, %d", x * 8, y, z * 8) : String.format("%d, %d, %d", x / 8, y, z / 8);
        if (level.dimension() == Level.END)
            return Component.literal(String.format("§d[%s] §e%s §b@ §d[ %s ]", AmsTranslations.message(source, "command." + command + ".the_end").getString(), name, current))
                    .append(copyButton(source, command, current, "the_end", ChatFormatting.LIGHT_PURPLE)).append(highlightButton(source, current));
        if (level.dimension() == Level.OVERWORLD)
            return Component.literal(String.format("§2[%s] §e%s §b@ §2[ %s ] §b-> §4[ %s ]", AmsTranslations.message(source, "command." + command + ".overworld").getString(), name, current, other))
                    .append(copyButton(source, command, current, "overworld", ChatFormatting.GREEN)).append(copyButton(source, command, other, "nether", ChatFormatting.DARK_RED)).append(highlightButton(source, current));
        if (level.dimension() == Level.NETHER)
            return Component.literal(String.format("§4[%s] §e%s §b@ §4[ %s ] §b-> §2[ %s ]", AmsTranslations.message(source, "command." + command + ".nether").getString(), name, current, other))
                    .append(copyButton(source, command, current, "nether", ChatFormatting.DARK_RED)).append(copyButton(source, command, other, "overworld", ChatFormatting.GREEN)).append(highlightButton(source, where ? other : current));
        return Component.literal("Unknown dimension").withStyle(ChatFormatting.RED);
    }

    private static MutableComponent copyButton(CommandSourceStack source, String command, String coordinates, String dimension, ChatFormatting color) {
        return Component.literal(" [C]").withStyle(style -> style.withColor(color).withBold(true)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.CopyToClipboard(coordinates.replace(",", "")))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(AmsTranslations.message(source, "command." + command + "." + dimension + "_button_hover").withStyle(ChatFormatting.YELLOW))));
    }

    private static MutableComponent highlightButton(CommandSourceStack source, String coordinates) {
        return Component.literal(" [+H]").withStyle(style -> style.withColor(ChatFormatting.YELLOW).withBold(true)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/coordCompass set " + coordinates.replace(",", "")))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(AmsTranslations.message(source, "fuzz.command.highlightCoordButtonHoverText").withStyle(ChatFormatting.YELLOW))));
    }

    private static BlockPos gotoPosition(ServerPlayer player, ServerLevel destination, BlockPos explicit) {
        if (explicit != null) return explicit;
        if (player.level().dimension() == Level.OVERWORLD && destination.dimension() == Level.NETHER)
            return new BlockPos((int) (player.getX() / 8), (int) player.getY(), (int) (player.getZ() / 8));
        if (player.level().dimension() == Level.NETHER && destination.dimension() == Level.OVERWORLD)
            return new BlockPos((int) (player.getX() * 8), (int) player.getY(), (int) (player.getZ() * 8));
        return player.blockPosition();
    }

    private static int goTo(CommandSourceStack source, ServerLevel destination, BlockPos position) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return OrgCommandNativeEffects.command(source, 1, () ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(player, () -> {
                    BlockPos target = gotoPosition(player, destination, position);
                    return new org.bukkit.Location(destination.getWorld(), target.getX(), target.getY(), target.getZ(), player.getViewXRot(1), 1);
                }), target -> AmsNativeCommandEffects.then(OrgCommandNativeEffects.teleport(player, target, () -> {
                }), ignored -> CompletableFuture.completedFuture(1))));
    }
}
