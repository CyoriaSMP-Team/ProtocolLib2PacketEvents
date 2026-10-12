package com.comphenix.protocol.reflect;

import com.comphenix.protocol.injector.netty.channel.NettyChannelInjector;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NettyChannelFieldLookupTest {
    private static final class OldFieldOrder {
        private final Object networkManager = new Object();
        private final Channel channel;
        OldFieldOrder(Channel channel) { this.channel = channel; }
    }

    @Test
    void picksExactChannelInsteadOfObjectWithNativeConnection() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertSame(channel, FuzzyReflection.getFieldValue(new OldFieldOrder(channel), Channel.class, true));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void realNettyInjectorReturnsChannelWhenNativeConnectionNotReady() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            NettyChannelInjector injector = new NettyChannelInjector(null, null, channel, null, null, null);
            assertSame(channel, FuzzyReflection.getFieldValue(injector, Channel.class, true));
        } finally { channel.finishAndReleaseAll(); }
    }
}
