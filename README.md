# ArcBuilder

Start structural work with [ARCHITECTURE.md](ARCHITECTURE.md).

Standalone RusCrafting Paper plugin for survival-friendly building assistance:
selection, fill, exact block replacement, copy/paste with player-relative
anchors and rotation, deconstruction, procedural tree crowns, construction-book
drafts, pricing, activation, copying, selling, and one-time-use protection.

The plugin uses arc-core 2.1.2 and does not depend on the ARC monolith.

## Player flow

- `/builder wand` gives selection guidance. Left click selects point 1; right
  click selects point 2. Distinct straight BlockDisplay outlines remain visible
  until the selection is cleared or expires.
- `/builder disconnect` previews an undoable plan that clears the connected
  sides of vanilla fences in the current selection. Add `confirm` to apply the
  same plan immediately without a preview. Later neighbor updates may reconnect
  fences according to vanilla block physics.
- `/builder replace <old> <new>` previews an exact-material replacement inside
  the selection. Compatible state such as stair orientation, slab type, log
  axis, and fence sides is preserved. Add final `confirm` to apply immediately.
  Survival consumes the new blocks and returns the old ones; creative does not
  exchange inventory.
- `/builder copy` stores the build relative to the player's position and facing.
  `/builder paste` previews it at the player's current position; rotation can be
  adjusted before confirmation.
- Fill, paste, deconstruction, crowns, and books silently skip containers,
  technical blocks, custom Slimefun/ItemsAdder blocks, and other unsafe state.
  Ordinary stone, sand, and concrete powder are supported.
- Wilderness is buildable. Inside a Lands claim the normal place/break checks
  still apply. WorldGuard is not a dependency.
- Construction books start as free drafts. Right click opens the world preview;
  right click again shows the exact activation or build quote. Shift-right click
  opens the compact symmetric transform menu.
- Money is represented internally as integer minor units. Decimal provider APIs
  are normalized only at the integration boundary.

## Runtime ownership

ArcBuilder owns `/builder`, its permissions, book UUID/MySQL registry, preview
entities, and the following configuration under `plugins/ArcBuilder/modules/`:

- `builder-tools.yml`
- `auto-build.yml`
- `builder-tools-runtime.yml`

The existing schematic library remains at `plugins/ARC/schematics/` through the
configured `../ARC/schematics` path. ARC must not contain Builder classes,
commands, listeners, permissions, or module configs after migration.

## Build

```bash
./gradlew --no-daemon test shadowJar
python3 ../arc-core/scripts/verify_consumer_architecture.py .
```

The disposable MySQL integration suite is intentionally CI-only and runs in
the repository's `mysql-integration` GitHub Actions job.

## Complete visual preview

Render every declared Russian player-facing surface with one command:

```bash
./scripts/render-visual-preview
```

The manifest at `visual-preview.yml` classifies the real locale/config keys as
chat, title/subtitle/action bar/boss bar, item tooltip, inventory GUI, world
text, or reusable fragment. The renderer downloads the pinned official
Minecraft 1.21.11 client and the currently published RusCrafting resource pack,
verifies their digests, uses the exact bitmap font, container/tooltip sprites,
vanilla item textures, and declared custom item models such as
`arc:background`, then writes a paginated gallery to
`build/reports/visual-preview/index.html` with `report.json` coverage evidence.
Generation fails if a new player-visible key is not assigned to a surface or a
placeholder has no representative value.

The production artifact is `build/libs/ArcBuilder-1.0.1.jar`. From the ops
repository it is deployed independently with:

```bash
./scripts/mc arcbuilder classic_survival
```
