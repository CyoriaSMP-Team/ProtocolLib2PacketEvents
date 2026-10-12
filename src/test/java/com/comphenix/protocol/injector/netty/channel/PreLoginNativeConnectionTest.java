package com.comphenix.protocol.injector.netty.channel;

import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreLoginNativeConnectionTest {
    @Test
    void findsLateNativePacketHandlerForFastLoginCipher() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyChannelInjector injector = new NettyChannelInjector(null, null, channel, null, null, null);
        assertFalse(injector.hasNativeConnection(), "cannot fabricate a native NMS Connection");
        net.minecraft.network.Connection nativeConn = new net.minecraft.network.Connection();
        channel.pipeline().addLast("packet_handler", nativeConn);
        injector.refreshNativeConnection();
        assertTrue(injector.hasNativeConnection());
        Field field = NettyChannelInjector.class.getDeclaredField("networkManager");
        field.setAccessible(true);
        assertSame(nativeConn, field.get(injector), "FastLoginPlus must receive actual NMS packet handler");
        channel.finishAndReleaseAll();
    }

    @Test
    void neverConfusesOtherChannelHandlerWithNmsConnection() {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyChannelInjector injector = new NettyChannelInjector(null, null, channel, null, null, null);
        channel.pipeline().addLast("packet_handler", new io.netty.channel.ChannelInboundHandlerAdapter());
        assertFalse(injector.hasNativeConnection());
        channel.finishAndReleaseAll();
    }
}
