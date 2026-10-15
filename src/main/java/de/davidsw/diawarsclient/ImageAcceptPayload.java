package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Server answer to an {@link ImageOfferPayload} (see {@link ImageUploader}).
 *
 * <p>Wire format: {@code ClientboundCustomPayloadPacket} with id
 * {@code diawars:image_accept} and a single {@code STRING_UTF8} JSON document:
 * {@code {"file": "...", "accepted": true, "total": N}} or
 * {@code {"file": "...", "accepted": false, "error": "<message>"}}.</p>
 */
public record ImageAcceptPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImageAcceptPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "image_accept"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImageAcceptPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ImageAcceptPayload::json,
                    ImageAcceptPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
