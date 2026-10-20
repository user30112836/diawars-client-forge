package de.davidsw.diawarsclient;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
/**
 * Forge port of the Fabric {@code DiawarsClient}.
 *
 * <p>Sends the same {@code diawars:client_info} payload as the Fabric mod right
 * after the client joins a server, so the Diawars Paper plugin can verify Forge
 * players exactly like Fabric players (see {@code ClientInfoListener} /
 * {@code ClientInfoStore} on the plugin).</p>
 *
 * <p>Wire format: {@code ServerboundCustomPayloadPacket} with id
 * {@code diawars:client_info} and a single {@code STRING_UTF8} JSON document.
 * Registered via Forge's {@code PayloadChannel} ({@code PLAY}/{@code SERVERBOUND})
 * with an {@code optional()} handshake so vanilla/Paper servers (which know
 * nothing about Forge channels) still accept the connection.</p>
 */
@Mod(DiawarsClientForge.MOD_ID)
public final class DiawarsClientForge {
    public static final String MOD_ID = "diawars_client";

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Payload channel for the client_info packet.
     * Instance name ({@code diawars_client:main}) is only used for the Forge
     * handshake; the actual packet id is {@code diawars:client_info} via
     * {@link ClientInfoPayload#TYPE}, which is what the Paper plugin listens for.
     */
    private static final Channel<CustomPacketPayload> CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(MOD_ID, "main"))
            .optional()
            .networkProtocolVersion(1)
            .payloadChannel()
            .protocol(NetworkProtocol.PLAY)
            .flow(PacketFlow.SERVERBOUND)
            .add(
                    ClientInfoPayload.TYPE,
                    ClientInfoPayload.CODEC,
                    (payload, context) -> {
                        // Client never receives this payload; only sends it.
                    }
            )
            .add(
                    ImagePayload.TYPE,
                    ImagePayload.CODEC,
                    (payload, context) -> {
                        // Client never receives image chunks; only sends them.
                    }
            )
            .add(
                    ImageRequestPayload.TYPE,
                    ImageRequestPayload.CODEC,
                    (payload, context) -> {
                        // Client never receives image requests; only sends them.
                    }
            )
            .add(
                    ImageOfferPayload.TYPE,
                    ImageOfferPayload.CODEC,
                    (payload, context) -> {
                        // Client never receives image offers; only sends them.
                    }
            )
            .build();

    /**
     * Clientbound channel for full-resolution image downloads: the server
     * answers {@code diawars:image_req} requests with chunked
     * {@code diawars:image_data} documents, which {@link ImageDownloader}
     * reassembles and swaps into map rendering. Upload offers
     * ({@code diawars:image_offer}) are answered with
     * {@code diawars:image_accept} on the same channel.
     */
    private static final Channel<CustomPacketPayload> DATA_CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(MOD_ID, "image_data"))
            .optional()
            .networkProtocolVersion(1)
            .payloadChannel()
            .protocol(NetworkProtocol.PLAY)
            .flow(PacketFlow.CLIENTBOUND)
            .add(
                    ImageDataPayload.TYPE,
                    ImageDataPayload.CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            ImageDownloader.handleDataJson(Minecraft.getInstance(), payload.json()))
            )
            .add(
                    ImageAcceptPayload.TYPE,
                    ImageAcceptPayload.CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            ImageUploader.handleAccept(payload.json()))
            )
            .build();

    /**
     * Clientbound channel for the {@code diawars:image_open} signal: the
     * server sends it when the player clicks the upload button in the images
     * menu, and the mod opens the upload UI on the next client tick.
     */
    private static final Channel<CustomPacketPayload> OPEN_CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(MOD_ID, "image_open"))
            .optional()
            .networkProtocolVersion(1)
            .payloadChannel()
            .protocol(NetworkProtocol.PLAY)
            .flow(PacketFlow.CLIENTBOUND)
            .add(
                    ImageOpenPayload.TYPE,
                    ImageOpenPayload.CODEC,
                    (payload, context) -> pendingOpenUploadScreen = true
            )
            .build();

    /**
     * Clientbound channel for the {@code diawars:text_input} signal: the
     * server sends it when the player clicks a menu entry that needs typed
     * input (bug report, vault invite/remove, event creation), and the mod
     * opens the text input dialog on the next client tick (see
     * {@link TextInputScreen}).
     */
    private static final Channel<CustomPacketPayload> TEXT_INPUT_CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(MOD_ID, "text_input"))
            .optional()
            .networkProtocolVersion(1)
            .payloadChannel()
            .protocol(NetworkProtocol.PLAY)
            .flow(PacketFlow.CLIENTBOUND)
            .add(
                    TextInputPayload.TYPE,
                    TextInputPayload.CODEC,
                    (payload, context) -> pendingTextInputJson = payload.json()
            )
            .build();

    /**
     * Clientbound channel for the {@code diawars:screenshot} signal: the
     * server sends it when the reporter opts in via {@code /report shot}, and
     * the mod captures the current frame and uploads it as a report
     * attachment on the next client tick (see {@link ReportScreenshot}).
     */
    private static final Channel<CustomPacketPayload> SCREENSHOT_CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(MOD_ID, "screenshot"))
            .optional()
            .networkProtocolVersion(1)
            .payloadChannel()
            .protocol(NetworkProtocol.PLAY)
            .flow(PacketFlow.CLIENTBOUND)
            .add(
                    ScreenshotPayload.TYPE,
                    ScreenshotPayload.CODEC,
                    (payload, context) -> pendingScreenshotJson = payload.json()
            )
            .build();

    public DiawarsClientForge(FMLJavaModLoadingContext context) {
        LOGGER.info("Diawars Client (Forge) initialized.");
        ImageDownloader.init(json -> {
            if (!canSendImages()) {
                return false;
            }
            sendImageRequest(json);
            return true;
        });
        ImageUploader.initOfferSender(json -> {
            if (!canSendImages()) {
                return false;
            }
            sendImageOffer(json);
            return true;
        });
        // Report screenshots upload without a screen; failures go to chat.
        ImageUploader.initErrorFeedback(message -> Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().showDebugChat(Component.literal(message))));
    }

    public static void sendClientInfo(net.minecraft.network.Connection connection) {
        try {
            String json = ClientInfoCollector.collectAsJson();
            CHANNEL.send(new ClientInfoPayload(json), connection);
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Failed to send diawars client_info packet.", e);
        }
    }

    /**
     * Set when the server asks us to open the upload UI (via the
     * {@code diawars:image_open} signal, e.g. from the images menu or
     * {@code /image upload}). The screen opens on the next client tick
     * because screen changes must not happen inside network handling.
     */
    public static volatile boolean pendingOpenUploadScreen;

    /**
     * JSON of the latest {@code diawars:text_input} dialog request; parsed and
     * opened on the next client tick, see {@link TextInputScreen}.
     */
    public static volatile String pendingTextInputJson;

    /**
     * JSON of the latest {@code diawars:screenshot} request; handled on the
     * next client tick because the capture must run on the render thread, see
     * {@link ReportScreenshot}.
     */
    public static volatile String pendingScreenshotJson;

    /** Sends one prepared {@code diawars:image} chunk over the current connection. */
    public static void sendImage(String json) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getConnection() == null) {
                return;
            }
            CHANNEL.send(new ImagePayload(json), minecraft.getConnection().getConnection());
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Failed to send diawars image packet.", e);
        }
    }

    /** Sends one {@code diawars:image_req} document over the current connection. */
    public static void sendImageRequest(String json) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getConnection() == null) {
                return;
            }
            CHANNEL.send(new ImageRequestPayload(json), minecraft.getConnection().getConnection());
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Failed to send diawars image request packet.", e);
        }
    }

    /** Sends one {@code diawars:image_offer} document over the current connection. */
    public static void sendImageOffer(String json) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getConnection() == null) {
                return;
            }
            CHANNEL.send(new ImageOfferPayload(json), minecraft.getConnection().getConnection());
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Failed to send diawars image offer packet.", e);
        }
    }

    /** Whether image chunks can currently be sent (connected to a server). */
    public static boolean canSendImages() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getConnection() == null) {
                return false;
            }
            net.minecraft.network.Connection raw = minecraft.getConnection().getConnection();
            return raw != null && raw.isConnected();
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static class ClientEvents {
        @SubscribeEvent
        public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            if (event.getConnection() == null) {
                LOGGER.warn("No connection available, skipping diawars client_info packet.");
                return;
            }
            sendClientInfo(event.getConnection());
        }

        @SubscribeEvent
        public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            ImageUploader.clearQueue();
            ImageDownloader.clearSession(Minecraft.getInstance());
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
            if (pendingOpenUploadScreen) {
                pendingOpenUploadScreen = false;
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null) {
                    minecraft.setScreenAndShow(new ImageUploadScreen());
                }
            }
            String pendingTextInput = pendingTextInputJson;
            if (pendingTextInput != null) {
                pendingTextInputJson = null;
                TextInputScreen textInputScreen = TextInputScreen.parseOrNull(pendingTextInput);
                if (textInputScreen != null && Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().setScreenAndShow(textInputScreen);
                }
            }
            String pendingScreenshot = pendingScreenshotJson;
            if (pendingScreenshot != null) {
                pendingScreenshotJson = null;
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null && minecraft.getConnection() != null) {
                    ReportScreenshot.handle(minecraft, pendingScreenshot);
                }
            }
            ImageDownloader.tick(Minecraft.getInstance());
            ImageUploader.tickOffer();
            if (ImageUploader.hasQueued() && canSendImages()) {
                int sent = 0;
                while (sent < 8 && ImageUploader.hasQueued()) {
                    sendImage(ImageUploader.poll());
                    sent++;
                }
            }
        }
    }
}
