/*
 * ProtocolLib2PacketEvents (P2P) - a drop-in ProtocolLib compatibility layer
 * powered by PacketEvents.
 *
 * Copyright (C) 2026 CyoriaSMP Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.comphenix.protocol;

import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.Collections;

/**
 * Asynchronous packet processing, mirroring ProtocolLib's {@code AsynchronousManager}.
 * <p>
 * Listeners registered here run on a worker pool instead of the Netty thread, so they may do
 * blocking work (database lookups, HTTP calls) without stalling the connection. Packets for a
 * given player are processed strictly in order, so a slow listener delays that player's later
 * packets rather than letting them overtake.
 * <p>The PacketEvents bridge may logically defer a packet while this manager works, but the
 * network callback itself is never blocked. The packet is released after asynchronous processing
 * completes, preserving cancellation and mutation semantics without occupying a Netty worker.</p>
 */
public interface AsynchronousManager {

    /** Registers a listener to be run off the network thread. */
    AsyncListenerHandler registerAsyncHandler(PacketListener listener);

    /** Removes a previously registered asynchronous handler. */
    default void unregisterAsyncHandler(AsyncListenerHandler handler) {
        if (handler != null) {
            unregisterAsyncHandler(handler.getAsyncListener());
        }
    }

    void unregisterAsyncHandler(PacketListener listener);

    void unregisterAsyncHandlers(Plugin plugin);

    Set<PacketType> getSendingTypes();

    Set<PacketType> getReceivingTypes();

    boolean hasAsynchronousListeners(PacketEvent packet);

    PacketStream getPacketStream();

    com.comphenix.protocol.error.ErrorReporter getErrorReporter();

    void cleanupAll();

    /** Compatibility hook for ProtocolLib listeners that release a packet asynchronously. */
    default void signalPacketTransmission(PacketEvent packet) {
        if (packet != null && packet.getAsyncMarker() != null) {
            packet.getAsyncMarker().signal();
        }
    }

    void registerTimeoutHandler(PacketListener listener);

    void unregisterTimeoutHandler(PacketListener listener);

    Set<PacketListener> getTimeoutHandlers();

    /** Every currently registered asynchronous listener. */
    Set<PacketListener> getAsyncHandlers();

    /** Packets queued but not yet processed by the worker pool. */
    int getQueuedPacketCount();

    /** Stops the worker pool. Called on plugin disable. */
    void shutdown();
}
