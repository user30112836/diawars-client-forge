package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Serverbound handshake probe: asks the server whether the Diawars plugin is
 * installed (see {@link ServerHandshake} and {@link HandshakeAckPayload}).
 *
 * <p>Wire format: {@code ServerboundCustomPayloadPacket} with id
 * {@code diawars:handshake} and an empty body (unit codec). The plugin
 * answers with the clientbound {@code diawars:handshake_ack}.</p>
 */
public record HandshakePayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HandshakePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "handshake"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HandshakePayload> CODEC =
            StreamCodec.unit(new HandshakePayload());

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
