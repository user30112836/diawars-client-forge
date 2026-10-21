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
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9 ._()-]{1,32}");
    private static final Gson GSON = new Gson();

    private static final Deque<String> QUEUE = new ArrayDeque<>();

    /** Max side length of one grid cell: keeps 25-cell encodings fast. */
    private static final int SPLIT_MAX_CELL = 2048;

    /** How long a name check may go unanswered before the split fails. */
    private static final long CHECK_TIMEOUT_MILLIS = 15_000;

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
    /** Total chunk count of the newly offered upload, for the HUD overlay. */
    private static volatile int pendingChunkTotal;
    /** Last upload error text for the HUD overlay, or null. */
    private static volatile String overlayError;
    /** When the overlay error was recorded (see {@link #overlayErrorSince}). */
    private static volatile long overlayErrorAt;

    /** Loader-specific sender for {@code diawars:image_check} documents. */
    public interface CheckSender {
        boolean sendCheck(String json);
    }

    private static volatile CheckSender checkSender;

    public static void initCheckSender(CheckSender sender) {
        checkSender = sender;
    }

    /** Runs a task on the client thread (loader hook, see {@link #initMainRunner}). */
    public interface MainRunner {
        void run(Runnable task);
    }

    private static volatile MainRunner mainRunner;

    /**
     * Installs the loader's client-thread hook. Preparing an upload happens
     * on the {@link #PREPARE_EXECUTOR prepare thread}; sending the offer
     * must go back through this hook (network sends happen on the client
     * thread).
     */
    public static void initMainRunner(MainRunner runner) {
        mainRunner = runner;
    }

    /**
     * Single background thread for everything expensive around uploads:
     * image decoding/encoding (offers, split segments) and the crop
     * preview load. One thread keeps peak memory bounded (16 MB sources).
     */
    private static final java.util.concurrent.ExecutorService PREPARE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "diawars-image-prepare");
                thread.setDaemon(true);
                return thread;
            });

    /** Runs a one-off task (e.g. image decoding) on the prepare thread. */
    public static void runBackground(Runnable job) {
        PREPARE_EXECUTOR.execute(job);
    }

    /**
     * Split upload batch state: the name check runs first; once the server
     * confirms every name, segments are prepared and offered one by one
     * (see {@link #tickBatch()}). All batch fields are guarded by the class
     * lock.
     */
    private static List<PreparedUpload> batch;
    private static int batchNext;
    private static volatile boolean checkingNames;
    private static volatile long checkSentAt;

    /** Split parameters kept while the server pre-checks the names. */
    private record SplitRequest(File file, String baseName, CropArea crop, int rows, int cols) {
    }

    private static volatile SplitRequest splitRequest;
    /** True while the prepare thread is decoding/encoding an upload. */
    private static volatile boolean preparing;
    /** Bumped whenever upload state resets; stale prepare jobs are dropped. */
    private static volatile long prepareEpoch;

    public static void initOfferSender(OfferSender sender) {
        offerSender = sender;
    }

    /** Plain-text error sink for uploads without a screen (report screenshots). */
    public interface ErrorFeedback {
        void show(String message);
    }

    private static volatile ErrorFeedback errorFeedback;

    public static void initErrorFeedback(ErrorFeedback sink) {
        errorFeedback = sink;
    }

    /**
     * Records an upload error: report-shot uploads go straight into chat
     * (they have no screen to show it on), everything else lands in
     * {@link #consumeOfferError()} like before.
     */
    private static void recordError(PreparedUpload upload, String message) {
        overlayError = message;
        overlayErrorAt = System.currentTimeMillis();
        if (upload != null && upload.report() != null) {
            ErrorFeedback sink = errorFeedback;
            if (sink != null) {
                sink.show(message);
                return;
            }
        }
        offerError = message;
    }

    /** Records a split error on the split upload's screen sink. */
    private static void failBatch(String message) {
        overlayError = message;
        overlayErrorAt = System.currentTimeMillis();
        offerError = message;
    }

    /** Discards the running batch (upload cancelled). */
    private static void clearBatch() {
        batch = null;
        batchNext = 0;
    }

    /** Cancels a running split upload at any phase (check, prepare, batch). */
    private static synchronized void abortSplit() {
        clearBatch();
        splitRequest = null;
        preparing = false;
    }

    /**
     * One prepared upload. {@code report} is non-null only for report
     * screenshot attachments (see {@link ReportScreenshot}): those uploads
     * are tagged with a {@code report} field in offer and chunks so the
     * server stores them under the report instead of the player's gallery.
     */
    public record PreparedUpload(String fileName, List<String> chunks, long bytes, String hash, String report) {
        public PreparedUpload(String fileName, List<String> chunks, long bytes, String hash) {
            this(fileName, chunks, bytes, hash, null);
        }
    }

    /**
     * Rectangular crop area in source image pixels. May extend beyond the
     * image (negative {@code x}/{@code y} or a size larger than a
     * dimension); pixels outside the image become transparent.
     */
    public record CropArea(int x, int y, int width, int height) {
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
                || c == '.' || c == '_' || c == '-' || c == '(' || c == ')' || c == ' ';
    }

    /**
     * Returns the sanitized upload name, or null when invalid.
     * Mirrors the server rules: letters, digits, {@code . _ - ( )} and spaces,
     * max 32 chars, and {@code all} is reserved.
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
     * Reads, crops and chunks an image at full resolution.
     *
     * <p>The cropped area is kept at its native resolution (capped at
     * {@value #MAX_DIMENSION}px and shrunk until the PNG fits
     * {@value #MAX_UPLOAD_BYTES} bytes): the server stores it as-is for
     * modded clients and derives the 128x128 map thumbnail itself, so
     * vanilla and Bedrock players keep seeing maps.
     *
     * @param file       source image on the local file system
     * @param wantedName upload name, or null/blank to derive it from the file
     * @param crop       area to use, or null for the whole image
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
        return encode(image, name, null);
    }

    /** Max squares per direction of a split upload (≤ 25 segments total). */
    public static final int MAX_GRID = 5;

    /**
     * Prepares every cell of a rows×cols split of the given crop area as its
     * own upload, named {@code base (k)} row-major (top-left is 1). Cells are
     * exact squares: {@code whole.width} must be {@code cols·cell} and
     * {@code whole.height} {@code rows·cell} for one shared cell side (the
     * crop screen builds such a rectangle). Each cell is capped at
     * {@value #SPLIT_MAX_CELL}px per side to keep encodings fast. The source
     * is decoded once; the segment names are pre-checkable before anything
     * uploads (see {@link #requestSplitUpload}).
     *
     * @param whole rectangular crop area covering the entire grid (see
     *              ImageCropScreen)
     * @throws IOException              when the file cannot be read as an image
     * @throws IllegalArgumentException when a name/grid/crop is invalid
     */
    public static List<PreparedUpload> prepareSplit(File file, String baseName, CropArea whole, int rows, int cols) throws IOException {
        if (rows < 1 || cols < 1 || rows > MAX_GRID || cols > MAX_GRID || (rows == 1 && cols == 1)) {
            throw new IllegalArgumentException("Ungültige Aufteilung (1×1 ist kein Split).");
        }
        String base = sanitize(baseName);
        if (base == null) {
            throw new IllegalArgumentException("Ungültiger Name! Erlaubt: Buchstaben, Zahlen, . _ - ( ) und Leerzeichen (max. 32 Zeichen, nicht \"all\").");
        }
        if (whole == null || whole.width() < 1 || whole.height() < 1
                || whole.width() % cols != 0 || whole.height() % rows != 0
                || whole.width() / cols != whole.height() / rows) {
            throw new IllegalArgumentException("Ungültige Aufteilung.");
        }
        int cell = whole.width() / cols;
        validateSource(file);

        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Datei ist kein lesbares Bild (PNG/JPG/GIF/BMP erwartet).");
        }
        image = applyCrop(image, whole);

        boolean keepAlpha = image.getColorModel().hasAlpha();
        List<PreparedUpload> segments = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                BufferedImage cellImage = new BufferedImage(cell, cell,
                        keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                Graphics2D g = cellImage.createGraphics();
                try {
                    g.drawImage(image, 0, 0, cell, cell, col * cell, row * cell,
                            (col + 1) * cell, (row + 1) * cell, null);
                } finally {
                    g.dispose();
                }
                if (cell > SPLIT_MAX_CELL) {
                    cellImage = scale(cellImage, (double) SPLIT_MAX_CELL / cell);
                }
                segments.add(encode(cellImage, segmentName(base, row * cols + col + 1), null));
            }
        }
        return segments;
    }

    /**
     * Prepares a report screenshot upload from an already-captured PNG file
     * (see {@link ReportScreenshot}): no name derivation, no crop — the file
     * and name come from the server request ({@code report-<id>}). Chunks and
     * offer are tagged with the report id so the server stores the image
     * under the report instead of the player's gallery.
     *
     * @throws IOException              when the file cannot be read as an image
     * @throws IllegalArgumentException when the upload name or report is invalid
     */
    public static PreparedUpload prepareShot(File pngFile, String wantedName, String report) throws IOException {
        if (report == null || report.isBlank()) {
            throw new IllegalArgumentException("Screenshot ohne Report-ID.");
        }
        String name = sanitize(wantedName);
        if (name == null) {
            throw new IllegalArgumentException("Ungültiger Screenshotname.");
        }
        BufferedImage image = ImageIO.read(pngFile);
        if (image == null) {
            throw new IOException("Screenshot ist kein lesbares Bild.");
        }
        return encode(image, name, report);
    }

    /** Fits, encodes and chunks an image; {@code report} tags report uploads. */
    private static PreparedUpload encode(BufferedImage image, String name, String report) throws IOException {
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
            if (report != null) {
                json.addProperty("report", report);
            }
            chunks.add(GSON.toJson(json));
        }
        return new PreparedUpload(name, chunks, png.length, sha256Hex(png), report);
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
        if (upload.report() != null) {
            offer.addProperty("report", upload.report());
        }
        if (!offerSender.sendOffer(GSON.toJson(offer))) {
            offerError = "Der Upload konnte nicht gestartet werden (keine Verbindung?).";
            return false;
        }
        pendingUpload = upload;
        offerSentAt = System.currentTimeMillis();
        waitingForAccept = true;
        pendingChunkTotal = upload.chunks().size();
        overlayError = null;
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
            // Server messages use MiniMessage tags; chat/screen shows plain text.
            recordError(pending, error.replaceAll("<[^>]*>", ""));
            // A rejected segment cancels the rest of a split upload.
            abortSplit();
        }
    }

    /** Fails a long-unanswered offer; call once per client tick. */
    public static void tickOffer() {
        if (waitingForAccept && System.currentTimeMillis() - offerSentAt > OFFER_TIMEOUT_MILLIS) {
            PreparedUpload pending = pendingUpload;
            pendingUpload = null;
            waitingForAccept = false;
            recordError(pending, "Der Server antwortet nicht auf den Upload.");
            // A segment that never gets accepted cancels the rest of a split.
            abortSplit();
        }
    }

    public static boolean isWaitingForAccept() {
        return waitingForAccept;
    }

    /**
     * Overall progress of the running upload as fraction 0..1, or -1 while
     * nothing uploads or the progress is indeterminate (e.g. no answer to
     * the offer yet). Feeds the bottom-right HUD overlay.
     */
    public static synchronized double uploadProgress() {
        if (checkingNames) {
            return -1.0;
        }
        int segments = batch != null ? batch.size() : (waitingForAccept || !QUEUE.isEmpty() ? 1 : 0);
        if (segments == 0) {
            return -1.0;
        }
        if (waitingForAccept) {
            // Offer sent, no answer yet: the segments before this one moved.
            return batch != null ? batchNext / (double) segments : -1.0;
        }
        int total = pendingChunkTotal;
        if (total <= 0) {
            return -1.0;
        }
        double intra = Math.min(1.0, (total - QUEUE.size()) / (double) total);
        return batch != null ? (batchNext - 1 + intra) / segments : Math.min(0.995, intra);
    }

    /**
     * Last upload error for the HUD overlay, if it was recorded at or after
     * {@code sinceMillis}, else null.
     */
    public static String overlayErrorSince(long sinceMillis) {
        String error = overlayError;
        return error != null && overlayErrorAt >= sinceMillis ? error : null;
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

    /**
     * Starts a split upload: first every segment name is sent to the server
     * for a pre-check (see {@link ImageCheckPayload}); segments upload one by
     * one only after the server confirms every name — if any name is taken,
     * invalid or the limit has no headroom, the whole upload is cancelled
     * with an error (nothing is uploaded). Returns false (and records an
     * error) when another upload is still active.
     */
    /** True while any upload phase is running (prepare, check, offer, send). */
    private static synchronized boolean uploadBusy() {
        return checkingNames || waitingForAccept || !QUEUE.isEmpty() || batch != null || preparing;
    }

    /** Marks the prepare phase as running and returns its epoch. */
    private static synchronized long beginPrepare() {
        preparing = true;
        overlayError = null;
        return ++prepareEpoch;
    }

    private static synchronized void preparingDone() {
        preparing = false;
    }

    /** True while the prepare thread is decoding/encoding an upload. */
    public static synchronized boolean isPreparing() {
        return preparing;
    }

    private static void runOnMain(Runnable task) {
        MainRunner runner = mainRunner;
        if (runner != null) {
            runner.run(task);
        } else {
            task.run();
        }
    }

    /**
     * Records a prepare failure: clears the phase, shows the error (overlay
     * + screens) and discards any pending split request.
     */
    private static synchronized void prepareFailed(long epoch, String message) {
        if (epoch != prepareEpoch) {
            return;
        }
        preparing = false;
        splitRequest = null;
        recordError(null, message != null ? message : "Bild konnte nicht gelesen werden.");
    }

    /**
     * Installs the prepared segments unless a newer request superseded
     * them; the next {@link #tickBatch()} then offers the first segment.
     */
    private static synchronized void completeSplitPrepare(long epoch, List<PreparedUpload> segments) {
        if (epoch != prepareEpoch) {
            return;
        }
        preparing = false;
        if (segments == null || segments.isEmpty()) {
            splitRequest = null;
            failBatch("Die Aufteilung konnte nicht erstellt werden.");
            return;
        }
        batch = segments;
        batchNext = 0;
    }

    /**
     * Starts a single upload: the image is prepared (decoded, cropped,
     * scaled, hashed) on the prepare thread so the UI never blocks, then
     * offered from the client thread. The server checks the image limit at
     * the offer, before any image data moves. Returns false (and records
     * an error) when another upload is still active.
     */
    public static boolean requestUploadAsync(File file, String wantedName, CropArea crop) {
        if (uploadBusy()) {
            offerError = "Bereits ein Upload aktiv, bitte warten.";
            return false;
        }
        long epoch = beginPrepare();
        runBackground(() -> {
            try {
                PreparedUpload upload = prepare(file, wantedName, crop);
                runOnMain(() -> {
                    if (epoch != prepareEpoch) {
                        return;
                    }
                    preparingDone();
                    if (!requestUpload(upload)) {
                        String error = consumeOfferError();
                        recordError(upload, error != null ? error : "Der Upload konnte nicht gestartet werden.");
                    }
                });
            } catch (Exception | OutOfMemoryError e) {
                prepareFailed(epoch, e.getMessage());
            }
        });
        return true;
    }

    /**
     * Starts a split upload: every segment name is pre-checked with the
     * server BEFORE anything is prepared or uploaded — if any name is
     * invalid/taken or the image limit has no headroom for the whole grid,
     * the upload is cancelled with an error and nothing was encoded. After
     * the server confirms (see {@link #handleCheckOk(String)}), the
     * segments are prepared on the prepare thread and offered one by one.
     * Returns false (and records an error) when another upload is still
     * active.
     */
    public static synchronized boolean requestSplitUploadAsync(File file, String baseName, CropArea crop,
            int rows, int cols) {
        if (uploadBusy()) {
            offerError = "Bereits ein Upload aktiv, bitte warten.";
            return false;
        }
        if (checkSender == null) {
            offerError = "Keine Serververbindung für den Upload.";
            return false;
        }
        com.google.gson.JsonArray names = new com.google.gson.JsonArray();
        for (int index = 1; index <= rows * cols; index++) {
            names.add(segmentName(baseName, index));
        }
        JsonObject json = new JsonObject();
        json.add("names", names);
        if (!checkSender.sendCheck(GSON.toJson(json))) {
            offerError = "Der Upload konnte nicht gestartet werden (keine Verbindung?).";
            return false;
        }
        splitRequest = new SplitRequest(file, baseName, crop, rows, cols);
        batch = null;
        batchNext = 0;
        checkingNames = true;
        checkSentAt = System.currentTimeMillis();
        overlayError = null;
        return true;
    }

    /**
     * Handles one {@code diawars:image_check_ok} document: on ok the
     * segments are prepared on the prepare thread and the first one is
     * offered on a later tick; otherwise the whole split upload is
     * cancelled (nothing was uploaded or encoded).
     */
    public static synchronized void handleCheckOk(String json) {
        if (!checkingNames) {
            return;
        }
        checkingNames = false;
        String error;
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            boolean ok = root != null && root.has("ok") && root.get("ok").isJsonPrimitive()
                    && root.get("ok").getAsBoolean();
            if (ok) {
                SplitRequest request = splitRequest;
                if (request == null) {
                    failBatch("Die Aufteilung ist nicht mehr aktuell.");
                    return;
                }
                long epoch = beginPrepare();
                runBackground(() -> {
                    try {
                        List<PreparedUpload> segments = prepareSplit(request.file(), request.baseName(),
                                request.crop(), request.rows(), request.cols());
                        completeSplitPrepare(epoch, segments);
                    } catch (Exception | OutOfMemoryError e) {
                        prepareFailed(epoch, e.getMessage() != null
                                ? e.getMessage() : "Die Aufteilung konnte nicht erstellt werden.");
                    }
                });
                return;
            }
            error = root != null && root.has("error") && root.get("error").isJsonPrimitive()
                    ? root.get("error").getAsString() : "Der Server hat die Aufteilung abgelehnt.";
        } catch (Exception e) {
            error = "Der Server hat die Aufteilung abgelehnt.";
        }
        // Server messages use MiniMessage tags; chat/screen shows plain text.
        failBatch(error.replaceAll("<[^>]*>", ""));
        splitRequest = null;
    }

    /**
     * Offers the next split segment whenever the connection is idle. Call
     * once per client tick (after tickOffer/drain). A rejected offer cancels
     * the remaining segments.
     */
    public static synchronized void tickBatch() {
        if (checkingNames) {
            if (System.currentTimeMillis() - checkSentAt > CHECK_TIMEOUT_MILLIS) {
                clearBatch();
                splitRequest = null;
                failBatch("Der Server antwortet nicht (Namenprüfung).");
            }
            return;
        }
        if (batch == null || waitingForAccept || hasQueued()) {
            return;
        }
        if (batchNext >= batch.size()) {
            clearBatch();
            return;
        }
        if (requestUpload(batch.get(batchNext))) {
            batchNext++;
        } else {
            clearBatch();
        }
    }

    /** While a split upload runs: 1-based segment number being sent, else 0. */
    public static synchronized int batchCurrentSegment() {
        return batch != null ? batchNext : 0;
    }

    /** Number of segments of the running split upload, else 0. */
    public static synchronized int batchSegmentCount() {
        return batch != null ? batch.size() : 0;
    }

    /** True while the server has not yet answered the split name check. */
    public static synchronized boolean isCheckingNames() {
        return checkingNames;
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
        checkingNames = false;
        pendingChunkTotal = 0;
        preparing = false;
        splitRequest = null;
        prepareEpoch++;
        clearBatch();
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
            throw new IllegalArgumentException("Ungültiger Name! Erlaubt: Buchstaben, Zahlen, . _ - ( ) und Leerzeichen (max. 32 Zeichen, nicht \"all\").");
        }
        return name;
    }

    /**
     * Builds a split segment name {@code base (k)} (k starts at 1); the base
     * is shortened so the whole name stays inside the 32-char limit.
     */
    public static String segmentName(String base, int index) {
        String suffix = " (" + index + ")";
        String name = base == null ? "" : base;
        int maxBase = 32 - suffix.length();
        if (name.length() > maxBase) {
            name = name.substring(0, maxBase).replaceAll("\\s+$", "");
        }
        if (name.isEmpty()) {
            name = "bild";
        }
        return name + suffix;
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
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(width, height, type);
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

    /**
     * Cuts the rectangular crop area out of the image. A crop fully inside
     * the image stays opaque; a crop that extends beyond it is padded with
     * transparent pixels, so the whole image can be fitted into a selection
     * without cutting anything off.
     */
    private static BufferedImage applyCrop(BufferedImage image, CropArea crop) throws IOException {
        int width = crop.width();
        int height = crop.height();
        if (width < 1 || height < 1) {
            throw new IOException("Ungültiger Bildausschnitt.");
        }
        boolean insideImage = crop.x() >= 0 && crop.y() >= 0
                && crop.x() + (long) width <= image.getWidth()
                && crop.y() + (long) height <= image.getHeight();
        if (insideImage) {
            BufferedImage sub = image.getSubimage(crop.x(), crop.y(), width, height);
            BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            try {
                g.drawImage(sub, 0, 0, null);
            } finally {
                g.dispose();
            }
            return copy;
        }
        BufferedImage padded = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = padded.createGraphics();
        try {
            g.drawImage(image, -crop.x(), -crop.y(), null);
        } finally {
            g.dispose();
        }
        return padded;
    }
}
