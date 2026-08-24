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
 */
package com.comphenix.protocol.injector;

import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.PacketStream;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.async.AsyncMarker;
import com.comphenix.protocol.error.ErrorReporter;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking front-end for ProtocolLib's explicit asynchronous listener API.
 *
 * <p>The packet callback itself never waits for this manager. Work is submitted to a per-player
 * serial lane on a shared pool and a {@link CompletableFuture} is completed when all interested
 * async listeners (and any explicit processing delay) have finished. The dispatcher decides
 * whether the original packet must be logically held and retransmitted after that future.</p>
 *
 * <p>Indexes are rebuilt only when handlers are registered/removed. Packet dispatch therefore
 * performs an O(1) packet-type lookup instead of scanning every async handler for every packet.</p>
 */
public class AsynchronousManagerImpl implements AsynchronousManager {

    private final ErrorReporter errorReporter;
    private final ExecutorService pool;
    private final Set<PacketListener> handlers = ConcurrentHashMap.newKeySet();
    private final Set<PacketListener> timeoutHandlers = ConcurrentHashMap.newKeySet();
    private final Map<PacketListener, AsyncListenerHandler> handlerHandles = new ConcurrentHashMap<>();
    private final Map<UUID, Executor> lanes = new ConcurrentHashMap<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile Map<PacketType, PacketListener[]> sendingIndex = Map.of();
    private volatile Map<PacketType, PacketListener[]> receivingIndex = Map.of();

    public AsynchronousManagerImpl(ErrorReporter errorReporter) {
        this(errorReporter, defaultPool());
    }

    public AsynchronousManagerImpl(ErrorReporter errorReporter, ExecutorService pool) {
        this.errorReporter = errorReporter;
        this.pool = pool;
    }

    private static ExecutorService defaultPool() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "P2P-async-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        int size = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        return Executors.newFixedThreadPool(size, factory);
    }

    @Override
    public AsyncListenerHandler registerAsyncHandler(PacketListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");
        handlers.add(listener);
        rebuildIndex();
        // Match ProtocolLib: registration returns a handler; it does not create a dedicated
        // worker thread. P2P's shared per-player pool invokes the registered listener.
        return handlerHandles.computeIfAbsent(listener, AsyncListenerHandler::new);
    }

    @Override
    public void unregisterAsyncHandler(AsyncListenerHandler handler) {
        if (handler != null) {
            unregisterAsyncHandler(handler.getAsyncListener());
            handler.cancel();
        }
    }

    @Override
    public void unregisterAsyncHandler(PacketListener listener) {
        handlers.remove(listener);
        AsyncListenerHandler handler = handlerHandles.remove(listener);
        if (handler != null) handler.cancel();
        rebuildIndex();
    }

    @Override
    public void unregisterAsyncHandlers(Plugin plugin) {
        boolean changed = false;
        for (PacketListener listener : new ArrayList<>(handlers)) {
            if (plugin.equals(listener.getPlugin()) && handlers.remove(listener)) {
                AsyncListenerHandler handle = handlerHandles.remove(listener);
                if (handle != null) handle.cancel();
                changed = true;
            }
        }
        if (changed) rebuildIndex();
    }

    private void rebuildIndex() {
        sendingIndex = buildIndex(true);
        receivingIndex = buildIndex(false);
    }

    private Map<PacketType, PacketListener[]> buildIndex(boolean sending) {
        Map<PacketType, List<PacketListener>> grouped = new HashMap<>();
        for (PacketListener listener : handlers) {
            ListeningWhitelist whitelist = sending ? listener.getSendingWhitelist() : listener.getReceivingWhitelist();
            if (whitelist == null || whitelist.isEmpty()) continue;
            for (PacketType type : whitelist.getTypes()) {
                grouped.computeIfAbsent(type, ignored -> new ArrayList<>()).add(listener);
            }
        }
        Map<PacketType, PacketListener[]> built = new HashMap<>(Math.max(1, grouped.size() * 2));
        for (Map.Entry<PacketType, List<PacketListener>> entry : grouped.entrySet()) {
            List<PacketListener> bucket = entry.getValue();
            bucket.sort(Comparator.comparingInt(listener -> priorityOf(listener, sending).ordinal()));
            built.put(entry.getKey(), bucket.toArray(PacketListener[]::new));
        }
        return Collections.unmodifiableMap(built);
    }

    private static ListenerPriority priorityOf(PacketListener listener, boolean sending) {
        ListeningWhitelist whitelist = sending ? listener.getSendingWhitelist() : listener.getReceivingWhitelist();
        return whitelist == null || whitelist.getPriority() == null ? ListenerPriority.NORMAL : whitelist.getPriority();
    }

    @Override
    public Set<PacketListener> getAsyncHandlers() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(handlers));
    }

    @Override public Set<PacketType> getSendingTypes() { return Collections.unmodifiableSet(new LinkedHashSet<>(sendingIndex.keySet())); }
    @Override public Set<PacketType> getReceivingTypes() { return Collections.unmodifiableSet(new LinkedHashSet<>(receivingIndex.keySet())); }

    @Override
    public boolean hasAsynchronousListeners(PacketEvent event) {
        if (event == null || event.getPacketType() == null) return false;
        return listenersFor(event.getPacketType(), event.isServerPacket()).length != 0;
    }

    public boolean hasAsynchronousListeners(PacketType type, boolean sending) {
        return type != null && listenersFor(type, sending).length != 0;
    }

    private PacketListener[] listenersFor(PacketType type, boolean sending) {
        PacketListener[] listeners = (sending ? sendingIndex : receivingIndex).get(type);
        return listeners == null ? EMPTY : listeners;
    }

    private static final PacketListener[] EMPTY = new PacketListener[0];

    /**
     * Processes explicit asynchronous listeners and returns immediately.
     * The returned future completes after listener processing and any explicit AsyncMarker delay.
     */
    public CompletableFuture<Void> process(PacketEvent event) {
        if (event == null || event.getPacketType() == null) return CompletableFuture.completedFuture(null);
        Player player = event.getPlayer();
        if (player == null) return CompletableFuture.completedFuture(null);

        boolean sending = event.isServerPacket();
        PacketListener[] interested = listenersFor(event.getPacketType(), sending);
        if (interested.length == 0) return CompletableFuture.completedFuture(null);

        AsyncMarker marker = event.getAsyncMarker();
        if (marker == null) {
            marker = new AsyncMarker();
            event.setAsyncMarker(marker);
        }
        final AsyncMarker selectedMarker = marker;
        event.setAsync(true);
        selectedMarker.setQueuedSendingIndex((long) sequence.incrementAndGet());

        CompletableFuture<Void> completion = new CompletableFuture<>();
        queued.incrementAndGet();
        laneFor(player.getUniqueId()).execute(() -> {
            try {
                for (PacketListener listener : interested) {
                    try {
                        if (sending) listener.onPacketSending(event);
                        else listener.onPacketReceiving(event);
                    } catch (Throwable error) {
                        errorReporter.reportDetailed(listener,
                                "Error in asynchronous handling of " + event.getPacketType(), error);
                    }
                }
                selectedMarker.setProcessed(true);
                waitForRelease(selectedMarker);
                if (selectedMarker.hasExpired() || selectedMarker.isAsyncCancelled()) {
                    event.setCancelled(true);
                }
                completion.complete(null);
            } catch (Throwable error) {
                event.setCancelled(true);
                completion.completeExceptionally(error);
            } finally {
                queued.decrementAndGet();
            }
        });
        return completion;
    }

    /** Backwards-compatible fire-and-forget helper. */
    public void enqueue(PacketEvent event) {
        process(event);
    }

    private static void waitForRelease(AsyncMarker marker) {
        Object lock = marker.getProcessingLock();
        synchronized (lock) {
            while (marker.getProcessingDelay() > 0 && !marker.isAsyncCancelled() && !marker.hasExpired()) {
                long remaining = marker.getTimeout() - System.currentTimeMillis();
                if (remaining <= 0) {
                    marker.setAsyncCancelled(true);
                    break;
                }
                try {
                    lock.wait(Math.min(remaining, 1000L));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    marker.setAsyncCancelled(true);
                    break;
                }
            }
        }
    }

    @Override public PacketStream getPacketStream() {
        try { return ProtocolLibrary.getProtocolManager(); }
        catch (IllegalStateException ignored) { return null; }
    }
    @Override public ErrorReporter getErrorReporter() { return errorReporter; }

    @Override
    public void cleanupAll() {
        lanes.clear();
        timeoutHandlers.clear();
        handlers.clear();
        sendingIndex = Map.of();
        receivingIndex = Map.of();
        for (AsyncListenerHandler handler : handlerHandles.values()) handler.cancel();
        handlerHandles.clear();
    }

    @Override public void registerTimeoutHandler(PacketListener listener) { if (listener != null) timeoutHandlers.add(listener); }
    @Override public void unregisterTimeoutHandler(PacketListener listener) { timeoutHandlers.remove(listener); }
    @Override public Set<PacketListener> getTimeoutHandlers() { return Collections.unmodifiableSet(new LinkedHashSet<>(timeoutHandlers)); }
    @Override public void signalPacketTransmission(PacketEvent packet) { if (packet != null && packet.getAsyncMarker() != null) packet.getAsyncMarker().signal(); }
    @Override public int getQueuedPacketCount() { return queued.get(); }

    public void releasePlayer(UUID playerId) {
        if (playerId != null) lanes.remove(playerId);
    }

    private Executor laneFor(UUID playerId) {
        return lanes.computeIfAbsent(playerId, ignored -> new SerialExecutor(pool));
    }

    @Override
    public void shutdown() {
        cleanupAll();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException error) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class SerialExecutor implements Executor {
        private final Executor delegate;
        private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
        private boolean running;

        private SerialExecutor(Executor delegate) { this.delegate = delegate; }

        @Override
        public void execute(Runnable command) {
            synchronized (this) {
                tasks.add(command);
                if (running) return;
                running = true;
            }
            scheduleNext();
        }

        private void scheduleNext() {
            Runnable next;
            synchronized (this) {
                next = tasks.poll();
                if (next == null) {
                    running = false;
                    return;
                }
            }
            delegate.execute(() -> {
                try { next.run(); }
                finally { scheduleNext(); }
            });
        }
    }
}
