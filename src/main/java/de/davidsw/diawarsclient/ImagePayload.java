package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Forge port of the Fabric {@code ImagePayload}.
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:image}, one {@code STRING_UTF8} JSON chunk document per
 * packet: {@code {"file": "...", "part": 0, "total": N, "data": "<base64>"}}),
 * so the Diawars Paper plugin accepts both loaders interchangeably.
 * The client resizes the image to 128x128 before sending (see
 * {@link ImageUploader}); the server reassembles the parts.</p>
 */
public record ImagePayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImagePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "image"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImagePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ImagePayload::json,
                    ImagePayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
