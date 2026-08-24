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
package com.comphenix.protocol.injector;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.error.ErrorReporter;
import com.comphenix.protocol.internal.BackendCoordinator;
import com.comphenix.protocol.internal.PacketNetworkProcessor;
import com.comphenix.protocol.internal.VersionAdapterRegistry;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.ConnectionSide;
import com.comphenix.protocol.events.NetworkMarker;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.events.ListenerOptions;
import com.comphenix.protocol.injector.netty.WirePacket;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.google.common.collect.ImmutableSet;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * Fans PacketEvents' single event stream out to every registered ProtocolLib
 * {@link PacketListener}. One instance backs {@link com.comphenix.protocol.ProtocolLibrary}
 * for the whole server; the plugin's one PacketEvents listener forwards each packet here.
 * <p>
 * Listeners are indexed by packet type rather than scanned linearly, because dispatch runs on
 * the Netty thread for every single packet: with a linear scan, one plugin listening for one
 * packet type would still cost a whitelist lookup on every packet of every type. The index is
 * rebuilt on registration changes, which are rare, and read lock-free during dispatch.
 */
public class PacketManagerImpl implements ProtocolManager, ListenerManager {

    private final ErrorReporter errorReporter;
    private final CopyOnWriteArrayList<PacketListener> listeners = new CopyOnWriteArrayList<>();
    private final BackendCoordinator backend = new BackendCoordinator();

    /**
     * Packet type -> listeners for it, in priority order. Replaced wholesale on every
     * registration change so dispatch never needs to synchronize.
     */
    private volatile Map<PacketType, PacketListener[]> sendingIndex = new HashMap<>();
    private volatile Map<PacketType, PacketListener[]> receivingIndex = new HashMap<>();

    /** Set once the async manager exists; dispatch hands it a copy of each handled event. */
    private volatile AsynchronousManagerImpl asynchronousManager;
    private final java.util.concurrent.ConcurrentHashMap<DeferredKey, CompletableFuture<Void>> deferredTails = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean closed;

    public PacketManagerImpl(ErrorReporter errorReporter) {
        this.errorReporter = errorReporter;
    }

    public void setAsynchronousManager(AsynchronousManagerImpl asynchronousManager) {
        this.asynchronousManager = asynchronousManager;
    }

    @Override
    public AsynchronousManager getAsynchronousManager() {
        return asynchronousManager;
    }

    @Override
    public void addPacketListener(PacketListener listener) {
        listeners.addIfAbsent(listener);
        rebuildIndex();
    }

    @Override
    public void removePacketListener(PacketListener listener) {
        if (listeners.remove(listener)) {
            rebuildIndex();
        }
    }

    @Override
    public void removePacketListeners(Plugin plugin) {
        List<PacketListener> removed = new ArrayList<>();
        for (PacketListener listener : listeners) {
            if (plugin.equals(listener.getPlugin()) && listeners.remove(listener)) {
                removed.add(listener);
            }
        }
        if (!removed.isEmpty()) {
            rebuildIndex();
        }
    }

    @Override
    public ImmutableSet<PacketListener> getPacketListeners() {
        return ImmutableSet.copyOf(listeners);
    }

    @Override
    public Set<PacketType> getListeningTypes() {
        Set<PacketType> out = new LinkedHashSet<>(sendingIndex.keySet());
        out.addAll(receivingIndex.keySet());
        return out;
    }

    private void rebuildIndex() {
        this.sendingIndex = buildIndex(true);
        this.receivingIndex = buildIndex(false);
    }

    private Map<PacketType, PacketListener[]> buildIndex(boolean sending) {
        Map<PacketType, List<PacketListener>> grouped = new HashMap<>();
        for (PacketListener listener : listeners) {
            var whitelist = sending ? listener.getSendingWhitelist() : listener.getReceivingWhitelist();
            if (whitelist == null || whitelist.isEmpty()) {
                continue;
            }
            for (PacketType type : whitelist.getTypes()) {
                grouped.computeIfAbsent(type, t -> new ArrayList<>()).add(listener);
            }
        }

        Map<PacketType, PacketListener[]> index = new HashMap<>(grouped.size() * 2);
        for (Map.Entry<PacketType, List<PacketListener>> entry : grouped.entrySet()) {
            List<PacketListener> bucket = entry.getValue();
            // ProtocolLib runs low priorities first so that high-priority listeners see, and can
            // overrule, their decisions. MONITOR therefore observes the final state.
            bucket.sort(Comparator.comparingInt(l -> priorityOf(l, sending).ordinal()));
            index.put(entry.getKey(), bucket.toArray(new PacketListener[0]));
        }
        return index;
    }

    private static ListenerPriority priorityOf(PacketListener listener, boolean sending) {
        var whitelist = sending ? listener.getSendingWhitelist() : listener.getReceivingWhitelist();
        return whitelist == null || whitelist.getPriority() == null
                ? ListenerPriority.NORMAL
                : whitelist.getPriority();
    }

    @Override
    public void sendServerPacket(Player receiver, PacketContainer packet) {
        sendServerPacket(receiver, packet, true);
    }

    @Override
    public void sendServerPacket(Player receiver, PacketContainer packet, boolean filters) {
        if (filters && backend.backendFor(packet) instanceof com.comphenix.protocol.internal.DirectPacketBackend) {
            sendDirectWithFilters(receiver, packet);
            return;
        }
        backend.send(receiver, packet, filters);
    }

    @Override
    public void broadcastServerPacket(PacketContainer packet) {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            backend.send(player, packet, true);
        }
    }

    @Override
    public Entity getEntityFromID(World world, int entityId) {
        return io.github.retrooper.packetevents.util.SpigotConversionUtil.getEntityById(world, entityId);
    }

    @Override
    public void receiveClientPacket(Player sender, PacketContainer packet) {
        receiveClientPacket(sender, packet, true);
    }

    @Override
    public void sendWirePacket(Player receiver, int id, byte[] bytes) {
        backend.sendWire(receiver, id, bytes);
    }

    @Override
    public void sendServerPacket(Player receiver, PacketContainer packet,
                                 NetworkMarker marker, boolean filters) {
        if (receiver == null || packet == null) return;
        PacketEvent event = PacketEvent.fromServer(this, packet, marker, receiver);
        PacketListener[] bucket = filters ? listenersFor(packet.getType(), true) : EMPTY_LISTENERS;
        dispatchProgrammatic(event, bucket, true, filters);
    }

    /** Direct/NMS packets do not pass through PacketEvents' event manager. */
    private void sendDirectWithFilters(Player receiver, PacketContainer packet) {
        if (packet == null) throw new IllegalArgumentException("packet cannot be null");
        PacketEvent event = PacketEvent.fromServer(this, packet,
                new NetworkMarker(ConnectionSide.SERVER_SIDE, packet.getType()), receiver);
        dispatchProgrammatic(event, listenersFor(packet.getType(), true), true, true);
    }

    @Override
    public void receiveClientPacket(Player sender, PacketContainer packet, boolean filters) {
        if (sender == null || packet == null) return;
        if (filters && backend.backendFor(packet) instanceof com.comphenix.protocol.internal.DirectPacketBackend) {
            receiveDirectWithFilters(sender, packet);
            return;
        }
        if (!filters) {
            backend.receive(sender, packet, false);
            return;
        }
        PacketEvent event = PacketEvent.fromClient(this, packet,
                new NetworkMarker(ConnectionSide.CLIENT_SIDE, packet.getType()), sender);
        dispatchProgrammatic(event, listenersFor(packet.getType(), false), false, true);
    }

    @Override
    public void receiveClientPacket(Player sender, PacketContainer packet,
                                    NetworkMarker marker, boolean filters) {
        if (sender == null || packet == null) return;
        if (!filters) {
            backend.receive(sender, packet, false);
            return;
        }
        PacketEvent event = PacketEvent.fromClient(this, packet,
                marker == null ? new NetworkMarker(ConnectionSide.CLIENT_SIDE, packet.getType()) : marker, sender);
        dispatchProgrammatic(event, listenersFor(packet.getType(), false), false, true);
    }

    private void receiveDirectWithFilters(Player sender, PacketContainer packet) {
        if (packet == null) throw new IllegalArgumentException("packet cannot be null");
        PacketEvent event = PacketEvent.fromClient(this, packet,
                new NetworkMarker(ConnectionSide.CLIENT_SIDE, packet.getType()), sender);
        dispatchProgrammatic(event, listenersFor(packet.getType(), false), false, true);
    }

    /**
     * Runs ProtocolLib filters for packets emitted through ProtocolManager itself. If a callback
     * needs a thread hop or explicit asynchronous processing, the API call returns immediately and
     * the packet is transmitted by the same per-player continuation queue used by wire events.
     */
    private void dispatchProgrammatic(PacketEvent event, PacketListener[] bucket,
                                      boolean sending, boolean filters) {
        if (!filters) {
            transmitDeferred(event, null);
            return;
        }
        AsynchronousManagerImpl async = asynchronousManager;
        boolean hasAsync = async != null && async.hasAsynchronousListeners(event);
        boolean needsMain = needsMainThreadHop(bucket, sending);
        DeferredKey key = deferredKey(event.getPlayer(), sending);
        boolean pending = key != null && hasDeferredTail(key);
        if (needsMain || hasAsync || pending) {
            enqueueDeferred(key, event, bucket, sending, needsMain, hasAsync, async,
                    null, false);
            return;
        }
        invokeBucket(bucket, event, sending);
        if (!event.isCancelled()) transmitDeferred(event, null);
    }

    @Override
    public int getProtocolVersion(Player player) {
        if (player == null || PacketEvents.getAPI() == null) {
            return Integer.MIN_VALUE;
        }
        var version = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
        return version == null ? Integer.MIN_VALUE : version.getProtocolVersion();
    }

    @Override
    public Set<PacketType> getSendingFilterTypes() {
        return filteredTypes(true);
    }

    @Override
    public Set<PacketType> getReceivingFilterTypes() {
        return filteredTypes(false);
    }

    private Set<PacketType> filteredTypes(boolean sending) {
        return java.util.Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>((sending ? sendingIndex : receivingIndex).keySet()));
    }

    @Override
    public void broadcastServerPacket(PacketContainer packet, Entity entity, boolean includeTracker) {
        if (entity == null) throw new IllegalArgumentException("entity cannot be null");
        for (Player player : getEntityTrackers(entity)) {
            if (includeTracker || !(entity instanceof Player) || !player.equals(entity)) {
                sendServerPacket(player, packet);
            }
        }
    }

    @Override
    public void broadcastServerPacket(PacketContainer packet, Location origin, int maxObserverDistance) {
        if (origin == null || origin.getWorld() == null) return;
        double maxDistanceSquared = (double) maxObserverDistance * maxObserverDistance;
        for (Player player : origin.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(origin) <= maxDistanceSquared) {
                sendServerPacket(player, packet);
            }
        }
    }

    @Override
    public void broadcastServerPacket(PacketContainer packet, Collection<? extends Player> targetPlayers) {
        if (targetPlayers == null) return;
        for (Player player : targetPlayers) sendServerPacket(player, packet);
    }

    @Override
    public List<Player> getEntityTrackers(Entity entity) {
        return VersionAdapterRegistry.current().trackers(entity);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public void close() {
        closed = true;
        listeners.clear();
        sendingIndex = new HashMap<>();
        receivingIndex = new HashMap<>();
    }

    private static void requireStructured(PacketContainer packet, String action) {
        if (!packet.hasStructuredAccess()) {
            throw new IllegalArgumentException("Cannot " + action + " " + packet.getType()
                    + ": PacketEvents has no wrapper for this packet type, so there is nothing to serialize. "
                    + "Only packets intercepted from the wire can be handled raw.");
        }
    }

    @Override
    public PacketContainer createPacket(PacketType type) {
        return new PacketContainer(type);
    }

    @Override
    public MinecraftVersion getMinecraftVersion() {
        return MinecraftVersion.current();
    }

    public void dispatchReceive(PacketReceiveEvent event) {
        dispatch(event, receivingIndex, false);
    }

    public void dispatchSend(PacketSendEvent event) {
        dispatch(event, sendingIndex, true);
    }

    @Override
    public boolean hasInboundListener(PacketType type) {
        return type != null && receivingIndex.containsKey(type);
    }

    @Override
    public boolean hasOutboundListener(PacketType type) {
        return type != null && sendingIndex.containsKey(type);
    }

    @Override
    public boolean hasMainThreadListener(PacketType type) {
        return hasInboundListener(type) || hasOutboundListener(type);
    }

    @Override
    public void invokeInboundPacketListeners(PacketEvent event) {
        dispatchDirect(event, false, false);
    }

    @Override
    public void invokeOutboundPacketListeners(PacketEvent event) {
        dispatchDirect(event, true, false);
    }

    @Override
    public boolean dispatchInboundPacket(PacketEvent event) {
        return dispatchDirect(event, false, true);
    }

    @Override
    public boolean dispatchOutboundPacket(PacketEvent event) {
        return dispatchDirect(event, true, true);
    }

    /**
     * Dispatches a raw packet owned by the direct Netty fallback.
     *
     * <p>Raw/unmodelled packets cannot currently be safely held and replayed without re-entering
     * the fallback injector, so this path deliberately never blocks a Netty worker. Direction-aware
     * listeners run inline. A listener that asks for a main-thread hop on a raw packet is reported
     * and still invoked inline rather than stalling the channel.</p>
     */
    private boolean dispatchDirect(PacketEvent event, boolean sending, boolean complete) {
        if (event == null || event.getPacket() == null || event.getPacketType() == null) return true;
        PacketListener[] bucket = listenersFor(event.getPacketType(), sending);
        if (bucket.length != 0) {
            for (PacketListener listener : bucket) {
                if (requiresMainThread(listener, sending) && !Bukkit.isPrimaryThread()) {
                    errorReporter.reportWarning(listener,
                            "Raw packet " + event.getPacketType()
                                    + " requested a main-thread listener; invoking inline to avoid blocking Netty", null);
                }
                invokeListenerDirect(listener, event, sending);
            }
        }
        AsynchronousManagerImpl async = asynchronousManager;
        if (!event.isCancelled() && async != null && async.hasAsynchronousListeners(event)) {
            // There is no safe replay token for an unmodelled raw frame. Run the async callback
            // for observability/compatibility but never hold the channel waiting for it.
            async.process(event);
        }
        if (complete && !event.isCancelled()) PacketNetworkProcessor.complete(event, this);
        return !event.isCancelled();
    }

    private void dispatch(ProtocolPacketEvent event, Map<PacketType, PacketListener[]> index, boolean sending) {
        PacketType type = PacketType.fromPacketEvents(event.getPacketType());
        if (type == null) return;

        PacketListener[] bucket = index.get(type);
        if (bucket == null) bucket = EMPTY_LISTENERS;
        AsynchronousManagerImpl async = asynchronousManager;
        boolean hasAsync = async != null && async.hasAsynchronousListeners(type, sending);
        if (bucket.length == 0 && !hasAsync) {
            // Hot path: do not even allocate/decode a PacketContainer when nobody cares.
            return;
        }

        Player player = event.getPlayer() instanceof Player existing
                ? existing : TemporaryPlayerAdapter.create(event.getUser());
        if (player == null) return;

        DeferredKey key = deferredKey(player, sending);
        boolean needsMain = needsMainThreadHop(bucket, sending);
        boolean pending = key != null && hasDeferredTail(key);
        boolean deferred = needsMain || hasAsync || pending;

        // A PacketEvents event owns a live Netty buffer whose lifetime ends with the callback.
        // If we need to cross that boundary, clone the event first. PacketEvents' clone uses a
        // retained duplicate and cleanUp() releases it when our continuation is finished.
        ProtocolPacketEvent sourceEvent = event;
        ProtocolPacketEvent heldEvent = null;
        if (deferred) {
            heldEvent = event.clone();
            // Preserve modifications made by PacketEvents listeners that ran before P2P at
            // MONITOR priority. PacketWrapper.readEvent() copies from lastUsedWrapper when the
            // wrapper class matches, while the newly decoded wrapper remains backed by our
            // retained duplicate rather than the soon-to-be-cleared original buffer.
            heldEvent.setLastUsedWrapper(event.getLastUsedWrapper());
            sourceEvent = heldEvent;
        }

        PacketContainer container = sending
                ? new PacketContainer(type, (PacketSendEvent) sourceEvent)
                : new PacketContainer(type, (PacketReceiveEvent) sourceEvent);
        PacketEvent packetEvent = sending
                ? PacketEvent.fromServer(this, container,
                        new NetworkMarker(NetworkMarkerSide.SERVER.side, type), player)
                : PacketEvent.fromClient(this, container,
                        new NetworkMarker(NetworkMarkerSide.CLIENT.side, type), player);

        // Never resurrect a packet cancelled by an earlier PacketEvents listener. P2P may add
        // another cancellation, but coexistence with native PacketEvents plugins wins here.
        boolean externallyCancelled = event.isCancelled();
        packetEvent.setCancelled(externallyCancelled);

        if (deferred) {
            // Logically hold this packet without holding the network callback. The completed
            // packet is replayed through PacketEvents' silent transport after all P2P work.
            event.setCancelled(true);
            enqueueDeferred(key, packetEvent, bucket, sending, needsMain, hasAsync, async,
                    heldEvent, externallyCancelled);
            return;
        }

        invokeBucket(bucket, packetEvent, sending);
        if (externallyCancelled) packetEvent.setCancelled(true);
        event.setCancelled(packetEvent.isCancelled());
        if (packetEvent.isCancelled()) return;

        if (container.hasStructuredAccess()) event.markForReEncode(true);
        PacketNetworkProcessor.applyOutputHandlers(event, packetEvent);
        PacketNetworkProcessor.complete(packetEvent, this);
    }

    private DeferredKey deferredKey(Player player, boolean sending) {
        if (player == null || player.getUniqueId() == null) return null;
        return new DeferredKey(player.getUniqueId(), sending);
    }

    private boolean hasDeferredTail(DeferredKey key) {
        CompletableFuture<Void> tail = deferredTails.get(key);
        return tail != null && !tail.isDone();
    }

    private void enqueueDeferred(DeferredKey key, PacketEvent event, PacketListener[] bucket,
                                 boolean sending, boolean needsMain, boolean hasAsync,
                                 AsynchronousManagerImpl async, ProtocolPacketEvent heldEvent,
                                 boolean externallyCancelled) {
        if (key == null) {
            // Temporary players should normally still expose a UUID. If one does not, use an
            // independent continuation instead of ever blocking the packet thread.
            runDeferred(event, bucket, sending, needsMain, hasAsync, async, heldEvent, externallyCancelled)
                    .whenComplete((value, error) -> cleanUpHeld(heldEvent));
            return;
        }

        CompletableFuture<Void> next = deferredTails.compute(key, (ignored, previous) -> {
            CompletableFuture<Void> base = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((value, error) -> null);
            return base.thenCompose(value ->
                    runDeferred(event, bucket, sending, needsMain, hasAsync, async,
                            heldEvent, externallyCancelled));
        });
        // Attach cleanup after compute() returns. A continuation can complete synchronously, and
        // mutating the same ConcurrentHashMap from inside its compute callback is a recursive update.
        next.whenComplete((value, error) -> {
            cleanUpHeld(heldEvent);
            deferredTails.remove(key, next);
        });
    }

    private CompletableFuture<Void> runDeferred(PacketEvent event, PacketListener[] bucket,
                                                boolean sending, boolean needsMain,
                                                boolean hasAsync, AsynchronousManagerImpl async,
                                                ProtocolPacketEvent heldEvent,
                                                boolean externallyCancelled) {
        CompletableFuture<Void> regular = runRegularStage(event, bucket, sending, needsMain);
        CompletableFuture<Void> processed = regular.thenCompose(ignored -> {
            if (externallyCancelled) event.setCancelled(true);
            if (event.isCancelled() || !hasAsync || async == null) {
                return CompletableFuture.completedFuture(null);
            }
            return async.process(event);
        });
        return processed.handle((ignored, error) -> {
            if (error != null) {
                event.setCancelled(true);
                errorReporter.reportDetailed(this,
                        "Deferred packet processing failed for " + event.getPacketType(), unwrap(error));
            }
            if (externallyCancelled) event.setCancelled(true);
            if (!event.isCancelled()) transmitDeferred(event, heldEvent);
            return null;
        });
    }

    private static void cleanUpHeld(ProtocolPacketEvent heldEvent) {
        if (heldEvent != null) {
            try {
                // When transmitDeferred hands this exact retained buffer to PacketEvents/Netty,
                // it clears heldEvent.byteBuf to mark ownership transferred. Do not release it
                // here a second time.
                if (heldEvent.getByteBuf() != null) heldEvent.cleanUp();
            } catch (Throwable ignored) { }
        }
    }

    private CompletableFuture<Void> runRegularStage(PacketEvent event, PacketListener[] bucket,
                                                    boolean sending, boolean needsMain) {
        if (!needsMain || Bukkit.isPrimaryThread()) {
            invokeBucket(bucket, event, sending);
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            ProtocolLibrary.getScheduler().runTask(() -> {
                try {
                    invokeBucket(bucket, event, sending);
                    completion.complete(null);
                } catch (Throwable error) {
                    completion.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            completion.completeExceptionally(error);
        }
        return completion;
    }

    private void transmitDeferred(PacketEvent event, ProtocolPacketEvent heldEvent) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();
        if (player == null || packet == null) return;

        try {
            NetworkMarker marker = event.getNetworkMarker();
            if (event.isServerPacket() && marker != null && !marker.getOutputHandlers().isEmpty()) {
                Object raw = packet.serializeToBuffer();
                if (raw == null) {
                    throw new IllegalStateException("No encoded buffer is available for " + event.getPacketType());
                }
                byte[] bytes = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.copyBytes(raw);
                for (var handler : marker.getOutputHandlers()) {
                    bytes = handler.handle(event, bytes);
                    if (bytes == null) throw new IllegalStateException("PacketOutputHandler returned null");
                }
                com.comphenix.protocol.internal.DirectNettyBackend.sendWire(player, packet.getId(), bytes);
            } else if (event.isServerPacket()) {
                transferStructuredBufferOwnership(heldEvent, packet, true, player);
            } else {
                transferStructuredBufferOwnership(heldEvent, packet, false, player);
            }
            if (event.getAsyncMarker() != null) event.getAsyncMarker().markTransmitted();
            PacketNetworkProcessor.complete(event, this);
        } catch (Throwable error) {
            errorReporter.reportDetailed(this,
                    "Unable to release deferred packet " + event.getPacketType(), error);
        }
    }


    /**
     * PacketEvents' wrapper transport synchronously transforms the wrapper into a ByteBuf and
     * sets wrapper.buffer to null before handing that ByteBuf to Netty. If the wrapper was
     * decoded from our retained event clone, wrapper.buffer and heldEvent.byteBuf are the exact
     * same reference. After the call returns Netty owns that reference, so the clone must not
     * release it again in cleanUp().
     */
    private void transferStructuredBufferOwnership(ProtocolPacketEvent heldEvent,
                                                   PacketContainer packet, boolean sending,
                                                   Player player) {
        com.github.retrooper.packetevents.wrapper.PacketWrapper<?> wrapper = packet.getPacketWrapper();
        Object heldBuffer = heldEvent == null ? null : heldEvent.getByteBuf();
        Object wrapperBuffer = wrapper == null ? null : wrapper.getBuffer();
        boolean sharedRetainedBuffer = heldBuffer != null && heldBuffer == wrapperBuffer;

        if (sending) backend.send(player, packet, false);
        else backend.receive(player, packet, false);

        // transformWrappers() clears wrapper.buffer after transferring the ByteBuf to Netty.
        if (sharedRetainedBuffer && wrapper.getBuffer() == null) {
            heldEvent.setByteBuf(null);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private enum NetworkMarkerSide {
        SERVER(com.comphenix.protocol.events.ConnectionSide.SERVER_SIDE),
        CLIENT(com.comphenix.protocol.events.ConnectionSide.CLIENT_SIDE);

        private final com.comphenix.protocol.events.ConnectionSide side;
        NetworkMarkerSide(com.comphenix.protocol.events.ConnectionSide side) { this.side = side; }
    }

    private static final PacketListener[] EMPTY_LISTENERS = new PacketListener[0];

    private PacketListener[] listenersFor(PacketType type, boolean sending) {
        PacketListener[] bucket = (sending ? sendingIndex : receivingIndex).get(type);
        return bucket == null ? EMPTY_LISTENERS : bucket;
    }

    private static boolean hasOption(PacketListener listener, boolean sending, ListenerOptions option) {
        var whitelist = sending ? listener.getSendingWhitelist() : listener.getReceivingWhitelist();
        return whitelist != null && whitelist.getOptions().contains(option);
    }

    /** ProtocolLib's threading contract is direction-sensitive. */
    static boolean requiresMainThread(PacketListener listener, boolean sending) {
        if (sending) {
            // Server -> client listeners run on the main thread unless ASYNC opts out.
            return !hasOption(listener, true, ListenerOptions.ASYNC);
        }
        // Client -> server listeners run on Netty/async by default; only SYNC opts in.
        return hasOption(listener, false, ListenerOptions.SYNC);
    }

    private static boolean needsMainThreadHop(PacketListener[] bucket, boolean sending) {
        if (Bukkit.isPrimaryThread()) return false;
        for (PacketListener listener : bucket) {
            if (requiresMainThread(listener, sending)) return true;
        }
        return false;
    }

    private void invokeBucket(PacketListener[] bucket, PacketEvent event, boolean sending) {
        for (PacketListener listener : bucket) invokeListenerDirect(listener, event, sending);
    }

    private void invokeListenerDirect(PacketListener listener, PacketEvent event, boolean sending) {
        try {
            if (sending) listener.onPacketSending(event);
            else listener.onPacketReceiving(event);
        } catch (Throwable error) {
            errorReporter.reportDetailed(listener,
                    "Error while handling " + (sending ? "sending" : "receiving")
                            + " of " + event.getPacketType(), error);
        }
    }

    private record DeferredKey(java.util.UUID playerId, boolean sending) { }
}
