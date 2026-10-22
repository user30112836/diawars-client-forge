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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;import javax.imageio.ImageIO;

/**
 * Second upload step: shows the image and lets the player pick the area to
 * upload. Drag inside the preview to move the selection, use the mouse wheel
 * over the preview to change its size. The selection may grow to the whole
 * square canvas ("Ganzes Bild"), so the entire image fits without cutting
 * anything off; pixels outside the image become transparent.
 * The "Aufteilen" toggle switches the selection into a rows×cols rectangle
 * of square cells (free shape, e.g. 2×3 or 5×1, via the "Zeilen"/"Spalten"
 * steppers; the wheel then changes the cell size) — every cell uploads as
 * its own square image named {@code base (k)}, one by one, after the server
 * pre-checks every name. Confirming crops the area and queues the upload
 * at full resolution (see {@link ImageUploader}).
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
    /** True while the preview decodes on the prepare thread. */
    private boolean loadingPreview;
    /** Set when the screen is removed; late load results are discarded. */
    private boolean screenRemoved;

    private int selX;
    private int selY;
    private int selW;
    private int selH;
    /** Split mode: side of every square cell (selW = cellSize·cols etc.). */
    private int cellSize;
    private boolean dragging;
    private double grabDX;
    private double grabDY;

    private Button confirmButton;
    private Button minusButton;
    private Button plusButton;
    private Button splitButton;
    private Button rowsButton;
    private Button colsButton;
    /** Left edge and width of the split steppers, laid out in init(). */
    private int rowsX;
    private int colsX;
    private int stepperWidth;
    /** False after an init() re-run until the steppers are re-added. */
    private boolean steppersAttached;
    private StringWidget infoLine;
    private String lastInfo = "";

    public ImageCropScreen(File sourceFile, String uploadName) {
        super(Component.literal("Bild zuschneiden"));
        this.sourceFile = sourceFile;
        this.uploadName = uploadName;
    }

    @Override
    protected void init() {
        if (this.previewTexture == null && this.loadError == null && !this.loadingPreview) {
            this.startLoading();
        }
        String savedInfo = this.lastInfo;
        this.clearWidgets();
        this.steppersAttached = false;

        // Scale the two button rows down on narrow GUI widths (see UiFit):
        // 4:3/5:4 screens or large GUI scale make the GUI much narrower than
        // the 16:9 layout the fixed rows were designed for.
        int[] bottom = UiFit.fit(new int[]{44, 44, 110, 110, 110}, 8, Math.max(1, this.width - 20), 30, 2);
        int[] split = UiFit.fit(new int[]{130, 110, 110}, 6, Math.max(1, this.width - 20), 40, 4);

        this.infoLine = new StringWidget(20, this.height - 88, this.width - 40, 12,
                Component.literal(savedInfo), this.font);
        this.addRenderableWidget(this.infoLine);
        this.addRenderableWidget(new StringWidget(20, this.height - 74, this.width - 40, 12,
                this.clippedText("Ziehen: verschieben · Mausrad / − / +: Größe · Ganzes Bild: transparent auffüllen"),
                this.font));

        this.splitButton = Button.builder(Component.literal(splitToggleLabel()),
                button -> this.onSplitToggle()).bounds(20, this.height - 60, split[0], 20).build();
        this.addRenderableWidget(this.splitButton);
        this.rowsX = 20 + split[0] + split[3];
        this.colsX = this.rowsX + split[1] + split[3];
        this.stepperWidth = split[1];
        if (this.splitUploads) {
            this.addShapeButtons();
        }

        int smallWidth = bottom[0];
        int buttonWidth = bottom[2];
        int gap = bottom[5];
        int rowWidth = bottom[0] + bottom[1] + bottom[2] + bottom[3] + bottom[4] + gap * 4;
        int buttonX = (this.width - rowWidth) / 2;
        int buttonY = this.height - 30;
        this.minusButton = Button.builder(Component.literal("−"),
                button -> this.resizeBy(-1)).bounds(buttonX, buttonY, smallWidth, 20).build();
        this.addRenderableWidget(this.minusButton);
        this.plusButton = Button.builder(Component.literal("+"),
                button -> this.resizeBy(1)).bounds(buttonX + smallWidth + gap, buttonY, bottom[1], 20).build();
        this.addRenderableWidget(this.plusButton);
        this.addRenderableWidget(Button.builder(Component.literal("Ganzes Bild"),
                button -> this.fitWholeImage())
                .bounds(buttonX + (smallWidth + gap) * 2, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Zurück"),
                button -> this.minecraft.setScreenAndShow(new ImageUploadScreen()))
                .bounds(buttonX + (smallWidth + gap) * 2 + buttonWidth + gap, buttonY, bottom[3], 20).build());
        this.confirmButton = Button.builder(Component.literal("Hochladen"),
                button -> this.onConfirm())
                .bounds(buttonX + (smallWidth + gap) * 2 + (buttonWidth + gap) * 2, buttonY, bottom[4], 20).build();
        this.addRenderableWidget(this.confirmButton);
    }

    /**
     * Cuts a hint/info text with "…" so it cannot render past the right
     * edge on narrow GUI widths (StringWidget does not clip by itself).
     */
    private MutableComponent clippedText(String text) {
        int maxWidth = this.width - 40;
        if (this.font.width(text) <= maxWidth) {
            return Component.literal(text);
        }
        int usable = Math.max(10, maxWidth - this.font.width("…"));
        return Component.literal(this.font.plainSubstrByWidth(text, usable) + "…");
    }

    private void startLoading() {
        this.loadingPreview = true;
        final File file = this.sourceFile;
        // Decode + re-encode off the UI thread ("Weiter" must not freeze
        // the game); only the GPU texture registration runs on the client
        // thread (see loadFinished).
        ImageUploader.runBackground(() -> {
            NativeImage image = null;
            String error = null;
            try {
                ImageUploader.validateSource(file);
            } catch (IOException e) {
                error = e.getMessage();
            }
            if (error == null) {
                try {
                    BufferedImage decoded = ImageIO.read(file);
                    if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
                        error = "Datei ist kein lesbares Bild (PNG/JPG/GIF/BMP erwartet).";
                    } else {
                        // Normalize to RGB first (same as the upload path):
                        // exotic color models (e.g. CMYK) and transparency
                        // would break PNG encoding.
                        BufferedImage rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(),
                                BufferedImage.TYPE_INT_RGB);
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
                        if (image.getWidth() < 1 || image.getHeight() < 1) {
                            image.close();
                            image = null;
                            error = "Datei ist kein lesbares Bild (PNG/JPG/GIF/BMP erwartet).";
                        }
                    }
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    error = "Vorschau konnte nicht erstellt werden (Bild zu groß?).";
                }
            }
            final NativeImage loaded = image;
            final String loadError = error;
            this.minecraft.execute(() -> this.loadFinished(loaded, loadError));
        });
    }

    /** Installs the decoded preview on the client thread. */
    private void loadFinished(NativeImage image, String error) {
        this.loadingPreview = false;
        if (this.screenRemoved) {
            if (image != null) {
                image.close();
            }
            return;
        }
        if (image == null) {
            this.loadError = error != null ? error : "Vorschau konnte nicht erstellt werden.";
            return;
        }
        this.imageWidth = image.getWidth();
        this.imageHeight = image.getHeight();
        this.minecraft.getTextureManager().release(PREVIEW_ID);
        this.previewTexture = new DynamicTexture(() -> "diawars crop preview", image);
        this.minecraft.getTextureManager().register(PREVIEW_ID, this.previewTexture);

        // The selectable canvas is the smallest square that fits the whole
        // image; the image is centred inside it. Anything the selection
        // covers outside the image becomes transparent, so the whole image
        // can be fitted without cutting it off.
        this.canvasSide = Math.max(this.imageWidth, this.imageHeight);
        int size = Math.min(this.imageWidth, this.imageHeight);
        this.selW = size;
        this.selH = size;
        this.selX = (this.canvasSide - size) / 2;
        this.selY = (this.canvasSide - size) / 2;
        this.cellSize = size;
    }

    @Override
    public void removed() {
        super.removed();
        this.screenRemoved = true;
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
                        + " · Zelle " + this.cellSize + "x" + this.cellSize;
            } else {
                info = "\"" + this.uploadName + "\" · Auswahl "
                        + this.selW + "x" + this.selH + " (volle Auflösung)";
            }
            if (!info.equals(this.lastInfo)) {
                this.lastInfo = info;
                this.infoLine.setMessage(this.clippedText(info));
            }
        } else if (this.loadingPreview) {
            if (!this.lastInfo.isEmpty()) {
                this.lastInfo = "";
                this.infoLine.setMessage(Component.literal("Bild wird geladen …"));
            }
        } else if (this.loadError != null && !this.loadError.equals(this.lastInfo)) {
            this.lastInfo = this.loadError;
            this.infoLine.setMessage(this.clippedText(this.loadError).withStyle(ChatFormatting.RED));
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
            } else if (this.loadingPreview) {
                extractor.centeredText(this.font, Component.literal("Bild wird geladen …"),
                        this.width / 2, this.height / 2, 0xFFB0B0B0);
            }
            return;
        }

        int drawX = this.previewX();
        int drawY = this.previewY();
        int drawW = this.previewDrawW();
        int drawH = this.previewDrawH();
        double scale = this.previewScale();
        double off = this.originOff();

        // Canvas background: stands in for the transparent padding around the
        // image (anything outside the image in the selection uploads as
        // transparent). The canvas is the square around the image; in split
        // mode the selectable grid frame may reach beyond it.
        int canvasX0 = drawX + (int) Math.round(off * scale);
        int canvasY0 = drawY + (int) Math.round(off * scale);
        int canvasDraw = (int) Math.round(this.canvasSide * scale);
        extractor.fill(canvasX0, canvasY0, canvasX0 + canvasDraw, canvasY0 + canvasDraw, 0xFF1E1E1E);

        // Grid frame outline: the area the split selection can cover.
        if (this.splitUploads) {
            int fx0 = canvasX0 + (int) Math.round(this.frameX0() * scale);
            int fy0 = canvasY0 + (int) Math.round(this.frameY0() * scale);
            int fx1 = canvasX0 + (int) Math.round((this.frameX0() + this.frameW()) * scale);
            int fy1 = canvasY0 + (int) Math.round((this.frameY0() + this.frameH()) * scale);
            extractor.outline(fx0, fy0, fx1 - fx0, fy1 - fy0, 0xFF606060);
        }

        // Image drawn inside the canvas at its centred offset.
        int imgX = canvasX0 + (int) Math.round(this.imageOffsetX() * scale);
        int imgY = canvasY0 + (int) Math.round(this.imageOffsetY() * scale);
        int imgW = Math.max(1, (int) Math.round(this.imageWidth * scale));
        int imgH = Math.max(1, (int) Math.round(this.imageHeight * scale));
        extractor.blit(RenderPipelines.GUI_TEXTURED, PREVIEW_ID,
                imgX, imgY, 0.0F, 0.0F, imgW, imgH,
                this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);

        int sx0 = drawX + (int) Math.round((this.selX + off) * scale);
        int sy0 = drawY + (int) Math.round((this.selY + off) * scale);
        int sx1 = drawX + (int) Math.round((this.selX + this.selW + off) * scale);
        int sy1 = drawY + (int) Math.round((this.selY + this.selH + off) * scale);

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
            // Grid lines across the selection: every square cell uploads on its own.
            int gridColor = 0x90FFFFFF;
            for (int i = 1; i < this.gridCols; i++) {
                int vx = sx0 + i * (int) Math.round(this.cellSize * scale);
                if (vx > sx0 && vx < sx1) {
                    extractor.fill(vx, sy0, vx + 1, sy1, gridColor);
                }
            }
            for (int i = 1; i < this.gridRows; i++) {
                int hy = sy0 + i * (int) Math.round(this.cellSize * scale);
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
            double canvasX = this.canvasMouseX(event.x());
            double canvasY = this.canvasMouseY(event.y());
            if (canvasX < this.selX || canvasX >= this.selX + this.selW
                    || canvasY < this.selY || canvasY >= this.selY + this.selH) {
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
            this.selX = (int) Math.round(this.canvasMouseX(event.x()) - this.grabDX);
            this.selY = (int) Math.round(this.canvasMouseY(event.y()) - this.grabDY);
            this.clampSelectionPos();
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

    /** Grows (dir > 0) or shrinks the selection around its centre. */
    private void resizeBy(int dir) {
        if (this.previewTexture == null) {
            return;
        }
        if (this.splitUploads) {
            // Fixed rows×cols aspect: the wheel/+/- change the square cell size.
            this.cellSize += dir * Math.max(8, this.cellSize / 8);
            this.applyShape();
            return;
        }
        int step = Math.max(8, this.selW / 8);
        int newSize = clamp(this.selW + dir * step, MIN_SIZE, this.canvasSide);
        if (newSize == this.selW) {
            return;
        }
        int centerX = this.selX + this.selW / 2;
        int centerY = this.selY + this.selH / 2;
        this.selW = newSize;
        this.selH = newSize;
        this.selX = clamp(centerX - newSize / 2, 0, this.canvasSide - newSize);
        this.selY = clamp(centerY - newSize / 2, 0, this.canvasSide - newSize);
    }

    /**
     * Recomputes the rows×cols selection rectangle from the cell size.
     * In split mode the selection slides inside the grid frame (which can
     * reach beyond the canvas); otherwise it is clamped to the canvas. The
     * selection centre is preserved.
     */
    private void applyShape() {
        this.cellSize = clamp(this.cellSize, minCellSide(), maxCellSide());
        int centerX = this.selX + this.selW / 2;
        int centerY = this.selY + this.selH / 2;
        this.selW = this.cellSize * this.gridCols;
        this.selH = this.cellSize * this.gridRows;
        this.selX = centerX - this.selW / 2;
        this.selY = centerY - this.selH / 2;
        this.clampSelectionPos();
    }

    /** Clamps the selection position into its selectable area. */
    private void clampSelectionPos() {
        if (this.splitUploads) {
            this.selX = clamp(this.selX, this.frameX0(), this.frameX0() + this.frameW() - this.selW);
            this.selY = clamp(this.selY, this.frameY0(), this.frameY0() + this.frameH() - this.selH);
        } else {
            this.selX = clamp(this.selX, 0, this.canvasSide - this.selW);
            this.selY = clamp(this.selY, 0, this.canvasSide - this.selH);
        }
    }

    /** Grows cells to the 64px preferred size when the canvas allows it. */
    private void applyGridClamp() {
        this.cellSize = Math.max(this.cellSize, Math.min(64, maxCellSide()));
        this.applyShape();
    }

    private String splitToggleLabel() {
        return this.splitUploads ? "Aufteilen: An" : "Aufteilen: Aus";
    }

    /**
     * Creates (or re-shows) the rows/cols steppers when split mode turns
     * on. Positions come from init()'s row layout; after an init() re-run
     * (i.e. a window resize) the buttons are re-added, since clearWidgets
     * detached them.
     */
    private void addShapeButtons() {
        if (this.rowsButton == null) {
            this.rowsButton = Button.builder(Component.literal(rowsButtonLabel()),
                    button -> this.onShapeButton(true)).build();
            this.colsButton = Button.builder(Component.literal(colsButtonLabel()),
                    button -> this.onShapeButton(false)).build();
        }
        this.rowsButton.setPosition(this.rowsX, this.height - 60);
        this.rowsButton.setWidth(this.stepperWidth);
        this.colsButton.setPosition(this.colsX, this.height - 60);
        this.colsButton.setWidth(this.stepperWidth);
        if (!this.steppersAttached) {
            this.addRenderableWidget(this.rowsButton);
            this.addRenderableWidget(this.colsButton);
            this.steppersAttached = true;
        }
        this.rowsButton.visible = true;
        this.colsButton.visible = true;
    }

    private void onSplitToggle() {
        this.splitUploads = !this.splitUploads;
        if (this.splitButton != null) {
            this.splitButton.setMessage(Component.literal(splitToggleLabel()));
        }
        if (this.splitUploads) {
            this.addShapeButtons();
            // Default to the full grid frame: the whole image is covered.
            this.cellSize = this.maxCellSide();
            this.applyShape();
        } else {
            if (this.rowsButton != null) {
                this.rowsButton.visible = false;
                this.colsButton.visible = false;
            }
            // Restore a square selection for the single upload path.
            int side = clamp(Math.min(this.selW, this.selH), MIN_SIZE, this.canvasSide);
            int centerX = this.selX + this.selW / 2;
            int centerY = this.selY + this.selH / 2;
            this.selW = side;
            this.selH = side;
            this.selX = clamp(centerX - side / 2, 0, this.canvasSide - side);
            this.selY = clamp(centerY - side / 2, 0, this.canvasSide - side);
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
        if (this.splitUploads) {
            this.cellSize = maxCellSide();
            this.applyShape();
            return;
        }
        this.selW = this.canvasSide;
        this.selH = this.canvasSide;
        this.selX = 0;
        this.selY = 0;
    }

    private void onConfirm() {
        // Cheap pre-checks up front (file present, name resolvable); the
        // expensive encoding runs asynchronously and nothing (decode or
        // upload) starts before the server confirmed the name(s)/limit.
        ImageUploader.CropArea crop = new ImageUploader.CropArea(this.selX - this.imageOffsetX(),
                this.selY - this.imageOffsetY(), this.selW, this.selH);
        try {
            ImageUploader.validateSource(this.sourceFile);
        } catch (Exception e) {
            this.showConfirmError(e.getMessage());
            return;
        }
        boolean started = this.splitUploads
                ? ImageUploader.requestSplitUploadAsync(this.sourceFile, this.uploadName, crop,
                        this.gridRows, this.gridCols)
                : ImageUploader.requestUploadAsync(this.sourceFile, this.uploadName, crop);
        if (!started) {
            String error = ImageUploader.consumeOfferError();
            this.showConfirmError(error != null ? error : "Upload bereits aktiv.");
            return;
        }
        // Everything else happens in the bottom-right HUD overlay while the
        // dialog stays closed.
        this.minecraft.gui.setScreen(null);
    }

    private void showConfirmError(String message) {
        this.lastInfo = "";
        this.infoLine.setMessage(this.clippedText("Fehler: " + (message != null ? message : "unbekannt"))
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

    /**
     * Side of the square preview area. In split mode the rows×cols grid
     * frame can reach beyond the square canvas, so the preview zooms out to
     * show the frame; the canvas stays centred in both cases.
     */
    private int boundSide() {
        if (!this.splitUploads) {
            return this.canvasSide;
        }
        return Math.max(this.canvasSide, Math.max(this.frameW(), this.frameH()));
    }

    /** Offset of the canvas origin inside the preview area (split overhang). */
    private double originOff() {
        return (this.boundSide() - this.canvasSide) / 2.0;
    }

    /** Canvas-space X of a mouse position inside the preview. */
    private double canvasMouseX(double mouseX) {
        return (mouseX - this.previewX()) / this.previewScale() - this.originOff();
    }

    /** Canvas-space Y of a mouse position inside the preview. */
    private double canvasMouseY(double mouseY) {
        return (mouseY - this.previewY()) / this.previewScale() - this.originOff();
    }

    /**
     * Max cell side in split mode: the smallest cell whose rows×cols frame
     * still covers the entire image (the image may be centred with
     * transparent bands where the aspects differ).
     */
    private int maxCellSide() {
        int fitW = ceilDiv(this.imageWidth, this.gridCols);
        int fitH = ceilDiv(this.imageHeight, this.gridRows);
        return Math.max(Math.max(fitW, fitH), MIN_SIZE);
    }

    /** Smallest sensible cell side. */
    private int minCellSide() {
        return MIN_SIZE;
    }

    /**
     * Side of the rows×cols frame: the grid rectangle covering the whole
     * image (see {@link #maxCellSide()}).
     */
    private int frameW() {
        return this.maxCellSide() * this.gridCols;
    }

    private int frameH() {
        return this.maxCellSide() * this.gridRows;
    }

    /** Left edge of the frame in canvas coordinates (may be negative). */
    private int frameX0() {
        return (this.canvasSide - this.frameW()) / 2;
    }

    /** Top edge of the frame in canvas coordinates (may be negative). */
    private int frameY0() {
        return (this.canvasSide - this.frameH()) / 2;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private double previewScale() {
        int availW = this.width - 40;
        int availH = (this.height - 96) - 30;
        return Math.min((double) availW / this.boundSide(), (double) availH / this.boundSide());
    }

    private int previewDrawW() {
        return Math.max(1, (int) Math.round(this.boundSide() * this.previewScale()));
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
        this.selX = (int) Math.round(canvasX - this.selW / 2.0);
        this.selY = (int) Math.round(canvasY - this.selH / 2.0);
        this.clampSelectionPos();
    }

    private static int clamp(long value, int min, int max) {
        return Math.max(min, Math.min(max, (int) value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
