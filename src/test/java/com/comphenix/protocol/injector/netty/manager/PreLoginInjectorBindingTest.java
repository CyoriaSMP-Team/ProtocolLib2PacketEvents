package com.comphenix.protocol.injector.netty.manager;

import com.comphenix.protocol.injector.netty.channel.NettyChannelInjector;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory;
import com.comphenix.protocol.reflect.ObjectAllocator;
import com.github.retrooper.packetevents.protocol.player.User;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreLoginInjectorBindingTest {
    @Test
    void activeChannelBindsBeforeNativeConnectionAndCanRefreshLater() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        User user = ObjectAllocator.allocate(User.class);
        Field field = User.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(user, channel);
        var player = TemporaryPlayerFactory.createTemporaryPlayer();
        try (NetworkManagerInjector manager = new NetworkManagerInjector(null, null, null)) {
            var early = manager.getTemporaryInjector(user, player);
            assertInstanceOf(NettyChannelInjector.class, early,
                    "valid channel must bind before native Connection exists");
            assertFalse(((NettyChannelInjector) early).hasNativeConnection());
            TemporaryPlayerFactory.setInjectorForPlayer(player, early);
            assertSame(early, TemporaryPlayerFactory.getInjectorFromPlayer(player));
            channel.pipeline().addLast("packet_handler", new net.minecraft.network.Connection());
            var late = manager.getTemporaryInjector(user, player);
            assertSame(early, late, "must not replace channel injector mid-login");
            assertTrue(((NettyChannelInjector) late).hasNativeConnection());
            assertSame(late, TemporaryPlayerFactory.getInjectorFromPlayer(player));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
