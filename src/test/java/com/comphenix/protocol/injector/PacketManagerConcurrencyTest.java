package com.comphenix.protocol.injector;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.error.BasicErrorReporter;
import com.comphenix.protocol.events.ListenerOptions;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketManagerConcurrencyTest {

    @Test
    void inboundDefaultStaysOnNetworkThread() {
        PacketListener listener = listener(
                ListeningWhitelist.EMPTY_WHITELIST,
                whitelist(PacketType.Play.Client.CHAT));
        assertFalse(PacketManagerImpl.requiresMainThread(listener, false));
    }

    @Test
    void inboundSyncExplicitlyRequestsMainThread() {
        PacketListener listener = listener(
                ListeningWhitelist.EMPTY_WHITELIST,
                whitelist(PacketType.Play.Client.CHAT, ListenerOptions.SYNC));
        assertTrue(PacketManagerImpl.requiresMainThread(listener, false));
    }

    @Test
    void outboundDefaultRequiresMainThreadButAsyncOptsOut() {
        PacketListener regular = listener(
                whitelist(PacketType.Play.Server.SYSTEM_CHAT),
                ListeningWhitelist.EMPTY_WHITELIST);
        PacketListener async = listener(
                whitelist(PacketType.Play.Server.SYSTEM_CHAT, ListenerOptions.ASYNC),
                ListeningWhitelist.EMPTY_WHITELIST);

        assertTrue(PacketManagerImpl.requiresMainThread(regular, true));
        assertFalse(PacketManagerImpl.requiresMainThread(async, true));
    }

    @Test
    void explicitAsyncManagerIndexesOnlyInterestedPacketTypes() {
        AsynchronousManagerImpl manager = new AsynchronousManagerImpl(new BasicErrorReporter());
        PacketListener listener = listener(
                ListeningWhitelist.EMPTY_WHITELIST,
                whitelist(PacketType.Play.Client.CHAT));
        try {
            manager.registerAsyncHandler(listener);
            assertTrue(manager.hasAsynchronousListeners(PacketType.Play.Client.CHAT, false));
            assertFalse(manager.hasAsynchronousListeners(PacketType.Play.Client.KEEP_ALIVE, false));
            assertTrue(manager.getReceivingTypes().contains(PacketType.Play.Client.CHAT));
            assertFalse(manager.getReceivingTypes().contains(PacketType.Play.Client.KEEP_ALIVE));
        } finally {
            manager.shutdown();
        }
    }

    private static ListeningWhitelist whitelist(PacketType type, ListenerOptions... options) {
        return ListeningWhitelist.newBuilder()
                .priority(ListenerPriority.NORMAL)
                .types(type)
                .options(options)
                .build();
    }

    private static PacketListener listener(ListeningWhitelist sending, ListeningWhitelist receiving) {
        return new PacketListener() {
            @Override public void onPacketSending(PacketEvent event) { }
            @Override public void onPacketReceiving(PacketEvent event) { }
            @Override public ListeningWhitelist getSendingWhitelist() { return sending; }
            @Override public ListeningWhitelist getReceivingWhitelist() { return receiving; }
            @Override public Plugin getPlugin() { return null; }
        };
    }
}
