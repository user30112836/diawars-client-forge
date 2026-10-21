package de.davidsw.diawarsclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Reads local image files and, while an upload runs, draws a small
 * bottom-right HUD progress indicator driven by {@link ImageUploader}.
 *
 * <p>Registered by the loader-specific entry class on both mods; draw state
 * is derived every frame, so the overlay disappears on its own once the
 * upload has fully been sent (or after a short error display).
 */
public final class ImageUploadOverlay {
    /** Distance of the indicator box from the bottom-right corner. */
    private static final int MARGIN = 8;
    /** Inner padding of the indicator box. */
    private static final int PADDING = 4;
    /** How long an upload error stays in the overlay (milliseconds). */
    private static final long ERROR_VISIBLE_MILLIS = 6_000;
    /** Maximum label width before it is truncated with an ellipsis. */
    private static final int MAX_LABEL_WIDTH = 160;

    private ImageUploadOverlay() {
    }

    /**
     * One HUD frame: a compact box in the bottom-right corner showing the
     * split-name-check phase, the sending progress in percent and as a thin
     * bar, or the last upload error for a few seconds. Renders nothing while
     * no upload is running and no recent error exists.
     */
    public static void extract(GuiGraphicsExtractor gg) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String error = ImageUploader.overlayErrorSince(now - ERROR_VISIBLE_MILLIS);
        String label;
        int accent;
        double progress;
        if (error != null) {
            label = "Upload fehlgeschlagen: " + error;
            accent = 0xFFFF5555;
            progress = -1.0;
        } else {
            progress = ImageUploader.uploadProgress();
            if (progress < 0.0) {
                return;
            }
            accent = 0xFF62E05F;
            int segments = ImageUploader.batchSegmentCount();
            if (ImageUploader.isCheckingNames()) {
                label = "Aufteilung wird geprüft …";
            } else if (segments > 0) {
                label = "Bild-Upload Teil " + ImageUploader.batchCurrentSegment() + "/" + segments
                        + ": " + percent(progress) + "%";
            } else {
                label = "Bild-Upload: " + percent(progress) + "%";
            }
        }

        Font font = minecraft.font;
        label = truncate(label, font);

        int lineHeight = font.lineHeight;
        int barHeight = 2;
        int gap = 3;
        int boxW = Math.max(MAX_LABEL_WIDTH / 3, font.width(label)) + PADDING * 2;
        int boxH = PADDING + lineHeight + gap + barHeight + PADDING;
        int boxX = gg.guiWidth() - MARGIN - boxW;
        int boxY1 = gg.guiHeight() - MARGIN;
        int boxY0 = boxY1 - boxH;

        gg.fill(boxX, boxY0, boxX + boxW, boxY1, 0xC0101010);
        gg.text(font, label, boxX + PADDING, boxY0 + PADDING, 0xFFFFFFFF);

        int barY1 = boxY1 - PADDING;
        int barY0 = barY1 - barHeight;
        int barX0 = boxX + PADDING;
        int barX1 = boxX + boxW - PADDING;
        gg.fill(barX0, barY0, barX1, barY1, 0xFF2E2E2E);
        if (progress >= 0.0) {
            int filled = (int) Math.round((barX1 - barX0) * progress);
            if (filled > 0) {
                gg.fill(barX0, barY0, barX0 + filled, barY1, accent);
            }
        } else {
            gg.fill(barX0, barY0, barX1, barY1, accent);
        }
    }

    /** Formats a progress fraction as 0..100. */
    private static int percent(double progress) {
        return Math.max(0, Math.min(100, (int) Math.round(progress * 100.0)));
    }

    /** Shortens the label with an ellipsis until it fits the overlay box. */
    private static String truncate(String label, Font font) {
        if (font.width(label) <= MAX_LABEL_WIDTH) {
            return label;
        }
        int usable = Math.max(10, MAX_LABEL_WIDTH - font.width("…"));
        return font.plainSubstrByWidth(label, usable) + "…";
    }
}
