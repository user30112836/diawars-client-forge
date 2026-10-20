package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Forge port of the Fabric {@code ImageCheckPayload}.
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:image_check}, one {@code STRING_UTF8} JSON document:
 * {@code {"names": ["bd12(1)", "bd12(2)" ...]}}) so the Diawars Paper plugin
 * pre-checks split upload names on both loaders (see {@link ImageUploader}).
 * The server answers on {@code diawars:image_check_ok}, see
 * {@link ImageCheckOkPayload}; the whole split upload is cancelled when any
 * name is taken, invalid or the image limit has no headroom.</p>
 */
public record ImageCheckPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImageCheckPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "image_check"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImageCheckPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ImageCheckPayload::json,
                    ImageCheckPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
