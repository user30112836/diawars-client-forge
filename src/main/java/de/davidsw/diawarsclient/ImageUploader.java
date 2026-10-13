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
 * <p>Reads local image files, resizes them to 128x128 and splits them into
 * small {@code diawars:image} payload chunks (see {@link ImagePayload}).
 * Prepared chunks are queued and sent paced (a few per client tick) by the
 * client tick handler, so uploads never burst the connection.</p>
 */
public final class ImageUploader {
    /** Map resolution: uploads are always resized to this (square) size. */
    public static final int IMAGE_SIZE = 128;

    /** Max base64 chars per chunk: keeps every payload well below packet limits. */
    public static final int MAX_CHUNK_CHARS = 20_000;

    /** Max accepted source file size (protects against huge files / OOM). */
    public static final long MAX_SOURCE_BYTES = 16L * 1024 * 1024;

    /** Folder inside the game directory where players drop their images. */
    public static final String FOLDER_NAME = "diawars-images";

    private static final String[] EXTENSIONS = {"png", "jpg", "jpeg", "webp", "gif", "bmp"};
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final Gson GSON = new Gson();

    private static final Deque<String> QUEUE = new ArrayDeque<>();

    private ImageUploader() {
    }

    public record PreparedUpload(String fileName, List<String> chunks) {
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
     * Reads, resizes (128x128) and chunks an image.
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
     * Reads, crops (square), resizes (128x128) and chunks an image.
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
            throw new IOException("Datei ist kein lesbares Bild (PNG/JPG erwartet).");
        }
        if (crop != null) {
            image = applyCrop(image, crop);
        }

        BufferedImage resized = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, IMAGE_SIZE, IMAGE_SIZE, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(resized, "png", out)) {
            throw new IOException("Bild konnte nicht kodiert werden.");
        }
        String base64 = Base64.getEncoder().encodeToString(out.toByteArray());

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
        return new PreparedUpload(name, chunks);
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
