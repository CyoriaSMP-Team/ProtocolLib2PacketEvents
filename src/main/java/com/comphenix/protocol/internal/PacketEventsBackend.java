package com.comphenix.protocol.internal;

import com.comphenix.protocol.events.PacketContainer;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.comphenix.protocol.injector.temporary.TemporaryPlayer;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerAdapter;
import org.bukkit.entity.Player;

/** PacketEvents transport for structured packets. */
public final class PacketEventsBackend implements PacketBackend {
    @Override public String name() { return "PacketEvents"; }

    private static void requireStructured(PacketContainer packet, String action) {
        if (packet == null || !packet.hasStructuredAccess()) {
            throw new IllegalArgumentException("Cannot " + action + " an unstructured packet through PacketEvents");
        }
    }

    @Override
    public void send(Player player, PacketContainer packet, boolean filters) {
        requireStructured(packet, "send");
        Object wrapper = packet.getPacketWrapper();
        User preLogin = temporaryTransport(player);
        if (preLogin != null) {
            if (wrapper != null) {
                if (filters) preLogin.sendPacket(packet.getPacketWrapper());
                else preLogin.sendPacketSilently(packet.getPacketWrapper());
            } else if (filters) preLogin.sendPacket(packet.getHandle());
            else preLogin.sendPacketSilently(packet.getHandle());
            return;
        }
        if (wrapper != null) {
            if (filters) PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet.getPacketWrapper());
            else PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet.getPacketWrapper());
        } else if (filters) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet.getHandle());
        } else {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet.getHandle());
        }
    }

    /**
     * A LOGIN/CONFIGURATION packet may have a ProtocolLib temporary player,
     * not a Bukkit CraftPlayer. The PacketEvents PlayerManager casts its input
     * to CraftPlayer; for temporary players the underlying pre-login User owns
     * the real channel and must be used for transport.
     *
     * Fail loudly if its channel is missing, instead of silently discarding a
     * credential-bearing LOGIN_START or bypassing authentication listeners.
     */
    static User temporaryTransport(Player player) {
        if (!(player instanceof TemporaryPlayer)) return null;
        User user = TemporaryPlayerAdapter.getUser(player);
        if (user == null || user.getChannel() == null) {
            throw new IllegalStateException("Temporary ProtocolLib player has no PacketEvents login channel");
        }
        return user;
    }

    @Override
    public void receive(Player player, PacketContainer packet, boolean filters) {
        requireStructured(packet, "receive");
        Object wrapper = packet.getPacketWrapper();
        User preLogin = temporaryTransport(player);
        if (preLogin != null) {
            if (wrapper != null) {
                if (filters) preLogin.receivePacket(packet.getPacketWrapper());
                else preLogin.receivePacketSilently(packet.getPacketWrapper());
            } else if (filters) preLogin.receivePacket(packet.getHandle());
            else preLogin.receivePacketSilently(packet.getHandle());
            return;
        }
        if (wrapper != null) {
            if (filters) PacketEvents.getAPI().getPlayerManager().receivePacket(player, packet.getPacketWrapper());
            else PacketEvents.getAPI().getPlayerManager().receivePacketSilently(player, packet.getPacketWrapper());
        } else if (filters) {
            PacketEvents.getAPI().getPlayerManager().receivePacket(player, packet.getHandle());
        } else {
            PacketEvents.getAPI().getPlayerManager().receivePacketSilently(player, packet.getHandle());
        }
    }

    @Override
    public void sendWire(Player player, int packetId, byte[] payload) {
        DirectNettyBackend.sendWire(player, packetId, payload);
    }
}
