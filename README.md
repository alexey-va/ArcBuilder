# ArcBuilder

Start structural work with [ARCHITECTURE.md](ARCHITECTURE.md).

Standalone RusCrafting Paper plugin for survival-friendly building assistance:
selection, fill, exact block replacement, copy/paste with player-relative
anchors and rotation, deconstruction, procedural tree crowns, construction-book
drafts, pricing, activation, copying, selling, and one-time-use protection.

The plugin uses arc-core 2.7.18 and requires ARC for its shared visual packet budget; Builder gameplay remains in this standalone plugin.

## Player flow

- `/builder assist` toggles a personal placement helper; `/builder assist on`
  and `/builder assist off` set it explicitly. Any player can change this
  preference without builder-tool permissions, and it survives reconnects.
  Hold an ordinary full-cube block without custom item data. Place two
  neighbouring blocks in a straight line normally, then aim at the
  outlined next cell and use the held block to continue the row, including
  from an air click beside the edge. Sneaking or changing the held slot resets
  the line for ordinary placement. The private display outline never changes
  blocks on its own. Each continuation uses the normal server placement path,
  preserving inventory costs, collision, protection plugins and block physics;
  the vanilla client's repeat delay still applies. Slabs, stairs, containers and
  custom items continue to use ordinary vanilla placement.
- `/builder wand` gives selection guidance. Left click selects point 1; right
  click selects point 2. Distinct straight BlockDisplay outlines remain visible
  until the selection is cleared or expires.
- Holding the selector with a completed selection shows a private world panel.
  Aim and left or right click: up to six actions per page cover fill, replace, copy, paste,
  deconstruction, fence disconnection, drafts, undo and selection reset. `<` / `>`
  change pages, with a page caption between them. The panel sits at eye level
  toward the selection's horizontal centre and faces the player. Walking updates
  its position and orientation with native client interpolation; head turns and
  crouching in place leave the controls still. Changing the selection updates its
  direction immediately. Blocked placements are excluded. It hides
  beyond eight blocks from the nearest selection edge and returns without losing
  the selection. A prepared plan shows materials and confirmation controls.
  Create book captures the current selection and, in survival, consumes one plain
  book from inventory while keeping the selector held. Fence disconnection follows
  the common actions on later pages.
- Fill and replace open a native material picker. Search accepts Russian labels
  and English IDs; **Find** updates 24 results per page. The six most recently
  selected materials appear first with a warm highlight, newest first, until logout; inventory
  materials lead the remaining choices for an empty query. Replace first offers safe materials found
  in the selection. Search is submitted explicitly because the vanilla Dialog
  protocol does not send text changes while typing.
- The configured language (`default-locale: ru`) is used regardless of the client.
  Set `locale.follow-client: true` and reload to follow the client language again.
- `/builder disconnect` previews an undoable plan that clears the connected
  sides of vanilla fences in the current selection. Add `confirm` to apply the
  same plan immediately without a preview. Later neighbor updates may reconnect
  fences according to vanilla block physics.
- Material arguments also accept Russian names such as `алмазныйБлок` and `дубовыеДоски`; Tab completes both Russian and English names.
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
  Reward-bearing ore blocks (every `*_ORE` block plus ancient debris) are also
  skipped so builder operations cannot bypass placed-block reward tracking.
  Ordinary stone, sand, and concrete powder are supported.
- Wilderness is buildable. Inside a Lands claim the normal place/break checks
  still apply. WorldGuard is not a dependency.
- Construction books start as free drafts. Right click opens an owner-only world
  preview for at most three minutes. Its fixed clickable plaque appears on the
  nearest face and opens session-only move/rotate controls plus the final build
  confirmation; these controls never rewrite the book. A chat button cancels the
  preview. Ordinary players may durably apply one construction book every 12
  hours; operators have an explicit bypass permission. Shift-right click still
  opens the authoring transform menu for intentionally editing future copies.
- Money is represented internally as integer minor units. Decimal provider APIs
  are normalized only at the integration boundary.

## Runtime ownership

ArcBuilder owns `/builder`, its permissions, book UUID/MySQL registry, preview
entities, and the following configuration under `plugins/ArcBuilder/modules/`:

- `builder-tools.yml`
- `auto-build.yml`
- `builder-tools-runtime.yml`

The existing schematic library remains at `plugins/ARC/schematics/` through the
configured `../ARC/schematics` path. ArcCore supplies asynchronous lazy loading,
verified file/content digests and native transforms with a bounded expiring cache.
Nested IDs such as `civs/myschema` work alongside existing suffixed book IDs. ARC must not contain Builder classes,
commands, listeners, permissions, or module configs after migration.

Plaque appearance lives under `construction.site.panel` in `builder-tools.yml`:
`background-color` accepts `#RRGGBB` or legacy `#AARRGGBB`; `background-opacity`
is a 0–1 multiplier on that alpha (default `1.0` preserves existing backgrounds).
`border-trail` controls the enabled particle, RGB color, size, speed in blocks per
second at 20 TPS, tail length/spacing/padding in blocks, tick cadence, range and
viewer cap. The bundled comments list supported types and validated bounds.
`glow.enabled` optionally adds four thin glowing BlockDisplay bars; its color,
material and thickness are configurable. It defaults off. Native 1.21.11
TextDisplay text does not submit an outline; full brightness is separate from glow.
The verified [client bytecode proof](../artifacts/world-panels-20261017/native-text-proof/README.md)
uses renderer `hwe$c` (the `submitText` call passes zero outline color).

Appearance-only `/builder reload` updates existing plaques in place, including
retained confirmations, without closing their controls. Mixed appearance and
gameplay edits still obey the normal busy barrier and are rejected together.
Unknown or duplicate YAML keys cannot conceal such edits. Particles reuse scene
capture, send only to eligible nearby viewers, and stop on hide/disable/close;
already-sent native particles expire naturally. Hard limits are 32 samples per
plaque emission and 96 particle births per viewer per tick across plaques.

For the focused source-driven animation, run `BuilderPlaqueAppearanceTest`, then
`python3 scripts/render-plaque-motion --client-jar <1.21.11-client.jar>
--font-assets <1.21.11-font-assets.zip>` (the existing surface renderer's asset
bundle). Outputs are `build/reports/builder-plaque/motion.html`, `motion.gif`,
before/after/glow PNGs and provenance. Font metrics and positions come from the
actual resources/source; synthetic scenery, dust physics and the glow outline
remain approximations. Unknown custom fonts use conservative decoration bounds.

For the complete operator key inventory and the safe `/builder reload` runbook
(runtime overlays, transactional preflight, active-state drain, enabled/disabled
transitions, and fail-closed rollback), see
[Operator configuration and reload](ARCHITECTURE.md#operator-configuration-and-reload).

## Build

```bash
./gradlew shadowJar
```

For a focused change, run the relevant unit test explicitly, for example
`./gradlew test --tests 'ru.arc.buildertools.BuilderToolsReloadPreflightTest' shadowJar`. Consumer architecture
verification is opt-in with `python3 ../arc-core/scripts/verify_consumer_architecture.py .`.
Full `clean check shadowJar` verification and the disposable MySQL integration
suite are intentionally CI-owned.

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

The production artifact is the versioned `build/libs/ArcBuilder-<version>.jar`. From the ops
repository it is deployed independently with:

```bash
./scripts/mc arcbuilder classic_survival
```
