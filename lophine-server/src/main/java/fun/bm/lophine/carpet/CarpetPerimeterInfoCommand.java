// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.arguments.ResourceArgument.getSummonableEntityType;
import static net.minecraft.commands.arguments.ResourceArgument.resource;

public class CarpetPerimeterInfoCommand
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("perimeterinfo").
                requires((player) -> CarpetCommandPermissions.canUse(player, fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandPerimeterInfo)).
                executes( (c) -> perimeterDiagnose(
                        c.getSource(),
                        BlockPos.containing(c.getSource().getPosition()),
                        null)).
                then(argument("center position", BlockPosArgument.blockPos()).
                        executes( (c) -> perimeterDiagnose(
                                c.getSource(),
                                BlockPosArgument.getSpawnablePos(c, "center position"),
                                null)).
                        then(argument("mob", resource(commandBuildContext, Registries.ENTITY_TYPE)).
                                suggests(SuggestionProviders.cast(SuggestionProviders.SUMMONABLE_ENTITIES)).
                                executes( (c) -> perimeterDiagnose(
                                        c.getSource(),
                                        BlockPosArgument.getSpawnablePos(c, "center position"),
                                        getSummonableEntityType(c, "mob").key().identifier().toString()
                                ))));
        dispatcher.register(command);
    }

    private record Report(CarpetPerimeterDiagnostics.Result spots, net.minecraft.network.chat.Component mob) {}
    private static int perimeterDiagnose(CommandSourceStack source, BlockPos pos, String mobId) {
        var level = source.getLevel();
        var completion = CarpetAsyncCommandResults.defer(source);
        CarpetRegionLease.<Report>runValue(level, (pos.getX() - 144) >> 4, (pos.getZ() - 144) >> 4,
            (pos.getX() + 144) >> 4, (pos.getZ() + 144) >> 4, lease -> {
                Mob mob = null;
                if (mobId != null) {
                    CompoundTag tag = new CompoundTag(); tag.putString("id", mobId);
                    Entity entity = EntityType.loadEntityRecursive(tag, level, new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.COMMAND, true), created -> {
                        created.snapTo(new BlockPos(pos.getX(), level.getMinY() - 10, pos.getZ()), created.getYRot(), created.getXRot());
                        return created;
                    });
                    if (!(entity instanceof Mob created)) { if (entity != null) entity.discard(); throw new IllegalArgumentException("/perimeterinfo requires a mob entity to test against"); }
                    mob = created;
                }
                try { return new Report(CarpetPerimeterDiagnostics.countSpots(level, pos, mob), mob == null ? null : mob.getDisplayName().copy()); }
                finally { if (mob != null) mob.discard(); }
            }).whenComplete((report, failure) -> {
                if (failure != null) { CarpetMessenger.m(source, "r Perimeter scan failed: " + failure.getMessage()); completion.complete(false, 0); return; }
                var lines = new java.util.ArrayList<net.minecraft.network.chat.Component>();
                lines.add(CarpetMessenger.c("w Spawning spaces around ", CarpetMessenger.tp("c", pos), "w :"));
                lines.add(CarpetMessenger.c("w   potential in-liquid: ", "wb " + report.spots.liquid));
                lines.add(CarpetMessenger.c("w   potential on-ground: ", "wb " + report.spots.ground));
                if (report.mob != null) {
                    lines.add(CarpetMessenger.c("w   ", report.mob, "w : ", "wb " + report.spots.specific));
                    report.spots.samples.forEach(sample -> lines.add(CarpetMessenger.c("w   ", CarpetMessenger.tp("c", sample))));
                }
                CarpetMessenger.send(source, lines);
                completion.complete(true, 1);
            });
        return 1;
    }
}
