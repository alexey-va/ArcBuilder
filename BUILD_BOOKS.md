# ArcBuilder construction books

Start here for any construction-book change. The broader transaction and
plugin map remains in `ARCHITECTURE.md`.

## Ownership map

| Concern | Owner |
| --- | --- |
| Item PDC, display name, lore and tooltip style | `BuildBook.kt`, `modules/auto-build.yml` |
| Right-click state machine and preview handoff | `BuilderBookInteractionPolicy.kt`, `BuilderToolsRuntime.kt` |
| Position, rotation and visible model | `BuildingManager`, `ConstructionSite`, the display renderer |
| Player-authored activation and copying | `BuilderBookLifecycle.kt` and mint coordinators |
| Persistent gradual construction | `BuilderConstructionProject.kt`, `BuilderConstructionResources.kt` |
| Reviewed legacy/system books | `SystemBuildBookCatalog.kt`, `modules/system-build-books.yml` |
| Starter-book delivery | CMI `kitstart` in the sibling operations repository |

## Player interaction contract

- Right-click a block: open or move exactly one book preview. If a prepared
  build plan exists, discard that plan before opening the new preview.
- Right-click air with the exact preview open: close the block preview and
  prepare the confirmation plan.
- Right-click air again with that book's plan prepared: keep the same plan,
  repeat its chat summary, and show the confirmation title. Never create a
  second plan or an ownerless block preview.
- Right-click air without a matching preview or plan: explain that a block
  preview is required.
- Sneak-right-click: open book settings. Only the authoritative blueprint
  author sees the copy button; ownership is revalidated when the paid copy is
  confirmed.

The invariant is one active book surface per player: block preview, prepared
plan, or neither. A transition must clean up the surface it leaves.

## Names, prices and materials

Player-facing text uses the catalogue title, never a schematic filename such
as `viking.schem`. The starter entry is `Стартовый дом`.

`issuePriceMinor` is the price of a newly issued player-authored copy. It is
not a construction charge. Construction never withdraws money. Ordinary
projects consume only the physical book at start and wait persistently for
each later material or nearby permitted output slot.

System entries may set `materials-included: true`. Such a book consumes only
itself and requires no building materials. This is intentionally enabled for
the `kitstart` starter house.

A reviewed system entry may also set `container-loot-table` to a vanilla
namespaced loot table. Only ordinary chest steps carrying that exact catalogue
setting bypass the general container-placement ban; player-authored and other
system-book containers remain skipped. The starter house uses
`minecraft:chests/spawn_bonus_chest`.

The player's inventory and hotbar remain interactive while a persistent
project builds. If an item captured for the next durable debit is moved or is
no longer available, that step returns to `WAITING_MATERIALS` and tells the
player to supply it in their inventory or a permitted nearby container.

## Schematic storage and origin fixes

The live canonical files are under the shared Minecraft root
`ARC/schematics`. ArcBuilder reaches the same directory through
`plugins/ArcBuilder/modules/builder-tools-runtime.yml` with the explicit
`../ARC/schematics` path. Spawn and survival links must resolve to that one
shared root; never copy the catalogue into separate per-server folders.

The reviewed catalogue pins every system schematic by SHA-256. After a
deliberate schematic edit, update the file and its catalogue hash together.
The maintenance task shifts the Sponge placement offset without rewriting
blocks or block-entity coordinates:

```bash
./gradlew rebaseSchematicOrigin \
  -PschematicInput=/path/input.schem \
  -PschematicOutput=/path/output.schem \
  -PschematicShiftY=1
```

For the starter house the corrected invariant is `Offset Y = 0`; the old file
had `Offset Y = -1` and appeared one block underground.

## Verification and rollout

Run only the repository-approved local checks:

```bash
./gradlew --no-daemon test shadowJar
python3 ../arc-core/scripts/verify_consumer_architecture.py .
```

Deployment is a separate operations transaction. Track and deploy the four
ArcBuilder module configs before the matching JAR, replace the shared schematic
atomically, update the CMI kit through its supported runtime content API, then
restart survival once. Read back exact config/JAR/schematic hashes, plugin
readiness, registry recovery, and recent WARN/ERROR logs before declaring the
rollout complete.

## Console issuance for reviewed schematics

`builder systembook <online-player> <catalogue-file.schem>` issues one system
book through ArcBuilder's own codec. It accepts the console or an in-game player with `arcbuild.admin.systembook`
(default: OP), and requires an enabled,
SHA-256-matched catalogue entry, a readable schematic within the configured scan
volume, and an empty storage slot on the current node. It never drops overflow
or manufactures a registered player blueprint. The catalogue remains the source
of the title and material policy. The resulting book uses the ordinary preview,
confirmation, material collection and one-use construction path, including its
existing unsafe-block exclusions. Issuing a book does not paste blocks.

Administrators can use `/builder systembook <catalogue-file.schem>` to issue
to themselves, or `/builder systembook <online-player> <catalogue-file.schem>`
to select a recipient. Command blocks cannot issue books.

### Reviewed system furniture

Digest-reviewed system books can place empty vanilla beds, chests, barrels,
furnaces/smokers/blast furnaces and flower pots. The exception is carried through
confirmation and the durable construction journal; it never admits schematic
block-entity NBT or replacement of an existing player container. Other tile
entities remain excluded. Beds use the existing atomic pair and one-bed cost.
For player-supplied materials, potted plants consume one flower pot; the plant is included decorative content
of the reviewed system book. As before, `materials-included` system books supply
all construction materials, including pots and beds. Player-authored books do not gain this exception.
Chest loot remains an explicit `container-loot-table` catalogue opt-in and uses
the existing durable loot-table application/recovery path.

Administrative `systembook` tab completion offers the command, online recipients,
and enabled reviewed catalogue IDs. Players can complete a catalogue ID directly
for self-issuance; console senders must first select an online recipient.
Catalogue suggestions use the validated in-memory catalog; issuance still
rechecks the schematic digest before creating the item.
