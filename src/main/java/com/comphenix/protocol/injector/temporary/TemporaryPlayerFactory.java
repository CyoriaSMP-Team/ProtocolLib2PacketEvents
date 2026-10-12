/*
 * ProtocolLib2PacketEvents - clean-room temporary-player factory.
 */
package com.comphenix.protocol.injector.temporary;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.injector.netty.Injector;
import com.comphenix.protocol.utility.ByteBuddyFactory;
import com.comphenix.protocol.utility.ChatExtensions;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.FieldValue;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Creates a generated Bukkit Player facade for clients before login completes. */
public class TemporaryPlayerFactory {
    private static final AtomicLong CLASS_SEQUENCE = new AtomicLong();
    private static final Constructor<? extends Player> PLAYER_CONSTRUCTOR = setupPlayerConstructor();

    private TemporaryPlayerFactory() {
    }

    public static Injector getInjectorFromPlayer(Player player) {
        if (!(player instanceof TemporaryPlayer temporary)) return null;
        Injector injector = temporary.getInjector();
        // FastLoginPlus retrieves this adapter at multiple asynchronous login
        // phases. When LOGIN_START ran before the PacketEvents/native pipeline
        // was fully initialized, retry on the same live User+Channel rather
        // than permanently treating a legitimate connection as disconnected.
        com.github.retrooper.packetevents.protocol.player.User user =
                TemporaryPlayerAdapter.getUser(player);
        if (user != null) {
            org.bukkit.plugin.Plugin plugin = com.comphenix.protocol.ProtocolLibrary.getPlugin();
            if (plugin instanceof com.comphenix.protocol.ProtocolLib bridge) {
                Injector resolved = bridge.getTemporaryInjector(user, player);
                if (resolved != null) {
                    if (resolved != injector) temporary.setInjector(resolved);
                    injector = resolved;
                }
            }
        }
        if (injector instanceof com.comphenix.protocol.injector.netty.channel.NettyChannelInjector netty) {
            netty.refreshNativeConnection();
        }
        return injector;
    }

    public static void setInjectorForPlayer(Player player, Injector injector) {
        if (!(player instanceof TemporaryPlayer temporary)) {
            throw new IllegalArgumentException("player is not a temporary player");
        }
        temporary.setInjector(injector);
    }

    @SuppressWarnings("unchecked")
    private static Constructor<? extends Player> setupPlayerConstructor() {
        MethodDelegation implementation = MethodDelegation.to(new TemporaryPlayerMethods());
        ElementMatcher.Junction<ByteCodeElement> playerMethods = ElementMatchers.not(
                ElementMatchers.isDeclaredBy(Object.class)
                        .or(ElementMatchers.isDeclaredBy(TemporaryPlayer.class)));
        try {
            Class<? extends Player> generated = ByteBuddyFactory.getInstance()
                    .createSubclass(TemporaryPlayer.class, ConstructorStrategy.Default.DEFAULT_CONSTRUCTOR)
                    .name(TemporaryPlayerFactory.class.getPackageName() + ".GeneratedTemporaryPlayer$"
                            + CLASS_SEQUENCE.incrementAndGet())
                    .implement(Player.class)
                    .method(playerMethods)
                    .intercept(implementation)
                    .make()
                    .load(ByteBuddyFactory.getInstance().getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
                    .getLoaded()
                    .asSubclass(Player.class);
            Constructor<? extends Player> constructor = generated.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Unable to create the generated temporary player", exception);
        }
    }

    private static Object sendMessage(Injector injector, String message) {
        for (PacketContainer packet : ChatExtensions.createChatPackets(message)) {
            injector.sendClientboundPacket(packet.getHandle(), null, false);
        }
        return null;
    }

    /** Byte Buddy delegate for the Bukkit Player methods. */
    private static final class TemporaryPlayerMethods {
        @RuntimeType
        public Object invoke(
                @This Object object,
                @Origin Method method,
                @FieldValue("injector") Injector injector,
                @AllArguments Object[] arguments
        ) throws Throwable {
            String name = method.getName();
            com.github.retrooper.packetevents.protocol.player.User user =
                    TemporaryPlayerAdapter.userFor(object);

            if (user != null) {
                if ("getAddress".equals(name)) return user.getAddress();
                if ("getServer".equals(name)) return Bukkit.getServer();
                if ("getName".equals(name)) {
                    return user.getName() == null ? "UNKNOWN[" + user.getAddress() + "]" : user.getName();
                }
                if ("getUniqueId".equals(name)) {
                    UUID uuid = user.getUUID();
                    return uuid == null
                            ? UUID.nameUUIDFromBytes(String.valueOf(user.getAddress()).getBytes())
                            : uuid;
                }
                if ("isOnline".equals(name)) return true;
                if ("sendMessage".equals(name) || "chat".equals(name)) {
                    if (arguments != null && arguments.length > 0) {
                        if (arguments[0] instanceof String message) {
                            user.sendMessage(message);
                        } else if (arguments[0] instanceof String[] messages) {
                            for (String message : messages) user.sendMessage(message);
                        }
                    }
                    return null;
                }
                if ("kickPlayer".equals(name)) {
                    user.closeConnection();
                    return null;
                }
            }

            if (injector == null) {
                throw new IllegalStateException("Unable to find injector for temporary player");
            }
            if ("getPlayer".equals(name)) return injector.getPlayer();
            if ("getAddress".equals(name)) return injector.getAddress();
            if ("getServer".equals(name)) return Bukkit.getServer();
            if ("sendMessage".equals(name) || "chat".equals(name)) {
                if (arguments != null && arguments.length > 0) {
                    if (arguments[0] instanceof String message) return sendMessage(injector, message);
                    if (arguments[0] instanceof String[] messages) {
                        for (String message : messages) sendMessage(injector, message);
                    }
                }
                return null;
            }
            if ("kickPlayer".equals(name)) {
                injector.disconnect(arguments != null && arguments.length > 0
                        ? String.valueOf(arguments[0]) : null);
                return null;
            }

            Player current = injector.getPlayer();
            if (current != null && current != object) {
                return method.invoke(current, arguments);
            }
            if ("isOnline".equals(name)) return injector.isConnected();
            if ("getName".equals(name)) {
                String playerName = injector.getPlayerName();
                return playerName == null ? "UNKNOWN[" + injector.getAddress() + "]" : playerName;
            }
            if ("getUniqueId".equals(name)) {
                UUID uuid = injector.getPlayerUniqueId();
                return uuid == null
                        ? UUID.nameUUIDFromBytes(String.valueOf(injector.getAddress()).getBytes())
                        : uuid;
            }
            throw new UnsupportedOperationException("The method " + name
                    + " is not supported for temporary players");
        }
    }

    public static Player createTemporaryPlayer() {
        try {
            return PLAYER_CONSTRUCTOR.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to create temporary player", exception);
        }
    }
}
