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
  Vanilla-/Paper-Server), Paket-Id `diawars:client_info` (plus
  `diawars:image` für Bild-Uploads, identisches Chunk-Format wie Fabric).

## Bilder hochladen

Lege Bilder (PNG/JPG/...) in den Ordner `diawars-images` in deinem
Minecraft-Verzeichnis oder wähle über das Menü einen beliebigen Pfad.
Öffne den Upload mit der Taste `U` (Steuerung) oder `/diawarsimage`.
Die Mod skaliert das Bild auf 128x128 und sendet es stückweise als
`diawars:image`-Paket an den Server. Die Antwort kommt im Chat;
mit `/image <datei>` oder über das Bilder-Menü erhältst du die Karte.

## Build

```bash
./gradlew build
```

## Hinweis

Forge-Mods mit Underscore-Ids (z. B. `oculus`, `cloth_config`) ggf. zusätzlich in
`mod_whitelist.yml` des Plugins eintragen (siehe Kommentar dort).
