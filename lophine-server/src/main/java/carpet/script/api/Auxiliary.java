package carpet.script.api;

import carpet.script.*;
import carpet.script.argument.BlockArgument;
import carpet.script.argument.FileArgument;
import carpet.script.argument.FunctionArgument;
import carpet.script.argument.Vector3Argument;
import carpet.script.exception.ExitStatement;
import carpet.script.exception.InternalExpressionException;
import carpet.script.external.ActorFunctions;
import carpet.script.external.Carpet;
import carpet.script.external.Vanilla;
import carpet.script.utils.*;
import carpet.script.value.*;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Rotations;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.io.file.PathUtils;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.max;
import static java.lang.Math.min;

public class Auxiliary {
    private static final Set<Path> DATAPACK_INSTALLATIONS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static final String MARKER_STRING = "__scarpet_marker";
    private static final Map<String, SoundSource> mixerMap = Arrays.stream(SoundSource.values()).collect(Collectors.toMap(SoundSource::getName, k -> k));
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().registerTypeAdapter(Value.class, new ScarpetJsonDeserializer()).create();

    @Deprecated
    public static String recognizeResource(Value value, boolean isFloder) {
        String origfile = value.getString();
        String file = origfile.toLowerCase(Locale.ROOT).replaceAll("[^A-Za-z0-9\\-+_/]", "");
        file = Arrays.stream(file.split("/+")).filter(s -> !s.isEmpty()).collect(Collectors.joining("/"));
        if (file.isEmpty() && !isFloder) {
            throw new InternalExpressionException("Cannot use " + origfile + " as resource name - must have some letters and numbers");
        }
        return file;
    }

    public static void apply(Expression expression) {
        expression.addContextFunction("sound", -1, (c, t, lv) -> {
            CarpetContext cc = (CarpetContext) c;
            if (lv.isEmpty()) {
                return ListValue.wrap(cc.registry(Registries.SOUND_EVENT).listElements().map(soundEventReference -> ValueConversions.of(soundEventReference.key().identifier())));
            }
            String rawString = lv.get(0).getString();
            Identifier soundName = InputValidator.identifierOf(rawString);
            Vector3Argument locator = Vector3Argument.findIn(lv, 1);

            Holder<SoundEvent> soundHolder = Holder.direct(SoundEvent.createVariableRangeEvent(soundName));
            float volume = 1.0F;
            float pitch = 1.0F;
            SoundSource mixer = SoundSource.MASTER;
            if (lv.size() > locator.offset) {
                volume = (float) NumericValue.asNumber(lv.get(locator.offset)).getDouble();
                if (lv.size() > 1 + locator.offset) {
                    pitch = (float) NumericValue.asNumber(lv.get(1 + locator.offset)).getDouble();
                    if (lv.size() > 2 + locator.offset) {
                        String mixerName = lv.get(2 + locator.offset).getString();
                        mixer = mixerMap.get(mixerName.toLowerCase(Locale.ROOT));
                        if (mixer == null) {
                            throw new InternalExpressionException(mixerName + " is not a valid mixer name");
                        }
                    }
                }
            }
            Vec3 vec = locator.vec;
            double d0 = Math.pow(volume > 1.0F ? (double) (volume * 16.0F) : 16.0D, 2.0D);
            ServerLevel level = cc.level();
            List<ServerPlayer> audience = carpet.script.external.EntityActors.players(cc.server(), level, p -> p.distanceToSqr(vec) < d0);
            if (audience.isEmpty()) return Value.ZERO;
            long seed = carpet.script.external.ScarpetRuntime.atEntity(audience.getFirst(), () -> audience.getFirst().level().getRandom().nextLong());
            ClientboundSoundPacket sound = new ClientboundSoundPacket(soundHolder, mixer, vec.x, vec.y, vec.z, volume, pitch, seed);
            for (ServerPlayer player : audience)
                carpet.script.external.ScarpetRuntime.atEntity(player, () -> {
                    if (player.level() == level && player.distanceToSqr(vec) < d0) player.connection.send(sound);
                    return null;
                });
            return new NumericValue(audience.size());
        });

        expression.addContextFunction("particle", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            if (lv.isEmpty()) {
                return ListValue.wrap(cc.registry(Registries.PARTICLE_TYPE).listElements().map(particleTypeReference -> ValueConversions.of(particleTypeReference.key().identifier())));
            }
            MinecraftServer ms = cc.server();
            ServerLevel world = cc.level();
            Vector3Argument locator = Vector3Argument.findIn(lv, 1);
            String particleName = lv.get(0).getString();
            int count = 10;
            double speed = 0;
            float spread = 0.5f;
            ServerPlayer player = null;
            if (lv.size() > locator.offset) {
                count = (int) NumericValue.asNumber(lv.get(locator.offset)).getLong();
                if (lv.size() > 1 + locator.offset) {
                    spread = (float) NumericValue.asNumber(lv.get(1 + locator.offset)).getDouble();
                    if (lv.size() > 2 + locator.offset) {
                        speed = NumericValue.asNumber(lv.get(2 + locator.offset)).getDouble();
                        if (lv.size() > 3 + locator.offset) // should accept entity as well as long as it is player
                        {
                            player = carpet.script.external.EntityActors.player(ms, lv.get(3 + locator.offset).getString());
                        }
                    }
                }
            }
            ParticleOptions particle = ShapeDispatcher.getParticleData(particleName, world.registryAccess());
            Vec3 vec = locator.vec;
            int capturedCount = count;
            float capturedSpread = spread;
            double capturedSpeed = speed;
            List<ServerPlayer> audience = player == null ? carpet.script.external.EntityActors.players(ms, world, p -> true) : List.of(player);
            for (ServerPlayer recipient : audience)
                carpet.script.external.ScarpetRuntime.atEntity(recipient, () -> {
                    world.sendParticles(recipient, particle, true, true, vec.x, vec.y, vec.z, capturedCount,
                            capturedSpread, capturedSpread, capturedSpread, capturedSpeed);
                    return null;
                });

            return Value.TRUE;
        });

        expression.addContextFunction("particle_line", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            ServerLevel world = cc.level();
            String particleName = lv.get(0).getString();
            ParticleOptions particle = ShapeDispatcher.getParticleData(particleName, world.registryAccess());
            Vector3Argument pos1 = Vector3Argument.findIn(lv, 1);
            Vector3Argument pos2 = Vector3Argument.findIn(lv, pos1.offset);
            double density = 1.0;
            ServerPlayer player = null;
            if (lv.size() > pos2.offset) {
                density = NumericValue.asNumber(lv.get(pos2.offset)).getDouble();
                if (density <= 0) {
                    throw new InternalExpressionException("Particle density should be positive");
                }
                if (lv.size() > pos2.offset + 1) {
                    Value playerValue = lv.get(pos2.offset + 1);
                    if (playerValue instanceof EntityValue entityValue) {
                        Entity e = entityValue.getEntity();
                        if (!(e instanceof final ServerPlayer sp)) {
                            throw new InternalExpressionException("'particle_line' player argument has to be a player");
                        }
                        player = sp;
                    } else {
                        player = carpet.script.external.EntityActors.player(cc.server(), playerValue.getString());
                    }
                }
            }

            return new NumericValue(ShapeDispatcher.drawParticleLine(
                    (player == null) ? carpet.script.external.EntityActors.players(cc.server(), world, p -> true) : Collections.singletonList(player),
                    particle, pos1.vec, pos2.vec, density
            ));
        });

        expression.addContextFunction("item_display_name", 1, (c, t, lv) -> new FormattedTextValue(ValueConversions.getItemStackFromValue(lv.get(0), false, ((CarpetContext) c).registryAccess()).getHoverName()));

        expression.addContextFunction("particle_box", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            ServerLevel world = cc.level();
            String particleName = lv.get(0).getString();
            ParticleOptions particle = ShapeDispatcher.getParticleData(particleName, world.registryAccess());
            Vector3Argument pos1 = Vector3Argument.findIn(lv, 1);
            Vector3Argument pos2 = Vector3Argument.findIn(lv, pos1.offset);

            double density = 1.0;
            ServerPlayer player = null;
            if (lv.size() > pos2.offset) {
                density = NumericValue.asNumber(lv.get(pos2.offset)).getDouble();
                if (density <= 0) {
                    throw new InternalExpressionException("Particle density should be positive");
                }
                if (lv.size() > pos2.offset + 1) {
                    Value playerValue = lv.get(pos2.offset + 1);
                    if (playerValue instanceof EntityValue entityValue) {
                        Entity e = entityValue.getEntity();
                        if (!(e instanceof final ServerPlayer sp)) {
                            throw new InternalExpressionException("'particle_box' player argument has to be a player");
                        }
                        player = sp;
                    } else {
                        player = carpet.script.external.EntityActors.player(cc.server(), playerValue.getString());
                    }
                }
            }
            Vec3 a = pos1.vec;
            Vec3 b = pos2.vec;
            Vec3 from = new Vec3(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z));
            Vec3 to = new Vec3(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z));
            int particleCount = ShapeDispatcher.Box.particleMesh(
                    player == null ? carpet.script.external.EntityActors.players(cc.server(), world, p -> true) : Collections.singletonList(player),
                    particle, density, from, to
            );
            return new NumericValue(particleCount);
        });
        // deprecated
        expression.alias("particle_rect", "particle_box");


        expression.addContextFunction("draw_shape", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            ServerLevel world = cc.level();
            MinecraftServer server = world.getServer();
            Set<ServerPlayer> playerTargets = new HashSet<>();
            List<ShapeDispatcher.ShapeWithConfig> shapes = new ArrayList<>();
            if (lv.size() == 1) // bulk
            {
                Value specLoad = lv.get(0);
                if (!(specLoad instanceof final ListValue spec)) {
                    throw new InternalExpressionException("In bulk mode - shapes need to be provided as a list of shape specs");
                }
                for (Value list : spec.getItems()) {
                    if (!(list instanceof final ListValue inner)) {
                        throw new InternalExpressionException("In bulk mode - shapes need to be provided as a list of shape specs");
                    }
                    shapes.add(ShapeDispatcher.fromFunctionArgs(server, world, inner.getItems(), playerTargets));
                }
            } else {
                shapes.add(ShapeDispatcher.fromFunctionArgs(server, world, lv, playerTargets));
            }

            ShapeDispatcher.sendShape(
                    playerTargets.isEmpty() ? carpet.script.external.EntityActors.players(cc.server(), world, p -> true) : playerTargets,
                    shapes, cc.registryAccess()
            );
            return Value.TRUE;
        });

        expression.addContextFunction("create_marker", -1, ActorFunctions.vector(1, (c, t, lv) -> {
            CarpetContext cc = (CarpetContext) c;
            BlockState targetBlock = null;
            Vector3Argument pointLocator;
            boolean interactable = true;
            Component name;
            try {
                Value nameValue = lv.get(0);
                name = nameValue.isNull() ? null : FormattedTextValue.getTextByValue(nameValue);
                pointLocator = Vector3Argument.findIn(lv, 1, true, false);
                if (lv.size() > pointLocator.offset) {
                    BlockArgument blockLocator = BlockArgument.findIn(cc, lv, pointLocator.offset, true, true, false);
                    if (!(blockLocator instanceof BlockArgument.MissingBlockArgument)) {
                        targetBlock = blockLocator.block.getBlockState();
                    }
                    if (lv.size() > blockLocator.offset) {
                        interactable = lv.get(blockLocator.offset).getBoolean();
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                throw new InternalExpressionException("'create_marker' requires a name and three coordinates, with optional direction, and optional block on its head");
            }
            Level level = cc.level();
            ArmorStand armorstand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
            double yoffset;
            if (targetBlock == null && name == null) {
                yoffset = 0.0;
            } else if (!interactable && targetBlock == null) {
                yoffset = -0.41;
            } else {
                if (targetBlock == null) {
                    yoffset = -armorstand.getBbHeight() - 0.41;
                } else {
                    yoffset = -armorstand.getBbHeight() + 0.3;
                }
            }
            armorstand.snapTo(
                    pointLocator.vec.x,
                    //pointLocator.vec.y - ((!interactable && targetBlock == null)?0.41f:((targetBlock==null)?(armorstand.getHeight()+0.41):(armorstand.getHeight()-0.3))),
                    pointLocator.vec.y + yoffset,
                    pointLocator.vec.z,
                    (float) pointLocator.yaw,
                    (float) pointLocator.pitch
            );
            armorstand.addTag(MARKER_STRING + "_" + ((cc.host.getName() == null) ? "" : cc.host.getName()));
            armorstand.addTag(MARKER_STRING);
            if (targetBlock != null) {
                armorstand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(targetBlock.getBlock().asItem()));
            }
            if (name != null) {
                armorstand.setCustomName(name);
                armorstand.setCustomNameVisible(true);
            }
            armorstand.setHeadPose(new Rotations((int) pointLocator.pitch, 0, 0));
            armorstand.setNoGravity(true);
            armorstand.setInvisible(true);
            armorstand.setPermanentlyInvulnerable(true);
            armorstand.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (interactable ? 8 : 16 | 8));
            level.addFreshEntity(armorstand);
            return new EntityValue(armorstand);
        }));

        expression.addContextFunction("remove_all_markers", 0, (c, t, lv) -> {
            CarpetContext cc = (CarpetContext) c;
            int total = 0;
            String markerName = MARKER_STRING + "_" + ((cc.host.getName() == null) ? "" : cc.host.getName());
            for (Entity e : carpet.script.external.EntityActors.entities(cc.level(), EntityTypes.ARMOR_STAND, as -> as.entityTags().contains(markerName), null)) {
                total++;
                carpet.script.external.ScarpetRuntime.atEntity(e, () -> {
                    e.discard();
                    return null;
                });
            }
            return new NumericValue(total);
        });

        expression.addUnaryFunction("nbt", NBTSerializableValue::fromValue);

        expression.addUnaryFunction("escape_nbt", v -> new StringValue(StringTag.quoteAndEscape(v.getString())));

        expression.addUnaryFunction("parse_nbt", v -> {
            if (v instanceof final NBTSerializableValue nbtsv) {
                return nbtsv.toValue();
            }
            NBTSerializableValue ret = NBTSerializableValue.parseString(v.getString());
            return ret == null ? Value.NULL : ret.toValue();
        });

        expression.addFunction("tag_matches", lv -> {
            int numParam = lv.size();
            if (numParam != 2 && numParam != 3) {
                throw new InternalExpressionException("'tag_matches' requires 2 or 3 arguments");
            }
            if (lv.get(1).isNull()) {
                return Value.TRUE;
            }
            if (lv.get(0).isNull()) {
                return Value.FALSE;
            }
            Tag source = ((NBTSerializableValue) NBTSerializableValue.fromValue(lv.get(0))).getTag();
            Tag match = ((NBTSerializableValue) NBTSerializableValue.fromValue(lv.get(1))).getTag();
            return BooleanValue.of(NbtUtils.compareNbt(match, source, numParam == 2 || lv.get(2).getBoolean()));
        });

        expression.addContextFunction("encode_nbt", -1, (c, t, lv) -> {
            int argSize = lv.size();
            if (argSize == 0 || argSize > 2) {
                throw new InternalExpressionException("'encode_nbt' requires 1 or 2 parameters");
            }
            Value v = lv.get(0);
            boolean force = (argSize > 1) && lv.get(1).getBoolean();
            Tag tag;
            try {
                tag = v.toTag(force, ((CarpetContext) c).registryAccess());
            } catch (NBTSerializableValue.IncompatibleTypeException exception) {
                throw new InternalExpressionException("cannot reliably encode to a tag the value of '" + exception.val.getPrettyString() + "'");
            }
            return new NBTSerializableValue(tag);
        });

        //"overridden" native call that prints to stderr
        expression.addContextFunction("print", -1, (c, t, lv) ->
        {
            if (lv.isEmpty() || lv.size() > 2) {
                throw new InternalExpressionException("'print' takes one or two arguments");
            }
            CarpetContext cc = (CarpetContext) c;
            CommandSourceStack s = cc.source();
            MinecraftServer server = s.getServer();
            Value res = lv.get(0);
            List<CommandSourceStack> targets = null;
            if (lv.size() == 2) {
                List<Value> playerValues = (res instanceof ListValue list) ? list.getItems() : Collections.singletonList(res);
                List<CommandSourceStack> playerTargets = new ArrayList<>();
                playerValues.forEach(pv -> {
                    ServerPlayer player = EntityValue.getPlayerByValue(server, pv);
                    if (player == null) {
                        throw new InternalExpressionException("Cannot target player " + pv.getString() + " in print");
                    }
                    playerTargets.add(carpet.script.external.ScarpetRuntime.entitySource(player));
                });
                targets = playerTargets;
                res = lv.get(1);
            } else if (c.host.user != null) {
                ServerPlayer player = carpet.script.external.EntityActors.player(cc.server(), cc.host.user);
                if (player != null) {
                    targets = Collections.singletonList(carpet.script.external.ScarpetRuntime.entitySource(player));
                }
            } // optionally retrieve from CC.host.responsibleSource to print?
            Component message = FormattedTextValue.getTextByValue(res);
            if (targets == null) {
                carpet.script.external.ScarpetRuntime.send(s, message, false);
            } else {
                targets.forEach(p -> carpet.script.external.ScarpetRuntime.send(p, message, false));
            }
            return res; // pass through for variables
        });

        expression.addContextFunction("display_title", -1, (c, t, lv) -> {
            if (lv.size() < 2) {
                throw new InternalExpressionException("'display_title' needs at least a target, type and message, and optionally times");
            }
            Value pVal = lv.get(0);
            if (!(pVal instanceof ListValue)) {
                pVal = ListValue.of(pVal);
            }
            MinecraftServer server = ((CarpetContext) c).server();
            Stream<ServerPlayer> targets = ((ListValue) pVal).getItems().stream().map(v ->
            {
                ServerPlayer player = EntityValue.getPlayerByValue(server, v);
                if (player == null) {
                    throw new InternalExpressionException("'display_title' requires a valid online player or a list of players as first argument. " + v.getString() + " is not a player.");
                }
                return player;
            });
            Function<Component, Packet<?>> packetGetter = null;
            String actionString = lv.get(1).getString().toLowerCase(Locale.ROOT);
            switch (actionString) {
                case "title" -> {
                    packetGetter = ClientboundSetTitleTextPacket::new;
                    if (lv.size() < 3) {
                        throw new InternalExpressionException("Third argument of 'display_title' must be present except for 'clear' type");
                    }
                }
                case "subtitle" -> {
                    packetGetter = ClientboundSetSubtitleTextPacket::new;
                    if (lv.size() < 3) {
                        throw new InternalExpressionException("Third argument of 'display_title' must be present except for 'clear' type");
                    }
                }
                case "actionbar" -> {
                    packetGetter = ClientboundSetActionBarTextPacket::new;
                    if (lv.size() < 3) {
                        throw new InternalExpressionException("Third argument of 'display_title' must be present except for 'clear' type");
                    }
                }
                case "clear" -> packetGetter = x -> new ClientboundClearTitlesPacket(true); // resetting default fade
                case "player_list_header", "player_list_footer" -> {
                }
                default ->
                        throw new InternalExpressionException("'display_title' requires 'title', 'subtitle', 'actionbar', 'player_list_header', 'player_list_footer' or 'clear' as second argument");
            }
            Component title;
            boolean soundsTrue = false;
            if (lv.size() > 2) {
                pVal = lv.get(2);
                title = FormattedTextValue.getTextByValue(pVal);
                soundsTrue = pVal.getBoolean();
            } else {
                title = null; // Will never happen, just to make lambda happy
            }
            if (packetGetter == null) {
                Map<String, Component> map;
                if (actionString.equals("player_list_header")) {
                    map = Carpet.getScarpetHeaders();
                } else {
                    map = Carpet.getScarpetFooters();
                }

                AtomicInteger total = new AtomicInteger(0);
                List<ServerPlayer> targetList = targets.collect(Collectors.toList());
                if (!soundsTrue) // null or empty string
                {
                    targetList.forEach(target -> {
                        carpet.script.external.ScarpetRuntime.atEntity(target, () -> {
                            map.remove(target.getScoreboardName());
                            return null;
                        });
                        total.getAndIncrement();
                    });
                } else {
                    targetList.forEach(target -> {
                        carpet.script.external.ScarpetRuntime.atEntity(target, () -> {
                            map.put(target.getScoreboardName(), title);
                            return null;
                        });
                        total.getAndIncrement();
                    });
                }
                Carpet.updateScarpetHUDs(((CarpetContext) c).server(), targetList);
                return NumericValue.of(total.get());
            }
            ClientboundSetTitlesAnimationPacket timesPacket; // TimesPacket
            if (lv.size() > 3) {
                if (lv.size() != 6) {
                    throw new InternalExpressionException("'display_title' needs all fade-in, stay and fade-out times");
                }
                int in = NumericValue.asNumber(lv.get(3), "fade in for display_title").getInt();
                int stay = NumericValue.asNumber(lv.get(4), "stay for display_title").getInt();
                int out = NumericValue.asNumber(lv.get(5), "fade out for display_title").getInt();
                timesPacket = new ClientboundSetTitlesAnimationPacket(in, stay, out);
            } else {
                timesPacket = null;
            }

            Packet<?> packet = packetGetter.apply(title);
            AtomicInteger total = new AtomicInteger(0);
            List<ServerPlayer> recipients = targets.toList();
            for (ServerPlayer p : recipients)
                carpet.script.external.ScarpetRuntime.atEntity(p, () -> {
                    if (timesPacket != null) p.connection.send(timesPacket);
                    p.connection.send(packet);
                    total.getAndIncrement();
                    return null;
                });
            return NumericValue.of(total.get());
        });

        expression.addFunction("format", values -> {
            if (values.isEmpty()) {
                throw new InternalExpressionException("'format' requires at least one component");
            }
            if (values.get(0) instanceof final ListValue list && values.size() == 1) {
                values = list.getItems();
            }
            return new FormattedTextValue(Carpet.Messenger_compose(values.stream().map(Value::getString).toArray()));
        });

        expression.addContextFunction("run", 1, (c, t, lv) ->
        {
            CommandSourceStack s = ((CarpetContext) c).source();
            try {
                Component[] error = {null};
                OptionalLong[] returnValue = {OptionalLong.empty()};
                List<Component> output = new java.util.concurrent.CopyOnWriteArrayList<>();
                String command = lv.get(0).getString();
                java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> execute = () -> {
                    try (var scope = fun.bm.lophine.carpet.CarpetAsyncCommandResults.open()) {
                        s.getServer().getCommands().performPrefixedCommand(new SnoopyCommandSource(s, error, output, returnValue), command);
                        return scope.completionFuture();
                    }
                };
                java.util.concurrent.CompletableFuture<Void> completed = s.getEntity() != null
                        ? carpet.script.external.ScarpetRuntime.atEntity(s.getEntity(), execute)
                        : s.getLevel() != null
                        ? carpet.script.external.ScarpetRuntime.atBlock(s.getLevel(), BlockPos.containing(s.getPosition()), execute)
                        : carpet.script.external.ScarpetRuntime.atGlobal(s.getServer(), execute);
                carpet.script.external.ScarpetRuntime.await(completed);
                if (returnValue[0].isEmpty()) {
                    return Value.NULL;
                }
                return ListValue.of(
                        NumericValue.of(returnValue[0].getAsLong()),
                        ListValue.wrap(output.stream().map(FormattedTextValue::new)),
                        FormattedTextValue.of(error[0])
                );
            } catch (Exception exc) {
                return ListValue.of(Value.NULL, ListValue.of(), new FormattedTextValue(Component.literal(exc.getMessage())));
            }
        });

        expression.addContextFunction("save", 0, (c, t, lv) ->
        {
            CommandSourceStack s = ((CarpetContext) c).source();
            carpet.script.external.ScarpetWorldSave.save(s.getServer());
            CarpetScriptServer.LOG.warn("Saved chunks");
            return Value.TRUE;
        });

        expression.addContextFunction("tick_time", 0, (c, t, lv) ->
                new NumericValue(((CarpetContext) c).server().getTickCount()));

        expression.addContextFunction("world_time", 0, ActorFunctions.global((c, t, lv) -> {
            c.host.issueDeprecation("world_time()");
            return new NumericValue(((CarpetContext) c).level().getGameTime());
        }));

        expression.addContextFunction("day_time", -1, ActorFunctions.global((c, t, lv) ->
        {
            Value time = new NumericValue(((CarpetContext) c).level().getOverworldClockTime());
            if (!lv.isEmpty()) {
                long newTime = NumericValue.asNumber(lv.get(0)).getLong();
                if (newTime < 0) {
                    newTime = 0;
                }
                ((CarpetContext) c).level().clockManager().setTotalTicks(((CarpetContext) c).registryAccess().getOrThrow(WorldClocks.OVERWORLD), newTime);
            }
            return time;
        }));

        expression.addContextFunction("last_tick_times", -1, (c, t, lv) ->
        {
            c.host.issueDeprecation("last_tick_times()");
            return SystemInfo.get("server_last_tick_times", (CarpetContext) c);
        });


        expression.addContextFunction("game_tick", -1, (c, t, lv) -> {
            CarpetContext cc = (CarpetContext) c;
            MinecraftServer server = cc.server();
            CarpetScriptServer scriptServer = (CarpetScriptServer) c.host.scriptServer();
            if (scriptServer == null) {
                return Value.NULL;
            }
            if (!server.isSameThread()) {
                // Scripts await the actual global tick; they never invoke a region tick from a worker.
            }
            if (scriptServer.tickDepth > 16) {
                throw new InternalExpressionException("'game_tick' function caused other 'game_tick' functions to run. You should not allow that.");
            }
            try {
                scriptServer.tickDepth++;
                Vanilla.MinecraftServer_forceTick(server, () -> System.nanoTime() - scriptServer.tickStart < 50000000L);
                if (!lv.isEmpty()) {
                    long msTotal = NumericValue.asNumber(lv.get(0)).getLong();
                    long endExpected = scriptServer.tickStart + msTotal * 1000000L;
                    long wait = endExpected - System.nanoTime();
                    if (wait > 0L) {
                        try {
                            carpet.script.external.ScarpetRuntime.await(java.util.concurrent.CompletableFuture.runAsync(() -> {
                            }, java.util.concurrent.CompletableFuture.delayedExecutor(wait, java.util.concurrent.TimeUnit.NANOSECONDS)));
                        } catch (java.util.concurrent.CancellationException interrupted) {
                            throw new InternalExpressionException("Scarpet game_tick interrupted");
                        }
                    }
                }
                scriptServer.tickStart = System.nanoTime(); // for the next tick
                Thread.yield();
            } finally {
                if (!scriptServer.stopAll) {
                    scriptServer.tickDepth--;
                }
            }
            if (scriptServer.stopAll) {
                throw new ExitStatement(Value.NULL);
            }
            return Value.TRUE;
        });

        expression.addContextFunction("seed", -1, (c, t, lv) -> {
            CommandSourceStack s = ((CarpetContext) c).source();
            c.host.issueDeprecation("seed()");
            return new NumericValue(s.getLevel().getSeed());
        });

        expression.addContextFunction("relight", -1, ActorFunctions.block(0, false, (c, t, lv) ->
        {
            return Value.NULL;
            /*
            CarpetContext cc = (CarpetContext) c;
            BlockArgument locator = BlockArgument.findIn(cc, lv, 0);
            BlockPos pos = locator.block.getPos();
            ServerLevel world = cc.level();
            Vanilla.ChunkMap_relightChunk(world.getChunkSource().chunkMap, new ChunkPos(pos));
            WorldTools.forceChunkUpdate(pos, world);
            return Value.TRUE;

             */
        }));

        // Should this be deprecated for system_info('source_dimension')?
        expression.addContextFunction("current_dimension", 0, (c, t, lv) ->
                ValueConversions.of(((CarpetContext) c).level()));

        expression.addContextFunction("view_distance", 0, (c, t, lv) -> {
            c.host.issueDeprecation("view_distance()");
            return new NumericValue(((CarpetContext) c).server().getPlayerList().getViewDistance());
        });

        // lazy due to passthrough and context changing ability
        expression.addLazyFunction("in_dimension", 2, (c, t, lv) -> {
            CommandSourceStack outerSource = ((CarpetContext) c).source();
            Value dimensionValue = lv.get(0).evalValue(c);
            Level world = ValueConversions.dimFromValue(dimensionValue, outerSource.getServer());
            if (world == outerSource.getLevel()) {
                return lv.get(1);
            }
            CommandSourceStack innerSource = outerSource.withLevel((ServerLevel) world);
            Context newCtx = c.recreate();
            ((CarpetContext) newCtx).swapSource(innerSource);
            newCtx.variables = c.variables;
            Value retval = lv.get(1).evalValue(newCtx);
            return (cc, tt) -> retval;
        });

        expression.addContextFunction("plop", -1, (c, t, lv) -> {
            if (lv.isEmpty()) {
                Map<Value, Value> plopData = new HashMap<>();
                CarpetContext cc = (CarpetContext) c;
                plopData.put(StringValue.of("scarpet_custom"),
                        ListValue.wrap(FeatureGenerator.featureMap.keySet().stream().sorted().map(StringValue::of))
                );
                plopData.put(StringValue.of("feature_types"),
                        ListValue.wrap(cc.registry(Registries.FEATURE_TYPE).keySet().stream().sorted().map(ValueConversions::of))
                );
                plopData.put(StringValue.of("features"),
                        ListValue.wrap(cc.registry(Registries.FEATURE).keySet().stream().sorted().map(ValueConversions::of))
                );
                plopData.put(StringValue.of("structure_types"),
                        ListValue.wrap(cc.registry(Registries.STRUCTURE_TYPE).keySet().stream().sorted().map(ValueConversions::of))
                );
                plopData.put(StringValue.of("structures"),
                        ListValue.wrap(cc.registry(Registries.STRUCTURE).keySet().stream().sorted().map(ValueConversions::of))
                );
                return MapValue.wrap(plopData);
            }
            BlockArgument locator = BlockArgument.findIn((CarpetContext) c, lv, 0);
            if (lv.size() <= locator.offset) {
                throw new InternalExpressionException("'plop' needs extra argument indicating what to plop");
            }
            String what = lv.get(locator.offset).getString();
            Boolean result = FeatureGenerator.plop(what, ((CarpetContext) c).level(), locator.block.getPos());
            return result == null ? Value.NULL : BooleanValue.of(result);
        });

        expression.addContextFunction("schedule", -1, (c, t, lv) -> {
            if (lv.size() < 2) {
                throw new InternalExpressionException("'schedule' should have at least 2 arguments, delay and call name");
            }
            long delay = NumericValue.asNumber(lv.get(0)).getLong();

            FunctionArgument functionArgument = FunctionArgument.findIn(c, expression.module, lv, 1, false, false);
            ((CarpetScriptServer) c.host.scriptServer()).events.scheduleCall(
                    (CarpetContext) c,
                    functionArgument.function,
                    functionArgument.checkedArgs(),
                    delay
            );
            return Value.TRUE;
        });

        expression.addImpureFunction("logger", lv ->
        {
            Value res;

            if (lv.size() == 1) {
                res = lv.get(0);
                CarpetScriptServer.LOG.info("{}", res.getString());
            } else if (lv.size() == 2) {
                String level = lv.get(0).getString().toLowerCase(Locale.ROOT);
                res = lv.get(1);
                switch (level) {
                    case "debug" -> CarpetScriptServer.LOG.debug("{}", res.getString());
                    case "warn" -> CarpetScriptServer.LOG.warn("{}", res.getString());
                    case "info" -> CarpetScriptServer.LOG.info("{}", res.getString());
                    // Somehow issue deprecation
                    case "fatal", "error" -> CarpetScriptServer.LOG.error("{}", res.getString());
                    default -> throw new InternalExpressionException("Unknown log level for 'logger': " + level);
                }
            } else {
                throw new InternalExpressionException("logger takes 1 or 2 arguments");
            }

            return res; // pass through for variables
        });

        expression.addContextFunction("list_files", 2, (c, t, lv) ->
        {
            FileArgument fdesc = FileArgument.from(c, lv, true, FileArgument.Reason.READ);
            Stream<String> files = ((CarpetScriptHost) c.host).listFolder(fdesc);
            return files == null ? Value.NULL : ListValue.wrap(files.map(StringValue::of));
        });

        expression.addContextFunction("read_file", 2, (c, t, lv) ->
        {
            FileArgument fdesc = FileArgument.from(c, lv, false, FileArgument.Reason.READ);
            if (fdesc.type == FileArgument.Type.NBT) {
                Tag state = ((CarpetScriptHost) c.host).readFileTag(fdesc);
                return state == null ? Value.NULL : new NBTSerializableValue(state);
            } else if (fdesc.type == FileArgument.Type.JSON) {
                JsonElement json;
                json = ((CarpetScriptHost) c.host).readJsonFile(fdesc);
                Value parsedJson = GSON.fromJson(json, Value.class);
                return parsedJson == null ? Value.NULL : parsedJson;
            } else {
                List<String> content = ((CarpetScriptHost) c.host).readTextResource(fdesc);
                return content == null ? Value.NULL : ListValue.wrap(content.stream().map(StringValue::new));
            }
        });

        expression.addContextFunction("delete_file", 2, (c, t, lv) ->
                BooleanValue.of(((CarpetScriptHost) c.host).removeResourceFile(FileArgument.from(c, lv, false, FileArgument.Reason.DELETE))));

        expression.addContextFunction("write_file", -1, (c, t, lv) -> {
            if (lv.size() < 3) {
                throw new InternalExpressionException("'write_file' requires three or more arguments");
            }
            FileArgument fdesc = FileArgument.from(c, lv, false, FileArgument.Reason.CREATE);

            boolean success;
            if (fdesc.type == FileArgument.Type.NBT) {
                Value val = lv.get(2);
                NBTSerializableValue tagValue = (val instanceof final NBTSerializableValue nbtsv)
                        ? nbtsv
                        : new NBTSerializableValue(val.getString());
                Tag tag = tagValue.getTag();
                success = ((CarpetScriptHost) c.host).writeTagFile(tag, fdesc);
            } else if (fdesc.type == FileArgument.Type.JSON) {
                List<String> data = Collections.singletonList(GSON.toJson(lv.get(2).toJson()));
                ((CarpetScriptHost) c.host).removeResourceFile(fdesc);
                success = ((CarpetScriptHost) c.host).appendLogFile(fdesc, data);
            } else {
                List<String> data = new ArrayList<>();
                if (lv.size() == 3) {
                    Value val = lv.get(2);
                    if (val instanceof final ListValue list) {
                        List<Value> lval = list.getItems();
                        lval.forEach(v -> data.add(v.getString()));
                    } else {
                        data.add(val.getString());
                    }
                } else {
                    for (int i = 2; i < lv.size(); i++) {
                        data.add(lv.get(i).getString());
                    }
                }
                success = ((CarpetScriptHost) c.host).appendLogFile(fdesc, data);
            }
            return BooleanValue.of(success);
        });

        expression.addContextFunction("load_app_data", -1, (c, t, lv) ->
        {
            FileArgument fdesc = new FileArgument(null, FileArgument.Type.NBT, null, false, false, FileArgument.Reason.READ, c.host);
            if (!lv.isEmpty()) {
                c.host.issueDeprecation("load_app_data(...) with arguments");
                String resource = recognizeResource(lv.get(0), false);
                boolean shared = lv.size() > 1 && lv.get(1).getBoolean();
                fdesc = new FileArgument(resource, FileArgument.Type.NBT, null, false, shared, FileArgument.Reason.READ, c.host);
            }
            return NBTSerializableValue.of(((CarpetScriptHost) c.host).readFileTag(fdesc));
        });

        expression.addContextFunction("store_app_data", -1, (c, t, lv) ->
        {
            if (lv.isEmpty()) {
                throw new InternalExpressionException("'store_app_data' needs NBT tag and an optional file");
            }
            Value val = lv.get(0);
            FileArgument fdesc = new FileArgument(null, FileArgument.Type.NBT, null, false, false, FileArgument.Reason.CREATE, c.host);
            if (lv.size() > 1) {
                c.host.issueDeprecation("store_app_data(...) with more than one argument");
                String resource = recognizeResource(lv.get(1), false);
                boolean shared = lv.size() > 2 && lv.get(2).getBoolean();
                fdesc = new FileArgument(resource, FileArgument.Type.NBT, null, false, shared, FileArgument.Reason.CREATE, c.host);
            }
            NBTSerializableValue tagValue = (val instanceof final NBTSerializableValue nbtsv)
                    ? nbtsv
                    : new NBTSerializableValue(val.getString());
            return BooleanValue.of(((CarpetScriptHost) c.host).writeTagFile(tagValue.getTag(), fdesc));
        });

        expression.addContextFunction("statistic", 3, ActorFunctions.player(0, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            ServerPlayer player = EntityValue.getPlayerByValue(cc.server(), lv.get(0));
            if (player == null) {
                return Value.NULL;
            }
            Identifier category;
            Identifier statName;
            category = InputValidator.identifierOf(lv.get(1).getString());
            statName = InputValidator.identifierOf(lv.get(2).getString());
            StatType<?> type = cc.registry(Registries.STAT_TYPE).getValue(category);
            if (type == null) {
                return Value.NULL;
            }
            Stat<?> stat = getStat(type, statName);
            if (stat == null) {
                return Value.NULL;
            }
            return new NumericValue(player.getStats().getValue(stat));
        }));

        //handle_event('event', function...)
        expression.addContextFunction("handle_event", -1, (c, t, lv) ->
        {
            if (lv.size() < 2) {
                throw new InternalExpressionException("'handle_event' requires at least two arguments, event name, and a callback");
            }
            String event = lv.get(0).getString();
            FunctionArgument callback = FunctionArgument.findIn(c, expression.module, lv, 1, true, false);
            CarpetScriptHost host = ((CarpetScriptHost) c.host);
            if (callback.function == null) {
                return BooleanValue.of(host.scriptServer().events.removeBuiltInEvent(event, host));
            }
            // args don't need to be checked will be checked at the event
            return BooleanValue.of(host.scriptServer().events.handleCustomEvent(event, host, callback.function, callback.args));
        });
        //signal_event('event', player or null, args.... ) -> number of apps notified
        expression.addContextFunction("signal_event", -1, (c, t, lv) ->
        {
            if (lv.isEmpty()) {
                throw new InternalExpressionException("'signal' requires at least one argument");
            }
            CarpetContext cc = (CarpetContext) c;
            CarpetScriptServer server = ((CarpetScriptHost) c.host).scriptServer();
            String eventName = lv.get(0).getString();
            // no such event yet
            if (CarpetEventServer.Event.getEvent(eventName, server) == null) {
                return Value.NULL;
            }
            ServerPlayer player = null;
            List<Value> args = Collections.emptyList();
            if (lv.size() > 1) {
                player = EntityValue.getPlayerByValue(server.server, lv.get(1));
                if (lv.size() > 2) {
                    args = lv.subList(2, lv.size());
                }
            }
            int counts = ((CarpetScriptHost) c.host).scriptServer().events.signalEvent(eventName, cc, player, args);
            if (counts < 0) {
                return Value.NULL;
            }
            return new NumericValue(counts);
        });

        // nbt_storage()
        // nbt_storage(key)
        // nbt_storage(key, nbt)
        expression.addContextFunction("nbt_storage", -1, ActorFunctions.global((c, t, lv) -> {
            if (lv.size() > 2) {
                throw new InternalExpressionException("'nbt_storage' requires 0, 1 or 2 arguments.");
            }
            CarpetContext cc = (CarpetContext) c;
            CommandStorage storage = cc.server().getCommandStorage();
            if (lv.isEmpty()) {
                return ListValue.wrap(storage.keys().map(NBTSerializableValue::nameFromRegistryId));
            }
            String key = lv.get(0).getString();
            CompoundTag oldNbt = storage.get(InputValidator.identifierOf(key));
            if (lv.size() == 2) {
                Value nbt = lv.get(1);
                NBTSerializableValue newNbt = (nbt instanceof final NBTSerializableValue nbtsv)
                        ? nbtsv
                        : NBTSerializableValue.parseStringOrFail(nbt.getString());
                storage.set(InputValidator.identifierOf(key), newNbt.getCompoundTag());
            }
            return NBTSerializableValue.of(oldNbt);
        }));

        // script run create_datapack('foo', {'foo' -> {'bar.json' -> {'c' -> true,'d' -> false,'e' -> {'foo' -> [1,2,3]},'a' -> 'foobar','b' -> 5}}})
        expression.addContextFunction("create_datapack", 2, (c, t, lv) -> {
            CarpetContext cc = (CarpetContext) c;
            String origName = lv.get(0).getString();
            String name = InputValidator.validateSimpleString(origName, true);
            MinecraftServer server = cc.server();
            Path installation = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(name + ".zip").toAbsolutePath().normalize();
            if (!DATAPACK_INSTALLATIONS.add(installation)) return Value.NULL;
            try {
                for (String dpName : carpet.script.external.ScarpetRuntime.atGlobal(server, () -> List.copyOf(server.getPackRepository().getAvailableIds()))) {
                    if (dpName.equalsIgnoreCase("file/" + name + ".zip") ||
                            dpName.equalsIgnoreCase("file/" + name)) {
                        return Value.NULL;
                    }

                }
                Value dpdata = lv.get(1);
                if (!(dpdata instanceof final MapValue dpMap)) {
                    throw new InternalExpressionException("datapack data needs to be a valid map type");
                }
                PackRepository packManager = server.getPackRepository();
                Path dbFloder = server.getWorldPath(LevelResource.DATAPACK_DIR);
                Path packFloder = dbFloder.resolve(name + ".zip");
                if (Files.exists(packFloder) || Files.exists(dbFloder.resolve(name))) {
                    return Value.NULL;
                }
                try {
                    try (FileSystem zipfs = FileSystems.newFileSystem(URI.create("jar:" + packFloder.toUri()), Map.of("create", "true"))) {
                        Path zipRoot = zipfs.getPath("/");
                        zipValueToJson(zipRoot.resolve("pack.mcmeta"), MapValue.wrap(Map.of(StringValue.of("pack"), MapValue.wrap(Map.of(
                                StringValue.of("min_format"), new NumericValue(SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major()),
                                StringValue.of("max_format"), new NumericValue(SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major()),
                                StringValue.of("description"), StringValue.of(name), StringValue.of("source"), StringValue.of("scarpet"))))));
                        walkTheDPMap((MapValue) dpMap.deepcopy(), zipRoot);
                    }
                    java.util.concurrent.CompletableFuture<Void> reload = carpet.script.external.ScarpetRuntime.atGlobal(server, () -> {
                        packManager.reload();
                        Pack resourcePackProfile = packManager.getPack("file/" + name + ".zip");
                        if (resourcePackProfile == null || packManager.getSelectedPacks().contains(resourcePackProfile))
                            throw new InternalExpressionException("Created datapack was unavailable or already selected");
                        List<Pack> list = Lists.newArrayList(packManager.getSelectedPacks());
                        resourcePackProfile.getDefaultPosition().insert(list, resourcePackProfile, Pack::selectionConfig, false);
                        return server.reloadResources(list.stream().map(Pack::getId).toList());
                    });
                    carpet.script.external.ScarpetRuntime.await(reload);
                    return Value.TRUE;
                } catch (IOException | RuntimeException failure) {
                    try {
                        PathUtils.delete(packFloder);
                    } catch (IOException ignored) {
                        throw new InternalExpressionException("Failed to install a datapack and failed to clean up after it");
                    }
                    return Value.FALSE;
                }
            } finally {
                DATAPACK_INSTALLATIONS.remove(installation);
            }
        });

        expression.addContextFunction("enable_hidden_dimensions", 0, (c, t, lv) -> {
            CarpetContext cc = (CarpetContext) c;
            cc.host.issueDeprecation("enable_hidden_dimensions in 1.18.2 and 1.19+");
            return Value.NULL;
        });
    }

    private static void zipValueToJson(Path path, Value output) throws IOException {
        JsonElement element = output.toJson();
        if (element == null) {
            throw new InternalExpressionException("Cannot interpret " + output.getPrettyString() + " as a json object");
        }
        String string = GSON.toJson(element);
        Files.createDirectories(path.getParent());
        BufferedWriter bufferedWriter = Files.newBufferedWriter(path);
        Throwable incident = null;
        try {
            bufferedWriter.write(string);
        } catch (Throwable shitHappened) {
            incident = shitHappened;
            throw shitHappened;
        } finally {
            if (incident != null) {
                try {
                    bufferedWriter.close();
                } catch (Throwable otherShitHappened) {
                    incident.addSuppressed(otherShitHappened);
                }
            } else {
                bufferedWriter.close();
            }
        }
    }

    private static void zipValueToText(Path path, Value output) throws IOException {
        List<Value> toJoin;
        String string;
        String delimiter = System.lineSeparator();
        // i dont know it shoule be \n or System.lineSeparator
        if (output instanceof LazyListValue lazyListValue) {
            toJoin = lazyListValue.unroll();
            string = toJoin.stream().map(Value::getString).collect(Collectors.joining(delimiter));
        } else if (output instanceof ListValue listValue) {
            toJoin = listValue.getItems();
            string = toJoin.stream().map(Value::getString).collect(Collectors.joining(delimiter));
        } else {
            string = output.getString();
        }


        Files.createDirectories(path.getParent());
        BufferedWriter bufferedWriter = Files.newBufferedWriter(path);
        Throwable incident = null;
        try {
            bufferedWriter.write(string);
        } catch (Throwable shitHappened) {
            incident = shitHappened;
            throw shitHappened;
        } finally {
            if (incident != null) {
                try {
                    bufferedWriter.close();
                } catch (Throwable otherShitHappened) {
                    incident.addSuppressed(otherShitHappened);
                }
            } else {
                bufferedWriter.close();
            }
        }
    }

    private static void zipValueToNBT(Path path, Value output) throws IOException {
        NBTSerializableValue tagValue = (output instanceof NBTSerializableValue nbtSerializableValue)
                ? nbtSerializableValue
                : new NBTSerializableValue(output.getString());
        Tag tag = tagValue.getTag();
        Files.createDirectories(path.getParent());
        if (tag instanceof final CompoundTag cTag) {
            NbtIo.writeCompressed(cTag, Files.newOutputStream(path));
        }
    }

    private static void walkTheDPMap(MapValue node, Path path) throws IOException {
        Map<Value, Value> items = node.getMap();
        for (Map.Entry<Value, Value> entry : items.entrySet()) {
            Value val = entry.getValue();
            String strkey = entry.getKey().getString();
            Path child = path.resolve(strkey);
            if (strkey.endsWith(".json")) {
                zipValueToJson(child, val);
            } else if (strkey.endsWith(".mcfunction") || strkey.endsWith(".txt") || strkey.endsWith(".mcmeta")) {
                zipValueToText(child, val);
            } else if (strkey.endsWith(".nbt")) {
                zipValueToNBT(child, val);
            } else {
                if (!(val instanceof final MapValue map)) {
                    throw new InternalExpressionException("Value of " + strkey + " should be a map");
                }
                Files.createDirectory(child);
                walkTheDPMap(map, child);
            }
        }
    }

    private static <T> @Nullable Stat<T> getStat(StatType<T> type, Identifier id) {
        T key = type.getRegistry().getValue(id);
        if (key == null || !type.contains(key)) {
            return null;
        }
        return type.get(key);
    }
}
