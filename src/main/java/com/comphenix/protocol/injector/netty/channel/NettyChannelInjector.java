package com.comphenix.protocol.injector.netty.channel;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.error.ErrorReporter;
import com.comphenix.protocol.events.ConnectionSide;
import com.comphenix.protocol.events.NetworkMarker;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketOutputHandler;
import com.comphenix.protocol.injector.ListenerManager;
import com.comphenix.protocol.injector.packet.PacketRegistry;
import com.comphenix.protocol.injector.netty.Injector;
import com.comphenix.protocol.injector.netty.WirePacket;
import com.comphenix.protocol.injector.temporary.TemporaryPlayer;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerAdapter;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.entity.Player;

import java.net.SocketAddress;

/** Netty-backed injector used for raw packet and hold/re-inject capabilities. */
public class NettyChannelInjector implements Injector {
    private static final String INBOUND = "p2p-inbound";
    private static final String ENCODER = "p2p-wire-encoder";
    private static final String OUTBOUND = "p2p-outbound";
    private volatile Player player;
    // The Netty channel is discovered before the native Minecraft Connection
    // handler may be in the pipeline. Refresh it as soon as the first LOGIN
    // packet arrives so FastLoginPlus can enable AES encryption on the real
    // NMS Connection. It must not be the raw Netty Channel or a dummy handle.
    private volatile Object networkManager;
    private final Channel channel;
    private final ListenerManager listenerManager;
    private final InjectionFactory factory;
    private final ErrorReporter reporter;
    private volatile PacketType.Protocol protocol;
    private volatile boolean inboundLengthPrefixed;
    private volatile boolean outboundLengthPrefixed;
    private volatile boolean injected;
    private volatile boolean closed;

    public NettyChannelInjector(Player player, Object networkManager, Channel channel,
                                ListenerManager listenerManager, InjectionFactory factory,
                                ErrorReporter reporter) {
        this.player = player;
        this.networkManager = networkManager;
        this.channel = channel;
        refreshNativeConnection();
        this.listenerManager = listenerManager;
        this.factory = factory;
        this.reporter = reporter;
        this.protocol = player == null ? PacketType.Protocol.HANDSHAKING : PacketType.Protocol.PLAY;
    }
    /**
     * Find the actual net.minecraft.network.Connection handler already installed
     * by the Minecraft server. No field/type guessing, no reflection into the
     * packet pipeline and no mutation of its handler order.
     */
    public void refreshNativeConnection() {
        if (networkManager != null || channel == null) return;
        io.netty.channel.ChannelHandler packetHandler = channel.pipeline().get("packet_handler");
        if (packetHandler == null) return;
        for (Class<?> clazz = packetHandler.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            if ("net.minecraft.network.Connection".equals(clazz.getName())) {
                networkManager = packetHandler;
                return;
            }
        }
    }

    public boolean hasNativeConnection() {
        refreshNativeConnection();
        return networkManager != null;
    }

    public SocketAddress getAddress() { return channel == null ? null : channel.remoteAddress(); }
    public int getProtocolVersion() {
        if (player == null || PacketEvents.getAPI() == null) return -1;
        var version = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
        return version == null ? -1 : version.getProtocolVersion();
    }
    public synchronized void inject() {
        if (closed || channel == null) throw new IllegalStateException("channel injector is closed or has no channel");
        if (injected) return;
        var pipeline = channel.pipeline();
        if (pipeline.get(INBOUND) == null) {
            String decoder = findDecoderAnchor(pipeline);
            inboundLengthPrefixed = decoder == null;
            if (decoder == null) pipeline.addFirst(INBOUND, new InboundPacketInterceptor(this));
            else pipeline.addBefore(decoder, INBOUND, new InboundPacketInterceptor(this));
        }
        outboundLengthPrefixed = findEncoderAnchor(pipeline) != null;
        if (pipeline.get(ENCODER) == null) pipeline.addLast(ENCODER, new WirePacketEncoder());
        if (pipeline.get(OUTBOUND) == null) {
            String anchor = findEncoderAnchor(pipeline);
            if (anchor == null) anchor = ENCODER;
            pipeline.addBefore(anchor, OUTBOUND, new OutboundPacketInterceptor(this));
        }
        if (player == null) ensureTemporaryPlayer();
        injected = true;
    }
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (channel != null) {
            if (channel.pipeline().get(INBOUND) != null) channel.pipeline().remove(INBOUND);
            if (channel.pipeline().get(ENCODER) != null) channel.pipeline().remove(ENCODER);
            if (channel.pipeline().get(OUTBOUND) != null) channel.pipeline().remove(OUTBOUND);
        }
    }
    public void sendClientboundPacket(Object packet, NetworkMarker marker, boolean filtered) {
        if (channel == null || closed) throw new IllegalStateException("channel is not available");
        channel.writeAndFlush(packet);
    }
    public void readServerboundPacket(Object packet) {
        if (channel == null || closed) throw new IllegalStateException("channel is not available");
        channel.pipeline().fireChannelRead(packet);
    }
    public void sendWirePacket(WirePacket packet) {
        if (packet == null) throw new IllegalArgumentException("packet cannot be null");
        if (!injected) inject();
        channel.writeAndFlush(packet);
    }
    public void disconnect(String message) { if (channel != null) channel.close(); closed = true; }
    public PacketType.Protocol getCurrentProtocol(PacketType.Sender sender) {
        return protocol;
    }
    public Player getPlayer() { return player; }
    public String getPlayerName() { return player == null ? null : player.getName(); }
    public java.util.UUID getPlayerUniqueId() { return player == null ? null : player.getUniqueId(); }
    public void setPlayer(Player player) {
        this.player = player;
        if (player != null && !(player instanceof TemporaryPlayer)) {
            this.protocol = PacketType.Protocol.PLAY;
        }
    }
    public boolean isConnected() { return channel != null && channel.isActive() && !closed; }
    public boolean isInjected() { return injected; }
    public boolean isClosed() { return closed; }
    void fireInbound(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof ByteBuf buffer) processRaw(context, buffer, false, null);
        else context.fireChannelRead(message);
    }

    void fireOutbound(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof ByteBuf buffer) processRaw(context, buffer, true, promise);
        else context.write(message, promise);
    }
    private void processRaw(ChannelHandlerContext context, ByteBuf buffer, boolean outbound,
                            ChannelPromise promise) throws Exception {
        Frame frame = Frame.read(buffer, outbound ? outboundLengthPrefixed : inboundLengthPrefixed);
        if (frame == null) {
            forward(context, buffer, outbound, promise);
            return;
        }
        PacketType type;
        try {
            type = PacketType.findCurrent(protocol,
                    outbound ? PacketType.Sender.SERVER : PacketType.Sender.CLIENT, frame.id);
        } catch (Throwable error) {
            report("Unable to resolve raw packet id " + frame.id + " in " + protocol, error);
            forward(context, buffer, outbound, promise);
            return;
        }

        // PacketEvents handles packets with a real wrapper. Passing those frames through here is
        // the duplicate-dispatch guard: only an unmodelled packet reaches this fallback.
        if (type == null || listenerManager == null || PacketRegistry.isSupported(type)
                || (outbound ? !listenerManager.hasOutboundListener(type)
                             : !listenerManager.hasInboundListener(type))) {
            updateProtocol(type, frame.body);
            forward(context, buffer, outbound, promise);
            return;
        }

        Player effectivePlayer = player == null ? ensureTemporaryPlayer() : player;
        PacketContainer packet = new PacketContainer(type, Unpooled.wrappedBuffer(frame.body));
        PacketEvent event = outbound
                ? PacketEvent.fromServer(this, packet,
                        new NetworkMarker(ConnectionSide.SERVER_SIDE, type), effectivePlayer)
                : PacketEvent.fromClient(this, packet,
                        new NetworkMarker(ConnectionSide.CLIENT_SIDE, type), effectivePlayer);
        boolean accepted = outbound
                ? listenerManager.dispatchOutboundPacket(event)
                : listenerManager.dispatchInboundPacket(event);
        if (!accepted || event.isCancelled()) {
            ReferenceCountUtil.release(buffer);
            return;
        }

        byte[] body = readCurrentBody(packet, frame.body);
        if (outbound) body = applyOutputHandlers(event, body);
        if (!java.util.Arrays.equals(frame.body, body)) {
            ByteBuf replacement = frame.rebuild(buffer, body);
            ReferenceCountUtil.release(buffer);
            forward(context, replacement, outbound, promise);
        } else {
            forward(context, buffer, outbound, promise);
        }
        updateProtocol(type, body);
    }

    private byte[] readCurrentBody(PacketContainer packet, byte[] fallback) {
        Object raw = packet.getRawBuffer();
        if (raw == null) return fallback.clone();
        try {
            return ByteBufHelper.copyBytes(raw);
        } catch (RuntimeException error) {
            report("Unable to read modified raw packet " + packet.getType(), error);
            return fallback.clone();
        }
    }

    private byte[] applyOutputHandlers(PacketEvent event, byte[] body) {
        byte[] result = body;
        for (PacketOutputHandler handler : event.getNetworkMarker().getOutputHandlers()) {
            result = handler.handle(event, result);
            if (result == null) {
                throw new IllegalStateException("PacketOutputHandler returned null for " + event.getPacketType());
            }
        }
        return result;
    }

    private static void forward(ChannelHandlerContext context, Object message, boolean outbound,
                                ChannelPromise promise) {
        if (outbound) context.write(message, promise);
        else context.fireChannelRead(message);
    }

    private Player ensureTemporaryPlayer() {
        Player current = player;
        if (current != null) return current;
        synchronized (this) {
            if (player == null) {
                Player temporary = TemporaryPlayerAdapter.createAnonymous();
                TemporaryPlayerFactory.setInjectorForPlayer(temporary, this);
                player = temporary;
            }
            return player;
        }
    }

    private void updateProtocol(PacketType type, byte[] body) {
        if (type == null) return;
        String name = type.name();
        if (protocol == PacketType.Protocol.HANDSHAKING && "SET_PROTOCOL".equals(name)) {
            int[] version = readVarInt(body, 0);
            if (version != null) {
                int offset = version[1];
                int[] hostLength = readVarInt(body, offset);
                if (hostLength != null) {
                    offset += hostLength[1] + hostLength[0];
                    if (offset + 2 <= body.length) offset += 2;
                    int[] nextState = readVarInt(body, offset);
                    if (nextState != null) {
                        protocol = nextState[0] == 1 ? PacketType.Protocol.STATUS
                                : nextState[0] == 2 ? PacketType.Protocol.LOGIN : protocol;
                    }
                }
            }
        } else if ("LOGIN_ACKNOWLEDGED".equals(name)) {
            protocol = PacketType.Protocol.CONFIGURATION;
        } else if ("CONFIGURATION_ACKNOWLEDGED".equals(name)) {
            protocol = PacketType.Protocol.PLAY;
        }
    }

    private static int[] readVarInt(byte[] bytes, int offset) {
        int result = 0;
        int shift = 0;
        for (int i = 0; i < 5 && offset + i < bytes.length; i++) {
            int value = bytes[offset + i] & 0xff;
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) return new int[]{result, i + 1};
            shift += 7;
        }
        return null;
    }

    private String findEncoderAnchor(io.netty.channel.ChannelPipeline pipeline) {
        for (String name : pipeline.names()) {
            if (!ENCODER.equals(name)
                    && name.toLowerCase(java.util.Locale.ROOT).contains("encoder")) return name;
        }
        return null;
    }

    private String findDecoderAnchor(io.netty.channel.ChannelPipeline pipeline) {
        for (String name : pipeline.names()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith("p2p-") && lower.contains("decoder")) return name;
        }
        return null;
    }

    private void report(String message, Throwable error) {
        if (reporter != null) reporter.reportWarning(this, message, error);
    }

    private static final class Frame {
        private final int id;
        private final byte[] body;
        private final int end;
        private final boolean lengthPrefixed;

        private Frame(int id, byte[] body, int end, boolean lengthPrefixed) {
            this.id = id;
            this.body = body;
            this.end = end;
            this.lengthPrefixed = lengthPrefixed;
        }

        private static Frame read(ByteBuf buffer, boolean lengthPrefixed) {
            int start = buffer.readerIndex();
            int[] length = lengthPrefixed ? readVarInt(buffer, start) : null;
            boolean framed = length != null && length[0] > 0
                    && start + length[1] + length[0] <= buffer.writerIndex()
                    && start + length[1] + length[0] == buffer.writerIndex();
            if (lengthPrefixed && !framed) return null;
            int frameStart = framed ? start + length[1] : start;
            int end = framed ? frameStart + length[0] : buffer.writerIndex();
            int[] packetId = readVarInt(buffer, frameStart);
            if (packetId == null || frameStart + packetId[1] > end) return null;
            int bodyStart = frameStart + packetId[1];
            byte[] body = new byte[end - bodyStart];
            buffer.getBytes(bodyStart, body);
            return new Frame(packetId[0], body, end, framed);
        }

        private static int[] readVarInt(ByteBuf buffer, int offset) {
            int result = 0;
            int shift = 0;
            for (int i = 0; i < 5 && offset + i < buffer.writerIndex(); i++) {
                int value = buffer.getUnsignedByte(offset + i);
                result |= (value & 0x7f) << shift;
                if ((value & 0x80) == 0) return new int[]{result, i + 1};
                shift += 7;
            }
            return null;
        }

        private ByteBuf rebuild(ByteBuf original, byte[] newBody) {
            ByteBuf replacement = Unpooled.buffer(newBody.length + 10 + original.readableBytes());
            if (lengthPrefixed) writeVarInt(replacement, varIntSize(id) + newBody.length);
            writeVarInt(replacement, id);
            replacement.writeBytes(newBody);
            if (end < original.writerIndex()) {
                replacement.writeBytes(original, end, original.writerIndex() - end);
            }
            return replacement;
        }

        private static int varIntSize(int value) {
            int size = 1;
            while ((value & ~0x7f) != 0) { value >>>= 7; size++; }
            return size;
        }

        private static void writeVarInt(ByteBuf output, int value) {
            while ((value & ~0x7f) != 0) {
                output.writeByte((value & 0x7f) | 0x80);
                value >>>= 7;
            }
            output.writeByte(value);
        }
    }

    Channel getChannel() { return channel; }
}
