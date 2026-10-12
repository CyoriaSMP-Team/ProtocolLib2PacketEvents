package com.comphenix.protocol.reflect;

import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FuzzyReflectionSecretKeyTest {
    public static class ModernConnection {
        public void setEncryptionKey(SecretKey key) { }
    }
    public static class AmbiguousConnection {
        public void setEncryptionKey(SecretKey key) { }
        public void anotherMethod(SecretKey key) { }
    }
    public static class OtherMethod {
        public void send(Object obj) { }
    }

    @Test
    void remappedModernMinecraftKeySetterIsDiscoverableByOldName() {
        var result = FuzzyReflection.fromClass(ModernConnection.class)
                .getMethodByParameters("a", SecretKey.class);
        assertEquals("setEncryptionKey", result.getName());
    }

    @Test
    void ambiguousCryptoMethodsDoNotGetPickedArbitrarily() {
        assertThrows(IllegalArgumentException.class,
                () -> FuzzyReflection.fromClass(AmbiguousConnection.class)
                        .getMethodByParameters("a", SecretKey.class));
    }

    @Test
    void normalMethodNameLookupStaysStrict() {
        assertThrows(IllegalArgumentException.class,
                () -> FuzzyReflection.fromClass(OtherMethod.class)
                        .getMethodByParameters("a", Object.class));
    }
}
