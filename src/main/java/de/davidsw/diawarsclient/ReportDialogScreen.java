package de.davidsw.diawarsclient;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Server-requested report dialog — the one "Melden" button in the main menu
 * opens this screen and every report aspect is filled here separately:
 * optional player name, optional reason, screenshot checkbox (pre-checked:
 * the screenshot is usually taken) and hand-image checkbox (pre-checked when
 * the server saw a Diawars image map in the main hand). Everything at once on
 * submit; unnecessary aspects are simply left empty, but at least one aspect
 * must be set (Senden disabled otherwise).
 *
 * <p>The dialog JSON ({@code {"handImage": true, "reasonMax": 240}}) comes
 * from the {@code diawars:report_dialog} payload. On submit the screen sends
 * the whole report to {@code diawars:report_submit} via the loader-injected
 * {@link SubmitSender} (see {@link ReportSubmitPayload}); the server
 * re-validates targets and requests the screenshot through
 * {@code diawars:screenshot}. Enter submits, Esc cancels.</p>
 */
public class ReportDialogScreen extends Screen {
    private static final Gson GSON = new Gson();
    private static final int FIELD_HEIGHT = 20;
    private static final int MAX_PLAYER_LENGTH = 16;

    /** Sends the submit JSON on the report_submit channel; false = dropped. */
    public interface SubmitSender {
        boolean send(String json);
    }

    private static volatile SubmitSender submitSender;

    public static void initSubmitSender(SubmitSender sender) {
        submitSender = sender;
    }

    private final boolean handImageAvailable;
    private final int reasonMax;

    private EditBox playerBox;
    private EditBox reasonBox;
    private Checkbox screenshotBox;
    private Checkbox handImageBox;
    private Button sendButton;

    /**
     * Parses the {@code diawars:report_dialog} JSON document and builds the
     * screen, or returns null when the payload is broken (then the dialog
     * simply does not open).
     */
    public static ReportDialogScreen parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject doc = GSON.fromJson(json, JsonObject.class);
            if (doc == null) {
                return null;
            }
            boolean handImage = doc.has("handImage") && doc.get("handImage").getAsJsonPrimitive().isBoolean()
                    && doc.get("handImage").getAsBoolean();
            int reasonMax = doc.has("reasonMax") && doc.get("reasonMax").getAsJsonPrimitive().isNumber()
                    ? doc.get("reasonMax").getAsInt() : 200;
            return new ReportDialogScreen(handImage, reasonMax);
        } catch (Exception e) {
            return null;
        }
    }

    private ReportDialogScreen(boolean handImageAvailable, int reasonMax) {
        super(Component.literal("Diawars Report"));
        this.handImageAvailable = handImageAvailable;
        this.reasonMax = Math.max(1, reasonMax);
    }

    @Override
    protected void init() {
        int margin = 20;

        this.addRenderableWidget(new StringWidget(margin, 32, this.width - margin * 2, 10,
                Component.literal("Spieler melden (optional)"), this.font));
        this.playerBox = new EditBox(this.font, margin, 44, this.width - margin * 2, FIELD_HEIGHT,
                Component.literal(""));
        this.playerBox.setMaxLength(MAX_PLAYER_LENGTH);
        this.playerBox.setHint(Component.literal("Online-Spielername"));
        this.playerBox.setResponder(value -> this.updateSendButton());
        this.addRenderableWidget(this.playerBox);

        this.addRenderableWidget(new StringWidget(margin, 70, this.width - margin * 2, 10,
                Component.literal("Grund (optional)"), this.font));
        this.reasonBox = new EditBox(this.font, margin, 82, this.width - margin * 2, FIELD_HEIGHT,
                Component.literal(""));
        this.reasonBox.setMaxLength(this.reasonMax);
        this.reasonBox.setHint(Component.literal("Warum meldest du das?"));
        this.reasonBox.setResponder(value -> this.updateSendButton());
        this.addRenderableWidget(this.reasonBox);

        this.screenshotBox = Checkbox.builder(Component.literal("Screenshot anhängen"), this.font)
                .pos(margin, 108)
                .selected(true)
                .build();
        this.addRenderableWidget(this.screenshotBox);

        this.handImageBox = Checkbox.builder(Component.literal("Bild-Karte aus der Hand anhängen"), this.font)
                .pos(margin, 128)
                .selected(this.handImageAvailable)
                .build();
        // Without a map in hand there is nothing to append.
        this.handImageBox.active = this.handImageAvailable;
        this.addRenderableWidget(this.handImageBox);

        int buttonWidth = 110;
        int gap = 8;
        int buttonX = (this.width - buttonWidth * 2 - gap) / 2;
        int buttonY = this.height - 30;
        this.sendButton = Button.builder(Component.literal("Senden"),
                button -> this.onSubmit()).bounds(buttonX, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(this.sendButton);
        this.addRenderableWidget(Button.builder(Component.literal("Abbrechen"),
                button -> this.onClose()).bounds(buttonX + buttonWidth + gap, buttonY, buttonWidth, 20).build());

        this.updateSendButton();
        this.setInitialFocus(this.playerBox);
    }

    /** Player/reason/hand-image content typed or selectable in the dialog. */
    private boolean hasContent() {
        return !this.playerBox.getValue().isBlank()
                || !this.reasonBox.getValue().isBlank()
                || (this.handImageAvailable && this.handImageBox.selected());
    }

    private void updateSendButton() {
        if (this.sendButton != null && this.playerBox != null && this.reasonBox != null && this.screenshotBox != null) {
            this.sendButton.active = this.hasContent() || this.screenshotBox.selected();
        }
    }

    @Override
    public void tick() {
        this.updateSendButton();
    }

    private void onSubmit() {
        // At least one aspect: report content or the chosen screenshot.
        if (!this.hasContent() && !this.screenshotBox.selected()) {
            return;
        }
        JsonObject doc = new JsonObject();
        String player = this.playerBox.getValue().trim();
        String reason = this.reasonBox.getValue().trim();
        if (!player.isEmpty()) {
            doc.addProperty("player", player);
        }
        if (!reason.isEmpty()) {
            doc.addProperty("reason", reason);
        }
        doc.addProperty("shot", this.screenshotBox.selected());
        doc.addProperty("handImage", this.handImageAvailable && this.handImageBox.selected());
        try {
            if (this.submitSender != null) {
                this.submitSender.send(doc.toString());
            }
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().warn("Failed to send report submit.", e);
        }
        this.onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            this.onSubmit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // NOTE: do NOT call extractBackground here, see ImageUploadScreen.
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        extractor.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
