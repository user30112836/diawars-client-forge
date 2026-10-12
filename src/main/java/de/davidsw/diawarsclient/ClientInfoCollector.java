package de.davidsw.diawarsclient;

import com.google.gson.Gson;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.versions.forge.ForgeVersion;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge port of the Fabric {@code ClientInfoCollector}.
 *
 * <p>Produces the exact same JSON schema as the Fabric mod so that
 * {@code ClientInfoStore.saveFromJson} on the Paper plugin keeps working unchanged:</p>
 * <pre>
 * {
 *   "minecraftVersion": "26.2",
 *   "loaderType": "forge",
 *   "loaderVersion": "65.1.3",
 *   "isLunarClient": false,
 *   "mods": [{"id": "...", "version": "..."}],
 *   "resourcePacks": ["..."],
 *   "shaderModInstalled": false,
 *   "shadersEnabled": false,
 *   "activeShaderPack": null
 * }
 * </pre>
 */
public class ClientInfoCollector {
    private static final Gson GSON = new Gson();

    public record ModEntry(String id, String version) {}

    public record ClientInfo(
            String minecraftVersion,
            String loaderType,
            String loaderVersion,
            boolean isLunarClient,
            List<ModEntry> mods,
            List<String> resourcePacks,
            boolean shaderModInstalled,
            boolean shadersEnabled,
            String activeShaderPack
    ) {}

    public static String collectAsJson() {
        List<ModEntry> mods = new ArrayList<>();
        String minecraftVersion = "unknown";
        String loaderVersion = "unknown";

        try {
            loaderVersion = ForgeVersion.getVersion();
        } catch (Exception | LinkageError e) {
            loaderVersion = "unknown";
        }

        for (var modInfo : ModList.getMods()) {
            String id = modInfo.getModId();
            String version = modInfo.getVersion().toString();

            // Same idea as the Fabric collector: skip the game itself and the
            // loader so the server whitelist only sees real content mods.
            // "java" and "mixinextras" are intentionally kept: the plugin's
            // default whitelist already allows them.
            if (id.equals("minecraft")) {
                minecraftVersion = version;
                continue;
            }
            if (id.equals("forge")) {
                if (loaderVersion.equals("unknown")) {
                    loaderVersion = version;
                }
                continue;
            }

            mods.add(new ModEntry(id, version));
        }

        if (minecraftVersion.equals("unknown")) {
            minecraftVersion = detectMinecraftVersion();
        }
        if (loaderVersion.equals("unknown")) {
            loaderVersion = ModList.getModContainerById("forge")
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
        }

        ShaderInfoCollector.ShaderInfo shaderInfo = ShaderInfoCollector.collect();

        ClientInfo info = new ClientInfo(
                minecraftVersion,
                "forge",
                loaderVersion,
                detectLunarClient(),
                mods,
                ResourcePackCollector.collectEnabledPacks(),
                shaderInfo.installed(),
                shaderInfo.enabled(),
                shaderInfo.activePackName()
        );

        return GSON.toJson(info);
    }

    private static String detectMinecraftVersion() {
        // Fallback when the "minecraft" mod entry is missing (should not happen
        // on a normal Forge client, but keeps the plugin's "missing version"
        // validation from kicking the player).
        try {
            var version = net.minecraft.SharedConstants.getCurrentVersion();
            // GameVersion#getName() returns e.g. "26.2" on 26.x.
            return version.name();
        } catch (Exception | LinkageError e) {
            return "unknown";
        }
    }

    private static boolean detectLunarClient() {
        try {
            Class.forName("com.moonsworth.lunar.genesis.Genesis");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
