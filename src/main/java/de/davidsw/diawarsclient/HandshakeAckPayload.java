package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Clientbound handshake answer confirming that the current server runs the
 * Diawars plugin (see {@link ServerHandshake} and {@link HandshakePayload}).
 *
 * <p>Wire format: {@code ClientboundCustomPayloadPacket} with id
 * {@code diawars:handshake_ack} and an empty body (unit codec). Sent only
 * as a response to {@code diawars:handshake}, so mod-less servers never
 * see it and older mod versions keep their old behavior.</p>
 */
public record HandshakeAckPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HandshakeAckPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "handshake_ack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HandshakeAckPayload> CODEC =
            StreamCodec.unit(new HandshakeAckPayload());

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
