package de.davidsw.diawarsclient;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Server-requested text input dialog.
 *
 * <p>Shows one text field (title, label and hint come from the
 * {@code diawars:text_input} payload) plus Send/Cancel buttons. Submitting
 * sends {@code <command prefix><typed text>} via
 * {@code ClientPacketListener#sendCommand}, so the server behaves exactly
 * like the player had typed the command in chat. Enter submits, Esc closes.
 * The field length is bounded by the server so the command packet stays
 * inside the vanilla limit.</p>
 */
public class TextInputScreen extends Screen {
    private static final Gson GSON = new Gson();
    private static final int FIELD_HEIGHT = 20;

    /** Command prefix without leading slash, e.g. {@code "bug report "}. */
    private final String command;
    private final String label;
    private final String hint;
    private final int maxLength;

    private EditBox input;
    private Button sendButton;

    /**
     * Parses the {@code diawars:text_input} JSON document and builds the
     * screen, or returns null when the payload is broken (then the dialog
     * simply does not open).
     */
    public static TextInputScreen parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject doc = GSON.fromJson(json, JsonObject.class);
            if (doc == null) {
                return null;
            }
            String command = stringOrDefault(doc, "command", "").trim();
            if (command.isEmpty()) {
                return null;
            }
            String title = stringOrDefault(doc, "title", "").trim();
            String label = stringOrDefault(doc, "label", "").trim();
            String hint = stringOrDefault(doc, "hint", "").trim();
            int maxLength = intOrDefault(doc, "maxLength", 100);
            return new TextInputScreen(command,
                    title.isEmpty() ? (label.isEmpty() ? "Eingabe" : label) : title,
                    label, hint, maxLength);

        } catch (Exception e) {
            return null;
        }
    }

    private TextInputScreen(String command, String title, String label, String hint, int maxLength) {
        super(Component.literal(title));
        this.command = command;
        this.label = label;
        this.hint = hint;
        this.maxLength = Math.max(1, maxLength);
    }

    private static String stringOrDefault(JsonObject doc, String key, String fallback) {
        if (doc.has(key) && doc.get(key).isJsonPrimitive()) {
            return doc.get(key).getAsString();
        }
        return fallback;
    }

    private static int intOrDefault(JsonObject doc, String key, int fallback) {
        if (doc.has(key) && doc.get(key).isJsonPrimitive() && doc.get(key).getAsJsonPrimitive().isNumber()) {
            return doc.get(key).getAsInt();
        }
        return fallback;
    }

    @Override
    protected void init() {
        int margin = 20;

        if (!this.label.isEmpty()) {
            this.addRenderableWidget(new StringWidget(margin, 40, this.width - margin * 2, 10,
                    Component.literal(this.label), this.font));
        }
        this.input = new EditBox(this.font, margin, 56, this.width - margin * 2, FIELD_HEIGHT,
                Component.literal(""));
        this.input.setMaxLength(this.maxLength);
        if (!this.hint.isEmpty()) {
            this.input.setHint(Component.literal(this.hint));
        }
        this.input.setResponder(value -> this.updateSendButton());
        this.addRenderableWidget(this.input);

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
        this.setInitialFocus(this.input);
    }

    private void updateSendButton() {
        if (this.sendButton != null && this.input != null) {
            this.sendButton.active = !this.input.getValue().isBlank();
        }
    }

    @Override
    public void tick() {
        this.updateSendButton();
    }

    private void onSubmit() {
        String value = this.input.getValue().trim();
        if (value.isEmpty()) {
            return;
        }
        try {
            if (this.minecraft.getConnection() != null) {
                this.minecraft.getConnection().sendCommand(this.command + value);
            }
        } catch (Exception e) {
            LogUtils.getLogger().warn("Failed to send text input command.", e);
        }
        this.onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
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
