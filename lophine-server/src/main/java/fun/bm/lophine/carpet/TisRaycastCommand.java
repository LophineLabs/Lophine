/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Adapted from Carpet TIS Addition, Fallen_Breath and contributors.
 * Upstream revision: 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
 */
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class TisRaycastCommand {
    private TisRaycastCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("raycast")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandRaycast))
                .then(Commands.literal("emsim").requires(source -> "endermelon".equals(GeneralCompatConfig.ultraSecretSetting))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("max_r", IntegerArgumentType.integer()).executes(context -> {
                                    context.getSource().getEntityOrException();
                                    TisRaycastSimulator.simulate(context.getSource(), BlockPosArgument.getLoadedBlockPos(context, "pos"), IntegerArgumentType.getInteger(context, "max_r"));
                                    return 0;
                                }))))
                .then(Commands.literal("block")
                        .then(Commands.argument("start", Vec3Argument.vec3())
                                .then(Commands.argument("end", Vec3Argument.vec3()).executes(TisRaycastCommand::raycast)
                                        .then(Commands.argument("shapeMode", StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(ClipContext.Block.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT)), builder))
                                                .executes(TisRaycastCommand::raycast)
                                                .then(Commands.argument("fluidMode", StringArgumentType.word())
                                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(ClipContext.Fluid.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT)), builder))
                                                        .executes(TisRaycastCommand::raycast)))))));
    }

    private static int raycast(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Vec3 from = Vec3Argument.getVec3(context, "start");
        Vec3 to = Vec3Argument.getVec3(context, "end");
        ClipContext.Block block = enumArgument(context, "shapeMode", ClipContext.Block.COLLIDER);
        ClipContext.Fluid fluid = enumArgument(context, "fluidMode", ClipContext.Fluid.NONE);
        Entity entity = source.getEntityOrException();
        ClipContext traceContext = new SnapshotClipContext(from, to, block, fluid, entity);
        Trace trace = new Trace(source.getLevel(), traceContext);
        trace.completion.whenComplete((result, failure) -> feedback(source, failure == null
                ? result.hit().getType() == HitResult.Type.MISS ? TisTranslations.message(source, "command.raycast.missed")
                : TisTranslations.text("command.raycast.hit").append(Component.literal(" " + result.blockDescription() + " at " + result.hit().getBlockPos().toShortString() + " (" + result.hit().getLocation() + ")"))
                : Component.literal("Raycast failed: " + failure.getMessage())));
        trace.runNextRegion();
        return 1;
    }

    private static <T extends Enum<T>> T enumArgument(final CommandContext<?> context, final String name, final T fallback) throws CommandSyntaxException {
        String text;
        try {
            text = StringArgumentType.getString(context, name);
        } catch (IllegalArgumentException absent) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new SimpleCommandExceptionType(TisTranslations.text("command.raycast.unknown_enum." + name, text)).create();
        }
    }

    static void feedback(final CommandSourceStack source, final String message) {
        feedback(source, Component.literal(message));
    }

    static void feedback(final CommandSourceStack source, final Component message) {
        Runnable send = () -> source.sendSuccess(() -> source.getEntity() instanceof ServerPlayer ? message
                : TisTranslations.translateText(message, TisTranslations.serverLanguage()), false);
        if (source.getEntity() instanceof ServerPlayer player && !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)) {
            player.getBukkitEntity().taskScheduler.schedule(entity -> send.run(), null, 1L);
        } else if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            send.run();
        } else {
            RegionizedServer.getInstance().addTask(send);
        }
    }

    private record TraceResult(BlockHitResult hit, String blockDescription) {
    }

    private static final class Trace {
        private final ServerLevel level;
        private final ClipContext context;
        private final RayWalker walker;
        private final CompletableFuture<TraceResult> completion = new CompletableFuture<>();

        private Trace(final ServerLevel level, final ClipContext context) {
            this.level = level;
            this.context = context;
            this.walker = new RayWalker(context.getFrom(), context.getTo());
        }

        private void runNextRegion() {
            if (this.walker.pos == null) {
                Vec3 delta = this.context.getFrom().subtract(this.context.getTo());
                this.completion.complete(new TraceResult(BlockHitResult.miss(this.context.getTo(),
                        Direction.getApproximateNearest(delta.x(), delta.y(), delta.z()), BlockPos.containing(this.context.getTo())), ""));
                return;
            }
            BlockPos first = this.walker.pos;
            RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(this.level, first.getX() >> 4, first.getZ() >> 4, () -> {
                try {
                    while (this.walker.pos != null && (this.walker.pos.getX() >> 4) == (first.getX() >> 4)
                            && (this.walker.pos.getZ() >> 4) == (first.getZ() >> 4)) {
                        BlockHitResult hit = this.level.clip(this.context, this.walker.pos);
                        if (hit != null) {
                            String state = hit.getType() == HitResult.Type.BLOCK ? this.level.getBlockState(hit.getBlockPos()).toString() : "";
                            this.completion.complete(new TraceResult(hit, state));
                            return;
                        }
                        this.walker.advance();
                    }
                    this.runNextRegion();
                } catch (Throwable failure) {
                    this.completion.completeExceptionally(failure);
                }
            });
        }
    }

    // The same epsilon, DDA tie breaking and traversal order as BlockGetter.traverseBlocks,
    // streamed one chunk at a time instead of retaining an unbounded list of positions.
    private static final class RayWalker {
        private BlockPos pos;
        private final int signX, signY, signZ;
        private final double deltaX, deltaY, deltaZ;
        private double timeX, timeY, timeZ;

        private RayWalker(final Vec3 from, final Vec3 to) {
            double toX = Mth.lerp(-1.0E-7, to.x(), from.x());
            double toY = Mth.lerp(-1.0E-7, to.y(), from.y());
            double toZ = Mth.lerp(-1.0E-7, to.z(), from.z());
            double fromX = Mth.lerp(-1.0E-7, from.x(), to.x());
            double fromY = Mth.lerp(-1.0E-7, from.y(), to.y());
            double fromZ = Mth.lerp(-1.0E-7, from.z(), to.z());
            double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
            this.signX = Mth.sign(dx);
            this.signY = Mth.sign(dy);
            this.signZ = Mth.sign(dz);
            this.deltaX = this.signX == 0 ? Double.MAX_VALUE : this.signX / dx;
            this.deltaY = this.signY == 0 ? Double.MAX_VALUE : this.signY / dy;
            this.deltaZ = this.signZ == 0 ? Double.MAX_VALUE : this.signZ / dz;
            this.timeX = this.deltaX * (this.signX > 0 ? 1.0 - Mth.frac(fromX) : Mth.frac(fromX));
            this.timeY = this.deltaY * (this.signY > 0 ? 1.0 - Mth.frac(fromY) : Mth.frac(fromY));
            this.timeZ = this.deltaZ * (this.signZ > 0 ? 1.0 - Mth.frac(fromZ) : Mth.frac(fromZ));
            this.pos = from.equals(to) ? null : BlockPos.containing(fromX, fromY, fromZ);
        }

        private void advance() {
            if (this.timeX > 1.0 && this.timeY > 1.0 && this.timeZ > 1.0) {
                this.pos = null;
            } else if (this.timeX < this.timeY) {
                if (this.timeX < this.timeZ) {
                    this.pos = this.pos.offset(this.signX, 0, 0);
                    this.timeX += this.deltaX;
                } else {
                    this.pos = this.pos.offset(0, 0, this.signZ);
                    this.timeZ += this.deltaZ;
                }
            } else if (this.timeY < this.timeZ) {
                this.pos = this.pos.offset(0, this.signY, 0);
                this.timeY += this.deltaY;
            } else {
                this.pos = this.pos.offset(0, 0, this.signZ);
                this.timeZ += this.deltaZ;
            }
        }
    }

    // Capture entity-dependent collision inputs before querying another Folia region.
    // In 26.3 only powder snow and liquid block shapes inspect the collision entity.
    private static final class SnapshotClipContext extends ClipContext {
        private final ClipContext.Block mode;
        private final SnapshotCollisionContext snapshot;

        private SnapshotClipContext(final Vec3 from, final Vec3 to, final ClipContext.Block block, final ClipContext.Fluid fluid, final Entity entity) {
            this(from, to, block, fluid, new SnapshotCollisionContext(entity));
        }

        private SnapshotClipContext(final Vec3 from, final Vec3 to, final ClipContext.Block block, final ClipContext.Fluid fluid, final SnapshotCollisionContext snapshot) {
            super(from, to, block, fluid, snapshot);
            this.mode = block;
            this.snapshot = snapshot;
        }

        @Override
        public VoxelShape getBlockShape(final BlockState state, final BlockGetter level, final BlockPos pos) {
            if (this.mode == ClipContext.Block.COLLIDER) {
                if (state.is(Blocks.POWDER_SNOW)) {
                    if (this.snapshot.fallDistance > 2.5) return Shapes.box(0.0, 0.0, 0.0, 1.0, 0.9F, 1.0);
                    return this.snapshot.fallingBlock || this.snapshot.walksOnPowderSnow && this.snapshot.isAbove(Shapes.block(), pos, false)
                            && !this.snapshot.descending ? Shapes.block() : Shapes.empty();
                }
                if (state.getBlock() instanceof LiquidBlock) {
                    return state.getValue(LiquidBlock.LEVEL) == 0 && this.snapshot.living
                            && this.snapshot.isAbove(this.snapshot.liquidShape, pos, true)
                            && this.snapshot.canStandOnFluid(level.getFluidState(pos.above()), state.getFluidState())
                            ? this.snapshot.liquidShape : Shapes.empty();
                }
            } else if (this.mode == ClipContext.Block.FALLDAMAGE_RESETTING) {
                if (state.is(BlockTags.FALL_DAMAGE_RESETTING)) return Shapes.block();
                if (this.snapshot.player && (state.is(Blocks.END_GATEWAY) || state.is(Blocks.END_PORTAL)))
                    return Shapes.block();
                return this.snapshot.player && state.is(Blocks.NETHER_PORTAL) && level instanceof ServerLevel serverLevel
                        && serverLevel.getGameRules().get(GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY) == 0 ? Shapes.block() : Shapes.empty();
            }
            return super.getBlockShape(state, level, pos);
        }
    }

    private static final class SnapshotCollisionContext implements CollisionContext {
        private final boolean descending, fallingBlock, walksOnPowderSnow, living, player;
        private final double entityBottom, fallDistance;
        private final ItemStack held;
        private final VoxelShape liquidShape;
        private final Set<Fluid> walkableFluids = new HashSet<>();

        private SnapshotCollisionContext(final Entity entity) {
            this.descending = entity.isDescending();
            this.entityBottom = entity.getY();
            this.fallDistance = entity.fallDistance;
            this.fallingBlock = entity instanceof FallingBlockEntity;
            this.walksOnPowderSnow = PowderSnowBlock.canEntityWalkOnPowderSnow(entity);
            this.player = entity.is(EntityTypes.PLAYER);
            this.living = entity instanceof LivingEntity;
            if (entity instanceof LivingEntity mob) {
                this.held = mob.getMainHandItem().copy();
                this.liquidShape = mob.getLiquidCollisionShape();
                for (Fluid fluid : BuiltInRegistries.FLUID) {
                    if (mob.canStandOnFluid(fluid.defaultFluidState())) this.walkableFluids.add(fluid);
                }
            } else {
                this.held = ItemStack.EMPTY;
                this.liquidShape = Shapes.empty();
            }
        }

        @Override
        public boolean isDescending() {
            return this.descending;
        }

        @Override
        public boolean isAbove(final VoxelShape shape, final BlockPos pos, final boolean fallback) {
            return this.entityBottom > pos.getY() + shape.max(Direction.Axis.Y) - 1.0E-5F;
        }

        @Override
        public boolean isHoldingItem(final Item item) {
            return this.held.is(item);
        }

        @Override
        public boolean alwaysCollideWithFluid() {
            return false;
        }

        @Override
        public boolean canStandOnFluid(final FluidState above, final FluidState fluid) {
            return this.walkableFluids.contains(fluid.getType()) && !above.getType().isSame(fluid.getType());
        }

        @Override
        public VoxelShape getCollisionShape(final BlockState state, final CollisionGetter level, final BlockPos pos) {
            return state.getCollisionShape(level, pos, this);
        }
    }
}
