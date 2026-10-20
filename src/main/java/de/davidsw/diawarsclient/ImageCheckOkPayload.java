package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Forge port of the Fabric {@code ImageCheckOkPayload}.
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:image_check_ok}, one {@code STRING_UTF8} JSON document:
 * {@code {"ok": true}} or {@code {"ok": false, "error": "<red>...</red>"}}).
 * On ok=false the whole split upload is cancelled and the error shows in the
 * upload dialog (MiniMessage tags stripped, like accept errors).</p>
 */
public record ImageCheckOkPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImageCheckOkPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "image_check_ok"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImageCheckOkPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ImageCheckOkPayload::json,
                    ImageCheckOkPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
