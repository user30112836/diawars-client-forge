package de.davidsw.diawarsclient;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * Forge port of the Fabric {@code ReportDialogPayload}.
 *
 * <p>Channel and encoding are intentionally identical to the Fabric mod
 * ({@code diawars:report_dialog}, one {@code STRING_UTF8} JSON document:
 * {@code {"handImage": true, "reasonMax": 240}}) so the Diawars Paper plugin
 * opens the report dialog on both loaders (see {@link ReportDialogScreen}).
 * {@code handImage} tells the dialog whether the server saw a Diawars image
 * map in the main hand at open time; {@code reasonMax} bounds the reason
 * text server-side. The submit flows back through
 * {@code diawars:report_submit}, see {@link ReportSubmitPayload}.</p>
 */
public record ReportDialogPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ReportDialogPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("diawars", "report_dialog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReportDialogPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    ReportDialogPayload::json,
                    ReportDialogPayload::new
            );

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
