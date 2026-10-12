# Diawars Client (Forge)

Forge-Port der Fabric-Mod `diawars-client-fabric` für Minecraft **26.2** (Java 25, Forge `65.1.3`).

Sendet beim Join dasselbe `diawars:client_info`-Paket mit demselben JSON-Schema
(`minecraftVersion`, `loaderType: "forge"`, `loaderVersion`, `mods`, `resourcePacks`,
`shaderModInstalled`, `shadersEnabled`, `activeShaderPack`, `isLunarClient`),
sodass das Diawars-Paper-Plugin (`ClientInfoListener` / `ClientInfoStore`) Fabric-
und Forge-Spieler identisch verarbeiten kann.

- Mod-Id (Forge, kein `-` erlaubt): `diawars_client` (Fabric: `diawars-client`,
  beide sind serverseitig in `ModWhitelistManager` immer erlaubt).
- Gefiltert (nicht reported): `minecraft`, `forge` — wie Fabric `minecraft`,
  `fabricloader`, `fabric-*` filtert.
- Shader-Erkennung: `iris` oder `oculus` (Forge-Fork) via `IrisApi` (Reflection).
- Netzwerk: Forge-`PayloadChannel` (`PLAY`/`SERVERBOUND`, `optional()` für
  Vanilla-/Paper-Server), Paket-Id `diawars:client_info`.

## Build

```bash
./gradlew build
```

## Hinweis

Forge-Mods mit Underscore-Ids (z. B. `oculus`, `cloth_config`) ggf. zusätzlich in
`mod_whitelist.yml` des Plugins eintragen (siehe Kommentar dort).
