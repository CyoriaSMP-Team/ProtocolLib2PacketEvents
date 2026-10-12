package com.comphenix.protocol.events;

import com.comphenix.protocol.reflect.ObjectAllocator;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class PacketWrapperAllocationTest {
    @Test
    void constructorFreeWrapperMustRecoverDistinctBufferLockAndRegistry() throws Exception {
        // Tests the actual constructor-free type used by FastLoginPlus, without
        // requiring PacketEvents to have started a Minecraft server.
        PacketWrapper<?> template = ObjectAllocator.allocate(PacketWrapper.class);
        Field lockField = PacketWrapper.class.getDeclaredField("bufferLock");
        lockField.setAccessible(true);
        Object expectedLock = new Object();
        lockField.set(template, expectedLock);
        Field registryField = PacketWrapper.class.getDeclaredField("registries");
        registryField.setAccessible(true);
        Object originalRegistry = registryField.get(template);

        WrapperLoginServerEncryptionRequest first =
                ObjectAllocator.allocate(WrapperLoginServerEncryptionRequest.class);
        WrapperLoginServerEncryptionRequest second =
                ObjectAllocator.allocate(WrapperLoginServerEncryptionRequest.class);
        assertNull(first.bufferLock);
        assertNull(second.bufferLock);
        PacketContainer.copyRuntimeStateFromConstructor(first, template);
        assertSame(expectedLock, first.bufferLock);
        assertSame(originalRegistry, registryField.get(first));
        lockField.set(template, new Object());
        PacketContainer.copyRuntimeStateFromConstructor(second, template);
        assertNotNull(second.bufferLock);
        assertNotSame(first.bufferLock, second.bufferLock);
    }

    @Test
    void missingConstructorLockIsRejectedInsteadOfSendingInvalidWrapper() throws Exception {
        PacketWrapper<?> template = ObjectAllocator.allocate(PacketWrapper.class);
        PacketWrapper<?> wrapper = ObjectAllocator.allocate(WrapperLoginServerEncryptionRequest.class);
        assertThrows(IllegalStateException.class,
                () -> PacketContainer.copyRuntimeStateFromConstructor(wrapper, template));
    }
}
