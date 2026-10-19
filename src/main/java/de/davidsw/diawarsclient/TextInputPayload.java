package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Forge port of the Fabric {@code TextInputPayload}.
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:text_input}, one {@code STRING_UTF8} JSON document:
 * {@code {"command": "bug report ", "title": "...", "label": "...",
 * "hint": "...", "maxLength": 244}}) so the Diawars Paper plugin opens the
 * text input dialog on both loaders (see {@link TextInputScreen}). On submit
 * the screen runs the command prefix plus the typed text through
 * {@code ClientPacketListener#sendCommand}, exactly like typing it in chat;
 * the server never sees a separate answer payload.</p>
 */
public record TextInputPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TextInputPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "text_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TextInputPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    TextInputPayload::json,
                    TextInputPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
