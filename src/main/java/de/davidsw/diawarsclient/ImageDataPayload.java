package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Full-resolution image download from the Diawars Paper plugin
 * (see {@link ImageDownloader}).
 *
 * <p>Wire format: {@code ClientboundCustomPayloadPacket} with id
 * {@code diawars:image_data} and a single {@code STRING_UTF8} JSON document
 * per chunk. Data chunks look like
 * {@code {"mapId": 123, "owner": "<uuid>", "file": "<name>", "hash": "<sha256>",
 * "part": 0, "total": N, "data": "<base64>"}}; control singles are
 * {@code {"mapId": ..., "unchanged": true}} (client cache is fresh),
 * {@code {"mapId": ..., "error": "unknown"|"too-big"}},
 * {@code {"deleted": true, "owner": "<uuid>", "file": "<name>"}} (admin
 * deleted the image or banned its owner: drop the cached copy) or
 * {@code {"refresh": true}} (ban lifted: forget negative marks and
 * re-request images).</p>
 */
public record ImageDataPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ImageDataPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "image_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImageDataPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ImageDataPayload::json,
                    ImageDataPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
