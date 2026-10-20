package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Forge port of the Fabric {@code ScreenshotPayload}.
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:screenshot}, one {@code STRING_UTF8} JSON document:
 * {@code {"report": "17", "file": "report-17"}}) so the Diawars Paper plugin
 * requests report screenshots the same way on both loaders. The mod captures
 * the current frame and uploads it through the regular offer/chunk pipeline
 * tagged with the report id (see {@link ReportScreenshot}).</p>
 */
public record ScreenshotPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ScreenshotPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "screenshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenshotPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ScreenshotPayload::json,
                    ScreenshotPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
