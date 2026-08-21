package com.whidte.trulybestfriends.network;

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * Fabric replacement for Forge's {@code SimpleChannel}: a single bidirectional
 * channel that multiplexes all packet types by a VarInt id, mirroring the
 * original {@code trulybestfriends.CHANNEL} contract.
 */
public final class TrulyNetwork {
    public static final String PROTOCOL_VERSION = "5";
    public static final ResourceLocation ID =
            new ResourceLocation("trulybestfriends", "main");

    private static final Map<Integer, PacketTypeEntry<?>> TYPES = new HashMap<>();
    private static final Map<Integer, PacketTypeEntry<?>> SERVER_BOUND = new HashMap<>();
    private static final Map<Integer, PacketTypeEntry<?>> CLIENT_BOUND = new HashMap<>();
    private static final Map<Class<?>, Integer> IDS_BY_CLASS = new HashMap<>();

    /** Serverbound wire envelope. */
    public static final class TrulyServerboundPayload implements FabricPacket {
        private static final PacketType<TrulyServerboundPayload> TYPE =
                PacketType.create(ID, TrulyServerboundPayload::read);
        private final int id;
        private final byte[] data;

        private TrulyServerboundPayload(int id, byte[] data) {
            this.id = id;
            this.data = data;
        }

        public static TrulyServerboundPayload read(FriendlyByteBuf buf) {
            return new TrulyServerboundPayload(buf.readVarInt(), buf.readByteArray());
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(id);
            buf.writeByteArray(data);
        }

        @Override
        public PacketType<?> getType() {
            return TYPE;
        }

        /** Wraps the payload in a readable buffer with its own reader index. */
        public FriendlyByteBuf buffer() {
            return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        }
    }

    /** Clientbound wire envelope. */
    public static final class TrulyClientboundPayload implements FabricPacket {
        private static final PacketType<TrulyClientboundPayload> TYPE =
                PacketType.create(ID, TrulyClientboundPayload::read);
        private final int id;
        private final byte[] data;

        private TrulyClientboundPayload(int id, byte[] data) {
            this.id = id;
            this.data = data;
        }

        public static TrulyClientboundPayload read(FriendlyByteBuf buf) {
            return new TrulyClientboundPayload(buf.readVarInt(), buf.readByteArray());
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(id);
            buf.writeByteArray(data);
        }

        @Override
        public PacketType<?> getType() {
            return TYPE;
        }

        /** Wraps the payload in a readable buffer with its own reader index. */
        public FriendlyByteBuf buffer() {
            return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        }
    }

    public interface Encoder<T> {
        void encode(T packet, FriendlyByteBuf buf);
    }

    public interface Decoder<T> {
        T decode(FriendlyByteBuf buf);
    }

    public interface PacketHandler<T> {
        void handle(T packet, PacketContext ctx);
    }

    private static final class PacketTypeEntry<T> {
        private final Encoder<T> encoder;
        private final Decoder<T> decoder;
        private PacketHandler<T> serverHandler;
        private PacketHandler<T> clientHandler;

        private PacketTypeEntry(Encoder<T> encoder, Decoder<T> decoder) {
            this.encoder = encoder;
            this.decoder = decoder;
        }

        private T decode(FriendlyByteBuf buf) {
            return decoder.decode(buf);
        }

        private void handleServer(FriendlyByteBuf buf, ServerPlayer player) {
            T packet = decode(buf);
            if (serverHandler != null) {
                serverHandler.handle(packet, new PacketContext(player));
            }
        }

        private void handleClient(FriendlyByteBuf buf) {
            T packet = decode(buf);
            if (clientHandler != null) {
                clientHandler.handle(packet, new PacketContext(null));
            } else {
                LogUtils.getLogger().warn("Received trulybestfriends packet {} with no client handler", idOf(packet));
            }
        }
    }

    private TrulyNetwork() {}

    /** Registers a client-to-server packet type. Called during common setup. */
    public static synchronized <T> void registerServerBound(int id, Class<T> type,
                                                            Encoder<T> encoder, Decoder<T> decoder,
                                                            PacketHandler<T> handler) {
        PacketTypeEntry<T> entry = new PacketTypeEntry<>(encoder, decoder);
        entry.serverHandler = handler;
        TYPES.put(id, entry);
        SERVER_BOUND.put(id, entry);
        IDS_BY_CLASS.put(type, id);
    }

    /** Registers a server-to-client packet codec. Its client handler is registered later from the client initializer. */
    public static synchronized <T> void registerClientBound(int id, Class<T> type,
                                                            Encoder<T> encoder, Decoder<T> decoder) {
        PacketTypeEntry<T> entry = new PacketTypeEntry<>(encoder, decoder);
        TYPES.put(id, entry);
        CLIENT_BOUND.put(id, entry);
        IDS_BY_CLASS.put(type, id);
    }

    /** Registers the client-side handler for a server-to-client packet. Client-only. */
    public static synchronized <T> void registerClientHandler(int id, PacketHandler<T> handler) {
        @SuppressWarnings("unchecked")
        PacketTypeEntry<T> entry = (PacketTypeEntry<T>) CLIENT_BOUND.get(id);
        if (entry != null) entry.clientHandler = handler;
    }

    /** Registers the server receiver. Called from the common initializer. */
    public static void initServer() {
        ServerPlayNetworking.registerGlobalReceiver(
                TrulyServerboundPayload.TYPE, TrulyNetwork::receiveServer);
    }

    /** Registers the client receiver. Called from the client initializer. */
    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                TrulyClientboundPayload.TYPE, TrulyNetwork::receiveClient);
    }

    private static void receiveServer(TrulyServerboundPayload payload, ServerPlayer player,
                                      PacketSender responseSender) {
        FriendlyByteBuf buf = payload.buffer();
        int id = buf.readVarInt();
        PacketTypeEntry<?> type = SERVER_BOUND.get(id);
        if (type == null) {
            LogUtils.getLogger().warn("Received unknown trulybestfriends serverbound packet id {}", id);
            return;
        }
        type.handleServer(buf, player);
    }

    private static void receiveClient(TrulyClientboundPayload payload, LocalPlayer player,
                                      PacketSender responseSender) {
        FriendlyByteBuf buf = payload.buffer();
        int id = buf.readVarInt();
        PacketTypeEntry<?> type = CLIENT_BOUND.get(id);
        if (type == null) {
            LogUtils.getLogger().warn("Received unknown trulybestfriends clientbound packet id {}", id);
            return;
        }
        type.handleClient(buf);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int encodePayload(Object packet, FriendlyByteBuf buf) {
        Integer id = IDS_BY_CLASS.get(packet.getClass());
        if (id == null) {
            throw new IllegalArgumentException("Unregistered trulybestfriends packet type: "
                    + packet.getClass().getName());
        }
        buf.writeVarInt(id);
        PacketTypeEntry type = TYPES.get(id);
        type.encoder.encode(packet, buf);
        return id;
    }

    /** Client -> Server send. */
    public static void sendToServer(Object packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encodePayload(packet, buf);
            ClientPlayNetworking.send(new TrulyServerboundPayload(payloadIdOf(packet), toBytes(buf)));
        } finally {
            buf.release();
        }
    }

    /** Server -> Client send. */
    public static void sendToClient(ServerPlayer player, Object packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encodePayload(packet, buf);
            ServerPlayNetworking.send(player, new TrulyClientboundPayload(payloadIdOf(packet), toBytes(buf)));
        } finally {
            buf.release();
        }
    }

    private static int payloadIdOf(Object packet) {
        Integer id = IDS_BY_CLASS.get(packet.getClass());
        if (id == null) {
            throw new IllegalArgumentException("Unregistered trulybestfriends packet type: "
                    + packet.getClass().getName());
        }
        return id;
    }

    private static byte[] toBytes(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }

    private static int idOf(Object packet) {
        Integer id = IDS_BY_CLASS.get(packet.getClass());
        return id == null ? -1 : id;
    }
}
