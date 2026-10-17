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

System entries default to `materials-included: true`: the server supplies the
construction materials and the player spends only the physical book. Every
current catalogue entry uses this policy, including all starter choices.
An explicit `false` remains an operator override; player-authored books retain
their own paid/player-supplied material contract.

Issued books contain the selected schematic's full cell count minus barrier
markers (air is included). Issuance retains each loaded clipboard in
`BuildingManager`; the count is cached with that Building. Switching the house
recomputes its displayed count, and normal interaction refreshes older system
books from the current catalogue. This is schematic volume, not an estimate of
how many blocks differ at a particular world location. Existing construction
journals retain their immutable material steps.

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

`minecraft:barrier` in a schematic marks a position whose existing world block
must be preserved. Book preview and construction omit these cells before reading
or planning target blocks, including rotated and mirrored books. Ordinary air
still clears a position. Mark exterior terrain/air with barriers while retaining
the house, foundations and the air needed to clear rooms and entrances.
Existing saved construction projects retain their original immutable steps.

The live canonical files are under the shared Minecraft root
`ARC/schematics`. ArcBuilder reaches the same directory through
`plugins/ArcBuilder/modules/builder-tools-runtime.yml` with the explicit
`../ARC/schematics` path. Spawn and survival links must resolve to that one
shared root; never copy the catalogue into separate per-server folders.

ArcBuilder opens that explicit root through the `ArcSchematicService` registered
by ArcCore. Every file lookup, verified load and native rotation/mirror is
asynchronous, including cache hits and book previews. The catalogue reads only
its YAML metadata; startup and reload do not decode, hash or preload the library.
Only the chosen option is loaded for selector changes and system-book issuance.
Arc Core owns the bounded expiring cache; ArcBuilder keeps a snapshot only while
its request, preview or plan needs it. Missing WorldEdit/provider service refuses
the requested book operation without falling back to synchronous file loading.

Use nested extensionless IDs such as `civs/myschema`. Existing exact `.schem`
and `.schematic` IDs remain accepted. Root containment, symlink boundaries,
size limits and ambiguous extensionless matches are validated by the provider.
The ID saved in an issued book is not normalized or rewritten, so existing PDC,
SQL identities and one-time-use fingerprints retain their original values.
Historical content hashing still uses minimum-relative non-air cells in Y/X/Z
order and canonical Bukkit block-data strings; the placement origin stays
separate in the player filename.

While a schematic loads, the book reports that it is loading. A late reply may
only use the original player session, world, mode, slot and exact item. Preview
clicks also retain the original sneak action and facing. Cancel, clear, quit and
module shutdown invalidate their pending continuations. Accepted console grants
use independent tickets (at most 256 pending) and recheck recipient session and
inventory space; unrelated hotbar, world or mode changes do not cancel a grant.

After a durable construction project has finalized its completion record,
ArcBuilder asks the shared service to reconnect fences, walls and panes whose
world state still exactly matches the committed block state. This bounded pass
never changes unfinished journal steps or replays broad neighbor physics. It is
best effort across shutdown and does not create a second durable repair queue.

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
# Before publishing a coordinated arc-core version, use its local composite:
./gradlew -ParcCoreDir=../arc-core test shadowJar
python3 ../arc-core/scripts/verify_consumer_architecture.py .
```

Deployment is a separate operations transaction. Track and deploy the four
ArcBuilder module configs before the matching JAR, replace the shared schematic
atomically, update the CMI kit through its supported runtime content API, then
restart survival once. Read back exact config/JAR/schematic hashes, plugin
readiness, registry recovery, and recent WARN/ERROR logs before declaring the
rollout complete.

## Console issuance for reviewed schematics

`builder systembook <online-player> <catalogue-id>` issues one system
book from the console through ArcBuilder's own codec. In-game issuance requires
`arcbuild.admin.systembook` (default: OP). Both forms require an enabled catalogue entry and an empty storage slot
on the current node. It never drops overflow
or manufactures a registered player blueprint. The catalogue remains the source
of the title and material policy. The resulting book uses the ordinary preview,
confirmation, material collection and one-use construction path, including its
existing unsafe-block exclusions. Issuing a book does not paste blocks.

Administrators can use `/builder systembook <catalogue-id>` to issue
to themselves, or `/builder systembook give <online-player> <catalogue-id>`
to select a recipient. The console always uses the recipient-first form, including
for a player named `Give`. In-game `give` is a keyword when followed by arguments;
a single `give` remains a self-issued catalogue ID. Other manually entered
recipient-first player commands remain accepted. Command blocks cannot issue books.

Issuance immediately writes the canonical catalogue path, title and material
policy into the book without loading schematic geometry or storing a digest.
Legacy catalogue aliases resolve to that same canonical ID. The schematic is
loaded asynchronously on first preview through ArcCore's shared service.
An in-game issuer sees the delivered title and recipient or a localized refusal;
success is reported only after the book occupies a recipient slot. Refusals are
logged with issuer, recipient, schematic ID and the original cause. Console
`SYSTEM_BOOK_ISSUED` / `SYSTEM_BOOK_REFUSED` responses remain stable for native tooling.

Use `random @starter` to issue a random starter book, or provide an explicit
pool: `builder systembook <online-player> random house-a.schem,house-b.schem` from the console.
The `@starter` pool consists of validated catalogue entries marked
`starter-enabled: true`; the issued book keeps every selected ID as a selector
option. Bare `random` is refused so the issuer always controls the pool. Add a
new starter by setting the flag in the reviewed catalogue. Regular catalogue
IDs remain available for direct issuance and completion.

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

Player `systembook` completion offers `give`, `random` and the current catalogue
level; online recipients appear only after `give`. Console completion starts with
online recipients. Catalogue suggestions use validated in-memory
metadata, never schematic I/O. Folder suggestions have no trailing slash in the
client; typing `/` opens the next level. Aliases remain callable without adding
duplicate catalogue choices.

Atelier system houses define their schematic origin at the front-center ground
block. A ground click maps that origin to the exact clicked block, so floor/ground
height is preserved and the entrance is in front of the player. Rotation remains
around that origin. Updating reviewed schematic files and catalogue hashes takes
effect for existing unopened system books after activation; previously saved
construction plans keep their own immutable coordinates.
