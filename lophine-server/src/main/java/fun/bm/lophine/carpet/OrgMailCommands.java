// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

public final class OrgMailCommands {
    private OrgMailCommands() {}
    private static OrgMailService service(CommandContext<CommandSourceStack> context){return OrgMailService.get(context.getSource().getServer());}
    private record Outcome(Object value, Throwable failure) { }
    private static <T> int report(CommandSourceStack source, Supplier<CompletableFuture<T>> operation) {
        var completion = CarpetAsyncCommandResults.defer(source);
        var actual = OrgMenuNativeEffects.admit(source.getServer(), operation::get);
        var outcome = actual.handle((value, failure) -> new Outcome(value, failure));
        var delivered = TisCommandContinuations.then(outcome, result -> {
            if (result.failure() == null && !Boolean.FALSE.equals(result.value())) return CompletableFuture.completedFuture(true);
            Throwable failure = result.failure();
            while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) failure = failure.getCause();
            Component text = failure instanceof OrgMailPresentation.Failure sourceFailure?sourceFailure.component:Component.literal(failure == null
                ? "Mail operation cancelled; retry after the inventory transaction finishes" : "Mail: " + failure.getMessage());
            return feedback(source, () -> { send(source, text); return false; });
        });
        var finished = delivered.whenComplete(ScarpetRuntime.captureNativeConsumer((success, failure) -> {
            boolean accepted=failure==null&&Boolean.TRUE.equals(success);Object value=outcome.getNow(new Outcome(null,null)).value();
            completion.complete(accepted,accepted?(value instanceof Number number?number.intValue():1):0);
        }));
        ScarpetNativeWork.record(finished);
        return 1;
    }
    private static <T> CompletableFuture<T> feedback(CommandSourceStack source, Supplier<T> body) {
        return source.getEntity() == null && source.getLevel() == null
            ? OrgCommandNativeEffects.global(source.getServer(), body) : TisCommandContinuations.feedback(source, body);
    }
    private static void send(CommandSourceStack source, Component text) {
        ServerPlayer viewer = source.getPlayer();
        if (viewer == null) source.sendSuccess(() -> text, false); else viewer.sendSystemMessage(text);
    }
    private static NameAndId recipient(CommandContext<CommandSourceStack> context)throws CommandSyntaxException {
        var profiles=GameProfileArgument.getGameProfiles(context,"player");if(profiles.isEmpty())throw GameProfileArgument.ERROR_UNKNOWN_PLAYER.create();
        if(profiles.size()>1){var hover=Component.empty();for(var profile:profiles){if(!hover.getSiblings().isEmpty())hover.append("\n");hover.append(Component.empty().append(net.minecraft.world.entity.EntityTypes.PLAYER.getDescription()).append(": ").append(profile.name()));}
            String key="carpet-org-addition.argument.player.toomany";throw new SimpleCommandExceptionType(Component.translatableWithFallback(key,OrgRuleTranslations.text(key,"Only one player is allowed" )).withStyle(style->style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(hover)))).create();}
        return profiles.iterator().next();
    }
    private static SuggestionProvider<CommandSourceStack> ids(OrgMailService.Operation operation){return(context,builder)->{ServerPlayer player=context.getSource().getPlayer();if(player==null)return builder.buildFuture();return service(context).numbers(player.getScoreboardName(),operation).thenCompose(ids->SharedSuggestionProvider.suggest(ids.stream().map(Object::toString),builder));};}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var root=Commands.literal("mail").requires(source->OrgUtilityCommands.permitted(source,GeneralCompatConfig.commandMail));
        root.then(Commands.literal("send").then(Commands.argument("player",GameProfileArgument.gameProfile()).executes(context->{var player=context.getSource().getPlayerOrException();var receiver=recipient(context);return report(context.getSource(),()->service(context).send(player,receiver));})));
        root.then(Commands.literal("multiple").then(Commands.argument("player",GameProfileArgument.gameProfile()).executes(context->{var player=context.getSource().getPlayerOrException();var receiver=recipient(context);return report(context.getSource(),()->service(context).multiple(player,receiver));})));
        for(var operation:OrgMailService.Operation.values()){
            var node=Commands.literal(operation.name().toLowerCase(java.util.Locale.ROOT));if(operation==OrgMailService.Operation.INTERCEPT)node.requires(source->OrgServerPermissions.allowed(source,"mail.intercept"));
            node.then(Commands.argument("id",IntegerArgumentType.integer(1)).suggests(ids(operation)).executes(context->{var player=context.getSource().getPlayerOrException();int id=IntegerArgumentType.getInteger(context,"id");return report(context.getSource(),()->TisCommandContinuations.then(service(context).take(player,id,operation),success->CompletableFuture.completedFuture(success?(operation==OrgMailService.Operation.INTERCEPT?id:1):Boolean.FALSE)));}));root.then(node);
        }
        root.then(Commands.literal("list").executes(context->{ServerPlayer player=context.getSource().getPlayerOrException();return report(context.getSource(),()->TisCommandContinuations.then(service(context).list(player),lines->feedback(context.getSource(),()->{
            if(lines.isEmpty())send(context.getSource(),OrgMailPresentation.mail("list.empty"));else{send(context.getSource(),Component.empty());send(context.getSource(),OrgMailPresentation.mail("list.head",lines.size()));OrgPages.print(context.getSource(),lines);}return lines.size();
        })));}));
        root.then(Commands.literal("override").requires(source->OrgHiddenPlayerActions.debug()).executes(context->report(context.getSource(),()->service(context).overrideCount())));
        dispatcher.register(root);
    }
}
