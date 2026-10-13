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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Second upload step: shows the image and lets the player pick the square
 * area to upload. Drag inside the preview to move the square, use the mouse
 * wheel over the preview to change its size. Confirming crops the area,
 * scales it to 128x128 and queues the upload (see {@link ImageUploader}).
 */
public class ImageCropScreen extends Screen {
    private static final Identifier PREVIEW_ID =
            Identifier.fromNamespaceAndPath("diawars_client", "crop_preview");
    private static final int MIN_SIZE = 8;

    private final File sourceFile;
    private final String uploadName;

    private DynamicTexture previewTexture;
    private int imageWidth;
    private int imageHeight;
    private String loadError;

    private int selX;
    private int selY;
    private int selSize;
    private boolean dragging;
    private double grabDX;
    private double grabDY;

    private Button confirmButton;
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

        this.infoLine = new StringWidget(20, this.height - 72, this.width - 40, 12,
                Component.literal(savedInfo), this.font);
        this.addRenderableWidget(this.infoLine);

        int buttonWidth = 110;
        int gap = 8;
        int rowWidth = buttonWidth * 2 + gap;
        int buttonX = (this.width - rowWidth) / 2;
        int buttonY = this.height - 30;
        this.addRenderableWidget(Button.builder(Component.literal("Zurück"),
                button -> this.minecraft.setScreenAndShow(new ImageUploadScreen()))
                .bounds(buttonX, buttonY, buttonWidth, 20).build());
        this.confirmButton = Button.builder(Component.literal("Hochladen"),
                button -> this.onConfirm())
                .bounds(buttonX + buttonWidth + gap, buttonY, buttonWidth, 20).build();
        this.confirmButton.active = this.previewTexture != null;
        this.addRenderableWidget(this.confirmButton);
    }

    private void loadPreview() {
        NativeImage image;
        try (InputStream in = new FileInputStream(this.sourceFile)) {
            image = NativeImage.read(in);
        } catch (IOException | RuntimeException e) {
            this.loadError = "Datei ist kein lesbares Bild (PNG/JPG erwartet).";
            return;
        }
        this.imageWidth = image.getWidth();
        this.imageHeight = image.getHeight();
        if (this.imageWidth < 1 || this.imageHeight < 1) {
            image.close();
            this.loadError = "Datei ist kein lesbares Bild (PNG/JPG erwartet).";
            return;
        }
        this.minecraft.getTextureManager().release(PREVIEW_ID);
        this.previewTexture = new DynamicTexture(() -> "diawars crop preview", image);
        this.minecraft.getTextureManager().register(PREVIEW_ID, this.previewTexture);

        int size = Math.min(this.imageWidth, this.imageHeight);
        this.selSize = size;
        this.selX = (this.imageWidth - size) / 2;
        this.selY = (this.imageHeight - size) / 2;
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
        if (this.previewTexture != null) {
            String info = "\"" + this.uploadName + "\" · Auswahl "
                    + this.selSize + "x" + this.selSize + " → 128x128";
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

        int previewTop = 30;
        int previewBottom = this.height - 88;
        int availW = this.width - 40;
        int availH = previewBottom - previewTop;
        double scale = Math.min((double) availW / this.imageWidth, (double) availH / this.imageHeight);
        int drawW = Math.max(1, (int) (this.imageWidth * scale));
        int drawH = Math.max(1, (int) (this.imageHeight * scale));
        int drawX = (this.width - drawW) / 2;
        int drawY = previewTop + (availH - drawH) / 2;

        extractor.blit(RenderPipelines.GUI_TEXTURED, PREVIEW_ID,
                drawX, drawY, 0.0F, 0.0F, drawW, drawH, this.imageWidth, this.imageHeight);

        int sx0 = drawX + (int) (this.selX * scale);
        int sy0 = drawY + (int) (this.selY * scale);
        int sx1 = drawX + (int) ((this.selX + this.selSize) * scale);
        int sy1 = drawY + (int) ((this.selY + this.selSize) * scale);

        int dim = 0x80000000;
        extractor.fill(drawX, drawY, drawX + drawW, sy0, dim);
        extractor.fill(drawX, sy1, drawX + drawW, drawY + drawH, dim);
        extractor.fill(drawX, sy0, sx0, sy1, dim);
        extractor.fill(sx1, sy0, drawX + drawW, sy1, dim);
        extractor.outline(sx0, sy0, sx1, sy1, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClicked) {
        if (event.button() == 0 && this.previewTexture != null && this.inPreview(event.x(), event.y())) {
            double imageX = (event.x() - this.previewX()) / this.previewScale();
            double imageY = (event.y() - this.previewY()) / this.previewScale();
            if (imageX < this.selX || imageX >= this.selX + this.selSize
                    || imageY < this.selY || imageY >= this.selY + this.selSize) {
                this.centerOn(imageX, imageY);
            }
            this.grabDX = imageX - this.selX;
            this.grabDY = imageY - this.selY;
            this.dragging = true;
        }
        return super.mouseClicked(event, doubleClicked);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.dragging && this.previewTexture != null) {
            double imageX = (event.x() - this.previewX()) / this.previewScale();
            double imageY = (event.y() - this.previewY()) / this.previewScale();
            this.selX = clamp(Math.round(imageX - this.grabDX), 0, this.imageWidth - this.selSize);
            this.selY = clamp(Math.round(imageY - this.grabDY), 0, this.imageHeight - this.selSize);
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
            int step = Math.max(8, this.selSize / 8);
            int maxSize = Math.min(this.imageWidth, this.imageHeight);
            this.selSize = clamp(this.selSize + (scrollY > 0 ? step : -step), MIN_SIZE, maxSize);
            this.selX = clamp(this.selX, 0, this.imageWidth - this.selSize);
            this.selY = clamp(this.selY, 0, this.imageHeight - this.selSize);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void onConfirm() {
        ImageUploader.PreparedUpload upload;
        try {
            upload = ImageUploader.prepare(this.sourceFile, this.uploadName,
                    new ImageUploader.CropArea(this.selX, this.selY, this.selSize));
        } catch (Exception e) {
            this.lastInfo = "";
            this.infoLine.setMessage(Component.literal("Fehler: " + e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        ImageUploader.enqueue(upload.chunks());
        this.minecraft.setScreenAndShow(new ImageUploadScreen());
    }

    private double previewScale() {
        int availW = this.width - 40;
        int availH = (this.height - 88) - 30;
        return Math.min((double) availW / this.imageWidth, (double) availH / this.imageHeight);
    }

    private int previewX() {
        int drawW = Math.max(1, (int) (this.imageWidth * this.previewScale()));
        return (this.width - drawW) / 2;
    }

    private int previewY() {
        int drawH = Math.max(1, (int) (this.imageHeight * this.previewScale()));
        return 30 + (((this.height - 88) - 30) - drawH) / 2;
    }

    private boolean inPreview(double mouseX, double mouseY) {
        int drawW = Math.max(1, (int) (this.imageWidth * this.previewScale()));
        int drawH = Math.max(1, (int) (this.imageHeight * this.previewScale()));
        int drawX = this.previewX();
        int drawY = this.previewY();
        return mouseX >= drawX && mouseX < drawX + drawW && mouseY >= drawY && mouseY < drawH + drawY;
    }

    private void centerOn(double imageX, double imageY) {
        this.selX = clamp(Math.round(imageX - this.selSize / 2.0), 0, this.imageWidth - this.selSize);
        this.selY = clamp(Math.round(imageY - this.selSize / 2.0), 0, this.imageHeight - this.selSize);
    }

    private static int clamp(long value, int min, int max) {
        return Math.max(min, Math.min(max, (int) value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
