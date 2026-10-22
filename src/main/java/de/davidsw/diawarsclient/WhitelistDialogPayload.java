package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Server-to-client request to open the mod whitelist dialog (see
 * {@link WhitelistScreen}).
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:whitelist_dialog}, one {@code STRING_UTF8} JSON document:
 * {@code {"mods": ["AppleSkin", ...], "requestMax": 40}}) so the Diawars
 * Paper plugin opens the whitelist dialog on both loaders (see
 * {@link WhitelistScreen}). {@code mods} lists the allowed mods by display
 * name (server-sorted); {@code requestMax} bounds the optional request field
 * server-side. Submit flows back through {@code diawars:whitelist_request},
 * see {@link WhitelistRequestPayload}.</p>
 */
public record WhitelistDialogPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WhitelistDialogPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "whitelist_dialog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistDialogPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    WhitelistDialogPayload::json,
                    WhitelistDialogPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
