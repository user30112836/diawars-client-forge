package de.davidsw.diawarsclient;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Captures a screenshot for a report attachment and uploads it.
 *
 * <p>Triggered by the {@code diawars:screenshot} signal: after the reporter
 * submits the reason, the server asks the mod to capture what the player
 * currently sees. The capture uses the vanilla screenshot read-back
 * ({@code Screenshot.takeScreenshot}, safe with the framebuffers while a UI
 * is open), writes a temp PNG under {@code diawars-screenshots/} and uploads
 * it through the regular offer/chunk pipeline tagged with the report id —
 * the server stores it under the report instead of the uploader's gallery
 * (see {@link ImageUploader#prepareShot}). The temp PNG is deleted after
 * encoding; nothing screenshots-related is kept client-side.</p>
 */
public final class ReportScreenshot {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** Transient temp folder inside the game directory (never scanned for uploads). */
    private static final String TEMP_FOLDER = "diawars-screenshots";

    private ReportScreenshot() {
    }

    /**
     * Handles one {@code diawars:screenshot} document. Must run on the client
     * thread: the capture straight away reads the current render target, so
     * the screenshot shows exactly what the player sees at that moment.
     *
     * @param json {@code {"report": "17", "file": "report-17"}}
     */
    public static void handle(Minecraft minecraft, String json) {
        String report;
        String fileName;
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) {
                throw new IllegalArgumentException("JSON is null");
            }
            report = root.get("report").getAsString();
            fileName = root.get("file").getAsString();
        } catch (Exception e) {
            LOGGER.warn("Invalid diawars:screenshot document: {}", json, e);
            return;
        }
        if (minecraft.getConnection() == null) {
            LOGGER.warn("Ignoring diawars:screenshot: not connected to a server.");
            return;
        }

        File folder = new File(minecraft.gameDirectory, TEMP_FOLDER);
        File temp = new File(folder, fileName + ".png");
        // The callback runs on the render thread when the GPU read-back has
        // finished; file write and upload then run off-thread so the frame
        // never stalls.
        net.minecraft.client.Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image ->
                CompletableFuture.runAsync(() -> {
                    try {
                        folder.mkdirs();
                        image.writeToFile(temp);
                    } catch (Exception e) {
                        LOGGER.warn("Could not write the report screenshot.", e);
                        showError(minecraft, "Der Screenshot konnte nicht aufgenommen werden!");
                        return;
                    } finally {
                        image.close();
                    }
                    upload(minecraft, temp, fileName, report);
                }));
    }

    private static void upload(Minecraft minecraft, File temp, String fileName, String report) {
        try {
            ImageUploader.PreparedUpload upload = ImageUploader.prepareShot(temp, fileName, report);
            if (ImageUploader.requestUpload(upload)) {
                // From here on the server provides the feedback in chat
                // (reject reasons or the attachment confirmation).
                return;
            }
            String error = ImageUploader.consumeOfferError();
            showError(minecraft, error != null ? error : "Der Screenshot-Upload ist fehlgeschlagen!");
        } catch (Exception e) {
            LOGGER.warn("Report screenshot upload failed.", e);
            showError(minecraft, "Der Screenshot-Upload ist fehlgeschlagen!");
        } finally {
            // The chunk data is in memory after prepareShot; the temp file
            // is done regardless of the upload outcome.
            temp.delete();
        }
    }

    private static void showError(Minecraft minecraft, String message) {
        try {
            minecraft.execute(() -> minecraft.showDebugChat(Component.literal(message)));
        } catch (Exception e) {
            LOGGER.warn("Could not show the report screenshot error.", e);
        }
    }
}
