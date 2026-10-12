package com.comphenix.protocol.injector.netty.channel;

import com.comphenix.protocol.error.ErrorReporter;
import com.comphenix.protocol.injector.ListenerManager;
import com.comphenix.protocol.injector.netty.Injector;
import com.github.retrooper.packetevents.PacketEvents;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InjectionFactory implements AutoCloseable {
    private final Plugin plugin;
    private final ErrorReporter reporter;
    private final ListenerManager listeners;
    private final Map<String, Injector> byName = new ConcurrentHashMap<>();
    private final Map<UUID, Injector> byUuid = new ConcurrentHashMap<>();
    private final Map<Channel, Injector> byChannel = new ConcurrentHashMap<>();
    private volatile boolean closed;
    public InjectionFactory(Plugin plugin, ErrorReporter reporter, ListenerManager listeners) { this.plugin=plugin;this.reporter=reporter;this.listeners=listeners; }
    public Plugin getPlugin() { return plugin; }
    public Injector fromPlayer(Player player) {
        if (player == null) return EmptyInjector.WITHOUT_PLAYER;
        Injector existing = byUuid.get(player.getUniqueId());
        if (existing != null) return existing;
        Object channelObject = null;
        if (PacketEvents.getAPI() != null) {
            channelObject = PacketEvents.getAPI().getPlayerManager().getChannel(player);
        }
        // PacketEvents intentionally exposes this as Object because some server
        // versions shade Netty.  Only install a native interceptor when the
        // channel is the compatible Netty type; the public PE channel helper is
        // the safe fallback for shaded channels.
        Injector result;
        if (channelObject instanceof Channel channel) {
            result = byChannel.get(channel);
            if (result == null) {
                result = new NettyChannelInjector(player, null, channel, listeners, this, reporter);
                Injector previous = byChannel.putIfAbsent(channel, result);
                if (previous != null) result = previous;
            } else {
                result.setPlayer(player);
            }
        } else if (channelObject != null) {
            result = new com.comphenix.protocol.injector.netty.ReflectiveInjector(player, channelObject);
        } else {
            result = new EmptyInjector(player);
        }
        return cacheInjector(player, result);
    }
    public Injector fromName(String name, Player player) { return player != null ? fromPlayer(player) : byName.getOrDefault(name, EmptyInjector.WITHOUT_PLAYER); }
    public Injector fromChannel(Channel channel) {
        if (channel == null) return EmptyInjector.WITHOUT_PLAYER;
        Injector existing = byChannel.get(channel);
        if (existing != null) {
            if (existing instanceof NettyChannelInjector netty) netty.refreshNativeConnection();
            return existing;
        }
        Injector created = new NettyChannelInjector(null, null, channel, listeners, this, reporter);
        Injector previous = byChannel.putIfAbsent(channel, created);
        return previous == null ? created : previous;
    }
    public Injector invalidate(Player player, String name) {
        Injector removed = player == null ? null : byUuid.remove(player.getUniqueId());
        if (name != null) {
            Injector named = byName.remove(name);
            if (removed == null) removed = named;
        }
        return removed == null ? EmptyInjector.WITHOUT_PLAYER : removed;
    }
    /** Removes a cached injector without creating a new channel adapter. */
    public Injector invalidate(Player player) {
        Injector removed = invalidate(player, player == null ? null : player.getName());
        removeChannelReference(removed);
        return removed;
    }
    public Injector invalidate(Channel channel) {
        Injector removed = channel == null ? null : byChannel.remove(channel);
        if (removed != null) removed.close();
        return removed == null ? EmptyInjector.WITHOUT_PLAYER : removed;
    }
    private void removeChannelReference(Injector target) {
        if (target == null) return;
        byChannel.entrySet().removeIf(entry -> entry.getValue() == target);
    }
    public Injector cacheInjector(Player player, Injector injector) { if (player != null && injector != null) { byUuid.put(player.getUniqueId(), injector); byName.put(player.getName(), injector); } return injector; }
    public Injector cacheInjector(String name, Injector injector) { if (name != null && injector != null) byName.put(name, injector); return injector; }
    public boolean isClosed() { return closed; }
    @Override public void close() { closed = true; byChannel.values().forEach(Injector::close); byUuid.values().forEach(Injector::close); byName.clear(); byUuid.clear(); byChannel.clear(); }
}
