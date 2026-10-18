package de.davidsw.diawarsclient;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pulls full-resolution images from the Diawars Paper plugin and swaps them
 * into map rendering (see the {@code MapTextureManager} mixin).
 *
 * <p>Protocol ({@code diawars:image_req} / {@code diawars:image_data}, JSON):
 * the scanner ({@link #tick}) collects vanilla map ids from the inventory and
 * nearby item frames and requests each unknown one once. The server answers
 * with chunked base64 PNG data, an {@code unchanged} single (disk cache is
 * fresh, keyed by SHA-256) or an {@code error} single (not one of ours —
 * remembered for a while so vanilla maps cost one tiny reply per session).
 *
 * <p>Disk cache lives in {@code diawars-cache/} inside the game directory
 * ({@code <owner>/<file>.png} + {@code .hash} sidecar, plus an
 * {@code index/<mapId>} link so a map id resolves to its cached file). All
 * methods must run on the client thread.
 */
public final class ImageDownloader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** Max accepted download parts (mirrors the server negotiation cap). */
    private static final int MAX_PARTS = 8192;

    /** Max texture dimension for GL upload (mirrors the server image cap). */
    private static final int MAX_TEXTURE_DIMENSION = 8192;

    /** Folder inside the game directory holding downloaded full images. */
    public static final String CACHE_FOLDER = "diawars-cache";

    /** Cache cap; oldest files go once exceeded. */
    public static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;

    /** How long a failed/foreign map id stays quiet before a retry. */
    private static final long NEGATIVE_MILLIS = 10L * 60 * 1000;

    /** How long a request may go unanswered before it can be re-sent. */
    private static final long REQUEST_TIMEOUT_MILLIS = 60_000;

    /** Item-frame scan radius (blocks). */
    private static final double SCAN_RADIUS = 24.0;

    /** Loader-specific sender for {@code diawars:image_req} documents. */
    public interface RequestSender {
        boolean send(String json);
    }

    private record Session(String key, Integer mapId, String owner, String file, String hash, int total,
                           Map<Integer, String> parts, long updated) {
    }

    private record FullTexture(Identifier id, DynamicTexture texture, String hash) {
    }

    private static RequestSender sender;
    private static final Map<String, Session> SESSIONS = new HashMap<>();
    private static final Map<Integer, FullTexture> TEXTURES = new HashMap<>();
    private static final Map<Integer, Long> PENDING = new HashMap<>();
    private static final Map<Integer, Long> NEGATIVE = new HashMap<>();
    /** Map ids whose image the server deleted: never requested again this session. */
    private static final Set<Integer> DELETED = new HashSet<>();
    private static int tickCounter;
    private static long lastPrune;

    private ImageDownloader() {
    }

    public static void init(RequestSender requestSender) {
        sender = requestSender;
    }

    /** Texture override for a vanilla map id, or null to keep vanilla rendering. */
    public static Identifier getTexture(int mapId) {
        FullTexture full = TEXTURES.get(mapId);
        return full == null ? null : full.id();
    }

    /** Periodic scan: inventory maps plus nearby item frames, every 40 ticks. */
    public static void tick(Minecraft minecraft) {
        if (++tickCounter % 40 != 0) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> now - e.getValue() > REQUEST_TIMEOUT_MILLIS);

        Set<Integer> ids = new HashSet<>();
        for (ItemStack stack : minecraft.player.getInventory().getNonEquipmentItems()) {
            collectMapId(stack, ids);
        }
        double maxDist = SCAN_RADIUS * SCAN_RADIUS;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof ItemFrame frame) {
                if (entity.distanceToSqr(minecraft.player) > maxDist) {
                    continue;
                }
                collectMapId(frame.getItem(), ids);
            }
        }
        for (int id : ids) {
            ensureMap(minecraft, id);
        }
    }

    private static void collectMapId(ItemStack stack, Set<Integer> out) {
        if (stack.isEmpty() || !stack.is(Items.FILLED_MAP)) {
            return;
        }
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id != null) {
            out.add(id.id());
        }
    }

    /** Requests the full image for a map id unless cached, pending or recently failed. */
    public static void ensureMap(Minecraft minecraft, int mapId) {
        if (TEXTURES.containsKey(mapId) || DELETED.contains(mapId) || sender == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (PENDING.containsKey(mapId) || now - NEGATIVE.getOrDefault(mapId, 0L) < NEGATIVE_MILLIS) {
            return;
        }
        String knownHash = null;
        String[] linked = readIndex(minecraft, mapId);
        if (linked != null) {
            knownHash = readCachedHash(minecraft, linked[0], linked[1]);
        }
        JsonObject json = new JsonObject();
        json.addProperty("mapId", mapId);
        if (knownHash != null) {
            json.addProperty("knownHash", knownHash);
        }
        if (sender.send(GSON.toJson(json))) {
            PENDING.put(mapId, now);
        }
    }

    /** Handles one {@code diawars:image_data} document (client thread only). */
    public static void handleDataJson(Minecraft minecraft, String json) {
        JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
            if (root == null) {
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("Ignoring invalid diawars image data.", e);
            return;
        }
        Integer mapId = root.has("mapId") && root.get("mapId").isJsonPrimitive()
                ? root.get("mapId").getAsInt() : null;
        String owner = root.has("owner") && root.get("owner").isJsonPrimitive()
                ? root.get("owner").getAsString() : null;
        String file = root.has("file") && root.get("file").isJsonPrimitive()
                ? root.get("file").getAsString() : null;
        String hash = root.has("hash") && root.get("hash").isJsonPrimitive()
                ? root.get("hash").getAsString() : null;

        if (root.has("deleted") && root.get("deleted").isJsonPrimitive()
                && root.get("deleted").getAsBoolean()) {
            // Admin deleted the image: drop the cached copy and every texture
            // that was built from it. Missing file means the whole owner.
            if (owner != null) {
                purgeCachedImage(minecraft, owner, file);
            }
            return;
        }
        if (root.has("error")) {
            String reason = root.get("error").isJsonPrimitive() ? root.get("error").getAsString() : "";
            if (mapId != null) {
                PENDING.remove(mapId);
                NEGATIVE.put(mapId, System.currentTimeMillis());
                deleteIndex(minecraft, mapId);
            }
            if ("unknown".equals(reason) && owner != null) {
                // The image is gone server-side: don't keep a stale copy.
                purgeCachedImage(minecraft, owner, file);
            }
            return;
        }
        if (owner == null || file == null || hash == null) {
            return;
        }
        if (root.has("unchanged") && root.get("unchanged").getAsBoolean()) {
            if (mapId != null) {
                PENDING.remove(mapId);
            }
            byte[] cached = readCachedImage(minecraft, owner, file);
            if (cached != null) {
                registerTexture(minecraft, mapId, owner, file, cached, hash);
            } else if (mapId != null) {
                // Cache entry vanished: fetch without hash.
                JsonObject retry = new JsonObject();
                retry.addProperty("mapId", mapId);
                if (sender != null && sender.send(GSON.toJson(retry))) {
                    PENDING.put(mapId, System.currentTimeMillis());
                }
            }
            return;
        }

        int part = root.has("part") ? root.get("part").getAsInt() : -1;
        int total = root.has("total") ? root.get("total").getAsInt() : -1;
        String data = root.has("data") && root.get("data").isJsonPrimitive()
                ? root.get("data").getAsString() : null;
        if (part < 0 || total < 1 || total > MAX_PARTS || data == null) {
            return;
        }
        String key = (mapId != null ? "map:" + mapId : "file:" + owner + "/" + file);
        Session session = SESSIONS.get(key);
        if (session == null || session.total() != total || !session.hash().equals(hash)) {
            session = new Session(key, mapId, owner, file, hash, total, new HashMap<>(), 0L);
            SESSIONS.put(key, session);
        }
        session.parts().put(part, data);
        if (session.parts().size() < total) {
            return;
        }
        SESSIONS.remove(key);
        if (mapId != null) {
            PENDING.remove(mapId);
        }
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < total; i++) {
            String chunk = session.parts().get(i);
            if (chunk == null) {
                return;
            }
            combined.append(chunk);
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(combined.toString());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Ignoring diawars image with invalid base64.", e);
            return;
        }
        if (!sha256Hex(bytes).equalsIgnoreCase(hash)) {
            LOGGER.warn("Ignoring diawars image with hash mismatch.");
            return;
        }
        writeCache(minecraft, mapId, owner, file, hash, bytes);
        registerTexture(minecraft, mapId, owner, file, bytes, hash);
    }

    private static void registerTexture(Minecraft minecraft, Integer mapId, String owner, String file,
                                        byte[] png, String hash) {
        if (mapId == null) {
            return;
        }
        FullTexture old = TEXTURES.get(mapId);
        if (old != null && old.hash().equalsIgnoreCase(hash)) {
            return;
        }
        NativeImage image;
        try {
            image = NativeImage.read(new ByteArrayInputStream(png));
        } catch (Exception e) {
            LOGGER.warn("Ignoring unreadable diawars image {}/{}.", owner, file, e);
            return;
        }
        if (image.getWidth() > MAX_TEXTURE_DIMENSION || image.getHeight() > MAX_TEXTURE_DIMENSION) {
            int width = image.getWidth();
            int height = image.getHeight();
            image.close();
            LOGGER.warn("Ignoring oversized diawars image {}/{} ({}x{}).", owner, file, width, height);
            if (mapId == null) {
                return;
            }
            NEGATIVE.put(mapId, System.currentTimeMillis());
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath("diawars-client", "maps/" + mapId);
        DynamicTexture texture = new DynamicTexture(() -> "diawars map " + mapId, image);
        minecraft.getTextureManager().register(id, texture);
        if (old != null) {
            minecraft.getTextureManager().release(old.id());
            old.texture().close();
        }
        TEXTURES.put(mapId, new FullTexture(id, texture, hash));
    }

    /** Drops session state and frees textures (disk cache persists). */
    public static void clearSession(Minecraft minecraft) {
        SESSIONS.clear();
        PENDING.clear();
        NEGATIVE.clear();
        DELETED.clear();
        for (FullTexture full : TEXTURES.values()) {
            try {
                minecraft.getTextureManager().release(full.id());
                full.texture().close();
            } catch (Exception e) {
                LOGGER.warn("Failed to release diawars map texture.", e);
            }
        }
        TEXTURES.clear();
    }

    /**
     * Removes the cached copy of an image (all of an owner's files when
     * {@code file} is null), releases the textures built from it and remembers
     * the affected map ids so they are not requested again this session.
     */
    private static void purgeCachedImage(Minecraft minecraft, String owner, String file) {
        if (file == null) {
            File ownerDir = new File(cacheDir(minecraft), owner);
            File[] files = ownerDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        f.delete();
                    }
                }
            }
        } else {
            try {
                Files.deleteIfExists(imageFile(minecraft, owner, file).toPath());
            } catch (IOException e) {
                LOGGER.warn("Could not delete cached diawars image {}/{}.", owner, file, e);
            }
            try {
                Files.deleteIfExists(hashFile(minecraft, owner, file).toPath());
            } catch (IOException e) {
                LOGGER.warn("Could not delete cached diawars hash {}/{}.", owner, file, e);
            }
        }

        File indexDir = new File(cacheDir(minecraft), "index");
        File[] links = indexDir.listFiles();
        if (links == null) {
            return;
        }
        String prefix = owner + "/";
        String wanted = file == null ? null : owner + "/" + file;
        for (File link : links) {
            String linked;
            try {
                linked = Files.readString(link.toPath(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                continue;
            }
            if (wanted != null ? !linked.equals(wanted) : !linked.startsWith(prefix)) {
                continue;
            }
            int mapId;
            try {
                mapId = Integer.parseInt(link.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            try {
                Files.deleteIfExists(link.toPath());
            } catch (IOException e) {
                LOGGER.warn("Could not delete diawars map index {}.", link.getName(), e);
            }
            dropTexture(minecraft, mapId);
            SESSIONS.values().removeIf(session -> session.mapId() != null && session.mapId() == mapId);
            PENDING.remove(mapId);
            NEGATIVE.put(mapId, System.currentTimeMillis());
            DELETED.add(mapId);
        }
    }

    private static void dropTexture(Minecraft minecraft, int mapId) {
        FullTexture old = TEXTURES.remove(mapId);
        if (old == null) {
            return;
        }
        try {
            minecraft.getTextureManager().release(old.id());
            old.texture().close();
        } catch (Exception e) {
            LOGGER.warn("Failed to release diawars map texture.", e);
        }
    }

    // ------------------------------------------------------------------
    // disk cache
    // ------------------------------------------------------------------

    private static File cacheDir(Minecraft minecraft) {
        File dir = new File(minecraft.gameDirectory, CACHE_FOLDER);
        dir.mkdirs();
        return dir;
    }

    private static File imageFile(Minecraft minecraft, String owner, String file) {
        return new File(cacheDir(minecraft), owner + "/" + file + ".png");
    }

    private static File hashFile(Minecraft minecraft, String owner, String file) {
        return new File(cacheDir(minecraft), owner + "/" + file + ".hash");
    }

    private static File indexFile(Minecraft minecraft, int mapId) {
        return new File(cacheDir(minecraft), "index/" + mapId);
    }

    private static byte[] readCachedImage(Minecraft minecraft, String owner, String file) {
        File f = imageFile(minecraft, owner, file);
        if (!f.isFile()) {
            return null;
        }
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    private static String readCachedHash(Minecraft minecraft, String owner, String file) {
        File f = hashFile(minecraft, owner, file);
        if (!f.isFile()) {
            return null;
        }
        try {
            String hash = Files.readString(f.toPath(), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            return hash.matches("[0-9a-f]{64}") ? hash : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Resolves a map id to owner/file via the index link, or null. */
    private static String[] readIndex(Minecraft minecraft, int mapId) {
        File f = indexFile(minecraft, mapId);
        if (!f.isFile()) {
            return null;
        }
        try {
            String[] parts = Files.readString(f.toPath(), StandardCharsets.UTF_8).trim().split("/", 2);
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()
                    && imageFile(minecraft, parts[0], parts[1]).isFile()) {
                return parts;
            }
        } catch (IOException e) {
            // fall through
        }
        return null;
    }

    private static void deleteIndex(Minecraft minecraft, int mapId) {
        try {
            Files.deleteIfExists(indexFile(minecraft, mapId).toPath());
        } catch (IOException e) {
            // ignore
        }
    }

    private static void writeCache(Minecraft minecraft, Integer mapId, String owner, String file,
                                   String hash, byte[] png) {
        try {
            File image = imageFile(minecraft, owner, file);
            image.getParentFile().mkdirs();
            Files.write(image.toPath(), png);
            Files.writeString(hashFile(minecraft, owner, file).toPath(), hash, StandardCharsets.UTF_8);
            if (mapId != null) {
                File link = indexFile(minecraft, mapId);
                link.getParentFile().mkdirs();
                Files.writeString(link.toPath(), owner + "/" + file, StandardCharsets.UTF_8);
            }
            pruneCache(minecraft);
        } catch (IOException e) {
            LOGGER.warn("Could not cache diawars image {}/{}.", owner, file, e);
        }
    }

    private static void pruneCache(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        if (now - lastPrune < 60_000) {
            return;
        }
        lastPrune = now;
        try {
            File root = cacheDir(minecraft);
            List<File> pngs = new ArrayList<>();
            collectPngs(root, pngs);
            long total = 0;
            for (File f : pngs) {
                total += f.length();
            }
            if (total <= MAX_CACHE_BYTES) {
                return;
            }
            pngs.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : pngs) {
                String name = f.getName();
                String base = name.substring(0, name.length() - ".png".length());
                File hash = new File(f.getParentFile(), base + ".hash");
                total -= f.length();
                f.delete();
                hash.delete();
                if (total <= MAX_CACHE_BYTES) {
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not prune diawars image cache.", e);
        }
    }

    private static void collectPngs(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().equals("index")) {
                    collectPngs(f, out);
                }
            } else if (f.isFile() && f.getName().endsWith(".png")) {
                out.add(f);
            }
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
}
