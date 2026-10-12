/*
 * ProtocolLib2PacketEvents - clean-room PacketEvents user bridge.
 */
package com.comphenix.protocol.injector.temporary;

import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Associates a PacketEvents pre-login user with the generated Bukkit Player
 * facade.  The generated facade itself is created by {@link TemporaryPlayerFactory}.
 */
public final class TemporaryPlayerAdapter {
    private static final Map<Player, User> USERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private TemporaryPlayerAdapter() {
    }

    public static Player create(User user) {
        if (user == null) {
            return null;
        }
        Player player = TemporaryPlayerFactory.createTemporaryPlayer();
        USERS.put(player, user);
        // The pre-login facade must carry the same native Connection as its
        // PacketEvents User. Without it, FastLoginPlus's ProtocolLibCompat
        // considers every player disconnected during Mojang session checks.
        org.bukkit.plugin.Plugin plugin = com.comphenix.protocol.ProtocolLibrary.getPlugin();
        if (plugin instanceof com.comphenix.protocol.ProtocolLib bridge) {
            com.comphenix.protocol.injector.netty.Injector injector =
                    bridge.getTemporaryInjector(user, player);
            if (injector != null) TemporaryPlayerFactory.setInjectorForPlayer(player, injector);
        }
        return player;
    }

    public static Player createAnonymous() {
        return TemporaryPlayerFactory.createTemporaryPlayer();
    }

    static User userFor(Object player) {
        return player instanceof Player ? USERS.get(player) : null;
    }

    public static User getUser(Player player) {
        return userFor(player);
    }
}
