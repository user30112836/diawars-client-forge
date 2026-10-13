package de.davidsw.diawarsclient;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.lwjgl.glfw.GLFW;
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
            .build();

    public DiawarsClientForge(FMLJavaModLoadingContext context) {
        LOGGER.info("Diawars Client (Forge) initialized.");
    }

    public static void sendClientInfo(net.minecraft.network.Connection connection) {
        try {
            String json = ClientInfoCollector.collectAsJson();
            CHANNEL.send(new ClientInfoPayload(json), connection);
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Failed to send diawars client_info packet.", e);
        }
    }

    /** Keybind opening the image upload screen (U by default, see controls menu). */
    public static KeyMapping openUploadScreenKey;

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
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            openUploadScreenKey = new KeyMapping(
                    "key.diawars_client.upload_image",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_U,
                    KeyMapping.Category.MISC
            );
            event.register(openUploadScreenKey);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null && openUploadScreenKey != null && openUploadScreenKey.consumeClick()) {
                minecraft.setScreenAndShow(new ImageUploadScreen());
            }
            if (ImageUploader.hasQueued() && canSendImages()) {
                int sent = 0;
                while (sent < 8 && ImageUploader.hasQueued()) {
                    sendImage(ImageUploader.poll());
                    sent++;
                }
            }
        }

        @SubscribeEvent
        public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(Commands.literal("diawarsimage").executes(context -> {
                Minecraft.getInstance().setScreenAndShow(new ImageUploadScreen());
                return 1;
            }));
        }
    }
}
