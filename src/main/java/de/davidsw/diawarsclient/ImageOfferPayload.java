package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Announces an image upload so server and client agree on the transfer size
 * up front (see {@link ImageUploader}).
 *
 * <p>Wire format: {@code ServerboundCustomPayloadPacket} with id
 * {@code diawars:image_offer} and a single {@code STRING_UTF8} JSON document:
 * {@code {"file": "...", "bytes": 12345, "total": N, "hash": "<sha256>"}}.
 * The server answers with {@link ImageAcceptPayload}; chunks
 * ({@link ImagePayload}) are only sent after an accept.</p>
 */
public record ImageOfferPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImageOfferPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "image_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImageOfferPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ImageOfferPayload::json,
                    ImageOfferPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
