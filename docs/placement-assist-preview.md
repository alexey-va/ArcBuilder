# Placement assistant preview

The focused export uses `BuilderPlacementAssistAppearance.edges()` and its actual ready/blocked materials. Six fixtures show a row, a floor, a wall, two nearby diagonal aim positions and a blocked row. The row requires the actual eye ray to cross `last + direction`. The plane selects the one cell at the ray's first entry into the established block layer, with an ordinary adjacent support. The diagonal pair differs by two centimetres and keeps the same selected cell. Each state contains exactly twelve display bars and its supporting blocks.

The player camera preserves each fixture's actual gaze; it is not recentered on the frame. A small crosshair is a viewer diagnostic for that gaze. Front, side and top views inspect the same geometry. This is an offline geometry and vanilla-texture check; Minecraft glow, glass blending, input timing, native prediction reconciliation and actual world lighting still require a native client. The source export is not evidence that a vanilla client accepted the interaction.

Generate the scene, then render it with the shared location-atelier renderer:

```sh
./gradlew test --tests ru.arc.buildertools.BuilderPlacementAssistPreviewTest
python3 scripts/render-placement-assist-preview.py --client-jar /path/to/minecraft-1.21.11-client.jar
python3 -m http.server 8766 --bind 127.0.0.1 --directory build/reports/placement-assist-v3
```

The client JAR must match the SHA-1 pinned in `visual-preview.yml`. The renderer defaults to sibling `../ruscrafting-ops`; use `--ops-root` to specify its checkout. Open `http://127.0.0.1:8766/index.html`, select each state, and inspect **Глаза игрока**, **Спереди**, **Сбоку** and **Сверху**. The export also records source hashes in `provenance.json` and face-overlap findings in `z-fighting-diagnostics.json`.

`BuilderPacketAttachmentTest` separately exports `build/reports/placement-assist-v3/compact-frame-budget-trace.json`. It decodes the real native packet wrappers under a simulated admission budget: a refused compact frame retains all twelve old edges, and a later admission moves all twelve to the latest pose with the same identities. This checks packet transaction coherence; it is not an observation of Minecraft client motion.
