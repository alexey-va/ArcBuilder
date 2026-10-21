# Placement assistant preview

The focused export uses `BuilderPlacementAssistAppearance.edges()` and its actual ready/blocked materials. Four fixtures show a locked row while the crosshair misses the next cell, a floor, a wall and a blocked row. Line selection comes from the production `last + direction` lock; plane cells come from the production projection and bounded candidate geometry with ordinary adjacent supports. Each state contains exactly twelve display bars and its supporting blocks.

The player camera preserves each fixture's actual gaze; it is not recentered on the frame. A small crosshair is a viewer diagnostic for that gaze. Front, side and top views inspect the same geometry. This is an offline geometry and vanilla-texture check; Minecraft glow, glass blending, input timing, native prediction reconciliation and actual world lighting still require a native client. The source export is not evidence that a vanilla client accepted the interaction.

Generate the scene, then render it with the shared location-atelier renderer:

```sh
./gradlew test --tests ru.arc.buildertools.BuilderPlacementAssistPreviewTest
python3 scripts/render-placement-assist-preview.py --client-jar /path/to/minecraft-1.21.11-client.jar
python3 -m http.server 8766 --bind 127.0.0.1 --directory build/reports/placement-assist
```

The client JAR must match the SHA-1 pinned in `visual-preview.yml`. The renderer defaults to sibling `../ruscrafting-ops`; use `--ops-root` to specify its checkout. Open `http://127.0.0.1:8766/index.html`, select each state, and inspect **Глаза игрока**, **Спереди**, **Сбоку** and **Сверху**. The export also records source hashes in `provenance.json` and face-overlap findings in `z-fighting-diagnostics.json`.
