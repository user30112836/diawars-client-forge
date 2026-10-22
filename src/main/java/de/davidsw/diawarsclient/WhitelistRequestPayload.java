package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Client-to-server submit of a "please add this mod to the whitelist"
 * request from the whitelist dialog (see {@link WhitelistScreen}).
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:whitelist_request}, one {@code STRING_UTF8} JSON document:
 * {@code {"name": "Display Name"}}) so the Diawars Paper plugin receives
 * whitelist requests the same way on both loaders. Only the display name is
 * sent; the server validates it against the whitelist and open requests,
 * stores the request and answers in chat.</p>
 */
public record WhitelistRequestPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WhitelistRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "whitelist_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistRequestPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    WhitelistRequestPayload::json,
                    WhitelistRequestPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
