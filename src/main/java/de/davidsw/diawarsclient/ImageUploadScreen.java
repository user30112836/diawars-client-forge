package de.davidsw.diawarsclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Forge port of the Fabric {@code ImageUploadScreen}.
 *
 * <p>Lets the player pick an image from the local file system and upload it
 * to the Diawars server as a chunked {@code diawars:image} payload. Files can
 * be chosen from the {@code diawars-images} folder inside the game directory
 * (refreshable list) or via an arbitrary path typed into the path field. The
 * upload name defaults to the file name and can be edited. Opened from the
 * server (images menu upload button, {@code /image upload}).</p>
 */
public class ImageUploadScreen extends Screen {
    private static final int LIST_ITEM_HEIGHT = 20;
    private static final int FIELD_HEIGHT = 20;

    private File folder;
    private FileList fileList;
    private EditBox pathField;
    private EditBox nameField;
    private Button uploadButton;
    private Button gameFolderButton;
    private Button picturesButton;
    private Button downloadsButton;
    private StringWidget folderLabel;
    private StringWidget statusLine;
    private File lastSelectedFile;
    private boolean nameTouched;
    private boolean wasSending;

    public ImageUploadScreen() {
        super(Component.literal("Bilder hochladen"));
    }

    @Override
    protected void init() {
        File gameFolder = ImageUploader.getFolder(this.minecraft);
        if (this.folder == null) {
            this.folder = gameFolder;
        }
        String savedPath = this.pathField != null ? this.pathField.getValue() : "";
        String savedName = this.nameField != null ? this.nameField.getValue() : "";
        boolean savedNameTouched = this.nameTouched;
        this.clearWidgets();

        int margin = 20;
        int presetWidths = 90 + 70 + 90 + 8 * 2;
        int presetX = (this.width - presetWidths) / 2;
        this.gameFolderButton = Button.builder(Component.literal("Game-Ordner"),
                button -> this.setFolder(gameFolder)).bounds(presetX, 28, 90, 20).build();
        this.picturesButton = Button.builder(Component.literal("Bilder"),
                button -> this.setFolder(ImageUploader.getPicturesFolder())).bounds(presetX + 98, 28, 70, 20).build();
        this.downloadsButton = Button.builder(Component.literal("Downloads"),
                button -> this.setFolder(ImageUploader.getDownloadsFolder())).bounds(presetX + 176, 28, 90, 20).build();
        this.addRenderableWidget(this.gameFolderButton);
        this.addRenderableWidget(this.picturesButton);
        this.addRenderableWidget(this.downloadsButton);

        this.folderLabel = new StringWidget(margin, 52, this.width - margin * 2, 12,
                Component.literal("Ordner: ..."), this.font);
        this.addRenderableWidget(this.folderLabel);

        int listTop = 66;
        int listBottom = this.height - 152;
        this.fileList = new FileList(this.minecraft, this.width, listBottom - listTop, listTop);
        this.addRenderableWidget(this.fileList);

        int y = this.height - 140;
        this.addRenderableWidget(new StringWidget(margin, y, this.width - margin * 2, 10,
                Component.literal("Eigener Pfad (optional, sonst Auswahl aus der Liste):"), this.font));
        y += 12;
        this.pathField = new EditBox(this.font, margin, y, this.width - margin * 2, FIELD_HEIGHT,
                Component.literal("Pfad"));
        this.pathField.setMaxLength(260);
        this.pathField.setHint(Component.literal("z. B. C:/Bilder/mein-bild.png"));
        this.pathField.setValue(savedPath);
        this.addRenderableWidget(this.pathField);
        y += 24;
        this.addRenderableWidget(new StringWidget(margin, y + 4, 50, 12,
                Component.literal("Name:"), this.font));
        this.nameField = new EditBox(this.font, margin + 54, y, this.width - margin * 2 - 54, FIELD_HEIGHT,
                Component.literal("Name"));
        this.nameField.setMaxLength(32);
        this.nameField.setHint(Component.literal("Dateiname auf dem Server"));
        this.nameField.setValue(savedName);
        this.nameField.setResponder(value -> this.nameTouched = true);
        this.nameTouched = savedNameTouched;
        this.addRenderableWidget(this.nameField);
        y += 24;
        this.statusLine = new StringWidget(margin, y, this.width - margin * 2, 12,
                Component.literal(""), this.font);
        this.addRenderableWidget(this.statusLine);

        int buttonWidth = 110;
        int gap = 8;
        int rowWidth = buttonWidth * 3 + gap * 2;
        int buttonX = (this.width - rowWidth) / 2;
        int buttonY = this.height - 30;
        this.addRenderableWidget(Button.builder(Component.literal("Aktualisieren"),
                button -> this.refreshList()).bounds(buttonX, buttonY, buttonWidth, 20).build());
        this.uploadButton = Button.builder(Component.literal("Hochladen"),
                button -> this.onUpload()).bounds(buttonX + buttonWidth + gap, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(this.uploadButton);
        this.addRenderableWidget(Button.builder(Component.literal("Schließen"),
                button -> this.onClose()).bounds(buttonX + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20).build());

        this.refreshList();
    }

    @Override
    public void tick() {
        this.uploadButton.active = DiawarsClientForge.canSendImages();

        FileList.Entry selected = this.fileList.getSelected();
        File selectedFile = selected != null ? selected.file() : null;
        if (selectedFile != null && !selectedFile.equals(this.lastSelectedFile)) {
            this.lastSelectedFile = selectedFile;
            this.pathField.setValue("");
            if (!this.nameTouched) {
                String derived = ImageUploader.deriveName(selectedFile);
                this.nameField.setValue(derived != null ? derived : "");
            }
        } else if (selectedFile == null) {
            this.lastSelectedFile = null;
        }

        if (ImageUploader.hasQueued()) {
            this.wasSending = true;
            this.setStatus("Sende ... (" + ImageUploader.queuedCount() + " Teile übrig)", ChatFormatting.YELLOW);
        } else if (this.wasSending) {
            this.wasSending = false;
            this.setStatus("Fertig! Die Antwort des Servers kommt im Chat.", ChatFormatting.GREEN);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // NOTE: do NOT call extractBackground here: the Gui already extracts
        // the screen background before this runs, and extracting it again
        // throws "Can only blur once per frame".
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        extractor.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void refreshList() {
        List<File> files = ImageUploader.listImages(this.folder);
        String info = this.folder.isDirectory() ? files.size() + " Bilder" : "Ordner fehlt";
        this.folderLabel.setMessage(Component.literal(shortenFolderText(
                "Ordner: " + this.folder.getAbsolutePath() + " (" + info + ")")));
        List<FileList.Entry> entries = new ArrayList<>();
        for (File file : files) {
            entries.add(new FileList.Entry(this.font, file));
        }
        this.fileList.replaceEntries(entries);
        this.lastSelectedFile = null;
        this.updateFolderButtons();
    }

    private void setFolder(File folder) {
        this.folder = folder;
        this.refreshList();
    }

    private void updateFolderButtons() {
        String current = this.folder.getAbsolutePath();
        this.gameFolderButton.active = !current.equals(ImageUploader.getFolder(this.minecraft).getAbsolutePath());
        this.picturesButton.active = !current.equals(ImageUploader.getPicturesFolder().getAbsolutePath());
        this.downloadsButton.active = !current.equals(ImageUploader.getDownloadsFolder().getAbsolutePath());
    }

    private void onUpload() {
        String manual = this.pathField.getValue().trim();
        File source;
        if (!manual.isEmpty()) {
            File typed = new File(manual);
            source = typed.isAbsolute() ? typed : new File(this.folder, manual);
        } else {
            FileList.Entry selected = this.fileList.getSelected();
            if (selected == null) {
                this.setStatus("Fehler: Wähle zuerst eine Datei aus der Liste oder gib einen Pfad ein.", ChatFormatting.RED);
                return;
            }
            source = selected.file();
        }

        String name = this.nameField.getValue().trim();
        ImageUploader.PreparedUpload upload;
        try {
            upload = ImageUploader.prepare(source, name.isEmpty() ? null : name);
        } catch (Exception e) {
            this.setStatus("Fehler: " + e.getMessage(), ChatFormatting.RED);
            return;
        }

        if (!DiawarsClientForge.canSendImages()) {
            this.setStatus("Fehler: Nicht mit einem Server verbunden!", ChatFormatting.RED);
            return;
        }

        ImageUploader.enqueue(upload.chunks());
        this.setStatus("Sende \"" + upload.fileName() + "\" (" + upload.chunks().size() + " Teile) ...", ChatFormatting.YELLOW);
    }

    private void setStatus(String text, ChatFormatting color) {
        this.statusLine.setMessage(Component.literal(text).withStyle(color));
    }

    /** Shortens the folder label from the front so it fits the screen width. */
    private String shortenFolderText(String text) {
        int maxWidth = this.width - 40;
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String tail = text;
        while (tail.length() > 1 && this.font.width("..." + tail) > maxWidth) {
            tail = tail.substring(1);
        }
        return "..." + tail;
    }

    private static final class FileList extends ObjectSelectionList<FileList.Entry> {
        FileList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, LIST_ITEM_HEIGHT);
        }

        static final class Entry extends ObjectSelectionList.Entry<FileList.Entry> {
            private final Font font;
            private final File file;

            Entry(Font font, File file) {
                this.font = font;
                this.file = file;
            }

            File file() {
                return this.file;
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.file.getName());
            }

            @Override
            public void extractContent(GuiGraphicsExtractor extractor, int x, int y, boolean hovered, float partialTick) {
                // NOTE: use the entry's own content coords like vanilla does;
                // the x/y parameters are not the row position.
                extractor.text(this.font, Component.literal(this.file.getName()),
                        this.getContentX() + 6, this.getContentY() + 6, hovered ? 0xFFFFFFA0 : 0xFFFFFFFF);
            }
        }
    }
}
