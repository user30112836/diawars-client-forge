package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Forge port of the Fabric {@code ReportSubmitPayload}.
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:report_submit}, one {@code STRING_UTF8} JSON document:
 * {@code {"player": "Name"?, "reason": "..."?, "shot": true, "handImage":
 * false}}) so the Diawars Paper plugin receives dialog submissions the same
 * way on both loaders. Player and hand image resolve server-side (the mod
 * only states what the reporter chose); the server answers in chat and —
 * when {@code shot} was chosen — requests the screenshot capture through
 * {@code diawars:screenshot} right away (see {@link ReportScreenshot}).</p>
 */
public record ReportSubmitPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ReportSubmitPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "report_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReportSubmitPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ReportSubmitPayload::json,
                    ReportSubmitPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
