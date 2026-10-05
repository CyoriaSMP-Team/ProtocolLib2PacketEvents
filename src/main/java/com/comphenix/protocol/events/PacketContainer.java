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
package com.comphenix.protocol.events;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.reflect.EquivalentConverter;
import com.comphenix.protocol.reflect.ObjectAllocator;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.ChunkCoordIntPair;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketTypeData;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * ProtocolLib-style handle around a single packet, mirroring {@code PacketContainer}.
 * <p>
 * Internally this holds a PacketEvents {@link PacketWrapper}: decoded live from a
 * {@link PacketReceiveEvent}/{@link PacketSendEvent}, or, for packets created from scratch,
 * allocated via {@link ObjectAllocator} with its version/type bookkeeping fields populated
 * reflectively. A freshly created packet has all other fields at their zero value, so the
 * caller must set everything it needs before sending.
 * <p>
 * Typed accessors ({@link #getIntegers()}, {@link #getItemModifier()}, ...) are
 * {@link StructureModifier}s over the wrapper's declared fields, selected by type and ordered
 * by declaration - the same indexing model ProtocolLib uses over NMS packet fields. Note that
 * the field layout is PacketEvents' rather than NMS's, so <em>indices are not guaranteed to
 * match those of real ProtocolLib</em> for a given packet; see the README.
 * <p>
 * Not every packet type has a PacketEvents wrapper. Those still construct fine and expose
 * {@link #getRawBuffer()} for direct byte-level access, but the typed accessors will report
 * zero fields; check {@link #hasStructuredAccess()} first.
 */
public class PacketContainer extends AbstractStructure implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final PacketType type;
    private final PacketWrapper<?> handle;
    private final Object nativeHandle;
    private final StructureModifier<Object> suppliedModifier;

    /** Synthetic ProtocolLib field model for PacketEvents LOGIN_START packets. */
    private static final class LoginStartProfileView {
        private UserProfile profile;

        private LoginStartProfileView(UserProfile profile) {
            this.profile = profile;
        }
    }

    public PacketContainer(PacketType type, PacketReceiveEvent event) {
        this.type = type;
        this.handle = decode(type, event, null);
        this.nativeHandle = this.handle == null && event != null ? event.getByteBuf() : null;
        this.suppliedModifier = null;
    }

    public PacketContainer(PacketType type, PacketSendEvent event) {
        this.type = type;
        this.handle = decode(type, null, event);
        this.nativeHandle = this.handle == null && event != null ? event.getByteBuf() : null;
        this.suppliedModifier = null;
    }

    public PacketContainer(PacketType type) {
        this.type = type;
        this.handle = allocate(type);
        this.nativeHandle = null;
        this.suppliedModifier = null;
    }

    public PacketContainer(PacketType type, PacketWrapper<?> handle) {
        this.type = type;
        this.handle = handle;
        this.nativeHandle = null;
        this.suppliedModifier = null;
    }

    /** Compatibility constructor for a native/NMS packet handle. */
    public PacketContainer(PacketType type, Object handle) {
        this.type = type;
        this.handle = handle instanceof PacketWrapper<?> wrapper ? wrapper : null;
        this.nativeHandle = handle instanceof PacketWrapper<?> ? null : handle;
        this.suppliedModifier = null;
    }

    /** Compatibility constructor for a caller-supplied field model. */
    public PacketContainer(PacketType type, Object handle, StructureModifier<Object> structure) {
        this.type = type;
        this.handle = handle instanceof PacketWrapper<?> wrapper ? wrapper : null;
        this.nativeHandle = handle instanceof PacketWrapper<?> ? null : handle;
        this.suppliedModifier = structure;
    }

    /** Serialization constructor retained for Java serialization frameworks. */
    protected PacketContainer() {
        this.type = null;
        this.handle = null;
        this.nativeHandle = null;
        this.suppliedModifier = null;
    }

    public StructureModifier<InternalStructure> getStructures() {
        return (StructureModifier<InternalStructure>) (StructureModifier<?>)
                getModifier().withType(Object.class, (EquivalentConverter) InternalStructure.getConverter());
    }

    @SuppressWarnings("unchecked")
    public StructureModifier<Optional<InternalStructure>> getOptionalStructures() {
        return (StructureModifier<Optional<InternalStructure>>) (StructureModifier<?>)
                getModifier().withType(Optional.class);
    }

    public <T> Optional<T> getMeta(String key) {
        return PacketMetadata.get(this, key);
    }

    public <T> void setMeta(String key, T value) {
        PacketMetadata.set(this, key, value);
    }

    public void removeMeta(String key) {
        PacketMetadata.remove(this, key);
    }

    public PacketType getType() {
        return type;
    }

    /** The underlying native handle when one is available, otherwise the PacketEvents wrapper. */
    public Object getHandle() {
        return nativeHandle != null ? nativeHandle : handle;
    }

    /** Internal PacketEvents-typed access used by the bridge itself. */
    public PacketWrapper<?> getPacketWrapper() {
        return handle;
    }

    /**
     * Whether PacketEvents provides a typed wrapper for this packet. When false, the typed
     * accessors are empty and {@link #getRawBuffer()} is the only way to reach the contents.
     */
    public boolean hasStructuredAccess() {
        if (handle != null) {
            return true;
        }
        // A native packet object can be sent through the server's encoder, but a
        // ByteBuf/byte[] is already wire data and must use the direct backend.
        return nativeHandle != null && !isRawBuffer(nativeHandle);
    }

    /**
     * The packet's raw network buffer (a Netty {@code ByteBuf}), for packet types PacketEvents
     * does not model. Returns {@code null} if the packet is not backed by a live buffer.
     */
    public Object getRawBuffer() {
        if (handle != null) {
            return handle.buffer;
        }
        return nativeHandle != null && isRawBuffer(nativeHandle) ? nativeHandle : null;
    }

    // --- primitive modifiers ----------------------------------------------------------

    public StructureModifier<Object> getModifier() {
        return suppliedModifier != null ? suppliedModifier : new StructureModifier<>(structureTarget(), Object.class);
    }

    public StructureModifier<Integer> getIntegers() {
        // ProtocolLib exposes MAP_CHUNK / CHUNK_DATA chunk X and Z as the first two
        // integer fields. PacketEvents stores those coordinates inside Column instead of
        // directly on WrapperPlayServerChunkData, so expose the nested object as the
        // ProtocolLib-compatible integer view for intercepted chunk packets.
        if (handle instanceof WrapperPlayServerChunkData chunkData && chunkData.getColumn() != null) {
            return new StructureModifier<>(chunkData.getColumn(), int.class);
        }
        return new StructureModifier<>(structureTarget(), int.class);
    }

    public StructureModifier<Long> getLongs() {
        return new StructureModifier<>(structureTarget(), long.class);
    }

    public StructureModifier<Short> getShorts() {
        return new StructureModifier<>(structureTarget(), short.class);
    }

    public StructureModifier<Byte> getBytes() {
        return new StructureModifier<>(structureTarget(), byte.class);
    }

    public StructureModifier<Float> getFloats() {
        return new StructureModifier<>(structureTarget(), float.class);
    }

    /** Singular alias present in older ProtocolLib releases. */
    public StructureModifier<Float> getFloat() {
        return getFloats();
    }

    public StructureModifier<Double> getDoubles() {
        return new StructureModifier<>(structureTarget(), double.class);
    }

    public StructureModifier<Boolean> getBooleans() {
        return new StructureModifier<>(structureTarget(), boolean.class);
    }

    public StructureModifier<String> getStrings() {
        return new StructureModifier<>(structureTarget(), String.class);
    }

    public StructureModifier<UUID> getUUIDs() {
        return new StructureModifier<>(structureTarget(), UUID.class);
    }

    public StructureModifier<byte[]> getByteArrays() {
        return new StructureModifier<>(structureTarget(), byte[].class);
    }

    public StructureModifier<String[]> getStringArrays() {
        return new StructureModifier<>(structureTarget(), String[].class);
    }

    public StructureModifier<int[]> getIntegerArrays() {
        return new StructureModifier<>(structureTarget(), int[].class);
    }

    /** Every {@code List} field on the packet, untyped. */
    public StructureModifier<List<?>> getLists() {
        return new StructureModifier<>(structureTarget(), List.class);
    }

    public StructureModifier<List<Integer>> getIntLists() {
        @SuppressWarnings("unchecked")
        StructureModifier<List<Integer>> result = (StructureModifier<List<Integer>>) (StructureModifier<?>)
                new StructureModifier<>(structureTarget(), List.class);
        return result;
    }

    // --- converted modifiers ----------------------------------------------------------

    /** Item stack fields, as Bukkit item stacks. */
    public StructureModifier<org.bukkit.inventory.ItemStack> getItemModifier() {
        return convert(BukkitConverters.getItemStackConverter());
    }

    /** Item stack fields, as PacketEvents item stacks (no Bukkit conversion). */
    public StructureModifier<ItemStack> getPacketEventsItemModifier() {
        return new StructureModifier<>(structureTarget(), ItemStack.class);
    }

    /** Chat component fields. */
    public StructureModifier<WrappedChatComponent> getChatComponents() {
        return convert(WrappedChatComponent.getConverter());
    }

    /** Block position fields. */
    public StructureModifier<BlockPosition> getBlockPositionModifier() {
        return convert(BlockPosition.getConverter());
    }

    /** Player profile fields. */
    public StructureModifier<WrappedGameProfile> getGameProfiles() {
        // PacketEvents models LOGIN_START as separate username + optional UUID fields,
        // while ProtocolLib exposes the same logical data as a GameProfile. FastLogin and
        // other ProtocolLib consumers therefore expect getGameProfiles().read(0) to exist.
        // Provide a tiny compatibility view for this packet instead of pretending the
        // PacketEvents wrapper physically contains a UserProfile field.
        if (handle instanceof WrapperLoginClientLoginStart loginStart) {
            UserProfile profile = new UserProfile(
                    loginStart.getPlayerUUID().orElse(null),
                    loginStart.getUsername());
            return new StructureModifier<>(
                    new LoginStartProfileView(profile),
                    UserProfile.class,
                    WrappedGameProfile.getConverter());
        }
        return convert(WrappedGameProfile.getConverter());
    }

    /**
     * Entity metadata fields.
     * <p>
     * PacketEvents stores metadata as a plain {@code List}, so this modifier selects
     * <em>every</em> list field on the packet. On packets that carry both metadata and another
     * list, check the index rather than assuming index 0.
     */
    public StructureModifier<WrappedDataWatcher> getDataWatcherModifier() {
        return convert(WrappedDataWatcher.getConverter());
    }

    /** Entity metadata values introduced by Minecraft 1.19.3. */
    @SuppressWarnings("unchecked")
    public StructureModifier<List<WrappedDataValue>> getDataValueCollectionModifier() {
        return (StructureModifier<List<WrappedDataValue>>) (StructureModifier<?>)
                new StructureModifier<>(structureTarget(), List.class, DataValueListConverter.INSTANCE);
    }

    /** Alias used by plugins that refer to a single metadata collection modifier. */
    public StructureModifier<List<WrappedDataValue>> getDataValueModifier() {
        return getDataValueCollectionModifier();
    }

    @SuppressWarnings("unchecked")
    public StructureModifier<WrappedChatComponent[]> getChatComponentArrays() {
        return (StructureModifier<WrappedChatComponent[]>) (StructureModifier<?>)
                new StructureModifier<>(structureTarget(), net.kyori.adventure.text.Component[].class,
                        ChatComponentArrayConverter.INSTANCE);
    }

    @SuppressWarnings("unchecked")
    public StructureModifier<ChunkCoordIntPair> getChunkCoordIntPairs() {
        return (StructureModifier<ChunkCoordIntPair>) (StructureModifier<?>)
                new StructureModifier<>(structureTarget(), long.class, ChunkCoordIntPair.getConverter());
    }

    @SuppressWarnings("unchecked")
    public StructureModifier<org.bukkit.entity.EntityType> getEntityTypeModifier() {
        return (StructureModifier<org.bukkit.entity.EntityType>) (StructureModifier<?>)
                new StructureModifier<>(structureTarget(), EntityType.class, EntityTypeConverter.INSTANCE);
    }

    @SuppressWarnings("unchecked")
    public StructureModifier<List<org.bukkit.inventory.MerchantRecipe>> getMerchantRecipeLists() {
        return (StructureModifier<List<org.bukkit.inventory.MerchantRecipe>>) (StructureModifier<?>)
                new StructureModifier<>(structureTarget(), List.class);
    }

    @SuppressWarnings("unchecked")
    public StructureModifier<List<org.bukkit.inventory.ItemStack>> getItemListModifier() {
        return (StructureModifier<List<org.bukkit.inventory.ItemStack>>) (StructureModifier<?>)
                new StructureModifier<>(structureTarget(), List.class, ItemStackListConverter.INSTANCE);
    }

    @SuppressWarnings("unchecked")
    public StructureModifier<org.bukkit.util.Vector> getVectors() {
        return (StructureModifier<org.bukkit.util.Vector>) (StructureModifier<?>)
                new StructureModifier<>(structureTarget(), org.bukkit.util.Vector.class);
    }

    public StructureModifier<EnumWrappers.Hand> getHands() {
        return convert(EnumWrappers.getHandConverter());
    }

    public StructureModifier<EnumWrappers.ItemSlot> getItemSlots() {
        return convert(EnumWrappers.getItemSlotConverter());
    }

    public StructureModifier<EnumWrappers.Direction> getDirections() {
        return convert(EnumWrappers.getDirectionConverter());
    }

    public StructureModifier<EnumWrappers.Difficulty> getDifficulties() {
        return convert(EnumWrappers.getDifficultyConverter());
    }

    public StructureModifier<EnumWrappers.PlayerDigType> getPlayerDigTypes() {
        return convert(EnumWrappers.getPlayerDigTypeConverter());
    }

    /** A modifier over an arbitrary field type. */
    public <T> StructureModifier<T> getSpecificModifier(Class<T> type) {
        return new StructureModifier<>(structureTarget(), type);
    }

    /** A modifier driven by a caller-supplied converter. */
    public <T> StructureModifier<T> convert(EquivalentConverter<T> converter) {
        return new StructureModifier<>(structureTarget(), converter.getGenericType(), converter);
    }

    /** A deep-ish copy sharing no field references with this container's wrapper. */
    public PacketContainer shallowClone() {
        if (getHandle() == null) {
            return new PacketContainer(type, (PacketWrapper<?>) null);
        }
        if (nativeHandle != null) {
            Object copy = cloneObject(nativeHandle);
            return new PacketContainer(type, copy,
                    new StructureModifier<>(copy, Object.class));
        }
        PacketContainer copy = new PacketContainer(type);
        StructureModifier<Object> source = getModifier();
        StructureModifier<Object> target = copy.getModifier();
        for (int i = 0; i < source.size() && i < target.size(); i++) {
            target.write(i, source.read(i));
        }
        return copy;
    }

    /** Best-effort deep copy of a packet graph, falling back to the shallow field model. */
    public PacketContainer deepClone() {
        if (getHandle() == null) {
            return this;
        }
        if (nativeHandle != null) {
            return new PacketContainer(type, cloneObject(nativeHandle), suppliedModifier);
        }
        return shallowClone();
    }

    /** PacketEvents-backed serialization hook; returns the live buffer when available. */
    public Object serializeToBuffer() {
        if (handle == null) {
            return getRawBuffer();
        }
        try {
            handle.write();
            return handle.getBuffer();
        } catch (RuntimeException ignored) {
            return handle.getBuffer();
        }
    }

    public static PacketContainer fromPacket(Object packet) {
        if (packet instanceof PacketContainer container) {
            return container;
        }
        PacketType type = PacketType.fromClass(packet == null ? null : packet.getClass());
        return new PacketContainer(type, packet);
    }

    /** Creates an empty Netty buffer for callers that need to encode a raw packet. */
    public static ByteBuf createPacketBuffer() {
        return Unpooled.buffer(0);
    }

    /**
     * Decodes a raw buffer through a registered PacketEvents stream codec when one exists.
     * Without a codec the raw buffer is returned unchanged; callers can then route it through
     * the direct backend, which is the only lossless operation for an unmodelled packet.
     */
    public static Object deserializeFromBuffer(PacketType packetType, Object buffer) {
        if (buffer == null) return null;
        Class<?> packetClass = packetType == null ? null
                : com.comphenix.protocol.injector.packet.PacketRegistry.tryGetPacketClass(packetType).orElse(null);
        com.comphenix.protocol.wrappers.WrappedStreamCodec codec = packetClass == null ? null
                : com.comphenix.protocol.injector.packet.PacketRegistry.getStreamCodec(packetClass);
        if (codec != null) return codec.decode(buffer);
        if (buffer instanceof ByteBuf byteBuf) return byteBuf.copy();
        return buffer;
    }

    public int getId() {
        return type == null ? -1 : type.getCurrentId();
    }

    private Object structureTarget() {
        return nativeHandle != null ? nativeHandle : handle;
    }

    private static boolean isRawBuffer(Object value) {
        return value instanceof byte[] || value instanceof io.netty.buffer.ByteBuf
                || value.getClass().getName().endsWith("ByteBuf");
    }

    private static Object cloneObject(Object value) {
        if (value == null) return null;
        if (value instanceof Cloneable) {
            try {
                java.lang.reflect.Method clone = value.getClass().getDeclaredMethod("clone");
                clone.setAccessible(true);
                return clone.invoke(value);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return value;
    }

    @Override
    public String toString() {
        return "PacketContainer[" + type + (handle == null ? ", raw" : "") + "]";
    }

    // --- construction -----------------------------------------------------------------

    /**
     * Decodes the packet using PacketEvents' wrapper for its type. Returns {@code null} when
     * no wrapper exists, leaving the container in raw-buffer mode rather than throwing - a
     * listener registered for such a packet should still fire and still be able to cancel it.
     */
    private static PacketWrapper<?> decode(PacketType type, PacketReceiveEvent receive, PacketSendEvent send) {
        Class<? extends PacketWrapper<?>> wrapperClass = wrapperClassOf(type);
        if (wrapperClass == null) {
            return null;
        }
        try {
            if (receive != null) {
                Constructor<? extends PacketWrapper<?>> ctor = wrapperClass.getConstructor(PacketReceiveEvent.class);
                return ctor.newInstance(receive);
            }
            Constructor<? extends PacketWrapper<?>> ctor = wrapperClass.getConstructor(PacketSendEvent.class);
            return ctor.newInstance(send);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to decode " + type + " via " + wrapperClass.getName(), e);
        }
    }

    private static PacketWrapper<?> allocate(PacketType type) {
        Class<? extends PacketWrapper<?>> wrapperClass = wrapperClassOf(type);
        if (wrapperClass == null) {
            throw new IllegalStateException("PacketEvents has no wrapper for " + type
                    + ", so it cannot be constructed from scratch. Build the packet bytes yourself and send "
                    + "them through PacketEvents directly.");
        }
        PacketWrapper<?> wrapper = ObjectAllocator.allocate(wrapperClass);
        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        ClientVersion clientVersion = serverVersion.toClientVersion();
        int id = type.toPacketEvents().getId(clientVersion);
        try {
            setField(wrapper, "clientVersion", clientVersion);
            setField(wrapper, "serverVersion", serverVersion);
            setField(wrapper, "packetTypeData", new PacketTypeData(type.toPacketEvents(), id));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to initialize an allocated wrapper for " + type, e);
        }
        return wrapper;
    }

    private static Class<? extends PacketWrapper<?>> wrapperClassOf(PacketType type) {
        return type.toPacketEvents() == null ? null : type.toPacketEvents().getWrapperClass();
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = PacketWrapper.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Converters that need Bukkit types. Kept nested so the Bukkit-facing conversion lives
     * next to the accessors that use it.
     */
    static final class BukkitConverters {

        private BukkitConverters() {
        }

        static EquivalentConverter<org.bukkit.inventory.ItemStack> getItemStackConverter() {
            return ITEM_STACK;
        }

        private static final EquivalentConverter<org.bukkit.inventory.ItemStack> ITEM_STACK =
                new EquivalentConverter<>() {
                    @Override
                    public org.bukkit.inventory.ItemStack getSpecific(Object generic) {
                        return generic == null ? null
                                : io.github.retrooper.packetevents.util.SpigotConversionUtil
                                        .toBukkitItemStack((ItemStack) generic);
                    }

                    @Override
                    public Object getGeneric(org.bukkit.inventory.ItemStack specific) {
                        return specific == null ? null
                                : io.github.retrooper.packetevents.util.SpigotConversionUtil
                                        .fromBukkitItemStack(specific);
                    }

                    @Override
                    public Class<org.bukkit.inventory.ItemStack> getSpecificType() {
                        return org.bukkit.inventory.ItemStack.class;
                    }

                    @Override
                    public Class<?> getGenericType() {
                        return ItemStack.class;
                    }
                };
    }

    private static final class DataValueListConverter implements EquivalentConverter<List<WrappedDataValue>> {
        private static final DataValueListConverter INSTANCE = new DataValueListConverter();

        @Override
        public List<WrappedDataValue> getSpecific(Object generic) {
            if (!(generic instanceof List<?>)) return null;
            List<WrappedDataValue> out = new java.util.ArrayList<>();
            for (Object value : (List<?>) generic) {
                out.add(value instanceof WrappedDataValue ? (WrappedDataValue) value
                        : new WrappedDataValue(value));
            }
            return out;
        }

        @Override
        public Object getGeneric(List<WrappedDataValue> specific) {
            if (specific == null) return null;
            List<Object> out = new java.util.ArrayList<>(specific.size());
            for (WrappedDataValue value : specific) {
                out.add(value == null ? null : value.getHandle());
            }
            return out;
        }

        @Override public Class<List<WrappedDataValue>> getSpecificType() { return (Class) List.class; }
        @Override public Class<?> getGenericType() { return List.class; }
    }

    private static final class ItemStackListConverter implements EquivalentConverter<List<org.bukkit.inventory.ItemStack>> {
        private static final ItemStackListConverter INSTANCE = new ItemStackListConverter();

        @Override
        public List<org.bukkit.inventory.ItemStack> getSpecific(Object generic) {
            if (!(generic instanceof List<?>)) return null;
            List<org.bukkit.inventory.ItemStack> out = new java.util.ArrayList<>();
            for (Object value : (List<?>) generic) {
                out.add(value instanceof ItemStack ? SpigotConversionUtil.toBukkitItemStack((ItemStack) value)
                        : (org.bukkit.inventory.ItemStack) value);
            }
            return out;
        }

        @Override
        public Object getGeneric(List<org.bukkit.inventory.ItemStack> specific) {
            if (specific == null) return null;
            List<ItemStack> out = new java.util.ArrayList<>(specific.size());
            for (org.bukkit.inventory.ItemStack value : specific) {
                out.add(value == null ? null : SpigotConversionUtil.fromBukkitItemStack(value));
            }
            return out;
        }

        @Override public Class<List<org.bukkit.inventory.ItemStack>> getSpecificType() { return (Class) List.class; }
        @Override public Class<?> getGenericType() { return List.class; }
    }

    private static final class ChatComponentArrayConverter implements EquivalentConverter<WrappedChatComponent[]> {
        private static final ChatComponentArrayConverter INSTANCE = new ChatComponentArrayConverter();

        @Override
        public WrappedChatComponent[] getSpecific(Object generic) {
            if (!(generic instanceof Object[])) return null;
            Object[] values = (Object[]) generic;
            WrappedChatComponent[] out = new WrappedChatComponent[values.length];
            for (int i = 0; i < values.length; i++) out[i] = WrappedChatComponent.fromHandle(values[i]);
            return out;
        }

        @Override
        public Object getGeneric(WrappedChatComponent[] specific) {
            if (specific == null) return null;
            net.kyori.adventure.text.Component[] out = new net.kyori.adventure.text.Component[specific.length];
            for (int i = 0; i < specific.length; i++) out[i] = specific[i] == null ? null : specific[i].getComponent();
            return out;
        }

        @Override public Class<WrappedChatComponent[]> getSpecificType() { return WrappedChatComponent[].class; }
        @Override public Class<?> getGenericType() { return net.kyori.adventure.text.Component[].class; }
    }

    private static final class EntityTypeConverter implements EquivalentConverter<org.bukkit.entity.EntityType> {
        private static final EntityTypeConverter INSTANCE = new EntityTypeConverter();

        @Override
        public org.bukkit.entity.EntityType getSpecific(Object generic) {
            return generic instanceof EntityType ? SpigotConversionUtil.toBukkitEntityType((EntityType) generic) : null;
        }

        @Override
        public Object getGeneric(org.bukkit.entity.EntityType specific) {
            return specific == null ? null : SpigotConversionUtil.fromBukkitEntityType(specific);
        }

        @Override public Class<org.bukkit.entity.EntityType> getSpecificType() { return org.bukkit.entity.EntityType.class; }
        @Override public Class<?> getGenericType() { return EntityType.class; }
    }
}
