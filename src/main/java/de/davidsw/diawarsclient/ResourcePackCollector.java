package de.davidsw.diawarsclient;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Forge port of the Fabric {@code ResourcePackCollector}.
 *
 * <p>Uses only vanilla classes ({@code Minecraft#getResourcePackRepository}),
 * so the behaviour is identical on both loaders: report every enabled pack
 * except vanilla builtins, by display title.</p>
 */
public class ResourcePackCollector {
    private static final Set<String> BUILTIN_IDS = Set.of(
            "vanilla",
            "fabric",
            "fabric/internal",
            "forge",
            "mod_resources",
            "minecraft",
            "programer_art"
    );

    public static List<String> collectEnabledPacks() {
        List<String> packs = new ArrayList<>();

        try {
            var repository = Minecraft.getInstance().getResourcePackRepository();
            for (var pack : repository.getSelectedPacks()) {
                if (BUILTIN_IDS.contains(pack.getId())) continue;
                packs.add(pack.getTitle().getString());
            }
        } catch (Exception | LinkageError e) {
            // Client not fully initialized yet (e.g. very early login):
            // report no packs rather than failing the whole client_info packet.
        }

        return packs;
    }
}
