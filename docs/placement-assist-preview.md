# Placement assistant preview

The focused export uses `BuilderPlacementAssistAppearance.edges()` and its actual ready/blocked materials. Each state contains the same twelve display bars and two ordinary placed blocks. This is an offline geometry and vanilla-texture check; Minecraft glow, glass blending, input timing and actual world lighting still require a native client.

Generate the scene, then render it with the shared location-atelier renderer:

```sh
./gradlew test --tests ru.arc.buildertools.BuilderPlacementAssistPreviewTest
python3 scripts/render-placement-assist-preview.py --client-jar /path/to/minecraft-1.21.11-client.jar
python3 -m http.server 8766 --bind 127.0.0.1 --directory build/reports/placement-assist
```

The client JAR must match the SHA-1 pinned in `visual-preview.yml`. The renderer defaults to sibling `../ruscrafting-ops`; use `--ops-root` to specify its checkout. Open `http://127.0.0.1:8766/index.html`, select each state, and inspect **Глаза игрока**, **Спереди**, **Сбоку** and **Сверху**. The export also records source hashes in `provenance.json` and face-overlap findings in `z-fighting-diagnostics.json`.
