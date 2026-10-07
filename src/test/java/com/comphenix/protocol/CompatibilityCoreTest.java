/*
 * ProtocolLib2PacketEvents - clean-room compatibility tests.
 */
package com.comphenix.protocol;

import com.comphenix.protocol.async.AsyncMarker;
import com.comphenix.protocol.events.ConnectionSide;
import com.comphenix.protocol.events.NetworkMarker;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.injector.netty.Injector;
import com.comphenix.protocol.injector.netty.WirePacket;
import com.comphenix.protocol.reflect.ObjectAllocator;
import com.comphenix.protocol.reflect.EquivalentConverter;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.injector.temporary.TemporaryPlayer;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.ChunkCoordIntPair;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import org.bukkit.entity.Player;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CompatibilityCoreTest {
    private static final EquivalentConverter<Object> RAW_OBJECT_CONVERTER = new EquivalentConverter<>() {
        @Override
        public Object getSpecific(Object generic) {
            return generic;
        }

        @Override
        public Object getGeneric(Object specific) {
            return specific;
        }

        @Override
        public Class<Object> getSpecificType() {
            return Object.class;
        }

        @Override
        public Class<?> getGenericType() {
            return Object.class;
        }
    };

    @Test
    void wirePacketIsImmutableAndComparable() {
        byte[] bytes = {1, 2, 3};
        WirePacket packet = new WirePacket(7, bytes);
        bytes[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, packet.getBytes());
        assertEquals(packet, new WirePacket(7, new byte[]{1, 2, 3}));
        byte[] returned = packet.getBytes();
        returned[1] = 99;
        assertEquals(2, packet.getBytes()[1]);
    }

    @Test
    void asyncMarkerTracksDelayExpiryAndOrder() {
        long now = System.currentTimeMillis();
        AsyncMarker marker = new AsyncMarker(4, now, 1000);
        assertEquals(4, marker.getOriginalSendingIndex());
        assertEquals(4, marker.getNewSendingIndex());
        assertEquals(1, marker.incrementProcessingDelay());
        assertEquals(0, marker.signal());
        marker.setNewSendingIndex(8);
        marker.setTimeout(now + 10);
        assertTrue(marker.hasExpired(now + 11));
        assertFalse(marker.hasExpired(now));
    }

    @Test
    void markerKeepsDirectionAndPostState() {
        NetworkMarker marker = new NetworkMarker(ConnectionSide.SERVER_SIDE, null);
        assertEquals(ConnectionSide.SERVER_SIDE, marker.getSide());
        assertTrue(marker.getScheduledPackets().isEmpty());
        assertNotNull(marker.getPostListeners());
    }

    @Test
    void temporaryPlayerIsAConcreteGeneratedPlayer() {
        Player player = TemporaryPlayerFactory.createTemporaryPlayer();
        assertTrue(player instanceof TemporaryPlayer);
        assertNull(TemporaryPlayerFactory.getInjectorFromPlayer(player));

        Injector injector = (Injector) Proxy.newProxyInstance(
                Injector.class.getClassLoader(), new Class<?>[]{Injector.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayerName", "getPlayerUniqueId" -> null;
                    case "getAddress" -> null;
                    case "isConnected", "isInjected", "isClosed" -> false;
                    case "getPlayer" -> null;
                    case "getProtocolVersion" -> Integer.MIN_VALUE;
                    case "getCurrentProtocol" -> PacketType.Protocol.PLAY;
                    default -> null;
                });
        TemporaryPlayerFactory.setInjectorForPlayer(player, injector);
        assertSame(injector, TemporaryPlayerFactory.getInjectorFromPlayer(player));
        assertTrue(player.getName().startsWith("UNKNOWN["));
    }

    @Test
    void packetTypeHoldersExposeEnumValues() {
        assertTrue(PacketType.Play.Server.getInstance().values().contains(PacketType.Play.Server.SPAWN_ENTITY));
        assertTrue(PacketType.Play.Client.getInstance().values().contains(PacketType.Play.Client.TAB_COMPLETE));
    }

    @Test
    void loginStartExposesProtocolLibGameProfileView() {
        UUID uuid = UUID.randomUUID();
        WrapperLoginClientLoginStart loginStart = ObjectAllocator.allocate(WrapperLoginClientLoginStart.class);
        new StructureModifier<String>(loginStart, String.class).write(0, "FastLoginUser");
        new StructureModifier<UUID>(loginStart, UUID.class).write(0, uuid);
        PacketContainer packet = new PacketContainer(PacketType.Login.Client.START, loginStart);

        assertEquals(1, packet.getGameProfiles().size());
        WrappedGameProfile profile = packet.getGameProfiles().read(0);
        assertEquals("FastLoginUser", profile.getName());
        assertEquals(uuid, profile.getUUID());
    }

    @Test
    void loginStartProfileViewAllowsMissingUuid() {
        WrapperLoginClientLoginStart loginStart = ObjectAllocator.allocate(WrapperLoginClientLoginStart.class);
        new StructureModifier<String>(loginStart, String.class).write(0, "LegacyUser");
        PacketContainer packet = new PacketContainer(PacketType.Login.Client.START, loginStart);

        WrappedGameProfile profile = packet.getGameProfiles().read(0);
        assertEquals("LegacyUser", profile.getName());
        assertNull(profile.getUUID());
    }

    @Test
    void loginStartProfileWritesThroughToLiveWrapper() {
        UUID initialUuid = UUID.randomUUID();
        WrapperLoginClientLoginStart loginStart = ObjectAllocator.allocate(WrapperLoginClientLoginStart.class);
        loginStart.setUsername("InitialUser");
        loginStart.setPlayerUUID(initialUuid);
        PacketContainer packet = new PacketContainer(PacketType.Login.Client.START, loginStart);

        UUID writtenUuid = UUID.randomUUID();
        packet.getGameProfiles().write(0, new WrappedGameProfile(writtenUuid, "WrittenUser"));

        assertEquals("WrittenUser", loginStart.getUsername());
        assertEquals(writtenUuid, loginStart.getPlayerUUID().orElse(null));
        WrappedGameProfile writtenProfile = packet.getGameProfiles().read(0);
        assertEquals("WrittenUser", writtenProfile.getName());
        assertEquals(writtenUuid, writtenProfile.getUUID());

        UUID currentUuid = UUID.randomUUID();
        loginStart.setUsername("CurrentUser");
        loginStart.setPlayerUUID(currentUuid);
        WrappedGameProfile currentProfile = packet.getGameProfiles().read(0);
        assertEquals("CurrentUser", currentProfile.getName());
        assertEquals(currentUuid, currentProfile.getUUID());

        UUID typedUuid = UUID.randomUUID();
        UserProfile typedProfile = new UserProfile(typedUuid, "TypedUser");
        StructureModifier<UserProfile> rawProfileView = packet.getGameProfiles().withType(UserProfile.class);
        assertEquals(1, rawProfileView.size());
        rawProfileView.write(0, typedProfile);
        assertEquals("TypedUser", loginStart.getUsername());
        assertEquals(typedUuid, loginStart.getPlayerUUID().orElse(null));
        assertEquals("TypedUser", rawProfileView.read(0).getName());
        assertEquals(typedUuid, rawProfileView.read(0).getUUID());
        assertEquals("TypedUser", packet.getGameProfiles().read(0).getName());
        assertEquals(typedUuid, packet.getGameProfiles().read(0).getUUID());

        UUID convertedUuid = UUID.randomUUID();
        StructureModifier<WrappedGameProfile> convertedProfileView = packet.getGameProfiles()
                .withType(WrappedGameProfile.class, WrappedGameProfile.getConverter());
        convertedProfileView.write(0, new WrappedGameProfile(convertedUuid, "ConvertedUser"));
        assertEquals("ConvertedUser", loginStart.getUsername());
        assertEquals(convertedUuid, loginStart.getPlayerUUID().orElse(null));
        assertEquals("ConvertedUser", convertedProfileView.read(0).getName());
        assertEquals("ConvertedUser", packet.getGameProfiles().read(0).getName());

        StructureModifier<WrappedGameProfile> profileView = packet.getGameProfiles();
        UUID targetedUuid = UUID.randomUUID();
        profileView.withTarget(profileView.getTarget())
                .write(0, new WrappedGameProfile(targetedUuid, "TargetedUser"));
        assertEquals("TargetedUser", loginStart.getUsername());
        assertEquals(targetedUuid, loginStart.getPlayerUUID().orElse(null));
        assertEquals("TargetedUser", profileView.read(0).getName());
        assertEquals(targetedUuid, packet.getGameProfiles().read(0).getUUID());

        WrapperLoginClientLoginStart redirectedLogin = ObjectAllocator.allocate(WrapperLoginClientLoginStart.class);
        UUID redirectedUuid = UUID.randomUUID();
        StructureModifier<WrappedGameProfile> redirectedProfileView = profileView.withTarget(redirectedLogin);
        redirectedProfileView.write(0, new WrappedGameProfile(redirectedUuid, "RedirectedUser"));
        assertEquals("RedirectedUser", redirectedLogin.getUsername());
        assertEquals(redirectedUuid, redirectedLogin.getPlayerUUID().orElse(null));
        assertEquals("RedirectedUser", redirectedProfileView.read(0).getName());
        assertEquals("RedirectedUser", new PacketContainer(PacketType.Login.Client.START, redirectedLogin)
                .getGameProfiles().read(0).getName());

        assertEquals(0, profileView.withType(String.class).size());
        assertEquals(0, profileView.withTarget(new Object()).size());

        WrapperLoginClientLoginStart secondLogin = ObjectAllocator.allocate(WrapperLoginClientLoginStart.class);
        UUID secondLoginInitialUuid = UUID.randomUUID();
        secondLogin.setUsername("SecondInitialUser");
        secondLogin.setPlayerUUID(secondLoginInitialUuid);
        PacketContainer secondLoginPacket = new PacketContainer(PacketType.Login.Client.START, secondLogin);
        StructureModifier<WrappedGameProfile> secondProfileAccessor = secondLoginPacket.getGameProfiles();
        StructureModifier<WrappedGameProfile> secondTargetProfileView = profileView
                .withTarget(secondLoginPacket.getGameProfiles().getTarget());
        UUID secondTargetUuid = UUID.randomUUID();
        secondTargetProfileView.writeSafely(0, new WrappedGameProfile(secondTargetUuid, "SecondTargetUser"));
        assertEquals(1, secondTargetProfileView.getFields().size());
        assertEquals(1, profileView.withType(Object.class).getFields().size());
        assertEquals("TargetedUser", profileView.read(0).getName());
        assertEquals("SecondTargetUser", secondLogin.getUsername());
        assertEquals(secondTargetUuid, secondLogin.getPlayerUUID().orElse(null));
        assertEquals("SecondTargetUser", secondTargetProfileView.getValues().get(0).getName());
        assertEquals("SecondTargetUser", secondTargetProfileView.readSafely(0).getName());
        assertEquals("SecondTargetUser", secondProfileAccessor.read(0).getName());

        StructureModifier<Object> objectProfileView = profileView.withType(Object.class);
        assertEquals(1, objectProfileView.size());
        assertEquals(Object.class, objectProfileView.getFieldType());
        Object currentRawProfile = objectProfileView.read(0);
        assertInstanceOf(UserProfile.class, currentRawProfile);
        assertEquals("TargetedUser", ((UserProfile) currentRawProfile).getName());

        UUID objectViewUuid = UUID.randomUUID();
        objectProfileView.write(0, new UserProfile(objectViewUuid, "ObjectViewUser"));
        assertEquals("ObjectViewUser", loginStart.getUsername());
        assertEquals(objectViewUuid, loginStart.getPlayerUUID().orElse(null));
        assertEquals("ObjectViewUser", ((UserProfile) objectProfileView.read(0)).getName());
        assertEquals(objectViewUuid, packet.getGameProfiles().read(0).getUUID());

        StructureModifier<Object> objectConvertedProfileView = profileView
                .withType(Object.class, RAW_OBJECT_CONVERTER);
        UUID convertedObjectUuid = UUID.randomUUID();
        objectConvertedProfileView.write(0, new UserProfile(convertedObjectUuid, "ObjectConverterUser"));
        assertEquals("ObjectConverterUser", loginStart.getUsername());
        assertEquals(convertedObjectUuid, loginStart.getPlayerUUID().orElse(null));
        assertInstanceOf(UserProfile.class, objectConvertedProfileView.read(0));

        StructureModifier<WrappedGameProfile> varargsProfileView = profileView
                .withParamType(Object.class, WrappedGameProfile.getConverter());
        StructureModifier<WrappedGameProfile> exactProfileSelectorView = profileView
                .withParamType(MinecraftReflection.getGameProfileClass(), WrappedGameProfile.getConverter());
        assertEquals(1, exactProfileSelectorView.size());
        assertEquals(UserProfile.class, exactProfileSelectorView.getFieldType());
        assertEquals(1, exactProfileSelectorView.getFields().size());
        assertEquals(UserProfile.class, exactProfileSelectorView.getField(0).getType());
        UUID exactSelectorUuid = UUID.randomUUID();
        exactProfileSelectorView.writeSafely(0,
                new WrappedGameProfile(exactSelectorUuid, "ExactSelectorUser"));
        assertEquals("ExactSelectorUser", loginStart.getUsername());
        assertEquals(exactSelectorUuid, loginStart.getPlayerUUID().orElse(null));
        assertEquals("ExactSelectorUser", exactProfileSelectorView.getValues().get(0).getName());
        assertEquals("ExactSelectorUser", exactProfileSelectorView.readSafely(0).getName());
        assertEquals("ExactSelectorUser", exactProfileSelectorView.optionRead(0).orElseThrow().getName());
        assertTrue(exactProfileSelectorView.optionRead(1).isEmpty());

        UUID varargsUuid = UUID.randomUUID();
        varargsProfileView.write(0, new WrappedGameProfile(varargsUuid, "VarargsUser"));
        assertEquals("VarargsUser", loginStart.getUsername());
        assertEquals(varargsUuid, loginStart.getPlayerUUID().orElse(null));
        assertEquals("VarargsUser", varargsProfileView.read(0).getName());

        StructureModifier<WrappedGameProfile> indexedProfileView = profileView
                .withParamType(UserProfile.class, WrappedGameProfile.getConverter(), 0);
        UUID indexedUuid = UUID.randomUUID();
        indexedProfileView.write(0, new WrappedGameProfile(indexedUuid, "IndexedUser"));
        assertEquals("IndexedUser", loginStart.getUsername());
        assertEquals(indexedUuid, loginStart.getPlayerUUID().orElse(null));

        assertEquals(0, profileView.withParamType(String.class, WrappedGameProfile.getConverter()).size());
        assertEquals(0, profileView.withParamType(WrappedGameProfile.class,
                WrappedGameProfile.getConverter()).size());
        assertEquals(0, profileView.withParamType(UserProfile.class,
                WrappedGameProfile.getConverter(), String.class).size());
    }

    @Test
    void chunkDataExposesProtocolLibChunkCoordinatesAsIntegers() {
        Column column = new Column(123, -456, true, new BaseChunk[0], null);
        WrapperPlayServerChunkData chunkData = ObjectAllocator.allocate(WrapperPlayServerChunkData.class);
        new StructureModifier<Column>(chunkData, Column.class).write(0, column);
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.MAP_CHUNK, chunkData);

        assertEquals(2, packet.getIntegers().size());
        assertEquals(123, packet.getIntegers().read(0));
        assertEquals(-456, packet.getIntegers().read(1));

        packet.getIntegers().write(0, -789).write(1, 987);
        assertEquals(-789, chunkData.getColumn().getX());
        assertEquals(987, chunkData.getColumn().getZ());
        assertEquals(-789, packet.getIntegers().read(0));
        assertEquals(987, packet.getIntegers().read(1));
    }

    @Test
    void unloadChunkExposesPackedLongReadWrite() {
        WrapperPlayServerUnloadChunk unloadChunk = ObjectAllocator.allocate(WrapperPlayServerUnloadChunk.class);
        unloadChunk.setChunkX(-12345);
        unloadChunk.setChunkZ(6789);
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.UNLOAD_CHUNK, unloadChunk);

        assertEquals(1, packet.getLongs().size());
        assertEquals(PacketWrapper.getChunkKey(-12345, 6789), packet.getLongs().read(0));
        assertEquals(new ChunkCoordIntPair(-12345, 6789), packet.getChunkCoordIntPairs().read(0));

        long updatedKey = PacketWrapper.getChunkKey(13579, -24680);
        packet.getLongs().write(0, updatedKey);
        assertEquals(13579, unloadChunk.getChunkX());
        assertEquals(-24680, unloadChunk.getChunkZ());
        assertEquals(updatedKey, packet.getLongs().read(0));
        assertEquals(new ChunkCoordIntPair(13579, -24680), packet.getChunkCoordIntPairs().read(0));

        unloadChunk.setChunkX(-31415);
        unloadChunk.setChunkZ(9265);
        assertEquals(PacketWrapper.getChunkKey(-31415, 9265), packet.getLongs().read(0));

        StructureModifier<Long> longView = packet.getLongs();
        long typedKey = PacketWrapper.getChunkKey(54321, -12345);
        StructureModifier<Long> typedLongView = longView.withType(long.class);
        assertEquals(1, typedLongView.size());
        typedLongView.write(0, typedKey);
        assertEquals(54321, unloadChunk.getChunkX());
        assertEquals(-12345, unloadChunk.getChunkZ());
        assertEquals(typedKey, typedLongView.read(0));
        assertEquals(typedKey, packet.getLongs().read(0));

        StructureModifier<Object> objectLongView = longView.withType(Object.class);
        assertEquals(1, objectLongView.size());
        assertEquals(1, objectLongView.getFields().size());
        assertEquals(Object.class, objectLongView.getFieldType());
        assertInstanceOf(Long.class, objectLongView.read(0));
        long objectKey = PacketWrapper.getChunkKey(111, -222);
        objectLongView.write(0, objectKey);
        assertEquals(111, unloadChunk.getChunkX());
        assertEquals(-222, unloadChunk.getChunkZ());
        assertEquals(objectKey, objectLongView.read(0));
        assertEquals(objectKey, packet.getLongs().read(0));

        StructureModifier<Object> objectConvertedLongView = longView
                .withType(Object.class, RAW_OBJECT_CONVERTER);
        long convertedObjectKey = PacketWrapper.getChunkKey(-333, 444);
        objectConvertedLongView.write(0, convertedObjectKey);
        assertEquals(-333, unloadChunk.getChunkX());
        assertEquals(444, unloadChunk.getChunkZ());
        assertEquals(convertedObjectKey, objectConvertedLongView.read(0));

        long convertedPairKey = PacketWrapper.getChunkKey(-2222, 3333);
        StructureModifier<ChunkCoordIntPair> convertedPairView = longView
                .withType(ChunkCoordIntPair.class, ChunkCoordIntPair.getConverter());
        convertedPairView.write(0, new ChunkCoordIntPair(-2222, 3333));
        assertEquals(convertedPairKey, packet.getLongs().read(0));
        assertEquals(new ChunkCoordIntPair(-2222, 3333), convertedPairView.read(0));
        assertEquals(-2222, unloadChunk.getChunkX());
        assertEquals(3333, unloadChunk.getChunkZ());

        long selfTargetKey = PacketWrapper.getChunkKey(404, -505);
        StructureModifier<Long> selfTargetView = longView.withTarget(longView.getTarget());
        selfTargetView.write(0, selfTargetKey);
        assertEquals(404, unloadChunk.getChunkX());
        assertEquals(-505, unloadChunk.getChunkZ());
        assertEquals(selfTargetKey, selfTargetView.read(0));
        assertEquals(selfTargetKey, packet.getLongs().read(0));

        WrapperPlayServerUnloadChunk redirectedUnload = ObjectAllocator.allocate(WrapperPlayServerUnloadChunk.class);
        long redirectedKey = PacketWrapper.getChunkKey(-606, 707);
        StructureModifier<Long> redirectedLongView = longView.withTarget(redirectedUnload);
        redirectedLongView.write(0, redirectedKey);
        assertEquals(-606, redirectedUnload.getChunkX());
        assertEquals(707, redirectedUnload.getChunkZ());
        assertEquals(redirectedKey, redirectedLongView.read(0));
        assertEquals(redirectedKey, new PacketContainer(PacketType.Play.Server.UNLOAD_CHUNK, redirectedUnload)
                .getLongs().read(0));

        assertEquals(0, longView.withType(String.class).size());
        assertEquals(0, longView.withTarget(new Object()).size());

        WrapperPlayServerUnloadChunk secondUnload = ObjectAllocator.allocate(WrapperPlayServerUnloadChunk.class);
        secondUnload.setChunkX(808);
        secondUnload.setChunkZ(-909);
        PacketContainer secondUnloadPacket = new PacketContainer(PacketType.Play.Server.UNLOAD_CHUNK, secondUnload);
        StructureModifier<Long> secondTargetLongView = longView
                .withTarget(secondUnloadPacket.getLongs().getTarget());
        long secondTargetKey = PacketWrapper.getChunkKey(-1010, 1111);
        secondTargetLongView.writeSafely(0, secondTargetKey);
        assertEquals(1, secondTargetLongView.getFields().size());
        assertEquals(404, unloadChunk.getChunkX());
        assertEquals(-505, unloadChunk.getChunkZ());
        assertEquals(-1010, secondUnload.getChunkX());
        assertEquals(1111, secondUnload.getChunkZ());
        assertEquals(secondTargetKey, secondTargetLongView.getValues().get(0));
        assertEquals(secondTargetKey, secondTargetLongView.readSafely(0));
        assertEquals(secondTargetKey, secondUnloadPacket.getLongs().read(0));
    }

    @Test
    void unloadChunkExposesWritableChunkCoordinatePair() {
        WrapperPlayServerUnloadChunk unloadChunk = ObjectAllocator.allocate(WrapperPlayServerUnloadChunk.class);
        unloadChunk.setChunkX(12);
        unloadChunk.setChunkZ(-34);
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.UNLOAD_CHUNK, unloadChunk);

        assertEquals(1, packet.getChunkCoordIntPairs().size());
        assertEquals(new ChunkCoordIntPair(12, -34), packet.getChunkCoordIntPairs().read(0));

        packet.getChunkCoordIntPairs().write(0, new ChunkCoordIntPair(-56, 78));
        assertEquals(-56, unloadChunk.getChunkX());
        assertEquals(78, unloadChunk.getChunkZ());
        assertEquals(PacketWrapper.getChunkKey(-56, 78), packet.getLongs().read(0));

        StructureModifier<ChunkCoordIntPair> pairView = packet.getChunkCoordIntPairs();
        long rawKey = PacketWrapper.getChunkKey(90, -91);
        StructureModifier<Long> rawLongView = pairView.withType(long.class);
        assertEquals(1, rawLongView.size());
        rawLongView.write(0, rawKey);
        assertEquals(90, unloadChunk.getChunkX());
        assertEquals(-91, unloadChunk.getChunkZ());
        assertEquals(rawKey, rawLongView.read(0));
        assertEquals(new ChunkCoordIntPair(90, -91), packet.getChunkCoordIntPairs().read(0));

        StructureModifier<ChunkCoordIntPair> convertedPairView = pairView
                .withType(ChunkCoordIntPair.class, ChunkCoordIntPair.getConverter());
        convertedPairView.write(0, new ChunkCoordIntPair(-92, 93));
        assertEquals(-92, unloadChunk.getChunkX());
        assertEquals(93, unloadChunk.getChunkZ());
        assertEquals(new ChunkCoordIntPair(-92, 93), convertedPairView.read(0));
        assertEquals(new ChunkCoordIntPair(-92, 93), packet.getChunkCoordIntPairs().read(0));

        StructureModifier<Object> objectPairView = pairView.withType(Object.class);
        assertEquals(1, objectPairView.size());
        assertEquals(1, objectPairView.getFields().size());
        assertEquals(Object.class, objectPairView.getFieldType());
        assertInstanceOf(Long.class, objectPairView.read(0));
        long objectPairKey = PacketWrapper.getChunkKey(98, -99);
        objectPairView.write(0, objectPairKey);
        assertEquals(98, unloadChunk.getChunkX());
        assertEquals(-99, unloadChunk.getChunkZ());
        assertEquals(objectPairKey, objectPairView.read(0));
        assertEquals(new ChunkCoordIntPair(98, -99), packet.getChunkCoordIntPairs().read(0));

        StructureModifier<ChunkCoordIntPair> objectVarargsPairView = pairView
                .withParamType(Object.class, ChunkCoordIntPair.getConverter());
        assertEquals(1, objectVarargsPairView.size());
        objectVarargsPairView.write(0, new ChunkCoordIntPair(-98, 99));
        assertEquals(-98, unloadChunk.getChunkX());
        assertEquals(99, unloadChunk.getChunkZ());
        StructureModifier<ChunkCoordIntPair> varargsPairView = pairView
                .withParamType(ChunkCoordIntPair.class, ChunkCoordIntPair.getConverter());
        assertEquals(1, varargsPairView.size());
        assertEquals(long.class, varargsPairView.getFieldType());
        assertEquals(1, varargsPairView.getFields().size());
        assertEquals(long.class, varargsPairView.getField(0).getType());
        assertEquals(new ChunkCoordIntPair(-98, 99), varargsPairView.getValues().get(0));
        assertEquals(new ChunkCoordIntPair(-98, 99), varargsPairView.readSafely(0));
        assertEquals(new ChunkCoordIntPair(-98, 99), varargsPairView.optionRead(0).orElseThrow());
        assertTrue(varargsPairView.optionRead(1).isEmpty());
        varargsPairView.writeSafely(0, new ChunkCoordIntPair(-97, 98));
        assertEquals(-97, unloadChunk.getChunkX());
        assertEquals(98, unloadChunk.getChunkZ());
        varargsPairView.write(0, new ChunkCoordIntPair(-100, 101));
        assertEquals(-100, unloadChunk.getChunkX());
        assertEquals(101, unloadChunk.getChunkZ());
        assertEquals(new ChunkCoordIntPair(-100, 101), varargsPairView.read(0));
        assertEquals(0, pairView.withParamType(String.class, ChunkCoordIntPair.getConverter()).size());
        assertEquals(0, pairView.withParamType(ChunkCoordIntPair.class,
                ChunkCoordIntPair.getConverter(), String.class).size());

        StructureModifier<Long> directLongSelector = packet.getLongs()
                .withParamType(long.class, null);
        assertEquals(1, directLongSelector.size());
        assertEquals(PacketWrapper.getChunkKey(-100, 101), directLongSelector.read(0));
        assertEquals(0, packet.getLongs().withParamType(Object.class, null).size());

        // The local int-index overload has no pinned counterpart and preserves the local
        // base behavior, which selects from the converter and ignores the type/index arguments.
        StructureModifier<ChunkCoordIntPair> indexedPairView = pairView
                .withParamType(String.class, ChunkCoordIntPair.getConverter(), 0);
        assertEquals(1, indexedPairView.size());
        indexedPairView.write(0, new ChunkCoordIntPair(102, -103));
        assertEquals(102, unloadChunk.getChunkX());
        assertEquals(-103, unloadChunk.getChunkZ());

        StructureModifier<ChunkCoordIntPair> selfTargetPairView = pairView.withTarget(pairView.getTarget());
        selfTargetPairView.write(0, new ChunkCoordIntPair(94, -95));
        assertEquals(94, unloadChunk.getChunkX());
        assertEquals(-95, unloadChunk.getChunkZ());
        assertEquals(new ChunkCoordIntPair(94, -95), selfTargetPairView.read(0));
        assertEquals(new ChunkCoordIntPair(94, -95), packet.getChunkCoordIntPairs().read(0));

        WrapperPlayServerUnloadChunk redirectedUnload = ObjectAllocator.allocate(WrapperPlayServerUnloadChunk.class);
        StructureModifier<ChunkCoordIntPair> redirectedPairView = pairView.withTarget(redirectedUnload);
        redirectedPairView.write(0, new ChunkCoordIntPair(-96, 97));
        assertEquals(-96, redirectedUnload.getChunkX());
        assertEquals(97, redirectedUnload.getChunkZ());
        assertEquals(new ChunkCoordIntPair(-96, 97), redirectedPairView.read(0));
        assertEquals(new ChunkCoordIntPair(-96, 97),
                new PacketContainer(PacketType.Play.Server.UNLOAD_CHUNK, redirectedUnload)
                        .getChunkCoordIntPairs().read(0));

        assertEquals(0, pairView.withType(String.class).size());
        assertEquals(0, pairView.withTarget(new Object()).size());

        WrapperPlayServerUnloadChunk secondUnload = ObjectAllocator.allocate(WrapperPlayServerUnloadChunk.class);
        secondUnload.setChunkX(1212);
        secondUnload.setChunkZ(-1313);
        PacketContainer secondUnloadPacket = new PacketContainer(PacketType.Play.Server.UNLOAD_CHUNK, secondUnload);
        StructureModifier<ChunkCoordIntPair> secondTargetPairView = pairView
                .withTarget(secondUnloadPacket.getChunkCoordIntPairs().getTarget());
        ChunkCoordIntPair secondTargetPair = new ChunkCoordIntPair(-1414, 1515);
        secondTargetPairView.writeSafely(0, secondTargetPair);
        assertEquals(1, secondTargetPairView.getFields().size());
        assertEquals(94, unloadChunk.getChunkX());
        assertEquals(-95, unloadChunk.getChunkZ());
        assertEquals(-1414, secondUnload.getChunkX());
        assertEquals(1515, secondUnload.getChunkZ());
        assertEquals(secondTargetPair, secondTargetPairView.getValues().get(0));
        assertEquals(secondTargetPair, secondTargetPairView.readSafely(0));
        assertEquals(secondTargetPair, secondUnloadPacket.getChunkCoordIntPairs().read(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoArgumentWithTypeWithoutConverterValidatesProtocolSelectors() {
        WrapperPlayServerUnloadChunk longWrapper = ObjectAllocator.allocate(WrapperPlayServerUnloadChunk.class);
        longWrapper.setChunkX(3);
        longWrapper.setChunkZ(-4);
        PacketContainer longPacket = new PacketContainer(PacketType.Play.Server.UNLOAD_CHUNK, longWrapper);

        StructureModifier<Long> rawLongView = longPacket.getLongs().withType(long.class, null);
        assertEquals(1, rawLongView.size());
        assertEquals(long.class, rawLongView.getFieldType());
        assertEquals(1, rawLongView.getFields().size());
        assertEquals(long.class, rawLongView.getField(0).getType());
        assertEquals(PacketWrapper.getChunkKey(3, -4), rawLongView.getValues().get(0));
        assertEquals(PacketWrapper.getChunkKey(3, -4), rawLongView.readSafely(0));
        assertEquals(PacketWrapper.getChunkKey(3, -4), rawLongView.optionRead(0).orElseThrow());
        long rawLongKey = PacketWrapper.getChunkKey(31, -41);
        rawLongView.writeSafely(0, rawLongKey);
        assertEquals(31, longWrapper.getChunkX());
        assertEquals(-41, longWrapper.getChunkZ());
        assertEquals(rawLongKey, longPacket.getLongs().read(0));
        assertEquals(0, longPacket.getLongs().withType(String.class, null).size());
        assertEquals(0, longPacket.getLongs().withType(null, null).size());
        assertEquals(0, longPacket.getLongs().withType(Object.class, null).size());

        // Keep the local one-argument wildcard distinct from ProtocolLib selector filtering.
        StructureModifier<Object> localLongWildcard = longPacket.getLongs().withType(Object.class);
        assertEquals(1, localLongWildcard.size());
        assertEquals(Object.class, localLongWildcard.getFieldType());
        long wildcardKey = PacketWrapper.getChunkKey(-32, 42);
        localLongWildcard.writeSafely(0, wildcardKey);
        assertEquals(-32, longWrapper.getChunkX());
        assertEquals(42, longWrapper.getChunkZ());

        WrapperPlayServerUnloadChunk pairWrapper = ObjectAllocator.allocate(WrapperPlayServerUnloadChunk.class);
        pairWrapper.setChunkX(5);
        pairWrapper.setChunkZ(6);
        PacketContainer pairPacket = new PacketContainer(PacketType.Play.Server.UNLOAD_CHUNK, pairWrapper);
        StructureModifier<?> pairSelectedView = pairPacket.getChunkCoordIntPairs()
                .withType(ChunkCoordIntPair.class, null);
        StructureModifier<Object> rawPairView = (StructureModifier<Object>) pairSelectedView;
        assertEquals(1, rawPairView.size());
        assertEquals(long.class, rawPairView.getFieldType());
        assertEquals(1, rawPairView.getFields().size());
        assertEquals(long.class, rawPairView.getField(0).getType());
        assertEquals(PacketWrapper.getChunkKey(5, 6), rawPairView.getValues().get(0));
        assertEquals(PacketWrapper.getChunkKey(5, 6), rawPairView.readSafely(0));
        assertEquals(PacketWrapper.getChunkKey(5, 6), rawPairView.optionRead(0).orElseThrow());
        long pairSelectedKey = PacketWrapper.getChunkKey(51, -61);
        rawPairView.writeSafely(0, pairSelectedKey);
        assertEquals(51, pairWrapper.getChunkX());
        assertEquals(-61, pairWrapper.getChunkZ());

        StructureModifier<Object> rawPairObjectView = pairPacket.getChunkCoordIntPairs()
                .withType(Object.class, null);
        assertEquals(1, rawPairObjectView.size());
        assertEquals(long.class, rawPairObjectView.getFieldType());
        long pairObjectKey = PacketWrapper.getChunkKey(-52, 62);
        rawPairObjectView.writeSafely(0, pairObjectKey);
        assertEquals(-52, pairWrapper.getChunkX());
        assertEquals(62, pairWrapper.getChunkZ());
        assertEquals(0, pairPacket.getChunkCoordIntPairs().withType(String.class, null).size());
        assertEquals(0, pairPacket.getChunkCoordIntPairs().withType(null, null).size());

        UUID initialProfileUuid = UUID.randomUUID();
        WrapperLoginClientLoginStart profileWrapper = ObjectAllocator.allocate(WrapperLoginClientLoginStart.class);
        profileWrapper.setUsername("RawProfileInitial");
        profileWrapper.setPlayerUUID(initialProfileUuid);
        PacketContainer profilePacket = new PacketContainer(PacketType.Login.Client.START, profileWrapper);
        StructureModifier<?> profileSelectedView = profilePacket.getGameProfiles()
                .withType(MinecraftReflection.getGameProfileClass(), null);
        StructureModifier<Object> rawProfileView = (StructureModifier<Object>) profileSelectedView;
        assertEquals(1, rawProfileView.size());
        assertEquals(UserProfile.class, rawProfileView.getFieldType());
        assertEquals(1, rawProfileView.getFields().size());
        assertEquals(UserProfile.class, rawProfileView.getField(0).getType());
        assertEquals("RawProfileInitial", ((UserProfile) rawProfileView.getValues().get(0)).getName());
        assertEquals("RawProfileInitial", ((UserProfile) rawProfileView.readSafely(0)).getName());
        assertEquals("RawProfileInitial", ((UserProfile) rawProfileView.optionRead(0).orElseThrow()).getName());
        UUID rawProfileUuid = UUID.randomUUID();
        rawProfileView.writeSafely(0, new UserProfile(rawProfileUuid, "RawProfileWritten"));
        assertEquals("RawProfileWritten", profileWrapper.getUsername());
        assertEquals(rawProfileUuid, profileWrapper.getPlayerUUID().orElse(null));

        StructureModifier<Object> rawProfileObjectView = profilePacket.getGameProfiles()
                .withType(Object.class, null);
        assertEquals(1, rawProfileObjectView.size());
        assertEquals(UserProfile.class, rawProfileObjectView.getFieldType());
        UUID rawProfileObjectUuid = UUID.randomUUID();
        rawProfileObjectView.writeSafely(0,
                new UserProfile(rawProfileObjectUuid, "RawProfileObjectWritten"));
        assertEquals("RawProfileObjectWritten", profileWrapper.getUsername());
        assertEquals(rawProfileObjectUuid, profileWrapper.getPlayerUUID().orElse(null));
        assertEquals(0, profilePacket.getGameProfiles().withType(String.class, null).size());
        assertEquals(0, profilePacket.getGameProfiles().withType(WrappedGameProfile.class, null).size());
        assertEquals(0, profilePacket.getGameProfiles().withType(null, null).size());
    }

    @Test
    void metadataEnumsAndNbtUsePacketEventsTypes() {
        assumeTrue(PacketEvents.getAPI() != null, "PacketEvents API is supplied by the server integration test");
        WrappedDataWatcher watcher = new WrappedDataWatcher();
        watcher.setDirection(0, EnumWrappers.Direction.NORTH, true);
        assertEquals(EnumWrappers.Direction.NORTH, watcher.getDirection(0));

        NbtCompound original = NbtFactory.ofCompound("");
        original.put("name", "Cyoria");
        original.put("level", 42);
        watcher.setNBTCompound(1, original, true);
        assertTrue(watcher.getObject(1) instanceof com.github.retrooper.packetevents.protocol.nbt.NBTCompound);
        NbtCompound roundTrip = watcher.getNBTCompound(1);
        assertNotNull(roundTrip);
        assertEquals("Cyoria", roundTrip.getString("name"));
        assertEquals(42, roundTrip.getInteger("level"));
    }
}
