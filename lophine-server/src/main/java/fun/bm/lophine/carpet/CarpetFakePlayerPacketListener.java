/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package fun.bm.lophine.carpet;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CarpetFakePlayerPacketListener extends ServerGamePacketListenerImpl {

    public CarpetFakePlayerPacketListener(MinecraftServer server, CarpetFakePlayer bot) {
        this(server, bot, new FakePlayerConnection());
    }

    private CarpetFakePlayerPacketListener(MinecraftServer server, CarpetFakePlayer bot, FakePlayerConnection connection) {
        super(server, connection, bot, CommonListenerCookie.createInitial(bot.gameProfile, false));
        connection.bind(this);
    }

    @Override
    public void send(@NotNull Packet<?> packet, @Nullable ChannelFutureListener listener) {
    }

    @Override
    public void disconnect(@NotNull DisconnectionDetails disconnectionInfo) {
        CarpetFakePlayer fake = (CarpetFakePlayer) this.player;
        Runnable remove = () -> {
            if (fake.hasDisconnected()) return;
            var cause = disconnectionInfo.disconnectionReason().flatMap(io.papermc.paper.connection.DisconnectionReason::game)
                    .orElse(org.bukkit.event.player.PlayerKickEvent.Cause.UNKNOWN);
            var leave = disconnectionInfo.quitMessage().map(io.papermc.paper.adventure.PaperAdventure::asAdventure)
                    .orElseGet(() -> net.kyori.adventure.text.Component.translatable("multiplayer.player.left",
                            net.kyori.adventure.text.format.NamedTextColor.YELLOW, fake.getBukkitEntity().displayName()));
            var event = new org.bukkit.event.player.PlayerKickEvent(fake.getBukkitEntity(),
                    io.papermc.paper.adventure.PaperAdventure.asAdventure(disconnectionInfo.reason()), leave, cause);
            if (this.server.isRunning() && !event.callEvent()) return;
            fake.quitReason = org.bukkit.event.player.PlayerQuitEvent.QuitReason.KICKED;
            this.server.getCarpetFakePlayers().removeAsync(fake, CarpetFakePlayerManager.RemoveReason.KICK, null, true, false, event.leaveMessage());
        };
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(fake)) remove.run();
        else fake.getBukkitEntity().getScheduler().execute(CarpetInternalPlugin.INSTANCE, remove, null, 1L);
    }

    @Override
    public boolean isAcceptingMessages() {
        return !this.player.hasDisconnected();
    }

    @Override
    public void tick() {
        ((CarpetFakePlayer) this.player).networkTick();
    }

    @Override
    public boolean hasClientLoaded() {
        return true; // Don't kick me out!
    }

    public static class FakePlayerConnection extends Connection {

        public FakePlayerConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        private void bind(CarpetFakePlayerPacketListener listener) {
            this.packetListener = listener;
        }

        @Override
        public void tick() {
            if (this.isConnected() && this.packetListener instanceof CarpetFakePlayerPacketListener listener) listener.tick();
        }

        @Override
        public void flushChannel() {
        }

        @Override
        public boolean isConnected() {
            return this.packetListener instanceof CarpetFakePlayerPacketListener listener && !listener.player.hasDisconnected();
        }

        @Override
        public void disconnect(@NotNull DisconnectionDetails details) {
            if (this.packetListener instanceof CarpetFakePlayerPacketListener listener) listener.disconnect(details);
        }

        @Override
        public boolean isConnecting() {
            return false;
        }

        @Override
        public boolean isMemoryConnection() {
            return false;
        }

        @Override
        public void send(@NotNull Packet<?> packet, @javax.annotation.Nullable ChannelFutureListener channelFutureListener, boolean flag) {
        }
    }
}
