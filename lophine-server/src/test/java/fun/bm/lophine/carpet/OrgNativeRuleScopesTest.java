package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetRuntime;
import carpet.script.external.ScarpetNativeWork;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import org.junit.jupiter.api.Test;

/** Actual rule scopes cross delayed native continuations; a closed guest and previous callback routing do not. */
public class OrgNativeRuleScopesTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static final carpet.script.ScriptServer FILES=new carpet.script.ScriptServer(){@Override public Path resolveResource(String name){return Path.of(name);}};
    private static final class Host extends carpet.script.ScriptHost {
        Host(){super(null,FILES,false,null,carpet.script.Expression.LoadOverride.DEFAULT);}
        @Override protected carpet.script.Module getModuleOrLibraryByName(String name){return null;}
        @Override protected void runModuleCode(carpet.script.Context context,carpet.script.Module module){}
        @Override protected carpet.script.ScriptHost duplicate(){return new Host();}
    }
    private static final class ReadyContext extends carpet.script.Context {ReadyContext(Host host){super(host);initialize();}}
    private static ItemStack fragile(){var stack=mock(ItemStack.class);when(stack.isDamageableItem()).thenReturn(true);when(stack.getMaxDamage()).thenReturn(10);when(stack.getDamageValue()).thenReturn(9);return stack;}
    private static <T> T sourceScopes(ServerPlayer player,ItemStack fragile,Supplier<T> operation){
        return OrgGameplayHelper.withBlockBreaker(player,()->OrgGameplayHelper.withTool(player,fragile,()->OrgGameplayHelper.withoutShulkerStacking(()->ScopedValue.where(GeneralCompatConfig.CHANNELING_TRIDENT,true).call(operation::get))));
    }
    @Test void delayedNativeRuleScopesKeepTheExactOriginalBreakerAndPureFlagsAfterGuestHostCloseAndWaitRealChildren(){
        boolean old=GeneralCompatConfig.noToolBreak;GeneralCompatConfig.noToolBreak=true;
        try(var enchantments=mockStatic(EnchantmentHelper.class)){
            var breaker=mock(ServerPlayer.class);var stack=fragile();enchantments.when(()->EnchantmentHelper.has(stack,EnchantmentEffectComponents.REPAIR_WITH_XP)).thenReturn(true);
            var host=new Host();var context=new ReadyContext(host);var queue=new CompletableFuture<Void>();var child=new CompletableFuture<Void>();var callback=new AtomicReference<Supplier<CompletableFuture<Integer>>>();
            var parent=ScarpetNativeWork.observeNative(null,()->{
                ScarpetNativeWork.record(queue);
                try(var frame=ScarpetRuntime.enterContext(context)){
                    sourceScopes(breaker,stack,()->{callback.set(ScarpetRuntime.captureNativeContinuation(()->ScarpetNativeWork.observeNative(null,()->{
                        assertSame(breaker,OrgGameplayHelper.blockBreaker());assertTrue(OrgGameplayHelper.toolNoBreakActive());assertFalse(OrgGameplayHelper.allowShulkerStacking());assertTrue(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));ScarpetNativeWork.record(child);return 37;
                    })));return null;});
                }
                return "parent";
            });
            assertNull(OrgGameplayHelper.blockBreaker());assertFalse(OrgGameplayHelper.toolNoBreakActive());assertTrue(OrgGameplayHelper.allowShulkerStacking());assertFalse(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));host.onClose();assertFalse(parent.isDone());
            var other=mock(ServerPlayer.class);var prior=new OrgGameplayHelper.NativeRuleScopes(false,other,true,false);
            var actual=prior.call(()->{var result=callback.get().get();assertSame(other,OrgGameplayHelper.blockBreaker());assertFalse(OrgGameplayHelper.toolNoBreakActive());assertTrue(OrgGameplayHelper.allowShulkerStacking());assertFalse(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));return result;});
            queue.complete(null);assertFalse(parent.isDone());assertFalse(actual.isDone());child.complete(null);assertEquals(37,actual.join());assertEquals("parent",parent.join());
            assertNull(OrgGameplayHelper.blockBreaker());assertFalse(OrgGameplayHelper.toolNoBreakActive());assertTrue(OrgGameplayHelper.allowShulkerStacking());assertFalse(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));
        }finally{GeneralCompatConfig.noToolBreak=old;}
    }
    @Test void detachedExternalCommandKeepsPureRuleFlagsAndNeverCarriesTheOldPhysicalBlockBreaker(){
        boolean old=GeneralCompatConfig.noToolBreak;GeneralCompatConfig.noToolBreak=true;
        try(var enchantments=mockStatic(EnchantmentHelper.class)){
            var breaker=mock(ServerPlayer.class);var other=mock(ServerPlayer.class);var stack=fragile();enchantments.when(()->EnchantmentHelper.has(stack,EnchantmentEffectComponents.REPAIR_WITH_XP)).thenReturn(true);
            var host=new Host();var context=new ReadyContext(host);Supplier<Integer> callback;
            try(var frame=ScarpetRuntime.enterContext(context)){callback=sourceScopes(breaker,stack,()->ScarpetRuntime.captureDetachedNativeContinuation(()->{
                assertNull(OrgGameplayHelper.blockBreaker());assertTrue(OrgGameplayHelper.toolNoBreakActive());assertFalse(OrgGameplayHelper.allowShulkerStacking());assertTrue(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));return 7;
            }));assertNull(OrgGameplayHelper.blockBreaker());}
            host.onClose();var prior=new OrgGameplayHelper.NativeRuleScopes(false,other,true,false);assertEquals(7,prior.call(()->{int value=callback.get();assertSame(other,OrgGameplayHelper.blockBreaker());assertFalse(OrgGameplayHelper.toolNoBreakActive());assertTrue(OrgGameplayHelper.allowShulkerStacking());assertFalse(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));return value;}));
        }finally{GeneralCompatConfig.noToolBreak=old;}
    }
    @Test void sourceScopesAndPreviousCallbackScopesRestoreOnBothOrdinaryAndDetachedNativeBodyFailure(){
        var first=mock(ServerPlayer.class);var priorOwner=mock(ServerPlayer.class);var failure=new IllegalStateException("actual native continuation failed");
        var source=new OrgGameplayHelper.NativeRuleScopes(true,first,false,true);var callbacks=source.call(()->java.util.List.of(ScarpetRuntime.<Integer>captureNativeContinuation(()->{assertSame(first,OrgGameplayHelper.blockBreaker());throw failure;}),ScarpetRuntime.<Integer>captureDetachedNativeContinuation(()->{assertNull(OrgGameplayHelper.blockBreaker());throw failure;})));
        new OrgGameplayHelper.NativeRuleScopes(false,priorOwner,true,false).call(()->{
            for(var callback:callbacks){assertSame(failure,assertThrows(IllegalStateException.class,callback::get));assertSame(priorOwner,OrgGameplayHelper.blockBreaker());assertFalse(OrgGameplayHelper.toolNoBreakActive());assertTrue(OrgGameplayHelper.allowShulkerStacking());assertFalse(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));}return null;
        });assertNull(OrgGameplayHelper.blockBreaker());assertFalse(OrgGameplayHelper.toolNoBreakActive());assertTrue(OrgGameplayHelper.allowShulkerStacking());assertFalse(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));
    }
    @Test void anActualVmGuestCallbackAndItsAcceptedNativeContinuationSeeTheSourceRuleScopes()throws Exception{
        var breaker=mock(ServerPlayer.class);var runtime=ScarpetRuntime.of(mock(net.minecraft.server.MinecraftServer.class));var source=new OrgGameplayHelper.NativeRuleScopes(true,breaker,false,true);
        var actual=source.call(()->runtime.submit(()->{
            assertSame(breaker,OrgGameplayHelper.blockBreaker());assertTrue(OrgGameplayHelper.toolNoBreakActive());assertFalse(OrgGameplayHelper.allowShulkerStacking());assertTrue(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));
            var tail=ScarpetRuntime.captureNativeContinuation(()->{assertSame(breaker,OrgGameplayHelper.blockBreaker());assertTrue(OrgGameplayHelper.toolNoBreakActive());return 37;});return tail.get();
        }));
        assertNull(OrgGameplayHelper.blockBreaker());assertFalse(OrgGameplayHelper.toolNoBreakActive());assertTrue(OrgGameplayHelper.allowShulkerStacking());assertFalse(GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));assertEquals(37,actual.get(3,TimeUnit.SECONDS));
    }

}
