package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Forge port of the Fabric {@code ClientInfoPayload}.
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:client_info}, single {@code STRING_UTF8} with the JSON document),
 * so the Diawars Paper plugin ({@code ClientInfoListener}) accepts both loaders
 * interchangeably. The JSON schema is documented in {@link ClientInfoCollector}.</p>
 */
public record ClientInfoPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClientInfoPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "client_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientInfoPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ClientInfoPayload::json,
                    ClientInfoPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
