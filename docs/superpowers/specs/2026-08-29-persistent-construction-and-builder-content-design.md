# Persistent Construction and Builder Content Design

## Goal

Move construction books from one atomic inventory exchange to durable,
incremental construction projects; retain the shared legacy schematic catalog,
restore the starter-house book, publish a comprehensive building shop on both
servers, and replace both construction-book item textures with real 32x32
pixel art.

## Current failure

`BUILD_BOOK` plans currently contain the exact book plus every player-supplied
material as an up-front cost and every replaced block as an up-front reward.
`BuilderInventory.canApply` therefore rejects the preview unless the player
already owns all materials and has room for every reward. The shared
`errors.inventory` text also mentions tool durability even though build-book
plans carry no tool fingerprint and use `toolDamage = 0`.

## Selected architecture

Only `BUILD_BOOK` leaves the ordinary atomic builder-operation path. Confirming
a build book first creates a durable construction project, consumes the exact
book through the existing one-time-use boundary when applicable, and then
places blocks incrementally. Fill, replace, paste, deconstruct, crown,
disconnect, undo, and their inventory transaction semantics remain unchanged.

A project stores the immutable plan, owner, world and placement identity,
current cursor, pending output, state, and timestamps in a local
`DurableRecordJournal`. ArcBuilder is the sole writer and survival is the world
owner, so MySQL remains authoritative only for registered book identity and
one-time consumption. The project journal is written before any world or
inventory mutation. Startup reloads non-terminal projects and resumes them only
after exact block-state and book-consumption reconciliation.

Projects build bottom-up. For each next change the engine:

1. revalidates the target block, world border, loaded chunk, and Lands build
   permission;
2. derives the canonical placement item;
3. consumes that item only when its material is in the book's
   `playerMaterials`; shop-included materials are already paid and virtual;
4. writes any safe Silk Touch replacement refund to project storage;
5. applies the block without physics and records CoreProtect evidence;
6. durably advances the cursor before continuing.

If a required item is absent, the project enters `WAITING_MATERIALS`. If a
refund cannot be stored, it enters `WAITING_OUTPUT_SPACE`. Neither case rolls
back completed construction or drops items into the world. Supplying the item
or emptying a source container allows the next scheduler pass to resume.

## Resource containers and theft boundary

Eligible resource containers are vanilla chests, trapped chests, and barrels
inside a configurable four-block shell around the construction bounding box.
Double chests are deduplicated by inventory identity. Hoppers, furnaces,
shulker boxes, ender chests, custom blocks, and other inventories are excluded.

The approved access rule is:

- wilderness containers are eligible;
- containers in a Land are eligible only when the construction owner has
  Lands `INTERACT_CONTAINER` permission at that exact block;
- a project outside a Land must never read a neighboring container inside a
  foreign Land unless the owner could really open it;
- build permission at the project is not a substitute for container access.

The check uses the owner's UUID so chest-backed construction may continue while
the player is offline. When the owner is online in the same world and within 48
blocks of the site bounds, their storage inventory is searched before nearby
containers. Outputs use containers first and the nearby online player's
inventory second. No item is ever removed from or inserted into a container
whose access check fails at the moment of mutation.

## Restart and conflict behavior

The cursor advances only after the corresponding block and resource/output
exchange is durably classifiable. On restart ArcBuilder compares each completed
prefix block with the recorded `afterBlockData` and the next block with its
recorded `beforeBlockData`. An externally changed next block pauses the project
as `RECOVERY_REQUIRED`; ArcBuilder never overwrites or guesses. The same project
ID remains locked against a second active build by the player and against
overlapping ArcBuilder chunk mutations.

The exact construction book is consumed at project activation, not project
completion. Registered player books still use their MySQL claim/commit ledger.
Whitelisted legacy/system books are physical one-use items and do not pretend
to be player-authored registry instances.

## Legacy catalog and starter kit

The shared library remains rooted at `plugins/ARC/schematics`, with ArcBuilder's
runtime path `../ARC/schematics`. Spawn and survival root symlinks must resolve
to the same real catalog before rollout. The old Denizen `buildings.yml` mapping
is migration input for a tracked ArcBuilder system-book catalog; the runtime
does not call Denizen.

Legacy books are accepted only when their `arc:building_key` is present in that
catalog and the referenced schematic's current SHA-256 matches the catalog
entry. Arbitrary legacy NBT therefore cannot select an unreviewed file. The CMI
`start` kit continues to issue the `viking.schem` first-home book, updated to
the active construction-book model data. CMI kit writes use the native ARC CMI
API and are read back from both nodes; serialized `Kits.yml` is never edited by
hand.

## Building shop

Spawn and survival receive byte-identical `sections/Building.yml` and
`shops/Building.yml`. The shop is split into filled pages for wood families,
stone/masonry, colored blocks/glass, copper/metals, natural materials, and
utility decoration. It includes every vanilla wood construction family
available in Paper 1.21.11, including pale oak logs, wood, stripped variants,
planks, slabs, stairs, fences, gates, doors, trapdoors, signs, and hanging
signs.

Prices reuse an existing buy/sell pair for the same material when one exists.
Missing crafted variants derive from the family's base material and vanilla
recipe yield, rounded to two decimals; sell price stays at or below 25 percent
of buy price. The two files are validated for unique slots, valid materials,
full pages before later pages, positive buy prices, and non-arbitrage.

## Construction-book textures

The existing `book_draft.png` and `book_active.png` are 16x16 and are replaced
at their existing model paths by two native 32x32 RGBA textures with transparent
backgrounds. They are redrawn rather than scaled:

- draft: light parchment blueprint book with a pencil/unfinished construction
  cue;
- active: copper-bound deep teal construction book with a completed structural
  cue;
- both use crisp one-pixel contours, at most twelve deliberate colors, no
  antialiasing, and a readable silhouette at native GUI size.

Spawn `contents/` is canonical. The normal ItemsAdder publication flow mirrors
it to survival. Validation checks exact 32x32 dimensions, RGBA, transparency,
palette size, non-empty alpha bounds, model references, and nearest-neighbor
previews before `/iazip` activation.

## Player feedback

Build-book startup no longer uses `errors.inventory`. Dedicated Russian and
English messages distinguish project creation, progress, missing materials,
missing container space, resumed-after-restart, completion, cancellation, and
recovery-required state. No build-book message mentions a tool. The project
status output lists progress, the next missing material, and the number of
eligible resource containers without exposing protected containers.

## Verification and rollout

ArcBuilder follows test-first development, local `test shadowJar`, consumer
architecture verification, and required GitHub MySQL CI. Ops YAML is validated
before deployment. Production rollout order is ArcBuilder JAR, runtime config
and catalog, CMI kit preview/upsert/readback, shop files plus reload, then the
canonical ItemsAdder textures plus `/iazip`. Each surface gets live readback;
no player item is issued for QA without separate player-mutation authorization.
