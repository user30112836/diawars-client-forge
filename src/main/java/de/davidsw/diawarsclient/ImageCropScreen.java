package de.davidsw.diawarsclient;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Second upload step: shows the image and lets the player pick the square
 * area to upload. Drag inside the preview to move the square, use the mouse
 * wheel over the preview to change its size. The selection may grow to the
 * whole square canvas ("Ganzes Bild"), so the entire image fits without
 * cutting anything off; pixels outside the image become transparent.
 * The "Aufteilen" toggle splits the selection into a rows×cols grid (free
 * shape, e.g. 2×3 or 5×1, via the "Zeilen"/"Spalten" steppers) — every cell
 * uploads as its own image named {@code base (k)}, one by one, after the
 * server pre-checks every name. Confirming crops the area and queues the
 * upload at full resolution (see {@link ImageUploader}).
 */
public class ImageCropScreen extends Screen {
    private static final Identifier PREVIEW_ID =
            Identifier.fromNamespaceAndPath("diawars_client", "crop_preview");
    private static final int MIN_SIZE = 8;

    private final File sourceFile;
    private final String uploadName;

    /** Split mode: the selection yields a rows×cols grid of images. */
    private boolean splitUploads;
    /** Grid shape, 1..{@link ImageUploader#MAX_GRID} per direction. */
    private int gridRows = 2;
    private int gridCols = 2;

    private DynamicTexture previewTexture;
    private int imageWidth;
    private int imageHeight;
    private int canvasSide;
    private String loadError;

    private int selX;
    private int selY;
    private int selSize;
    private boolean dragging;
    private double grabDX;
    private double grabDY;

    private Button confirmButton;
    private Button minusButton;
    private Button plusButton;
    private Button splitButton;
    private Button rowsButton;
    private Button colsButton;
    private StringWidget infoLine;
    private String lastInfo = "";

    public ImageCropScreen(File sourceFile, String uploadName) {
        super(Component.literal("Bild zuschneiden"));
        this.sourceFile = sourceFile;
        this.uploadName = uploadName;
    }

    @Override
    protected void init() {
        if (this.previewTexture == null && this.loadError == null) {
            this.loadPreview();
        }
        String savedInfo = this.lastInfo;
        this.clearWidgets();

        this.infoLine = new StringWidget(20, this.height - 88, this.width - 40, 12,
                Component.literal(savedInfo), this.font);
        this.addRenderableWidget(this.infoLine);
        this.addRenderableWidget(new StringWidget(20, this.height - 74, this.width - 40, 12,
                Component.literal("Ziehen: verschieben · Mausrad / − / +: Größe · Ganzes Bild: transparent auffüllen"), this.font));

        this.splitButton = Button.builder(Component.literal(splitToggleLabel()),
                button -> this.onSplitToggle()).bounds(20, this.height - 60, 130, 20).build();
        this.addRenderableWidget(this.splitButton);
        if (this.splitUploads) {
            this.addShapeButtons();
        }

        int smallWidth = 44;
        int buttonWidth = 110;
        int gap = 8;
        int rowWidth = smallWidth * 2 + buttonWidth * 3 + gap * 4;
        int buttonX = (this.width - rowWidth) / 2;
        int buttonY = this.height - 30;
        this.minusButton = Button.builder(Component.literal("−"),
                button -> this.resizeBy(-1)).bounds(buttonX, buttonY, smallWidth, 20).build();
        this.addRenderableWidget(this.minusButton);
        this.plusButton = Button.builder(Component.literal("+"),
                button -> this.resizeBy(1)).bounds(buttonX + smallWidth + gap, buttonY, smallWidth, 20).build();
        this.addRenderableWidget(this.plusButton);
        this.addRenderableWidget(Button.builder(Component.literal("Ganzes Bild"),
                button -> this.fitWholeImage())
                .bounds(buttonX + (smallWidth + gap) * 2, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Zurück"),
                button -> this.minecraft.setScreenAndShow(new ImageUploadScreen()))
                .bounds(buttonX + (smallWidth + gap) * 2 + buttonWidth + gap, buttonY, buttonWidth, 20).build());
        this.confirmButton = Button.builder(Component.literal("Hochladen"),
                button -> this.onConfirm())
                .bounds(buttonX + (smallWidth + gap) * 2 + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(this.confirmButton);
    }

    private void loadPreview() {
        // Decode once via ImageIO (same decoder as the upload): supports
        // PNG/JPG/GIF/BMP. The texture keeps the full source resolution and
        // the GPU scales it when drawing (see extractRenderState), so the
        // preview always shows the original quality.
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(this.sourceFile);
        } catch (IOException | RuntimeException e) {
            decoded = null;
        }
        if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
            this.loadError = "Datei ist kein lesbares Bild (PNG/JPG/GIF/BMP erwartet).";
            return;
        }
        NativeImage image;
        try {
            // Normalize to RGB first (same as the upload path): exotic color
            // models (e.g. CMYK) and transparency would break PNG encoding.
            BufferedImage rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            try {
                g.drawImage(decoded, 0, 0, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(rgb, "png", png)) {
                throw new IOException("encode failed");
            }
            image = NativeImage.read(new ByteArrayInputStream(png.toByteArray()));
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            this.loadError = "Vorschau konnte nicht erstellt werden (Bild zu groß?).";
            return;
        }
        this.imageWidth = image.getWidth();
        this.imageHeight = image.getHeight();
        if (this.imageWidth < 1 || this.imageHeight < 1) {
            image.close();
            this.loadError = "Datei ist kein lesbares Bild (PNG/JPG/GIF/BMP erwartet).";
            return;
        }
        this.minecraft.getTextureManager().release(PREVIEW_ID);
        this.previewTexture = new DynamicTexture(() -> "diawars crop preview", image);
        this.minecraft.getTextureManager().register(PREVIEW_ID, this.previewTexture);

        // The selectable canvas is the smallest square that fits the whole
        // image; the image is centred inside it. Anything the selection covers
        // outside the image becomes transparent, so the whole image can be
        // fitted without cutting it off.
        this.canvasSide = Math.max(this.imageWidth, this.imageHeight);
        int size = Math.min(this.imageWidth, this.imageHeight);
        this.selSize = size;
        this.selX = (this.canvasSide - size) / 2;
        this.selY = (this.canvasSide - size) / 2;
        // Split mode needs enough room for every grid cell to stay usable.
        if (this.splitUploads) {
            this.applyGridClamp();
        }
    }

    @Override
    public void removed() {
        super.removed();
        if (this.previewTexture != null) {
            // DynamicTexture.close also frees its pixels.
            this.previewTexture.close();
            this.minecraft.getTextureManager().release(PREVIEW_ID);
            this.previewTexture = null;
        }
    }

    @Override
    public void tick() {
        boolean ready = this.previewTexture != null;
        this.confirmButton.active = ready;
        this.minusButton.active = ready;
        this.plusButton.active = ready;
        if (this.splitButton != null) {
            this.splitButton.active = ready;
        }
        if (this.rowsButton != null) {
            this.rowsButton.active = ready;
            this.colsButton.active = ready;
        }
        if (ready) {
            String info;
            if (this.splitUploads) {
                int count = this.gridRows * this.gridCols;
                info = "\"" + this.uploadName + "\" · " + this.gridRows + "×" + this.gridCols
                        + " Aufteilung → " + count + " Bilder "
                        + ImageUploader.segmentName(this.uploadName, 1) + " … "
                        + ImageUploader.segmentName(this.uploadName, count)
                        + " · Auswahl " + this.selSize + "x" + this.selSize;
            } else {
                info = "\"" + this.uploadName + "\" · Auswahl "
                        + this.selSize + "x" + this.selSize + " (volle Auflösung)";
            }
            if (!info.equals(this.lastInfo)) {
                this.lastInfo = info;
                this.infoLine.setMessage(Component.literal(info));
            }
        } else if (this.loadError != null && !this.loadError.equals(this.lastInfo)) {
            this.lastInfo = this.loadError;
            this.infoLine.setMessage(Component.literal(this.loadError).withStyle(ChatFormatting.RED));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        extractor.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        if (this.previewTexture == null) {
            if (this.loadError != null) {
                extractor.centeredText(this.font, Component.literal(this.loadError).withStyle(ChatFormatting.RED),
                        this.width / 2, this.height / 2, 0xFFFFFFFF);
            }
            return;
        }

        int drawX = this.previewX();
        int drawY = this.previewY();
        int drawW = this.previewDrawW();
        int drawH = this.previewDrawH();
        double scale = this.previewScale();

        // Canvas background: stands in for the transparent padding around the
        // image (anything outside the image in the selection uploads as
        // transparent).
        extractor.fill(drawX, drawY, drawX + drawW, drawY + drawH, 0xFF1E1E1E);

        // Image drawn inside the canvas at its centred offset.
        int imgX = drawX + (int) Math.round(this.imageOffsetX() * scale);
        int imgY = drawY + (int) Math.round(this.imageOffsetY() * scale);
        int imgW = Math.max(1, (int) Math.round(this.imageWidth * scale));
        int imgH = Math.max(1, (int) Math.round(this.imageHeight * scale));
        extractor.blit(RenderPipelines.GUI_TEXTURED, PREVIEW_ID,
                imgX, imgY, 0.0F, 0.0F, imgW, imgH,
                this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);

        int sx0 = drawX + (int) Math.round(this.selX * scale);
        int sy0 = drawY + (int) Math.round(this.selY * scale);
        int sx1 = drawX + (int) Math.round((this.selX + this.selSize) * scale);
        int sy1 = drawY + (int) Math.round((this.selY + this.selSize) * scale);

        int dim = 0x80000000;
        if (sy0 > drawY) {
            extractor.fill(drawX, drawY, drawX + drawW, sy0, dim);
        }
        if (sy1 < drawY + drawH) {
            extractor.fill(drawX, sy1, drawX + drawW, drawY + drawH, dim);
        }
        if (sx0 > drawX) {
            extractor.fill(drawX, sy0, sx0, sy1, dim);
        }
        if (sx1 < drawX + drawW) {
            extractor.fill(sx1, sy0, drawX + drawW, sy1, dim);
        }
        // NOTE: outline takes x/y plus width/height (not two corners).
        extractor.outline(sx0, sy0, sx1 - sx0, sy1 - sy0, 0xFFFFFFFF);
        // Canvas border marks the bounds of the (square) upload.
        extractor.outline(drawX, drawY, drawW, drawH, 0xFF808080);

        if (this.splitUploads) {
            // Grid lines across the selection: every cell uploads on its own.
            int gridColor = 0x90FFFFFF;
            for (int i = 1; i < this.gridCols; i++) {
                int vx = sx0 + (int) Math.round((this.selSize * (double) i / this.gridCols) * scale);
                if (vx > sx0 && vx < sx1) {
                    extractor.fill(vx, sy0, vx + 1, sy1, gridColor);
                }
            }
            for (int i = 1; i < this.gridRows; i++) {
                int hy = sy0 + (int) Math.round((this.selSize * (double) i / this.gridRows) * scale);
                if (hy > sy0 && hy < sy1) {
                    extractor.fill(sx0, hy, sx1, hy + 1, gridColor);
                }
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClicked) {
        if (event.button() == 0 && this.previewTexture != null && this.inPreview(event.x(), event.y())) {
            double canvasX = (event.x() - this.previewX()) / this.previewScale();
            double canvasY = (event.y() - this.previewY()) / this.previewScale();
            if (canvasX < this.selX || canvasX >= this.selX + this.selSize
                    || canvasY < this.selY || canvasY >= this.selY + this.selSize) {
                this.centerOn(canvasX, canvasY);
            }
            this.grabDX = canvasX - this.selX;
            this.grabDY = canvasY - this.selY;
            this.dragging = true;
        }
        return super.mouseClicked(event, doubleClicked);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.dragging && this.previewTexture != null) {
            double canvasX = (event.x() - this.previewX()) / this.previewScale();
            double canvasY = (event.y() - this.previewY()) / this.previewScale();
            this.selX = clamp(Math.round(canvasX - this.grabDX), 0, this.canvasSide - this.selSize);
            this.selY = clamp(Math.round(canvasY - this.grabDY), 0, this.canvasSide - this.selSize);
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.previewTexture != null && this.inPreview(mouseX, mouseY) && scrollY != 0.0) {
            this.resizeBy(scrollY > 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Grows (dir > 0) or shrinks the selection square around its centre. */
    private void resizeBy(int dir) {
        if (this.previewTexture == null) {
            return;
        }
        int step = Math.max(8, this.selSize / 8);
        int min = this.splitUploads ? minSelSide() : MIN_SIZE;
        int newSize = clamp(this.selSize + dir * step, min, this.canvasSide);
        if (newSize == this.selSize) {
            return;
        }
        int centerX = this.selX + this.selSize / 2;
        int centerY = this.selY + this.selSize / 2;
        this.selSize = newSize;
        this.selX = clamp(centerX - newSize / 2, 0, this.canvasSide - newSize);
        this.selY = clamp(centerY - newSize / 2, 0, this.canvasSide - newSize);
    }

    /** Min selection side for the grid: prefers 64px cells, stays within the canvas. */
    private int minSelSide() {
        int largest = Math.max(this.gridRows, this.gridCols);
        int preferred = 64 * largest;
        return Math.min(this.canvasSide, Math.max(largest, preferred));
    }

    /** Grows the selection to the grid minimum, keeping its centre. */
    private void applyGridClamp() {
        int min = minSelSide();
        if (this.selSize >= min) {
            return;
        }
        int centerX = this.selX + this.selSize / 2;
        int centerY = this.selY + this.selSize / 2;
        this.selSize = min;
        this.selX = clamp(centerX - min / 2, 0, this.canvasSide - min);
        this.selY = clamp(centerY - min / 2, 0, this.canvasSide - min);
    }

    private String splitToggleLabel() {
        return this.splitUploads ? "Aufteilen: An" : "Aufteilen: Aus";
    }

    /** Creates (or re-shows) the rows/cols steppers when split mode turns on. */
    private void addShapeButtons() {
        if (this.rowsButton == null) {
            this.rowsButton = Button.builder(Component.literal(rowsButtonLabel()),
                    button -> this.onShapeButton(true)).bounds(156, this.height - 60, 110, 20).build();
            this.addRenderableWidget(this.rowsButton);
            this.colsButton = Button.builder(Component.literal(colsButtonLabel()),
                    button -> this.onShapeButton(false)).bounds(272, this.height - 60, 110, 20).build();
            this.addRenderableWidget(this.colsButton);
        } else {
            this.rowsButton.visible = true;
            this.colsButton.visible = true;
        }
    }

    private void onSplitToggle() {
        this.splitUploads = !this.splitUploads;
        if (this.splitButton != null) {
            this.splitButton.setMessage(Component.literal(splitToggleLabel()));
        }
        if (this.splitUploads) {
            this.addShapeButtons();
            this.applyGridClamp();
        } else if (this.rowsButton != null) {
            this.rowsButton.visible = false;
            this.colsButton.visible = false;
        }
    }

    private String rowsButtonLabel() {
        return "Zeilen: " + this.gridRows;
    }

    private String colsButtonLabel() {
        return "Spalten: " + this.gridCols;
    }

    private void onShapeButton(boolean rows) {
        if (rows) {
            this.gridRows = this.gridRows >= ImageUploader.MAX_GRID ? 1 : this.gridRows + 1;
            this.rowsButton.setMessage(Component.literal(rowsButtonLabel()));
        } else {
            this.gridCols = this.gridCols >= ImageUploader.MAX_GRID ? 1 : this.gridCols + 1;
            this.colsButton.setMessage(Component.literal(colsButtonLabel()));
        }
        this.applyGridClamp();
    }

    /** Selects the whole canvas: the entire image plus transparent padding. */
    private void fitWholeImage() {
        if (this.previewTexture == null) {
            return;
        }
        this.selSize = this.canvasSide;
        this.selX = 0;
        this.selY = 0;
    }

    private void onConfirm() {
        try {
            if (this.splitUploads) {
                List<ImageUploader.PreparedUpload> segments = ImageUploader.prepareSplit(this.sourceFile,
                        this.uploadName,
                        new ImageUploader.CropArea(this.selX - this.imageOffsetX(),
                                this.selY - this.imageOffsetY(), this.selSize),
                        this.gridRows, this.gridCols);
                if (!ImageUploader.requestSplitUpload(segments)) {
                    String error = ImageUploader.consumeOfferError();
                    this.showConfirmError(error != null ? error : "Upload bereits aktiv.");
                    return;
                }
            } else {
                ImageUploader.PreparedUpload upload;
                try {
                    upload = ImageUploader.prepare(this.sourceFile, this.uploadName,
                            new ImageUploader.CropArea(this.selX - this.imageOffsetX(),
                                    this.selY - this.imageOffsetY(), this.selSize));
                } catch (Exception e) {
                    this.showConfirmError(e.getMessage());
                    return;
                }
                // The offer handshake agrees the part count with the server first;
                // chunks queue up once the server accepts (shown in the upload screen).
                if (!ImageUploader.requestUpload(upload)) {
                    String error = ImageUploader.consumeOfferError();
                    this.showConfirmError(error != null ? error : "Upload bereits aktiv.");
                    return;
                }
            }
        } catch (Exception e) {
            this.showConfirmError(e.getMessage());
            return;
        }
        // Offer errors and progress show on the upload screen.
        this.minecraft.setScreenAndShow(new ImageUploadScreen());
    }

    private void showConfirmError(String message) {
        this.lastInfo = "";
        this.infoLine.setMessage(Component.literal("Fehler: " + (message != null ? message : "unbekannt"))
                .withStyle(ChatFormatting.RED));
    }

    /** Horizontal offset of the image inside the square canvas. */
    private int imageOffsetX() {
        return (this.canvasSide - this.imageWidth) / 2;
    }

    /** Vertical offset of the image inside the square canvas. */
    private int imageOffsetY() {
        return (this.canvasSide - this.imageHeight) / 2;
    }

    private double previewScale() {
        int availW = this.width - 40;
        int availH = (this.height - 96) - 30;
        return Math.min((double) availW / this.canvasSide, (double) availH / this.canvasSide);
    }

    private int previewDrawW() {
        return Math.max(1, (int) Math.round(this.canvasSide * this.previewScale()));
    }

    private int previewDrawH() {
        return this.previewDrawW();
    }

    private int previewX() {
        return (this.width - this.previewDrawW()) / 2;
    }

    private int previewY() {
        return 30 + (((this.height - 96) - 30) - this.previewDrawH()) / 2;
    }

    private boolean inPreview(double mouseX, double mouseY) {
        int drawW = this.previewDrawW();
        int drawH = this.previewDrawH();
        int drawX = this.previewX();
        int drawY = this.previewY();
        return mouseX >= drawX && mouseX < drawX + drawW && mouseY >= drawY && mouseY < drawY + drawH;
    }

    private void centerOn(double canvasX, double canvasY) {
        this.selX = clamp(Math.round(canvasX - this.selSize / 2.0), 0, this.canvasSide - this.selSize);
        this.selY = clamp(Math.round(canvasY - this.selSize / 2.0), 0, this.canvasSide - this.selSize);
    }

    private static int clamp(long value, int min, int max) {
        return Math.max(min, Math.min(max, (int) value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
