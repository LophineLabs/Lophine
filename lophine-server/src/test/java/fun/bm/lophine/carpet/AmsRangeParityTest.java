package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsRangeParityTest {
    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @TempDir
    Path directory;

    @AfterEach
    void reset() {
        GeneralCompatConfig.maxPlayerBlockInteractionRange = -1;
        GeneralCompatConfig.maxPlayerEntityInteractionRange = -1;
        GeneralCompatConfig.maxPlayerBlockInteractionRangeScope = "server";
        GeneralCompatConfig.maxPlayerEntityInteractionRangeScope = "server";
    }

    static int call(CommandSourceStack source, Entity target, Holder<Attribute> attribute, double value) {
        try {
            var method = net.minecraft.server.commands.AttributeCommand.class.getDeclaredMethod("setAttributeBase", CommandSourceStack.class, Entity.class, Holder.class, double.class);
            method.setAccessible(true);
            return (Integer) method.invoke(null, source, target, attribute, value);
        } catch (InvocationTargetException failure) {
            throw new RuntimeException(failure.getCause());
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    @Test
    void actualControlledAttributeSetKeepsValidationDisableOriginalSuccessThenOriginalOne() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            GeneralCompatConfig.maxPlayerBlockInteractionRange = 17;
            var attribute = mock(AttributeInstance.class);
            var attributes = mock(AttributeMap.class);
            when(f.target.getAttributes()).thenReturn(attributes);
            var validation = new CompletableFuture<Void>();
            var disable = new CompletableFuture<Void>();
            var name = new CompletableFuture<Void>();
            var success = new CompletableFuture<Void>();
            var callback = new CompletableFuture<Void>();
            when(attributes.getInstance(Attributes.BLOCK_INTERACTION_RANGE)).thenAnswer(call -> {
                assertSame(f.target, f.current);
                f.order.add("validation");
                ScarpetNativeWork.record(validation);
                return attribute;
            });
            when(f.target.getDisplayName()).thenAnswer(call -> {
                assertSame(f.target, f.current);
                f.order.add("name");
                ScarpetNativeWork.record(name);
                return Component.literal("actual target");
            });
            doAnswer(call -> {
                assertSame(f.sourcePlayer, f.current);
                var message = ((Supplier<Component>) call.getArgument(0)).get();
                boolean vanilla = message.getContents() instanceof TranslatableContents content && content.getKey().equals("commands.attribute.base_value.set.success");
                f.order.add(vanilla ? "success" : "disable");
                ScarpetNativeWork.record(vanilla ? success : disable);
                return null;
            }).when(f.source).sendSuccess(any(), eq(false));
            doAnswer(call -> {
                assertSame(f.sourcePlayer, f.current);
                f.order.add("callback");
                ScarpetNativeWork.record(callback);
                return null;
            }).when(f.callback).onResult(true, 1);
            assertEquals(1, call(f.source, f.target, Attributes.BLOCK_INTERACTION_RANGE, 50));
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(List.of("validation"), f.order);
            validation.complete(null);
            f.drain();
            assertEquals(List.of("validation", "disable"), f.order);
            disable.complete(null);
            f.drain();
            assertEquals(List.of("validation", "disable", "name"), f.order);
            name.complete(null);
            f.drain();
            assertEquals(List.of("validation", "disable", "name", "success"), f.order);
            assertFalse(actual.isDone());
            success.complete(null);
            f.drain();
            assertEquals("callback", f.order.getLast());
            assertFalse(actual.isDone());
            callback.complete(null);
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            verify(attribute, never()).setBaseValue(anyDouble());
        }
    }

    @Test
    void realControlledDisableMessageFailureBlocksOriginalNameAndSuccessAndReturnsZero() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            GeneralCompatConfig.maxPlayerEntityInteractionRange = 17;
            var attributes = mock(AttributeMap.class);
            when(f.target.getAttributes()).thenReturn(attributes);
            when(attributes.getInstance(Attributes.ENTITY_INTERACTION_RANGE)).thenReturn(mock(AttributeInstance.class));
            when(f.target.getDisplayName()).thenReturn(Component.literal("target"));
            var child = new CompletableFuture<Void>();
            var failure = new IllegalStateException("actual disable reply");
            doAnswer(call -> {
                var message = ((Supplier<Component>) call.getArgument(0)).get();
                if (message.getString().startsWith("rule.")) {
                    ScarpetNativeWork.record(child);
                    throw failure;
                }
                return null;
            }).when(f.source).sendSuccess(any(), eq(false));
            call(f.source, f.target, Attributes.ENTITY_INTERACTION_RANGE, 50);
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertFalse(actual.isDone());
            verify(f.target, never()).getDisplayName();
            child.complete(null);
            f.drain();
            assertEquals(0, actual.join());
            verify(f.target, never()).getDisplayName();
            verify(f.callback).onResult(false, 0);
        }
    }

    @Test
    void originalGlobalBaseOverrideUsesExactNativeHolderAndNotAnotherMatchingDescription() {
        GeneralCompatConfig.maxPlayerBlockInteractionRange = 17;
        GeneralCompatConfig.maxPlayerBlockInteractionRangeScope = "global";
        var actual = new AttributeInstance(Attributes.BLOCK_INTERACTION_RANGE, ignored -> {
        });
        var foreign = new AttributeInstance(Holder.direct(new RangedAttribute(Attributes.BLOCK_INTERACTION_RANGE.value().getDescriptionId(), 5, 0, 100)), ignored -> {
        });
        assertEquals(17, actual.getBaseValue());
        assertEquals(5, foreign.getBaseValue());
    }

    @Test
    void originalServerScopeKeepsStoredBaseAndOriginalIndependentSanitizeHook() {
        GeneralCompatConfig.maxPlayerBlockInteractionRange = 17;
        GeneralCompatConfig.maxPlayerBlockInteractionRangeScope = "server";
        var actual = new AttributeInstance(Attributes.BLOCK_INTERACTION_RANGE, ignored -> {
        });
        assertEquals(Attributes.BLOCK_INTERACTION_RANGE.value().getDefaultValue(), actual.getBaseValue());
        assertEquals(17, actual.getValue());
    }
}
