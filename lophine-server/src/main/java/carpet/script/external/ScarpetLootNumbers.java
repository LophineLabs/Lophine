// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.DispatcherProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;
import net.minecraft.world.level.storage.loot.providers.number.floats.ContextFloatProvider;
import net.minecraft.world.level.storage.loot.providers.score.ContextScoreboardNameProvider;
import net.minecraft.world.level.storage.loot.providers.score.FixedScoreboardNameProvider;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.phys.Vec3;

/** Ordered native numeric expressions; actual foreign reads never change the original loot world or random stream. */
public final class ScarpetLootNumbers {
    private ScarpetLootNumbers() {}
    public static CompletableFuture<Integer> integer(ContextIntProvider provider,LootContext context){
        return admit(context,()->safe(integerUnsafe(provider,context),0));
    }
    public static CompletableFuture<Float> floating(ContextFloatProvider provider,LootContext context){
        return admit(context,()->safe(floatingUnsafe(provider,context).thenApply(value->Float.isFinite(value)?value:0F),0F));
    }
    private static <T> CompletableFuture<T> safe(CompletableFuture<T> expression,T fallback){
        return expression.handle((value,failure)->{if(failure==null)return value;Throwable cause=unwrap(failure);if(cause instanceof ArithmeticValueFailure)return fallback;throw new CompletionException(cause);});
    }
    private static Throwable unwrap(Throwable failure){while((failure instanceof CompletionException||failure instanceof java.util.concurrent.ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();return failure;}
    private static <T> CompletableFuture<T> admit(LootContext context,Supplier<CompletableFuture<T>> expression){
        var actual=new CompletableFuture<T>();ScarpetNativeWork.record(actual);var world=context.getLevel();if(world!=null)ScarpetNativeWork.trackNative(world.getServer(),actual);
        try{var body=expression.get();ScarpetNativeWork.aliasDependency(actual,body);body.whenComplete((value,failure)->{if(failure==null)actual.complete(value);else actual.completeExceptionally(failure);});}
        catch(Throwable failure){actual.completeExceptionally(failure);}
        var caller=actual.copy();ScarpetNativeWork.aliasDependency(caller,actual);return caller;
    }
    private static Holder<ContextIntProvider> i(int value){return Holder.direct(new net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue(value));}
    private static Holder<ContextFloatProvider> f(float value){return Holder.direct(new net.minecraft.world.level.storage.loot.providers.number.floats.ConstantValue(value));}
    /** Only a direct numeric expression's expected arithmetic error is the source safe-get fallback. */
    private static final class ArithmeticValueFailure extends RuntimeException {
        ArithmeticValueFailure(ArithmeticException failure){super(null,failure,false,false);}
    }
    private static <T> CompletableFuture<T> scalar(Supplier<T> operation){try{return CompletableFuture.completedFuture(operation.get());}catch(ArithmeticException failure){return CompletableFuture.failedFuture(new ArithmeticValueFailure(failure));}catch(Throwable failure){return CompletableFuture.failedFuture(failure);}}
    private static CompletableFuture<Integer> integerUnsafe(ContextIntProvider provider,LootContext context){
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue value)return scalar(()->value.getIntUnsafe(context));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Absolute value)return integerUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Absolute(i(input)).getIntUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Negate value)return integerUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Negate(i(input)).getIntUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.FromFloat value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.FromFloat(f(input)).getIntUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Difference value)return integerUnsafe(value.left().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->integerUnsafe(value.right().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Difference(i(left),i(right)).getIntUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Quotient value)return integerUnsafe(value.left().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->integerUnsafe(value.right().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Quotient(i(left),i(right)).getIntUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.FloorQuotient value)return integerUnsafe(value.left().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->integerUnsafe(value.right().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.FloorQuotient(i(left),i(right)).getIntUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Modulus value)return integerUnsafe(value.left().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->integerUnsafe(value.right().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Modulus(i(left),i(right)).getIntUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.FloorModulus value)return integerUnsafe(value.left().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->integerUnsafe(value.right().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.FloorModulus(i(left),i(right)).getIntUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Power value)return integerUnsafe(value.base().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->integerUnsafe(value.exponent().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Power(i(left),i(right)).getIntUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Average value)return integers(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Average(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::i).toList())).getIntUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Maximum value)return integers(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Maximum(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::i).toList())).getIntUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Minimum value)return integers(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Minimum(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::i).toList())).getIntUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Sum value)return integers(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Sum(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::i).toList())).getIntUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.Product value)return integers(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.ints.Product(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::i).toList())).getIntUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.UniformGenerator value)return integerUnsafe(value.min().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(min->integerUnsafe(value.max().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(max->original(context,()->new net.minecraft.world.level.storage.loot.providers.number.ints.UniformGenerator(i(min),i(max)).getIntUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.BinomialDistributionGenerator value)return integerUnsafe(value.n().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(n->floatingUnsafe(value.p().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(p->!Float.isFinite(p)?CompletableFuture.failedFuture(new ArithmeticValueFailure(new ArithmeticException("Invalid binomial probability: "+p))):original(context,()->new net.minecraft.world.level.storage.loot.providers.number.ints.BinomialDistributionGenerator(i(n),f(p)).getIntUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.ConditionalValue value)return ScarpetLootConditions.test(value.condition().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(passed->integerUnsafe((passed?value.onTrue():value.onFalse()).value(),context)));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.NumberDispatcher value)return select(value.cases(),value.defaultValue(),context,0).thenCompose(ScarpetRuntime.captureNativeFunction(selected->integerUnsafe(selected.value(),context)));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.WeightedListValue value)return original(context,()->value.distribution().getRandomOrThrow(context.getRandom())).thenCompose(ScarpetRuntime.captureNativeFunction(selected->integerUnsafe(selected.value(),context)));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.StorageValue value)return original(context,()->value.access().getNumericTag(context)).thenCompose(ScarpetRuntime.captureNativeFunction(number->number==null?integerUnsafe(value.fallback().value(),context):scalar(number::intValue)));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.ScoreboardValue value)return score(value,context).thenCompose(ScarpetRuntime.captureNativeFunction(number->number==null?integerUnsafe(value.fallback().value(),context):CompletableFuture.completedFuture(number)));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.ints.EnvironmentAttributeValue value)return environment(context,value.attribute().isPositional(),()->value.getIntUnsafe(context));
        return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported native integer provider: "+provider.getClass().getName()));
    }
    private static CompletableFuture<Float> floatingUnsafe(ContextFloatProvider provider,LootContext context){
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.ConstantValue value)return scalar(()->value.getFloatUnsafe(context));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.EnchantmentLevelProvider value)return scalar(()->value.getFloatUnsafe(context));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Absolute value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Absolute(f(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Negate value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Negate(f(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Ceiling value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Ceiling(f(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Cosine value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Cosine(f(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Floor value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Floor(f(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Round value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Round(f(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Sine value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Sine(f(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.SquareRoot value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.SquareRoot(f(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Truncate value)return floatingUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Truncate(f(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.FromInt value)return integerUnsafe(value.input().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(input->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.FromInt(i(input)).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Difference value)return floatingUnsafe(value.left().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->floatingUnsafe(value.right().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Difference(f(left),f(right)).getFloatUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Quotient value)return floatingUnsafe(value.left().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->floatingUnsafe(value.right().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Quotient(f(left),f(right)).getFloatUnsafe(context))))));
        // Float modulus evaluates its right operand first and never evaluates left when right is zero.
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Modulus value)return floatingUnsafe(value.right().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->right==0F?CompletableFuture.completedFuture(Float.NaN):floatingUnsafe(value.left().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Modulus(f(left),f(right)).getFloatUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Power value)return floatingUnsafe(value.base().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(left->floatingUnsafe(value.exponent().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(right->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Power(f(left),f(right)).getFloatUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Average value)return floats(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Average(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::f).toList())).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Maximum value)return floats(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Maximum(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::f).toList())).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Minimum value)return floats(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Minimum(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::f).toList())).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Sum value)return floats(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Sum(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::f).toList())).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Product value)return floats(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Product(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::f).toList())).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.Length value)return floats(value.inputs().stream().toList(),context,0,new ArrayList<>()).thenCompose(ScarpetRuntime.captureNativeFunction(inputs->scalar(()->new net.minecraft.world.level.storage.loot.providers.number.floats.Length(HolderSet.direct(inputs.stream().map(ScarpetLootNumbers::f).toList())).getFloatUnsafe(context))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.UniformGenerator value)return floatingUnsafe(value.min().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(min->floatingUnsafe(value.max().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(max->original(context,()->new net.minecraft.world.level.storage.loot.providers.number.floats.UniformGenerator(f(min),f(max)).getFloatUnsafe(context))))));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.ConditionalValue value)return ScarpetLootConditions.test(value.condition().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(passed->floatingUnsafe((passed?value.onTrue():value.onFalse()).value(),context)));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.NumberDispatcher value)return select(value.cases(),value.defaultValue(),context,0).thenCompose(ScarpetRuntime.captureNativeFunction(selected->floatingUnsafe(selected.value(),context)));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.WeightedListValue value)return original(context,()->value.distribution().getRandomOrThrow(context.getRandom())).thenCompose(ScarpetRuntime.captureNativeFunction(selected->floatingUnsafe(selected.value(),context)));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.StorageValue value)return original(context,()->value.access().getNumericTag(context)).thenCompose(ScarpetRuntime.captureNativeFunction(number->number==null?floatingUnsafe(value.fallback().value(),context):scalar(number::floatValue)));
        if(provider instanceof net.minecraft.world.level.storage.loot.providers.number.floats.EnvironmentAttributeValue value)return environment(context,value.attribute().isPositional(),()->value.getFloatUnsafe(context));
        return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported native floating provider: "+provider.getClass().getName()));
    }
    private static CompletableFuture<List<Integer>> integers(List<Holder<ContextIntProvider>> inputs,LootContext context,int index,List<Integer> values){
        if(index==inputs.size())return CompletableFuture.completedFuture(List.copyOf(values));
        return integerUnsafe(inputs.get(index).value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(value->{values.add(value);return integers(inputs,context,index+1,values);}));
    }
    private static CompletableFuture<List<Float>> floats(List<Holder<ContextFloatProvider>> inputs,LootContext context,int index,List<Float> values){
        if(index==inputs.size())return CompletableFuture.completedFuture(List.copyOf(values));
        return floatingUnsafe(inputs.get(index).value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(value->{values.add(value);return floats(inputs,context,index+1,values);}));
    }
    private static <T extends Validatable> CompletableFuture<Holder<T>> select(List<DispatcherProvider.Case<T>> cases,Holder<T> fallback,LootContext context,int index){
        if(index==cases.size())return CompletableFuture.completedFuture(fallback);
        var selected=cases.get(index);
        return ScarpetLootConditions.test(selected.condition().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(passed->passed?CompletableFuture.completedFuture(selected.value()):select(cases,fallback,context,index+1)));
    }
    private static CompletableFuture<Integer> score(net.minecraft.world.level.storage.loot.providers.number.ints.ScoreboardValue provider,LootContext context){
        CompletableFuture<String> name;
        if(provider.target() instanceof ContextScoreboardNameProvider target){
            Entity entity=context.getOptional(target.target().contextParam());
            if(entity==null)return CompletableFuture.completedFuture(null);
            name=ScarpetLootConditions.actor(entity,entity::getScoreboardName);
        }else if(provider.target() instanceof FixedScoreboardNameProvider fixed)name=CompletableFuture.completedFuture(fixed.name());
        else return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported native scoreboard name provider: "+provider.target().getClass().getName()));
        return name.thenCompose(ScarpetRuntime.captureNativeFunction(actualName->original(context,()->{
            var scoreboard=context.getLevel().getScoreboard();var objective=scoreboard.getObjective(provider.score());
            if(objective==null)return null;var info=scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly(actualName),objective);return info==null?null:info.value();
        })));
    }
    public static CompletableFuture<Boolean> ranges(IntRangePredicate range,LootContext context,ContextIntProvider input){
        if(range instanceof IntRangePredicate.Line line&&line.carpetMin().isEmpty()&&line.carpetMax().isEmpty())return CompletableFuture.completedFuture(true);
        return admit(context,()->integer(input,context).thenCompose(ScarpetRuntime.captureNativeFunction(value->ranges(range,context,value))));
    }
    public static CompletableFuture<Boolean> ranges(FloatRangePredicate range,LootContext context,ContextFloatProvider input){
        if(range instanceof FloatRangePredicate.Line line&&line.carpetMin().isEmpty()&&line.carpetMax().isEmpty())return CompletableFuture.completedFuture(true);
        return admit(context,()->floating(input,context).thenCompose(ScarpetRuntime.captureNativeFunction(value->ranges(range,context,value))));
    }
    public static CompletableFuture<Boolean> ranges(IntRangePredicate range,LootContext context,int value){
        return admit(context,()->{
            if(range instanceof IntRangePredicate.Point point)return integer(point.value().value(),context).thenApply(bound->bound==value);
            var line=(IntRangePredicate.Line)range;
            Supplier<CompletableFuture<Boolean>> max=ScarpetRuntime.captureNativeContinuation(()->line.carpetMax().isEmpty()?CompletableFuture.completedFuture(true):integer(line.carpetMax().get().value(),context).thenApply(bound->value<=bound));
            return line.carpetMin().isEmpty()?max.get():integer(line.carpetMin().get().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(bound->value>=bound?max.get():CompletableFuture.completedFuture(false)));
        });
    }
    public static CompletableFuture<Boolean> ranges(FloatRangePredicate range,LootContext context,float value){
        return admit(context,()->{
            if(range instanceof FloatRangePredicate.Point point)return floating(point.value().value(),context).thenApply(bound->bound==value);
            var line=(FloatRangePredicate.Line)range;
            Supplier<CompletableFuture<Boolean>> max=ScarpetRuntime.captureNativeContinuation(()->line.carpetMax().isEmpty()?CompletableFuture.completedFuture(true):floating(line.carpetMax().get().value(),context).thenApply(bound->value<=bound));
            return line.carpetMin().isEmpty()?max.get():floating(line.carpetMin().get().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(bound->value>=bound?max.get():CompletableFuture.completedFuture(false)));
        });
    }
    public static CompletableFuture<Integer> clamp(IntLimit limit,LootContext context,int value){
        return admit(context,()->{
            if(limit.carpetMin().isEmpty())return limit.carpetMax().isEmpty()?CompletableFuture.completedFuture(value):integer(limit.carpetMax().get().value(),context).thenApply(max->Math.min(max,value));
            return integer(limit.carpetMin().get().value(),context).thenCompose(ScarpetRuntime.captureNativeFunction(min->limit.carpetMax().isEmpty()?CompletableFuture.completedFuture(Math.max(min,value)):integer(limit.carpetMax().get().value(),context).thenApply(max->Mth.clamp(value,min,max))));
        });
    }
    private record Outcome<T>(T value,ArithmeticException failure){
        static <T> Outcome<T> evaluate(Supplier<T> body){try{return new Outcome<>(body.get(),null);}catch(ArithmeticException failure){return new Outcome<>(null,failure);}}
        CompletableFuture<T> result(){return failure==null?CompletableFuture.completedFuture(value):CompletableFuture.failedFuture(new ArithmeticValueFailure(failure));}
    }
    private static <T> CompletableFuture<Outcome<T>> observe(LootContext context,Supplier<T> body){
        var observed=ScarpetNativeWork.observeNative(null,()->Outcome.evaluate(body));
        var world=context.getLevel();if(world!=null)ScarpetNativeWork.trackNative(world.getServer(),observed);
        return ScarpetNativeWork.recoverGuestValue(observed);
    }
    private static <T> CompletableFuture<T> original(LootContext context,Supplier<T> body){
        if(ScarpetLootRandomOwners.bound(context))return ScarpetLootRandomOwners.consume(context,()->Outcome.evaluate(body)).thenCompose(ScarpetRuntime.captureNativeFunction(value->value.result()));
        var world=context.getLevel();Supplier<CompletableFuture<Outcome<T>>> accepted=ScarpetRuntime.captureNativeContinuation(()->observe(context,body));
        CompletableFuture<Outcome<T>> actual;
        if(world==null)actual=accepted.get();
        else{
            Vec3 origin=context.getOptional(LootContextParams.ORIGIN);
            actual=ScarpetExplosionActors.world(world,BlockPos.containing(origin==null?Vec3.ZERO:origin),accepted).thenCompose(ScarpetRuntime.captureNativeFunction(value->value));
        }
        return actual.thenCompose(ScarpetRuntime.captureNativeFunction(Outcome::result));
    }
    private static <T> CompletableFuture<T> environment(LootContext context,boolean positional,Supplier<T> body){
        Vec3 origin=context.getOptional(LootContextParams.ORIGIN);
        if(!positional||origin==null||context.getLevel()==null)return original(context,body);
        BlockPos block=BlockPos.containing(origin);
        Supplier<CompletableFuture<Outcome<T>>> accepted=ScarpetRuntime.captureNativeContinuation(()->observe(context,body));
        var actual=fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<Outcome<T>>>runLoadedValue(context.getLevel(),(block.getX()-16)>>4,(block.getZ()-16)>>4,(block.getX()+16)>>4,(block.getZ()+16)>>4,lease->accepted.get()).thenCompose(ScarpetRuntime.captureNativeFunction(value->value));
        return actual.thenCompose(ScarpetRuntime.captureNativeFunction(Outcome::result));
    }
}
