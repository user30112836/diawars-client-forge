package de.davidsw.diawarsclient;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Forge port of the Fabric {@code WhitelistScreen}: the server-requested mod
 * whitelist dialog shows every mod of the server's
 * allowed-mods section by
 * allowed-mods section by display name (scrollable list) and lets the player
 * request a new mod — entering its display name is enough. Submitted requests
 * go to {@code diawars:whitelist_request} (see {@link WhitelistRequestPayload})
 * and are answered in chat by the server. Enter submits, Esc closes.
 *
 * <p>The dialog JSON ({@code {"mods": [...], "installed": [...],
 * "requestMax": 40}}) comes from the {@code diawars:whitelist_dialog}
 * payload. {@code installed} lists the display names of the allowed mods the
 * player already has loaded; those rows are drawn yellow and can be hidden
 * with the toggle button. Requests are optional: the Send button only submits
 * when the field has content.</p>
 */
public class WhitelistScreen extends Screen {
    private static final Gson GSON = new Gson();
    private static final int LIST_ITEM_HEIGHT = 20;
    private static final int FIELD_HEIGHT = 20;
    private static final int TOGGLE_WIDTH = 170;

    /** Sends the request JSON on the whitelist_request channel; false = dropped. */
    public interface RequestSender {
        boolean send(String json);
    }

    private static volatile RequestSender requestSender;

    public static void initRequestSender(RequestSender sender) {
        requestSender = sender;
    }

    private final List<String> mods;
    /** Lowercased display names the player already has loaded (yellow rows). */
    private final Set<String> installed;
    private final int requestMax;

    private ModList modList;
    private StringWidget headerLabel;
    private EditBox requestBox;
    private Button requestButton;
    private boolean hideInstalled;

    /**
     * Parses the {@code diawars:whitelist_dialog} JSON document and builds the
     * screen, or returns null when the payload is broken (then the dialog
     * simply does not open).
     */
    public static WhitelistScreen parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject doc = GSON.fromJson(json, JsonObject.class);
            if (doc == null) {
                return null;
            }
            List<String> mods = new ArrayList<>();
            JsonArray modsArray = doc.getAsJsonArray("mods");
            if (modsArray != null) {
                for (var element : modsArray) {
                    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        mods.add(element.getAsString());
                    }
                }
            }
            int requestMax = doc.has("requestMax") && doc.get("requestMax").getAsJsonPrimitive().isNumber()
                    ? doc.get("requestMax").getAsInt() : 40;
            Set<String> installed = new HashSet<>();
            JsonArray installedArray = doc.getAsJsonArray("installed");
            if (installedArray != null) {
                for (var element : installedArray) {
                    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        installed.add(element.getAsString().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return new WhitelistScreen(mods, installed, requestMax);
        } catch (Exception e) {
            LogUtils.getLogger().warn("Failed to parse whitelist dialog.", e);
            return null;
        }
    }

    private WhitelistScreen(List<String> mods, Set<String> installed, int requestMax) {
        super(Component.literal("Erlaubte Mods"));
        this.mods = mods;
        this.installed = installed;
        this.requestMax = Math.max(1, requestMax);
    }

    @Override
    protected void init() {
        int margin = 20;
        // init() re-runs on window resize: keep the typed request text.
        String savedRequest = this.requestBox != null ? this.requestBox.getValue() : "";
        this.clearWidgets();

        this.headerLabel = new StringWidget(margin, 28, this.width - margin * 2, 10,
                Component.literal(""), this.font);
        this.addRenderableWidget(this.headerLabel);

        int listTop = 44;
        int listBottom = this.height - 108;
        this.modList = new ModList(this.minecraft, this.width, listBottom - listTop, listTop);
        this.addRenderableWidget(this.modList);

        this.addRenderableWidget(new StringWidget(margin, this.height - 76, this.width - margin * 2, 10,
                Component.literal("Neue Mod anfragen (nur der Name):"), this.font));
        this.requestBox = new EditBox(this.font, margin, this.height - 60, this.width - margin * 2, FIELD_HEIGHT,
                Component.literal(""));
        this.requestBox.setMaxLength(this.requestMax);
        this.requestBox.setHint(Component.literal("Anzeigename der Mod"));
        this.requestBox.setValue(savedRequest);
        this.requestBox.setResponder(value -> this.updateRequestButton());
        this.addRenderableWidget(this.requestBox);

        this.addRenderableWidget(Button.builder(Component.literal(this.toggleLabel()),
                        button -> {
                            this.hideInstalled = !this.hideInstalled;
                            button.setMessage(Component.literal(this.toggleLabel()));
                            this.refreshList();
                        })
                .bounds((this.width - TOGGLE_WIDTH) / 2, this.height - 102, TOGGLE_WIDTH, 20)
                .build());

        int buttonWidth = 110;
        int gap = 8;
        int buttonX = (this.width - buttonWidth * 2 - gap) / 2;
        int buttonY = this.height - 30;
        this.requestButton = Button.builder(Component.literal("Anfrage senden"),
                button -> this.onSubmit()).bounds(buttonX, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(this.requestButton);
        this.addRenderableWidget(Button.builder(Component.literal("Schließen"),
                button -> this.onClose()).bounds(buttonX + buttonWidth + gap, buttonY, buttonWidth, 20).build());

        this.refreshList();
        this.updateRequestButton();
        this.setInitialFocus(this.requestBox);
    }

    /** Applies the installed filter to the list and refreshes the header count. */
    private void refreshList() {
        List<ModList.Entry> entries = new ArrayList<>();
        for (String mod : this.mods) {
            boolean installed = this.installed.contains(mod.toLowerCase(Locale.ROOT));
            if (this.hideInstalled && installed) {
                continue;
            }
            entries.add(new ModList.Entry(this.font, mod, installed));
        }
        this.modList.replaceEntries(entries);
        if (this.headerLabel != null) {
            String count = this.hideInstalled
                    ? entries.size() + " von " + this.mods.size()
                    : String.valueOf(this.mods.size());
            this.headerLabel.setMessage(Component.literal(
                    "Installierbare Mods (" + count + "):"));
        }
    }

    private String toggleLabel() {
        return this.hideInstalled ? "Installierte anzeigen" : "Installierte ausblenden";
    }

    private void updateRequestButton() {
        if (this.requestButton != null && this.requestBox != null) {
            this.requestButton.active = !this.requestBox.getValue().isBlank();
        }
    }

    @Override
    public void tick() {
        this.updateRequestButton();
    }

    private void onSubmit() {
        String name = this.requestBox.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        JsonObject doc = new JsonObject();
        doc.addProperty("name", name);
        try {
            if (this.requestSender != null) {
                this.requestSender.send(doc.toString());
            }
        } catch (Exception e) {
            LogUtils.getLogger().warn("Failed to send whitelist request.", e);
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

    private static final class ModList extends ObjectSelectionList<ModList.Entry> {
        ModList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, LIST_ITEM_HEIGHT);
        }

        static final class Entry extends ObjectSelectionList.Entry<ModList.Entry> {
            private final Font font;
            private final String modName;
            private final boolean installed;

            Entry(Font font, String modName, boolean installed) {
                this.font = font;
                this.modName = modName;
                this.installed = installed;
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.modName);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor extractor, int x, int y, boolean hovered, float partialTick) {
                // NOTE: use the entry's own content coords like vanilla does;
                // the x/y parameters are not the row position.
                extractor.text(this.font, Component.literal(shortenToFit(this.modName)),
                        this.getContentX() + 2, this.getContentY() + 6,
                        this.installed ? 0xFFFFFF55 : 0xFFFFFFFF);
            }

            /** Cuts the mod name with "..." so it stays inside the list. */
            private String shortenToFit(String name) {
                int maxWidth = this.getContentWidth() - 4;
                if (this.font.width(name) <= maxWidth) {
                    return name;
                }
                while (name.length() > 1 && this.font.width(name + "...") > maxWidth) {
                    name = name.substring(0, name.length() - 1);
                }
                return name + "...";
            }
        }
    }
}
