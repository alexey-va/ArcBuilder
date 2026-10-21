# Display decoration editor

`/builder display` opens the native editor menu. Access requires
`arcbuild.display`, independently of `arcbuild.use`. `arcbuild.display.admin`
additionally allows editing other owners' **editor-created** objects. It never
admits another plugin's displays, holograms or previews.

## Player controls

Hold any existing item; the editor issues no inventory tool. The menu can create
a decorative copy of the held block/item or a plain text display. The original
item stays in the inventory. Displays have no item collection or conversion path.

| Input | Result |
| --- | --- |
| Right-click an editor object | Select and start the current transform |
| Move the mouse | Move, rotate or resize the private draft |
| Right-click again | Validate and save the draft |
| Left-click | Cancel the gesture; a second click clears selection |
| F | Cycle Move → Rotate → Scale |
| Shift+F | Cycle free / world X / Y / Z |
| Wheel while transforming | Adjust distance / 5-degree rotation / scale |
| Shift while moving | Attach the transformed mesh to a collision surface |
| Shift while rotating / scaling | Snap to 15 degrees / 10 percent |
| Q | Cancel the current gesture and reopen the menu |
| Change hotbar slot while no gesture is active | End the editor session |

In free Rotate, horizontal mouse movement controls yaw and vertical movement
controls pitch. In Scale, looking upward enlarges and downward shrinks. The
surface magnet takes precedence over a constrained movement axis.

The menu also changes axes, duplicates the selection, edits its text, confirms
deletion and undoes up to 20 committed changes in the current session.
Deletion identifies the captured object's type and location. Text accepts at most
120 characters, four short lines and 240 default-font pixels per line; `\n`
introduces a line break. Text is literal Adventure content, not MiniMessage or a
command. Unsupported control/private-use/supplementary glyphs are refused.

Direct routes are `/builder display block [material]`, `item [material]`,
`text [text]`, `move`, `rotate`, `scale`, `axis [free|x|y|z]`, `confirm`, `cancel`,
`duplicate`, `delete`, `undo`, `tool` and `exit`. Ordinary builder commands end an
editor session before entering their own tool flow.

## Ownership and mutation

Persistent native BlockDisplay, ItemDisplay and TextDisplay entities carry the
plugin's `display_editor` version marker and `display_owner` UUID. The editor
does not scan worlds on startup or keep a second persistent object registry.
Shutdown removes only temporary drafts; saved objects remain in their chunks.

A gesture creates one nonpersistent, hidden-by-default native Display and shows
it only to its owner. The persistent source is hidden only to that viewer and
is never moved during preview. Cancel, permission/access loss, inventory opening,
teleport, world change, death, quit, chunk unload and module close discard the
draft and release the lock. The native small-scene exception is bounded to one
draft per session and 16 concurrent sessions. There is no durable preview state
or rollback write after authority is revoked.

Commit rechecks the separate permission, current held tool context, source marker
and owner, exclusive selection lock, exact source snapshot, and both source and
destination build access. Validation covers every block of each rendered
envelope, including height, world border, loaded chunks and reach. The runtime
adapter also applies operation/recovery locks and actual block placement access
in Lands, including air cells. Admin ownership override does not bypass these
world/protection checks.

Undo checks the same boundaries and refuses changed objects. Recreating a deleted
entity rebinds older undo records to its new native UUID. No action gives, drops,
places or rewards a real item/block.

## Geometry and work limits

Picking inverse-transforms the ray into each rendered mesh's coordinate space,
including entity rotation and the complete native Display transformation. It
does not use Display's empty server hitbox or an untransformed entity-origin
sphere. Slabs use their actual bottom/top/double model bounds and local centres.
The 651 verified vanilla direct generated/handheld item models use their native
1/16-block thickness, including when snapping a diamond to a wall. These shapes
are captured with the typed content; no block-state parsing or asset I/O runs
in the gesture loop. Text uses its authored default-font extent, a fixed
billboard and no wrapping at the configured width.

Other block silhouettes, conditional/special item models, explicit `itemModel`
and either custom-model-data representation retain a unit envelope. Transparent
item pixels and arbitrary resource-pack replacements are not per-pixel collision
meshes. Consequently those fallback models can have visible gaps when snapped.

The bundled item inventory comes from the official Minecraft 1.21.11 client JAR
(SHA-1 `ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd`): only direct `minecraft:model`
descriptors whose entire parent chain reaches `builtin/generated`, with no
explicit elements or `display.none` transform, are included. Native
`ItemModelGenerator` fixes Z to 7.5–8.5 model units out of 16. Vanilla
`block/slab` and `block/slab_top` define Y 0–8 and 8–16 respectively. Refresh
this bounded inventory against the official assets when upgrading Minecraft.

A click queries only a 20-block AABB, rejects more than 256 native candidates,
then checks the nearest visible ray hit within 12 blocks. Paper's collision ray
runs only after the complete epsilon-expanded chunk rectangle is loaded; an
unloaded diagonal corner cannot cause a native chunk load. The nearby-entity
query itself is one indivisible platform call.

Transforms permit scales 0.05–4, a maximum eight-block envelope per axis, at most
729 footprint cells and 20-block corner reach. Full footprint protection runs
only on final actions. Per-tick previews check the source/destination centre and
loaded bounds; the existing runtime tick owns the bounded 16-session loop. Idle
sessions expire after ten minutes. A private cyan outline plus red/green/blue
world-axis particles uses at most 51 points every four ticks; axis guides start
outside the transformed object's envelope.

## Evidence

Pure tests cover affine picking, empty AABB corners, transformed surface contact,
negative coordinates, complete footprints, limits, chunk-corner preflight,
ownership and exclusive locks. MockBukkit tests exercise private-to-persistent
commit, inventory preservation, source/destination protection, stale snapshots
and callbacks, permission loss, cancellation, quit, undo chains and cleanup.
Shape tests cover all slab variants, generated/handheld items, special-model and
custom-data fallbacks. Controller tests check the saved native slab top against
a ceiling and the generated diamond face against a wall after Shift snapping.
The test harness replaces MockBukkit's unimplemented native collision ray and
dialog display/dismissal. Its pre-spawn factory supplies the missing
`visibleByDefault` and `billboard` property accessors while preserving the real
mock registry, entity lifecycle and production initializer. Production uses
Paper 1.21.11 APIs. The custom-model fallback test also preserves explicit model
and component metadata that MockBukkit's `ItemMetaMock.clone/equals` omit.

The dialog composition test checks both locale catalogs, action order, selected
labels, object identity in deletion and the 426-pixel width budget. Offline
source-derived previews establish composition only. Native-client rendering,
mouse feel, ray collision with custom models and packet/tracker timing need a
real Paper/client observation; build or mock results do not establish them.
