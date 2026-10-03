package carpet.script.external;
import ca.spottedleaf.moonrise.common.util.TickThread;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
import net.minecraft.core.*;import net.minecraft.core.component.*;import net.minecraft.server.*;import net.minecraft.server.level.*;import net.minecraft.util.*;import net.minecraft.world.damagesource.*;import net.minecraft.world.entity.*;import net.minecraft.world.item.*;import net.minecraft.world.item.enchantment.*;import net.minecraft.world.item.enchantment.effects.*;import net.minecraft.world.level.storage.loot.*;import net.minecraft.world.level.storage.loot.parameters.*;import net.minecraft.world.level.storage.loot.predicates.*;import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
public class ScarpetAttackEnchantmentsTest {
 @BeforeAll static void bootstrap(){ScarpetLootTablesTest.bootstrap();}
 final MinecraftServer server=mock(MinecraftServer.class,RETURNS_DEEP_STUBS);final ServerLevel original=mock(ServerLevel.class),foreign=mock(ServerLevel.class);final LivingEntity caller=mock(LivingEntity.class),victim=mock(LivingEntity.class);final DamageSource source=mock(DamageSource.class);
 @BeforeEach void setup(){when(original.getServer()).thenReturn(server);when(foreign.getServer()).thenReturn(server);when(caller.level()).thenReturn(original);when(victim.level()).thenReturn(foreign);when(caller.blockPosition()).thenReturn(new BlockPos(3,70,4));when(victim.blockPosition()).thenReturn(new BlockPos(800,65,-900));when(caller.position()).thenReturn(Vec3.atCenterOf(new BlockPos(3,70,4)));when(victim.position()).thenReturn(Vec3.atCenterOf(new BlockPos(800,65,-900)));when(source.getEntity()).thenReturn(caller);for(var slot:EquipmentSlot.VALUES_ARRAY){when(caller.getItemBySlot(slot)).thenReturn(ItemStack.EMPTY);when(victim.getItemBySlot(slot)).thenReturn(ItemStack.EMPTY);}}
 private org.mockito.MockedStatic<TickThread> owners(){var ticks=mockStatic(TickThread.class);ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);ticks.when(()->TickThread.isTickThreadFor(any(ServerLevel.class),any(BlockPos.class))).thenReturn(true);return ticks;}
 private ItemStack weapon(Enchantment enchantment){var result=new ItemStack(Items.STONE);var levels=new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);levels.set(Holder.direct(enchantment),1);result.set(DataComponents.ENCHANTMENTS,levels.toImmutable());return result;}
 private ScarpetAttackEnchantments.SourceAdmission admission(RandomSource random){when(original.getRandom()).thenReturn(random);return new ScarpetAttackEnchantments.SourceAdmission(original,caller.blockPosition(),random,caller);}
 @Test void sourceNativeKnockbackOracleKeepsDistinctOriginalContextAndVictimValueRandomStreams(){
  var enchantment=mock(Enchantment.class);doCallRealMethod().when(enchantment).modifyKnockback(any(),anyInt(),any(),any(),any(),any());
  var requirement=Holder.<LootItemCondition>direct(LootItemRandomChanceCondition.randomChance(0.63F).build());
  when(enchantment.getEffects(EnchantmentEffectComponents.KNOCKBACK)).thenReturn(List.of(new ConditionalEffect<>(new RemoveBinomial(LevelBasedValue.constant(0.36F)),Optional.of(requirement))));var weapon=weapon(enchantment);
  try(var ticks=owners()){
   for(long seed=1;seed<=32;seed++){
    when(original.getRandom()).thenReturn(RandomSource.create(seed));when(victim.getRandom()).thenReturn(RandomSource.create(seed+777));float expected=EnchantmentHelper.modifyKnockback(original,weapon,victim,source,13F);
    when(victim.getRandom()).thenReturn(RandomSource.create(seed+777));float actual=EnchantmentHelper.carpetModifyKnockbackAsync(admission(RandomSource.create(seed)),weapon,victim,source,13F).join();assertEquals(expected,actual,"native scalar oracle "+seed);
   }
  }
 }
 @Test void nativeEquipmentChancePreservesSameEntityTwoSourcePassesWithoutDeduplication(){
  var enchantment=mock(Enchantment.class);when(enchantment.matchingSlot(any())).thenReturn(true);
  when(enchantment.getEffects(EnchantmentEffectComponents.EQUIPMENT_DROPS)).thenReturn(List.of(new TargetedConditionalEffect<>(EnchantmentTarget.VICTIM,EnchantmentTarget.VICTIM,new AddValue(LevelBasedValue.constant(2F)),Optional.empty()),new TargetedConditionalEffect<>(EnchantmentTarget.ATTACKER,EnchantmentTarget.VICTIM,new AddValue(LevelBasedValue.constant(3F)),Optional.empty())));
  var chanceWeapon=weapon(enchantment);when(caller.getItemBySlot(EquipmentSlot.MAINHAND)).thenReturn(chanceWeapon);when(caller.getRandom()).thenReturn(RandomSource.create(3));
  try(var ticks=owners()){float expected=EnchantmentHelper.processEquipmentDropChance(original,caller,source,1F);float actual=EnchantmentHelper.carpetProcessEquipmentDropChanceAsync(admission(RandomSource.create(5)),caller,source,1F).join();assertEquals(expected,actual);assertEquals(6F,actual);}
 }
 @Test void postEffectsWaitActualVictimChildBeforeNextSlotGetterAndThenOriginalWeaponOwner(){
  var enchantment=mock(Enchantment.class);when(enchantment.matchingSlot(any())).thenReturn(true);var first=mock(EnchantmentEntityEffect.class);var second=mock(EnchantmentEntityEffect.class);var child=new CompletableFuture<Void>();var order=new ArrayList<String>();
  when(enchantment.getEffects(EnchantmentEffectComponents.POST_ATTACK)).thenReturn(List.of(new TargetedConditionalEffect<>(EnchantmentTarget.VICTIM,EnchantmentTarget.VICTIM,first,Optional.empty()),new TargetedConditionalEffect<>(EnchantmentTarget.ATTACKER,EnchantmentTarget.ATTACKER,second,Optional.empty())));
  var originalWeapon=weapon(enchantment);when(victim.getItemBySlot(EquipmentSlot.VALUES_ARRAY[0])).thenReturn(originalWeapon);
  doAnswer(call->{assertSame(original,call.getArgument(0));assertSame(victim,call.getArgument(3));order.add("victim physical");ScarpetNativeWork.record(child);return null;}).when(first).apply(any(),anyInt(),any(),any(),any());
  doAnswer(call->{assertSame(caller,call.getArgument(3));assertSame(originalWeapon,((EnchantedItemInUse)call.getArgument(2)).itemStack());order.add("weapon physical");return null;}).when(second).apply(any(),anyInt(),any(),any(),any());
  try(var ticks=owners()){
   var actual=EnchantmentHelper.carpetPostAttackEffectsWithItemSourceAsync(admission(RandomSource.create(3)),victim,source,originalWeapon);assertEquals(List.of("victim physical"),order);assertFalse(actual.isDone());assertFalse(actual.cancel(false));verify(victim,never()).getItemBySlot(EquipmentSlot.VALUES_ARRAY[1]);child.complete(null);actual.join();assertEquals(List.of("victim physical","weapon physical"),order);
  }
 }
 @Test void allOfPhysicalEffectChildWaitsBeforeFollowingEffectWithSameOriginalPosition(){
  var first=mock(EnchantmentEntityEffect.class);var second=mock(EnchantmentEntityEffect.class);var child=new CompletableFuture<Void>();var position=new Vec3(6,7,8);var originalWeapon=new ItemStack(Items.STONE);var item=new EnchantedItemInUse(originalWeapon,EquipmentSlot.MAINHAND,caller);
  doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(first).apply(any(),anyInt(),any(),any(),any());
  try(var ticks=owners()){
   var actual=ScarpetEnchantmentEntityEffects.apply(AllOf.entityEffects(first,second),admission(RandomSource.create(7)),1,item,victim,position);assertFalse(actual.isDone());verifyNoInteractions(second);child.complete(null);actual.join();verify(second).apply(original,1,item,victim,position);
  }
 }
 @Test void contextualRandomRequirementsRunActualCallerWhileOriginRemainsActualVictim(){
  var enchantment=mock(Enchantment.class);var requirement=mock(LootItemCondition.class);var observed=new java.util.concurrent.atomic.AtomicReference<LootContext>();
  when(requirement.test(any())).thenAnswer(call->{var context=(LootContext)call.getArgument(0);observed.set(context);assertSame(original,context.getLevel());assertEquals(victim.position(),context.getOptional(LootContextParams.ORIGIN));assertTrue(ScarpetLootRandomOwners.bound(context));assertEquals(caller,ScarpetNativeWork.capture().owner());return true;});
  when(enchantment.getEffects(EnchantmentEffectComponents.KNOCKBACK)).thenReturn(List.of(new ConditionalEffect<>(new AddValue(LevelBasedValue.constant(1F)),Optional.of(Holder.direct(requirement)))));when(victim.getRandom()).thenReturn(RandomSource.create(2));
  try(var ticks=owners()){assertEquals(2F,EnchantmentHelper.carpetModifyKnockbackAsync(admission(RandomSource.create(1)),weapon(enchantment),victim,source,1F).join());assertNotNull(observed.get());}
 }

 @Test void postAttackGuestChildFailurePreservesActualTailAndOriginalNativeParentFailure(){
  var enchantment=mock(Enchantment.class);when(enchantment.matchingSlot(any())).thenReturn(true);var first=mock(EnchantmentEntityEffect.class);var second=mock(EnchantmentEntityEffect.class);var child=new CompletableFuture<Void>();var order=new ArrayList<String>();when(enchantment.getEffects(EnchantmentEffectComponents.POST_ATTACK)).thenReturn(List.of(new TargetedConditionalEffect<>(EnchantmentTarget.ATTACKER,EnchantmentTarget.ATTACKER,first,Optional.empty()),new TargetedConditionalEffect<>(EnchantmentTarget.ATTACKER,EnchantmentTarget.ATTACKER,second,Optional.empty())));doAnswer(c->{order.add("first");ScarpetNativeWork.record(child);return null;}).when(first).apply(any(),anyInt(),any(),any(),any());doAnswer(c->{order.add("second");return null;}).when(second).apply(any(),anyInt(),any(),any(),any());var actual=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();var guest=new IllegalArgumentException("real Guest callback failure");ScarpetNativeWork.markGuestFailure(guest);
  try(var ticks=owners()){var parent=ScarpetNativeWork.observeNative(caller,()->{actual.set(EnchantmentHelper.carpetPostAttackEffectsWithItemSourceAsync(admission(RandomSource.create(7)),victim,source,weapon(enchantment)));return null;});assertFalse(parent.isDone());child.completeExceptionally(guest);actual.get().join();assertEquals(List.of("first","second"),order);assertTrue(parent.isCompletedExceptionally());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));}
 }
 @Test void postAttackRealNativeChildFailureStopsFollowingPhysicalEffect(){
  var enchantment=mock(Enchantment.class);when(enchantment.matchingSlot(any())).thenReturn(true);var first=mock(EnchantmentEntityEffect.class);var second=mock(EnchantmentEntityEffect.class);var child=new CompletableFuture<Void>();when(enchantment.getEffects(EnchantmentEffectComponents.POST_ATTACK)).thenReturn(List.of(new TargetedConditionalEffect<>(EnchantmentTarget.ATTACKER,EnchantmentTarget.ATTACKER,first,Optional.empty()),new TargetedConditionalEffect<>(EnchantmentTarget.ATTACKER,EnchantmentTarget.ATTACKER,second,Optional.empty())));doAnswer(c->{ScarpetNativeWork.record(child);return null;}).when(first).apply(any(),anyInt(),any(),any(),any());
  try(var ticks=owners()){var actual=EnchantmentHelper.carpetPostAttackEffectsWithItemSourceAsync(admission(RandomSource.create(7)),victim,source,weapon(enchantment));child.completeExceptionally(new IllegalStateException("true Native failure"));assertThrows(CompletionException.class,actual::join);verifyNoInteractions(second);}
 }
}
