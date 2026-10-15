package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Asks the Diawars Paper plugin for a full-resolution image.
 *
 * <p>Wire format: {@code ServerboundCustomPayloadPacket} with id
 * {@code diawars:image_req} and a single {@code STRING_UTF8} JSON document:
 * {@code {"mapId": 123}} or
 * {@code {"owner": "<uuid>", "file": "<name>", "knownHash": "<sha256>"}}.
 * The server answers with {@link ImageDataPayload} chunks (or an
 * {@code unchanged}/{@code error} single).</p>
 */
public record ImageRequestPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImageRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "image_req"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImageRequestPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ImageRequestPayload::json,
                    ImageRequestPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
