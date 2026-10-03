// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import carpet.script.external.Carpet;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import me.earthme.luminol.config.ConfigsInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A rule command retains its actual configuration, observers, guest notification and recipient tails.
 */
public final class CarpetRuleChanges {
    private static final ThreadLocal<Boolean> CHANGING = new ThreadLocal<>();

    private CarpetRuleChanges() {
    }

    public static boolean isChanging() {
        return Boolean.TRUE.equals(CHANGING.get());
    }

    private record Update(Object applied, boolean changed, boolean restart, String message, boolean networkBlocked,
                          int commandResult) {
    }

    private record Outcome(Update update, Throwable failure) {
    }

    static int change(CommandSourceStack source, String name, String text, boolean persist, boolean reset) {
        var completion = CarpetAsyncCommandResults.defer(source);
        var observer = ScarpetNativeWork.observeNative(source.getEntity(), () -> {
            ScarpetNativeWork.record(completion.future());
            var configured = AmsNativeCommandEffects.global(source.getServer(), () -> configure(source, name, text, persist, reset));
            var effects = TisCommandContinuations.then(configured, update -> {
                var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                CompletableFuture<Void> notification = update.changed()
                        ? ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.<Void>observeNative(null, () -> {
                    var submitted = ScarpetRuntime.of(source.getServer()).submit(() -> {
                        Carpet.ruleChanged(source, name, update.applied());
                        return (Void) null;
                    });
                    ScarpetNativeWork.record(submitted);
                    submitted.whenComplete((value, failure) -> {
                        if (failure != null) callbackFailure.set(failure);
                    });
                    return null;
                }))
                        : CompletableFuture.completedFuture(null);
                ScarpetNativeWork.record(notification);
                return TisCommandContinuations.then(notification, ignored -> {
                    var published = update.changed() ? AmsNativeCommandEffects.global(source.getServer(), () -> {
                        CarpetProtocalDataBase.apply();
                        return (Void) null;
                    }) : CompletableFuture.<Void>completedFuture(null);
                    return TisCommandContinuations.then(published, unused -> {
                        var commands = update.changed() && changesCommands(name) ? refreshCommands(source) : CompletableFuture.<Void>completedFuture(null);
                        return TisCommandContinuations.then(commands, done -> AmsNativeCommandEffects.source(source, () -> {
                            if (update.restart()) CarpetRuleObservers.restartRequired(source, name);
                            return new Outcome(update, callbackFailure.get());
                        }));
                    });
                });
            });
            ScarpetNativeWork.record(effects);
            var outcome = effects.handle((value, failure) -> failure == null ? value : new Outcome(null, failure));
            var delivered = TisCommandContinuations.then(outcome, value -> AmsNativeCommandEffects.source(source, () -> {
                if (value.failure() == null) {
                    if (value.update().networkBlocked())
                        CarpetMessenger.send(source, List.of(AmsTranslations.message(source, "observer.amsNetworkProtocol.need_enable_protocol", name).withStyle(ChatFormatting.YELLOW)));
                    else source.sendSuccess(() -> Component.literal(value.update().message()), false);
                } else {
                    Throwable error = value.failure();
                    while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null)
                        error = error.getCause();
                    source.sendFailure(Component.literal(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                }
                return value;
            }));
            var defaulted = TisCommandContinuations.then(delivered, value -> !persist && !reset && value.failure() == null && !value.update().networkBlocked()
                    ? defaultTail(source, name, text, value) : CompletableFuture.completedFuture(value));
            var committed = defaulted.whenComplete(ScarpetRuntime.captureNativeConsumer((value, failure) ->
                    completion.complete(failure == null && value.failure() == null, failure == null && value.failure() == null ? value.update().commandResult() : 0)));
            ScarpetNativeWork.record(committed);
            return null;
        });
        ScarpetNativeWork.trackNative(source.getServer(), observer);
        return 1;
    }

    private record DefaultValue(CarpetRuleRegistry.Binding binding, ConfigsInstance config, Object value) {
    }

    /**
     * AMS reads its current flag at setRule TAIL, after the ordinary rule feedback has completed.
     */
    private static CompletableFuture<Outcome> defaultTail(CommandSourceStack source, String name, String text, Outcome outcome) {
        var prepared = AmsNativeCommandEffects.global(source.getServer(), () -> {
            if (!fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.carpetAlwaysSetDefault) return null;
            var binding = CarpetRuleRegistry.get(name);
            var config = CarpetRuleRegistry.config();
            Object value = binding.parse(text);
            try {
                config.applySingleConfig(binding.path(), value, false);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
            return new DefaultValue(binding, config, value);
        });
        return TisCommandContinuations.then(prepared, value -> value == null ? CompletableFuture.completedFuture(outcome)
                : TisCommandContinuations.then(AmsNativeCommandEffects.source(source, () -> {
            source.sendSuccess(() -> Component.literal(name + " = " + value.value() + " saved as default."), false);
            return (Void) null;
        }), ignored -> AmsNativeCommandEffects.global(source.getServer(), () -> {
            value.config().getFileInstance().set(value.binding().path(), value.value());
            value.config().saveConfigs();
            return outcome;
        })));
    }

    private static Update configure(CommandSourceStack source, String name, String text, boolean persist, boolean reset) {
        Boolean previous = CHANGING.get();
        CHANGING.set(true);
        try {
            CarpetRuleRegistry.requireAvailable(name);
            var binding = CarpetRuleRegistry.get(name);
            var config = CarpetRuleRegistry.config();
            Object value = reset ? config.getDefaultConfig(binding.path()) : binding.parse(text);
            if (value == null) throw new IllegalArgumentException("No default is available for this rule.");
            if (fun.bm.lophine.protocol.AmsNetworkProtocol.blocksNetworkRuleChange(name, value, config.getDefaultConfig(binding.path()),
                    fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.amsNetworkProtocol, source.getServer().isRunning())) {
                Object ruleDefault = config.getDefaultConfig(binding.path());
                var result = config.applySingleConfig(binding.path(), ruleDefault, false);
                if (result == ConfigsInstance.SingleConfigResult.UNKNOWN_KEY)
                    throw new IllegalArgumentException("Rule configuration is unavailable: " + name);
                return new Update(binding.value(), result == ConfigsInstance.SingleConfigResult.UPDATED, false, "", true, 0);
            }
            if ((name.equals("fakePlayerNamePrefix") || name.equals("fakePlayerNameSuffix")) && !TisFakePlayerRules.validateNameSetting(name, value.toString(), source))
                throw new IllegalArgumentException("Invalid name fragment; repeat this setting to confirm it.");
            if (name.equals("microTimingDyeMarker"))
                value = TisMicroTimingMarkers.validateDyeRule(value.toString(), fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.microTimingDyeMarker, source);
            if (name.equals("ultraSecretSetting"))
                value = TisDebugSettings.validateUltra(value.toString(), fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.ultraSecretSetting, source);
            Carpet.validateRuleChange(source, name, value);
            var result = config.applySingleConfig(binding.path(), value, persist);
            if (result == ConfigsInstance.SingleConfigResult.UNKNOWN_KEY)
                throw new IllegalArgumentException("Rule configuration is unavailable: " + name);
            if (reset) {
                config.getFileInstance().remove(binding.path());
                config.saveConfigs();
            }
            if (result == ConfigsInstance.SingleConfigResult.UPDATED) {
                if (name.equals("microTiming")) TisMicroTiming.enabledChanged(source);
                if (name.equals("microTimingTarget")) TisMicroTiming.targetChanged(source);
            }
            Object applied = binding.value();
            boolean restart = result == ConfigsInstance.SingleConfigResult.SAVED_FOR_RESTART;
            String message = restart ? name + " = " + value + " saved; restart required." : name + " = " + applied + (persist ? " saved as default." : " (temporary).");
            return new Update(applied, result == ConfigsInstance.SingleConfigResult.UPDATED, restart, message, false, 1);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        } finally {
            if (previous == null) CHANGING.remove();
            else CHANGING.set(previous);
        }
    }

    private static boolean changesCommands(String name) {
        return Set.of("carpetCommandPermissionLevel", "perfPermissionLevel", "opPlayerNoCheat", "preventAdministratorCheat", "ultraSecretSetting").contains(name)
                || name.startsWith("command") || name.startsWith("playerCommand") || name.startsWith("tick") || name.startsWith("open") && name.endsWith("Permission");
    }

    private static CompletableFuture<Void> refreshCommands(CommandSourceStack source) {
        return TisCommandContinuations.then(AmsNativeCommandEffects.global(source.getServer(), () -> List.copyOf(source.getServer().getPlayerList().getPlayers())), players -> {
            var updates = new ArrayList<CompletableFuture<?>>();
            for (var player : players)
                updates.add(AmsNativeCommandEffects.owned(player, () -> {
                    if (!player.isRemoved() && !player.hasDisconnected()) {
                        var actual = source.getServer().getCommands().carpetReloadCommands(player);
                        ScarpetNativeWork.record(actual);
                        return actual;
                    }
                    return CompletableFuture.<Void>completedFuture(null);
                }));
            return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new));
        });
    }
}
