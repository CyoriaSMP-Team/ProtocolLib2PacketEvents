package com.comphenix.protocol.internal;

import com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketEventsBackendTemporaryUserTest {
    @Test
    void fullyJoinedPlayerDoesNotUsePreLoginTransport() {
        // The early guard is null-safe and the real-player path is untouched.
        assertNull(PacketEventsBackend.temporaryTransport(null));
    }

    @Test
    void anonymousTemporaryPlayerWithoutUserFailsClosed() {
        Player facade = TemporaryPlayerFactory.createTemporaryPlayer();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PacketEventsBackend.temporaryTransport(facade));
        assertTrue(error.getMessage().contains("no PacketEvents login channel"));
    }

    @Test
    void temporaryTransportRejectsUnmappedFacadeWithoutTryingCraftPlayerCast() {
        // This regression guard tests the pre-login failure mode without a
        // running PacketEvents API (which User's constructor requires).
        Player facade = TemporaryPlayerFactory.createTemporaryPlayer();
        assertThrows(IllegalStateException.class,
                () -> PacketEventsBackend.temporaryTransport(facade));
    }
}
