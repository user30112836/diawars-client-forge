package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Forge port of the Fabric {@code ImageOpenPayload}: server-to-client signal
 * asking the mod to open the image upload UI (see {@link ImageUploadScreen}).
 *
 * <p>Wire format and semantics are identical to the Fabric mod
 * ({@code diawars:image_open}, empty body). The server sends it when the
 * player clicks the upload button in the images menu; the client opens the
 * screen on the next client tick.</p>
 */
public record ImageOpenPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImageOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "image_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImageOpenPayload> CODEC =
            StreamCodec.unit(new ImageOpenPayload());

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
