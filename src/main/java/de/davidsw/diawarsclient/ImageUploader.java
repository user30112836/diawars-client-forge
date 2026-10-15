package de.davidsw.diawarsclient;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Forge port of the Fabric {@code ImageUploader}.
 *
 * <p>Reads local image files and splits them into small {@code diawars:image}
 * payload chunks at full resolution (see {@link ImagePayload}). Prepared
 * chunks are queued and sent paced (a few per client tick) by the client tick
 * handler, so uploads never burst the connection.</p>
 */
public final class ImageUploader {
    /**
     * Map thumbnail resolution: the server derives its 128x128 map view from
     * whatever is uploaded, so uploads stay square (see the crop screen) but
     * keep their full resolution for modded clients.
     */
    public static final int MAP_SIZE = 128;

    /** Max stored image dimension (mirrors the server limit). */
    public static final int MAX_DIMENSION = 8192;

    /**
     * Max PNG bytes per upload. Stays below the server cap (64 MB decoded) so
     * a prepared upload is never rejected for its size. Transfers agree on
     * the part count up front (offer/accept), so images are effectively as
     * large as the player wants.
     */
    public static final long MAX_UPLOAD_BYTES = 63_000_000L;

    /** How long an offer may go unanswered before the upload fails. */
    private static final long OFFER_TIMEOUT_MILLIS = 30_000;

    /** Max base64 chars per chunk: keeps every payload well below packet limits. */
    public static final int MAX_CHUNK_CHARS = 20_000;

    /** Max accepted source file size (protects against huge files / OOM). */
    public static final long MAX_SOURCE_BYTES = 16L * 1024 * 1024;

    /** Folder inside the game directory where players drop their images. */
    public static final String FOLDER_NAME = "diawars-images";

    private static final String[] EXTENSIONS = {"png", "jpg", "jpeg", "gif", "bmp"};
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final Gson GSON = new Gson();

    private static final Deque<String> QUEUE = new ArrayDeque<>();

    private ImageUploader() {
    }

    /** Loader-specific sender for {@code diawars:image_offer} documents. */
    public interface OfferSender {
        boolean sendOffer(String json);
    }

    private static OfferSender offerSender;
    private static volatile PreparedUpload pendingUpload;
    private static volatile long offerSentAt;
    private static volatile boolean waitingForAccept;
    private static volatile String offerError;

    public static void initOfferSender(OfferSender sender) {
        offerSender = sender;
    }

    public record PreparedUpload(String fileName, List<String> chunks, long bytes, String hash) {
    }

    /** Square crop area in source image pixels. */
    public record CropArea(int x, int y, int size) {
    }

    public static File getFolder(Minecraft minecraft) {
        File dir = new File(minecraft.gameDirectory, FOLDER_NAME);
        dir.mkdirs();
        return dir;
    }

    /** System pictures folder; never created, may be missing. */
    public static File getPicturesFolder() {
        return resolveUserDir("XDG_PICTURES_DIR", "Pictures");
    }

    /** System downloads folder; never created, may be missing. */
    public static File getDownloadsFolder() {
        return resolveUserDir("XDG_DOWNLOAD_DIR", "Downloads");
    }

    /**
     * Resolves an OS user directory (pictures, downloads, ...).
     *
     * <p>On Linux the XDG config ({@code ~/.config/user-dirs.dirs}) wins, so
     * localized names (e.g. German {@code ~/Bilder}, French {@code ~/Images})
     * and custom locations just work. Everywhere else (Windows, macOS, or a
     * missing/disabled XDG entry) it falls back to {@code ~/&lt;name&gt;},
     * which matches the Windows/macOS defaults.</p>
     */
    private static File resolveUserDir(String xdgKey, String fallbackName) {
        String home = System.getProperty("user.home");
        String configured = readUserDirsEntry(new File(home, ".config/user-dirs.dirs"), xdgKey);
        if (configured != null) {
            if (configured.equals("$HOME")) {
                return new File(home);
            }
            if (configured.startsWith("$HOME/")) {
                return new File(home, configured.substring("$HOME/".length()));
            }
            File absolute = new File(configured);
            if (absolute.isAbsolute()) {
                return absolute;
            }
        }
        return new File(home, fallbackName);
    }

    /** Reads one {@code KEY="value"} entry from an XDG user-dirs file, or null. */
    private static String readUserDirsEntry(File configFile, String key) {
        if (!configFile.isFile()) {
            return null;
        }
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(
                configFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.startsWith(key + "=")) {
                    continue;
                }
                String value = line.substring(key.length() + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (IOException | SecurityException e) {
            return null;
        }
        return null;
    }

    public static List<File> listImages(File folder) {
        File[] files = folder.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String ext : EXTENSIONS) {
                if (lower.endsWith("." + ext)) {
                    return true;
                }
            }
            return false;
        });
        if (files == null) {
            return List.of();
        }
        List<File> result = new ArrayList<>();
        for (File file : files) {
            if (file.isFile()) {
                result.add(file);
            }
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    /**
     * Derives a server-compatible upload name from a file, or null if
     * impossible. Overlong names are shortened and rejected characters are
     * dropped so the autofill still offers something usable (editable).
     */
    public static String deriveName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String direct = sanitize(name);
        if (direct != null) {
            return direct;
        }
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (isNameChar(c)) {
                cleaned.append(c);
            }
        }
        String shortened = cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned.toString();
        return sanitize(shortened);
    }

    private static boolean isNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
    }

    /**
     * Returns the sanitized upload name, or null when invalid.
     * Mirrors the server rules: letters, digits, {@code . _ -}, max 32 chars,
     * and {@code all} is reserved.
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (!NAME_PATTERN.matcher(name).matches()) {
            return null;
        }
        if (name.equalsIgnoreCase("all")) {
            return null;
        }
        if (name.equals(".") || name.equals("..")) {
            return null;
        }
        return name;
    }

    /**
     * Reads and chunks an image at full resolution.
     *
     * @param file       source image on the local file system
     * @param wantedName upload name, or null/blank to derive it from the file
     * @throws IOException              when the file cannot be read as an image
     * @throws IllegalArgumentException when the upload name is invalid
     */
    public static PreparedUpload prepare(File file, String wantedName) throws IOException {
        return prepare(file, wantedName, null);
    }

    /**
     * Reads, crops (square) and chunks an image at full resolution.
     *
     * <p>The cropped square is kept at its native resolution (capped at
     * {@value #MAX_DIMENSION}px and shrunk until the PNG fits
     * {@value #MAX_UPLOAD_BYTES} bytes): the server stores it as-is for
     * modded clients and derives the 128x128 map thumbnail itself, so
     * vanilla and Bedrock players keep seeing maps.
     *
     * @param file       source image on the local file system
     * @param wantedName upload name, or null/blank to derive it from the file
     * @param crop       square area to use, or null for the whole image
     * @throws IOException              when the file cannot be read as an image
     * @throws IllegalArgumentException when the upload name is invalid
     */
    public static PreparedUpload prepare(File file, String wantedName, CropArea crop) throws IOException {
        validateSource(file);
        String name = resolveName(file, wantedName);

        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Datei ist kein lesbares Bild (PNG/JPG/GIF/BMP erwartet).");
        }
        if (crop != null) {
            image = applyCrop(image, crop);
        }

        BufferedImage fitted = fitForUpload(image);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(fitted, "png", out)) {
            throw new IOException("Bild konnte nicht kodiert werden.");
        }
        byte[] png = out.toByteArray();
        String base64 = Base64.getEncoder().encodeToString(png);

        List<String> chunks = new ArrayList<>();
        int total = (base64.length() + MAX_CHUNK_CHARS - 1) / MAX_CHUNK_CHARS;
        for (int i = 0; i < total; i++) {
            String part = base64.substring(i * MAX_CHUNK_CHARS, Math.min(base64.length(), (i + 1) * MAX_CHUNK_CHARS));
            JsonObject json = new JsonObject();
            json.addProperty("file", name);
            json.addProperty("part", i);
            json.addProperty("total", total);
            json.addProperty("data", part);
            chunks.add(GSON.toJson(json));
        }
        return new PreparedUpload(name, chunks, png.length, sha256Hex(png));
    }

    /**
     * Starts an upload: sends the offer so server and client agree on the
     * part count first; chunks queue up once the server accepts. Returns
     * false (and records an error) when another upload is still active.
     */
    public static synchronized boolean requestUpload(PreparedUpload upload) {
        if (waitingForAccept || hasQueued()) {
            offerError = "Bereits ein Upload aktiv, bitte warten.";
            return false;
        }
        if (offerSender == null) {
            offerError = "Keine Serververbindung für den Upload.";
            return false;
        }
        JsonObject offer = new JsonObject();
        offer.addProperty("file", upload.fileName());
        offer.addProperty("bytes", upload.bytes());
        offer.addProperty("total", upload.chunks().size());
        offer.addProperty("hash", upload.hash());
        if (!offerSender.sendOffer(GSON.toJson(offer))) {
            offerError = "Der Upload konnte nicht gestartet werden (keine Verbindung?).";
            return false;
        }
        pendingUpload = upload;
        offerSentAt = System.currentTimeMillis();
        waitingForAccept = true;
        return true;
    }

    /**
     * Handles one {@code diawars:image_accept} document (client thread only):
     * queues the chunks on accept, records the error otherwise.
     */
    public static void handleAccept(String json) {
        PreparedUpload pending = pendingUpload;
        if (pending == null) {
            return;
        }
        JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("file")
                    || !pending.fileName().equals(root.get("file").getAsString())) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        pendingUpload = null;
        waitingForAccept = false;
        boolean accepted = root.has("accepted") && root.get("accepted").getAsBoolean();
        if (accepted) {
            enqueue(pending.chunks());
        } else {
            String error = root.has("error") && root.get("error").isJsonPrimitive()
                    ? root.get("error").getAsString() : "Der Server hat den Upload abgelehnt.";
            // Server messages use MiniMessage tags; the screen shows plain text.
            offerError = error.replaceAll("<[^>]*>", "");
        }
    }

    /** Fails a long-unanswered offer; call once per client tick. */
    public static void tickOffer() {
        if (waitingForAccept && System.currentTimeMillis() - offerSentAt > OFFER_TIMEOUT_MILLIS) {
            pendingUpload = null;
            waitingForAccept = false;
            offerError = "Der Server antwortet nicht auf den Upload.";
        }
    }

    public static boolean isWaitingForAccept() {
        return waitingForAccept;
    }

    /** Returns and clears the last offer error, or null. */
    public static String consumeOfferError() {
        String error = offerError;
        offerError = null;
        return error;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static synchronized void enqueue(List<String> chunks) {
        QUEUE.addAll(chunks);
    }

    public static synchronized boolean hasQueued() {
        return !QUEUE.isEmpty();
    }

    public static synchronized int queuedCount() {
        return QUEUE.size();
    }

    public static synchronized String poll() {
        return QUEUE.poll();
    }

    public static synchronized void clearQueue() {
        QUEUE.clear();
        pendingUpload = null;
        waitingForAccept = false;
    }

    /** Checks file presence and size without decoding (for pre-validation). */
    public static void validateSource(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("Datei nicht gefunden: " + (file == null ? "?" : file.getPath()));
        }
        if (file.length() > MAX_SOURCE_BYTES) {
            throw new IOException("Datei ist zu groß (max. 16 MB).");
        }
    }

    /** Resolves the effective upload name or throws when invalid. */
    public static String resolveName(File file, String wantedName) {
        String name = wantedName == null || wantedName.isBlank() ? deriveName(file) : sanitize(wantedName);
        if (name == null) {
            throw new IllegalArgumentException("Ungültiger Name! Erlaubt: Buchstaben, Zahlen, . _ - (max. 32 Zeichen, nicht \"all\").");
        }
        return name;
    }

    /** Shrinks the image until it fits the server upload cap (never below 128px). */
    private static BufferedImage fitForUpload(BufferedImage image) throws IOException {
        BufferedImage current = image;
        int largest = Math.max(current.getWidth(), current.getHeight());
        if (largest > MAX_DIMENSION) {
            current = scale(current, MAX_DIMENSION / (double) largest);
        }
        while (pngSize(current) > MAX_UPLOAD_BYTES) {
            if (Math.min(current.getWidth(), current.getHeight()) <= MAP_SIZE) {
                throw new IOException("Bild ist zu groß (max. 64 MB nach Verkleinerung).");
            }
            current = scale(current, 0.75);
        }
        return current;
    }

    private static BufferedImage scale(BufferedImage image, double factor) {
        int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static long pngSize(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("Bild konnte nicht kodiert werden.");
        }
        return out.size();
    }

    /** Cuts the square crop area out of the image (clamped to the bounds). */
    private static BufferedImage applyCrop(BufferedImage image, CropArea crop) throws IOException {
        int size = Math.min(crop.size(), Math.min(image.getWidth(), image.getHeight()));
        if (size < 1) {
            throw new IOException("Ungültiger Bildausschnitt.");
        }
        int x = Math.max(0, Math.min(crop.x(), image.getWidth() - size));
        int y = Math.max(0, Math.min(crop.y(), image.getHeight() - size));
        BufferedImage sub = image.getSubimage(x, y, size, size);
        BufferedImage copy = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(sub, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }
}
