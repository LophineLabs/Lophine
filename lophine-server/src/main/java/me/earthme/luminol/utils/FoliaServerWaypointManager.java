package me.earthme.luminol.utils;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.google.common.collect.Sets;
import me.earthme.luminol.config.modules.experiment.CommandConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FoliaServerWaypointManager extends ServerWaypointManager {
    private final Set<WaypointTransmitter> waypoints = ConcurrentHashMap.newKeySet();
    private final Set<ServerPlayer> trackingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<ServerPlayer, Map<WaypointTransmitter, WaypointTransmitter.Connection>> connections = new ConcurrentHashMap<>();

    public FoliaServerWaypointManager(ServerLevel serverLevel) {
        super(serverLevel);
    }

    @Override
    public void breakAllConnections() {
        Iterator<Map.Entry<ServerPlayer, Map<WaypointTransmitter, WaypointTransmitter.Connection>>> connectionTablesEntryIterator = this.connections.entrySet().iterator();
        while (connectionTablesEntryIterator.hasNext()) {
            final Map<WaypointTransmitter, WaypointTransmitter.Connection> table = connectionTablesEntryIterator.next().getValue();
            connectionTablesEntryIterator.remove();

            for (WaypointTransmitter.Connection connection : table.values()) {
                connection.disconnect();
            }
        }
    }

    @Override
    public void remakeConnections(WaypointTransmitter waypoint) {
        throw new UnsupportedOperationException("Unused");
    }

    @Override
    public Set<WaypointTransmitter> transmitters() {
        return this.waypoints;
    }

    @Override
    public void trackWaypoint(WaypointTransmitter waypoint) {
        if (!CommandConfig.waypointsAndWaypointCommand) {
            return;
        }

        // Leaves start - fakeplayer
        if (waypoint instanceof org.leavesmc.leaves.bot.ServerBot bot && !bot.getConfigValue(org.leavesmc.leaves.bot.agent.Configs.ENABLE_LOCATOR_BAR)) {
            return;
        }
        // Leaves end - fakeplayer

        this.waypoints.add(waypoint);

        for (ServerPlayer toCreateFor : this.trackingPlayers) {
            this.createConnection(toCreateFor, waypoint);
        }
    }

    @Override
    public void updateWaypoint(WaypointTransmitter waypoint) {
        if (!CommandConfig.waypointsAndWaypointCommand) {
            return;
        }

        if (this.waypoints.contains(waypoint)) {
            for (ServerPlayer player : this.trackingPlayers) {
                final Map<WaypointTransmitter, WaypointTransmitter.Connection> connections = this.connections.get(player);

                if (connections != null) {
                    final WaypointTransmitter.Connection connection = connections.get(waypoint);

                    if (connection != null) {
                        this.updateConnection(player, waypoint, connection);
                    } else {
                        this.createConnection(player, waypoint);
                    }
                } else {
                    this.createConnection(player, waypoint);
                }
            }
        }
    }

    @Override
    public void untrackWaypoint(WaypointTransmitter waypoint) {
        if (!CommandConfig.waypointsAndWaypointCommand) {
            return;
        }

        for (Map.Entry<ServerPlayer, Map<WaypointTransmitter, WaypointTransmitter.Connection>> connectionMapEntry : this.connections.entrySet()) {
            final Map<WaypointTransmitter, WaypointTransmitter.Connection> connectionsOfCurr = connectionMapEntry.getValue();
            final ServerPlayer ownerOfCurr = connectionMapEntry.getKey();

            final WaypointTransmitter.Connection removed = connectionsOfCurr.remove(waypoint);
            if (removed != null) {
                scheduleIfOffTarget(ownerOfCurr, removed::disconnect);
            }
        }

        this.waypoints.remove(waypoint);
    }

    @Override
    public void addPlayer(ServerPlayer player) {
        this.trackingPlayers.add(player);

        scheduleIfOffTarget(player, () -> {
            // Leaves start - fakeplayer
            if (player instanceof org.leavesmc.leaves.bot.ServerBot) {
                return;
            }
            // Leaves end - fakeplayer
            for (WaypointTransmitter transmitter : this.waypoints) {
                this.createConnection(player, transmitter);
            }

            if (player.isTransmittingWaypoint()) {
                this.trackWaypoint(player);
            }
        });
    }

    @Override
    public void updatePlayer(ServerPlayer player) {
        Map<WaypointTransmitter, WaypointTransmitter.Connection> conneections = this.connections.get(player);

        if (conneections == null) {
            return;
        }

        Sets.SetView<WaypointTransmitter> newTransmitters = Sets.difference(this.waypoints, conneections.keySet());

        for (Map.Entry<WaypointTransmitter, WaypointTransmitter.Connection> entry : conneections.entrySet()) {
            this.updateConnection(player, entry.getKey(), entry.getValue());
        }

        for (WaypointTransmitter waypointTransmitter : newTransmitters) {
            this.createConnection(player, waypointTransmitter);
        }
    }

    @Override
    public void removePlayer(ServerPlayer player) {
        this.trackingPlayers.remove(player);

        scheduleIfOffTarget(player, () -> {
            final Map<WaypointTransmitter, WaypointTransmitter.Connection> removedConnections = this.connections.remove(player);

            if (removedConnections != null) {
                for (WaypointTransmitter.Connection connection : removedConnections.values()) {
                    connection.disconnect();
                }
            }

            this.untrackWaypoint(player);
        });
    }

    private static boolean isLocatorBarEnabledFor(@NotNull ServerPlayer player) {
        return player.level().getGameRules().get(GameRules.LOCATOR_BAR);
    }

    private static void scheduleIfOffTarget(Entity entity, Runnable action) {
        if (!TickThread.isTickThreadFor(entity)) {
            entity.getBukkitEntity().taskScheduler.schedule(_ -> action.run(), _ -> action.run(), 1L);
            return;
        }

        action.run();
    }

    private void createConnection(ServerPlayer player, WaypointTransmitter waypoint) {
        if (player != waypoint) {
            if (isLocatorBarEnabledFor(player)) {
                // -> waypoint
                scheduleIfOffTarget(player, () -> waypoint.makeWaypointConnectionWith(player).ifPresentOrElse(newConnection -> {
                    final Map<WaypointTransmitter, WaypointTransmitter.Connection> connectionsOfThisPlayer = this.connections.computeIfAbsent(player, _ -> new ConcurrentHashMap<>());

                    connectionsOfThisPlayer.put(waypoint, newConnection);

                    newConnection.connect();
                }, () -> {
                    final Map<WaypointTransmitter, WaypointTransmitter.Connection> connectionsOfThisPlayer = this.connections.get(player);

                    if (connectionsOfThisPlayer != null) {
                        WaypointTransmitter.Connection removedConnection = connectionsOfThisPlayer.remove(waypoint);
                        if (removedConnection != null) {
                            removedConnection.disconnect();
                        }
                    }
                }));
            }
        }
    }

    private void updateConnection(ServerPlayer player, WaypointTransmitter waypoint, WaypointTransmitter.Connection connection) {
        if (player != waypoint) {
            if (isLocatorBarEnabledFor(player)) {
                scheduleIfOffTarget(player, () -> {
                    if (!connection.isBroken()) {
                        connection.update();
                    } else {
                        waypoint.makeWaypointConnectionWith(player).ifPresentOrElse(newConnection -> {
                            final Map<WaypointTransmitter, WaypointTransmitter.Connection> connectionsOfThisPlayer = this.connections.computeIfAbsent(player, _ -> new ConcurrentHashMap<>());

                            connectionsOfThisPlayer.put(waypoint, newConnection);


                            newConnection.connect();
                        }, () -> {
                            final Map<WaypointTransmitter, WaypointTransmitter.Connection> connectionsOfThisPlayer = this.connections.get(player);

                            if (connectionsOfThisPlayer != null) {
                                connectionsOfThisPlayer.remove(waypoint);
                            }

                            connection.disconnect();
                        });
                    }
                });
            }
        }
    }
}
