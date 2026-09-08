# ArcBuilder architecture

Use this file as the starting map for structural work. It describes the
standalone Paper plugin, its command flow, safety boundaries, persistence, and
the files that normally change when a builder operation is added.

## Repository boundary

ArcBuilder owns survival-friendly building tools and construction books. It is
independent from the `ARC` monolith and consumes published `arc-core` modules
for lifecycle, scheduling, text, player-state snapshots, SQL, logging, and
health reporting.

- Paper entry point: `src/main/kotlin/ru/ruscrafting/builder/paper/ArcBuilderPlugin.kt`
- Builder module binding: `src/main/kotlin/ru/arc/buildertools/BuilderToolsModule.kt`
- Runtime orchestration: `src/main/kotlin/ru/arc/buildertools/BuilderToolsRuntime.kt`
- Command and permission declarations: `src/main/resources/plugin.yml`
- Builder policy, limits, and player text: `src/main/resources/modules/builder-tools.yml`
- Book presentation and behavior: `src/main/resources/modules/auto-build.yml`
- Reviewed legacy/system book catalogue: `src/main/resources/modules/system-build-books.yml`

`ArcBuilderPlugin` installs the Paper runtime and optional hooks,
initializes `BuilderToolsModule`, publishes health, and closes those owners in
reverse order. Startup fails closed if a required runtime invariant is not met.

## Command path

`/builder` is bound by `BuilderToolsModule` and delegated to one
`BuilderToolsRuntime`. Root literals live in `BuilderRootCommand` inside
`BuilderToolsExperience.kt`; tab completion and dispatch live in
`BuilderToolsRuntime`.

| Command | Planner or owner | Plan kind | Permission |
| --- | --- | --- | --- |
| `wand`, `clear` | `BuilderSelectionController` plus the runtime | none | any builder permission |
| `fill` | `BuilderFillController` | `FILL` | `arcbuild.fill` |
| `replace <old> <new> [confirm]` | `BuilderReplaceController` | `REPLACE` | `arcbuild.replace` |
| `disconnect [confirm]` | `BuilderFenceConnectionController` | `FENCE_DISCONNECT` | `arcbuild.disconnect` |
| `copy`, `paste` | `BuilderClipboardController` | `PASTE` for paste | `arcbuild.copy`, `arcbuild.paste` |
| `deconstruct` | `BuilderDeconstructionController` | `DECONSTRUCT` | `arcbuild.deconstruct` |
| `crown` | `BuilderCrownController` | `CROWN` | `arcbuild.crown` |
| `book` | `BuilderBookLifecycle` and its coordinators | `BUILD_BOOK` when building | book permissions |
| `confirm`, `cancel`, `undo`, `status` | `BuilderToolsRuntime` | `UNDO` for undo | existing operation context |

`BuilderPermissionPolicy.kt` is the canonical mapping between features and
permissions. `arcbuild.use` is the umbrella permission; size and hourly tiers
are resolved there as well.

`BuilderPlanningHost` is the shared runtime preflight and plan boundary for
the fill, replace, and fence-disconnect controllers.

## Selection and preview

`BuilderSelectionController` owns two in-memory corners per player. The selector
wand is recognized by persistent item data in `BuilderToolsRuntime`. A complete
selection becomes the immutable `BuilderSelection` domain value from
`BuilderToolsDomain.kt`, which provides bounded top-down and bottom-up position
iteration.

The runtime validates axis and scan-volume limits before a planner sees the
selection. `BuilderBlockDisplayRenderer` and `BuilderPreviewSessions` render
player-only selection and plan displays; preview rendering never mutates the
world. Selection, clipboard, pending plan, and active operation are distinct
states and must not be collapsed into one session object.

The completed-selection chat surface exposes only operations that consume the
current selection. `copy` runs immediately because it only records a temporary
clipboard, while `disconnect` and `deconstruct` run only their preview-producing
forms. Commands that require material arguments (`fill` and `replace`) use
`suggest_command` with a trailing space so the player finishes the command;
the surface never clicks an immediate-confirm variant.

## Plan and mutation transaction

All ordinary world changes use `BuilderPlan` and `BuilderBlockChange` from
`BuilderToolsDomain.kt`:

1. A feature controller scans the selection on the Paper primary thread. It
   checks feature permission and protection, then records exact canonical
   `beforeBlockData` and `afterBlockData`. Planning does not change the world.
2. `BuilderToolsRuntime.preparePlan` installs a timed preview. The player still
   owns all materials and the world remains untouched.
3. `/builder confirm` revalidates the plan, game mode, inventory snapshot,
   block states, range, loaded chunks, world border, and build protection.
4. The journal crosses `PREPARED` and `APPLYING` durability barriers before
   mutation. `BuilderOperationLocks` prevents conflicting player or chunk work.
5. Changes are applied in bounded per-tick batches with
   `Block.setBlockData(after, false)`. CoreProtect is notified for each applied
   change when its bridge is available.
6. Rewards are delivered and the journal becomes `COMMITTED`. Any failure
   restores prior block data and player state or leaves an explicit recovery
   hold instead of guessing about an ambiguous outcome.
7. `/builder undo` builds a new inverse plan from a committed record; it uses
   the same preview, confirmation, protection, journal, and recovery path.

`BuilderToolsPaperSupport.kt` contains the local journal, exact item codec and
inventory exchange, block-safety policy, placement-cost rules, and optional
CoreProtect bridge. `BuilderPlayerRecoveryCoordinator` owns delayed player-state
recovery after disconnects or uncertain acknowledgement.

## Committed neighbour physics

Ordinary plans write blocks without physics while inventory and journal rollback
are still possible. After the COMMITTED durability barrier, the runtime releases
the plan's world locks and runs one bounded pass (at most `blocks-per-tick`
positions per tick), retaining the player lease until it finishes. It never
rewrites a block that earlier physics or another actor has already changed.
Physics failures are logged and reported separately; a committed operation is
never rolled back after vanilla drops or neighbour changes may have happened.

`PaperBuilderPhysicsUpdater` binds the exact Paper 1.21.11
`Level.notifyAndUpdatePhysics` method. It uses UPDATE_NEIGHBORS (1), depth 512,
and the original/current block states. This performs both neighbour and shape
updates without a second block write or duplicate client packet. The binding is
validated before entering the mutation transaction. Reapplying identical data
with `setBlockData(..., true)` is a vanilla no-op and is not used as a refresh.
The adapter is version-bound; the real Paper E2E lamp/undo scenario verifies it.
The implementation contract is documented in [Paper 1.21.11 Level.java.patch](https://raw.githubusercontent.com/PaperMC/Paper/ver/1.21.11/paper-server/patches/sources/net/minecraft/world/level/Level.java.patch).

Fence-disconnection plans and their undo intentionally skip this pass. Durable
construction-book steps use their separate existing lifecycle. Vanilla physics
may change blocks beyond the selected region and continue on later ticks; undo
retains exact-state validation and refuses stale tracked states. This pass is
best-effort across server shutdown, not a persisted physics simulation or a
snapshot of all possible vanilla cascades.

## Safety and integrations

`BuilderBlockSafety` rejects unsafe technical state, tile entities, custom
Slimefun/ItemsAdder blocks, extended pistons, occupied beds, waterlogged
state, materials without a canonical construction item, every current
`*_ORE` material, and ancient debris. Treating reward-bearing ores as unsafe
also excludes them from clipboard capture and makes both ordinary-plan and
durable construction-book revalidation fail closed. Individual controllers add
operation-specific restrictions. Ordinary redstone components, lit and powered
states are allowed. The material gate detects tile entities from Paper's
`BlockData.createBlockState()` and caches the result, rather than rejecting
whole name families. Containers and other blocks with tile data remain outside
the lossless plain-item transaction.

`BuilderToolsRuntime.ensureMutable` is the shared boundary for range, loaded
chunks, world border, Lands build permission, and placement checks. Wilderness
is buildable. WorldGuard is deliberately not a dependency. CoreProtect is
required by the bundled policy unless the runtime override disables that gate
for a controlled test environment.

Optional integrations are discovered in `ru.arc.hooks`: Lands, Slimefun,
ItemsAdder, EconomyShopGUI, RedisEconomy/Vault, and zAuctionHouse. Feature code
must go through these boundaries rather than linking optional plugins directly
from domain values.

## Fence disconnection

`/builder disconnect` is intentionally a one-shot selection operation. The
plain command prepares a preview; `/builder disconnect confirm` applies the
same plan immediately. Both forms use the same preflight, revalidation,
journaling, mutation, CoreProtect, and undo pipeline.

The planner has deliberately narrow scope:

- only vanilla materials whose names end in `_FENCE` are considered;
- fence gates, walls, panes, custom blocks, and other `MultipleFacing` blocks
  are not changed;
- every currently connected `MultipleFacing` side is cleared in the planned
  block state;
- already disconnected fences are skipped;
- the operation has no item cost or reward and remains confirmed and undoable;
- a later neighbor update may reconnect a fence according to vanilla physics.

Do not add coordinate persistence or block-physics listeners to this command.
Persistent connection suppression would be a separate feature with a separate
lifecycle and data model.

## Deconstruction tools

Survival deconstruction scans the complete storage inventory for damageable
tools. The planner searches the held slot first and then all other storage slots
for a preferred tool for each block. It pools their durability, never spends the
final point on any tool, and snapshots every used slot into the journaled plan
so confirmation fails if an item moves or changes.

Drops use Paper's normal tool-aware drop query with Silk Touch removed from a
clone of the selected tool; all other item state, including Fortune, is kept.
The explicit `arcbuild.deconstruct.without-tool` permission permits bare-hand
fallback when there is no suitable remaining tool. It is deliberately not a
child of `arcbuild.use`, so operators must grant it separately. Because this
permission is declared in `plugin.yml`, adding it to a running server requires
the normal plugin restart path rather than `/builder reload`.

## Exact block replacement

`/builder replace <old> <new>` scans the current selection for one exact vanilla
material. The plain command prepares a preview; adding `confirm` as the final
argument applies the same plan immediately. Both paths use the ordinary
preflight, inventory, journal, CoreProtect, and undo transaction.

Material arguments in `replace` and `fill` accept both Bukkit names and Russian
camelCase names, for example `diamond_block` or `алмазныйБлок`. Matching ignores
case; Russian aliases also accept `е` for `ё` and underscore separators.
`BuilderMaterialArguments` owns parsing and completion names. Ambiguous Russian
names are omitted instead of silently choosing a material. Replace completion
uses the planner's coupled-block restriction and default-state safety checks;
legacy materials are excluded before querying modern Bukkit material metadata.
Russian names precede English identifiers in material completions. The runtime
also reorders the final `AsyncPlayerSendSuggestionsEvent` result because
Brigadier sorts Bukkit completions before packet delivery; it preserves the
existing suggestions, ranges, tooltips and permission filtering.

The bundled `materials/ru_ru.json` contains the `block.minecraft.<id>` labels from
Minecraft 1.21.11's official Russian asset (SHA-1
`6efaa4396b51eae6de896704c442cd8002b1a66c`, asset index
`951ed1deacbc1d616ba0378d8e110008249c3e40`). It requires no runtime download.

`BuilderReplaceController` copies only block-data properties supported by both
source and target through `BuilderCompatibleBlockState`. This preserves useful
state such as direction, rotation, stair half and shape, slab type, log axis,
fence faces, candle count, lantern suspension, snow cover, and walls without
copying unrelated state. Unsafe containers and custom blocks are skipped.
Coupled multi-block sources such as doors, beds, and tall bisected blocks are
skipped, while coupled targets are rejected, so the operation never creates or
replaces a single incomplete half.

In survival, every changed block consumes the target construction item and
returns the source construction item when present. `replace air stone` (or
`replace воздух камень`) fills all three vanilla air variants and returns no
source item. Explicit `cave_air` and `void_air` match only their exact variant.
The journal retains each original state for undo. Air is suggested only as the
source argument. Creative plans carry no item exchange.
Replacing with air remains unsupported; removal belongs to `deconstruct`.

## Clipboard and construction books

`BuilderClipboardController` copies only safe blocks, stores coordinates
relative to the selected cuboid and the player's anchor, rotates cloned block
data, and plans paste at the player's current block position.

The book subsystem is larger and should be entered through
`BuilderBookLifecycle.kt`:

- `BuilderDraftLifecycle` and `BuilderDraftJournal` own free draft creation and
  crash recovery;
- `PlayerBuildBookStore` owns player schematic persistence;
- `BuilderBookSqlRegistry` is the authoritative MySQL registry for blueprints,
  instances, minting, ownership generation, and one-time use;
- claim, mint, release, pricing, wallet, auction, and status behavior is split
  into the correspondingly named coordinators;
- `BuildingManager` and `ConstructionSite` own interactive book previews and
  transforms.

Confirmed build books do not use the ordinary all-at-once mutation path.
`BuilderConstructionProject.kt` owns a durable, restart-safe project that
advances one exact step at a time. A validated door, bed, or other true
two-block pair is ordered by its item-owning primary half and both halves are
written in the same server tick after both positions pass the live safety
check; the durable companion step then reconciles the already-applied block.
Only the physical book is consumed at startup. Each step obtains its own
material later, applies its prevalidated change, and stores any replaced block
before advancing the durable cursor.
Missing material or output space is a waiting state, not a failed build.
Ambiguous block, permission, persistence, or output-delivery state fails closed
into `RECOVERY_REQUIRED`.

At confirmation, the project durably records the vertical construction face
whose center is nearest to the player. The global site display recreates its
fixed text panel outside that face after restart, with a face-specific yaw;
`construction.site.panel.face` is only the fallback for legacy records that do
not contain this presentation field.

Every book debit, step debit, and recovered-block delivery uses a durable
write-ahead receipt containing the exact source identity plus before/after
inventory slots. Replaying a receipt recognizes an already-applied effect,
finishes a provably partial effect, or refuses drift; it never blindly repeats
an item mutation after a crash or rejected journal write.

`BuilderConstructionResources.kt` is the sole material boundary for these
projects. It searches the online owner's nearby inventory plus vanilla chests,
trapped chests, and barrels in the configured shell outside the construction
bounds. Wilderness containers are allowed. A container in a Lands claim is
included only when the project owner's UUID has `INTERACT_CONTAINER` there;
that permission is checked once during discovery and again immediately before
inventory mutation. Never replace this with a check against the construction
site's claim, because a wilderness project may sit beside somebody else's
protected chest.

System/legacy physical books are deliberately separate from player-authored
registered books. `SystemBuildBookCatalog.kt` loads the reviewed catalogue,
constrains every path to the shared schematic root, and verifies its SHA-256 at
startup and again on use. Do not make every file in the shared root callable:
it also contains player schematics and operational test files.

`BuilderBookInteractionPolicy` is the canonical state machine for book clicks.
A player may own one block preview or one prepared build plan, never both.
Opening a new block preview discards an existing book plan first; repeated air
clicks keep and explain the same prepared plan instead of creating an orphaned
display. `BuilderToolsRuntime` canonicalizes legacy item titles from the system
catalogue before either transition, so filenames are never player-facing.

Player-authored copying is author-only at both boundaries: the editor exposes
its copy button only to the blueprint creator, and `BuilderBookLifecycle`
revalidates authoritative blueprint ownership immediately before charging and
minting. A stored issue price describes a newly issued copy, not construction;
building never withdraws money.

System catalogue entries may opt into `materials-included`. Those books consume
only their physical book and create steps without material requirements. The
starter `viking.schem` book uses this policy. Its Sponge `Offset Y` is zero so a
ground anchor places the house on, rather than below, the clicked surface.
The same reviewed entry may opt ordinary chest steps into one namespaced
vanilla `container-loot-table`; durable replay verifies both block data and the
assigned table before advancing. Player-authored containers remain unsafe.
See `BUILD_BOOKS.md` for the maintenance command, live shared-root contract,
CMI kit boundary, and rollout checklist.

The MySQL integration suite belongs to GitHub Actions and must not be run
locally.

## Configuration and player-facing text

`BuilderToolsConfig` layers bundled `builder-tools.yml` with the generated
runtime override `builder-tools-runtime.yml`. It validates numerical bounds and
requires every locale to contain every plan-kind label. New player-facing text
belongs in both `locales.ru` and `locales.en` in `builder-tools.yml`.

`visual-preview.yml` maps every localized surface to the gallery renderer.
Update it when a new key is not already covered by the existing chat or
fragment globs.

### Operator configuration and reload

The bundled `modules/builder-tools.yml` is the policy source of truth. Its
operator-tunable groups are:

| Group | Keys and effect |
| --- | --- |
| `enabled`, `allowed-worlds`, `storage` | Gate the module, select worlds, and select the schematic root. `enabled`/worlds/root may be overlaid by `builder-tools-runtime.yml`. |
| `limits`, `timers` | Change caps, range, plan/clipboard/undo lifetimes, and journal retention. |
| `construction` | Container search radius, online-inventory range, tick period, probe budget, bounded per-project container cache, per-call resolution budget, sampled sound/particle feedback, and the construction-site outline/panel presentation. |
| `runtime` | In-memory health refresh period, player-recovery retry period, and progress cadence. Lifecycle health-log cadence remains platform-owned. |
| `preview` | Preview cadence/radius, selection particle budget, nearest-block display budget, plan display range, guidance/recentering cadence, and plan-title timings. |
| `shop` | Read-only quote and auto-buy gates/limits. |
| `book-contracts` | Contract enablement/pricing, auction recovery retry, player-material summary limit, and MySQL settings. Contract enablement/pricing/SQL may be overlaid by the runtime file. |
| `safety` | Lands/CoreProtect requirements and the replaceable-material allowlist. |
| `locales` and `default-locale` | Player-visible messages, including `locales.*.reload.{success,busy,failed}`. Keep `ru` and `en` complete. |

The new scalar keys are read by `BuilderToolsConfig` at
`src/main/kotlin/ru/arc/buildertools/BuilderToolsConfig.kt:22-100` and their
bounds are enforced at `src/main/kotlin/ru/arc/buildertools/BuilderToolsConfig.kt:102-189`. Values not listed
as runtime overlays above are read from the base file, not from an arbitrary
runtime copy. `auto-build.yml` and `system-build-books.yml` are also reload
inputs: the former is validated and the latter is path/hash checked when the
candidate is enabled (`src/main/kotlin/ru/arc/buildertools/BuilderToolsReload.kt:103-130`).

Startup and an accepted reload both merge missing bundled defaults into the
base builder and auto-build files, preserving operator values and unknown keys
through arc-core's `mergeMissingFromBundled`. Reload first snapshots the exact
bytes and POSIX permissions of both files. Each normal merge-forward is a
single-file replacement through arc-core `Config.saveStrict`; exact rollback
restores use `AtomicFileStore`. The two files are not one cross-file
transaction, so if publication or config-cache reload fails, each snapshot is
restored and the cache is reloaded as a best-effort rollback. Any rollback
failure is propagated to the fail-closed path. Reload first validates the
unmodified candidate; merge-forward is published only after the new runtime has
been constructed (`src/main/kotlin/ru/arc/buildertools/BuilderToolsReload.kt:325-389`).
Malformed YAML, wrong scalar types, missing required files, invalid
limits/locales, and enabled catalogue/auto-build failures reject the candidate.
Disabled mode still validates shared schema, limits, timers and locales; only
integrations that are genuinely inactive, such as catalogue digests and
contract SQL connectivity, are deferred until enablement.

Use `/builder reload` exactly (no extra arguments). It is available to console
or a sender with `arcbuild.admin.reload`, declared as an operator permission in
`src/main/resources/plugin.yml:18-21,81-83`. The tab completer adds `reload`
only for that permission (`src/main/kotlin/ru/arc/buildertools/BuilderToolsModule.kt:79-90`);
it is not exposed to unauthorized senders. Console uses the default locale;
players use their locale. The three reload messages are declared in both
locale trees at `src/main/resources/modules/builder-tools.yml:225-234,840-849`.

An accepted reload is a runtime-generation replacement, not an in-place field
edit. The active-state barrier is checked first. A safe candidate is then
strictly parsed and validated while the old generation is intact; only then is
the old runtime closed and a new runtime constructed and published
(`src/main/kotlin/ru/arc/buildertools/BuilderToolsReload.kt:43-93`). Consequently
all settings consumed by `BuilderToolsRuntime` are applied together, but the
following states must be drained first:

| Refusal | Meaning |
| --- | --- |
| `STARTING_OR_RECOVERING` | Runtime is closing/starting, recovering, recovery-blocked, or has pending player recoveries. |
| `ACTIVE_OPERATION` | Any ordinary player operation lease is active. |
| `DURABLE_BOOK_FLOW` | Book/recovery locks, delivery-space waiters, release backlog, or book recovery is active. |
| `PENDING_PREVIEW` | A pending ordinary preview or planned construction project exists. |
| `VOLATILE_PLAYER_STATE` | A selection, clipboard, or global book preview is still open. |
| `ACTIVE_CONSTRUCTION` | A construction write or completion cleanup is currently crossing its durable boundary. Stable persistent projects are recreated from their journal by the new runtime and may continue through reload. |

These checks are the exact predicates in
`src/main/kotlin/ru/arc/buildertools/BuilderToolsRuntime.kt:2293-2311`.
Selections, clipboard contents, and global book previews are included in the
barrier; `/builder reload` reports `busy` and leaves the old generation intact
until they are closed. A runtime close still clears previews, selection, and
clipboard (`src/main/kotlin/ru/arc/buildertools/BuilderToolsRuntime.kt:2352-2379`).
Do not use reload as a way to interrupt an ordinary live operation. Durable
construction projects are the exception: when no construction write is in
flight, the replacement runtime reloads their records, rebuilds their site
displays with the new configuration, reacquires their locks, and continues.

Reload outcomes are fail-closed:

- `busy` leaves the old runtime untouched; wait for the reported state to drain
  and retry.
- `failed` leaves the old generation in service when preflight fails. If a
  later activation or publication step fails, the candidate is closed, exact
  file snapshots are restored when publication had begun, and the previous
  config/runtime is recreated
  (`src/main/kotlin/ru/arc/buildertools/BuilderToolsReload.kt:68-93,325-389`).
  Never hand-edit live state to “finish” a partial reload.
- If rollback also fails, the service returns a rejected result with a
  rollback failure, logs both failures, marks health down, and disables the
  plugin (`src/main/kotlin/ru/arc/buildertools/BuilderToolsModule.kt:142-155`).
  Treat that as disabled/fail-closed and restart ArcBuilder after correcting the
  files.

`enabled: true -> false` is a controlled disable: after the same barrier, the
runtime is closed and `/builder` returns the disabled message. `false -> true`
must pass the enabled candidate checks and construct all runtime owners; on any
failure it remains disabled. `/builder reload` itself remains available while
disabled because module routing handles it before the runtime
(`src/main/kotlin/ru/arc/buildertools/BuilderToolsModule.kt:64-74,104-159`).

Changing `plugin.yml`, permissions, command declarations, soft dependencies, or
the Paper entry point requires a server restart; those are bootstrap metadata,
not runtime settings. Changing a schematic root, MySQL identity, or book
catalogue is reloadable only after a deliberate data/path review and a fully
drained durable state. The optional `server-id` in
`builder-tools-runtime.yml` is read only during plugin enable
(`src/main/kotlin/ru/ruscrafting/builder/paper/ArcBuilderPlugin.kt:27-34`), so
changing it requires a restart and is not a `/builder reload` setting. If a
change must preserve active operations, recoveries, book leases, or persistent
construction projects, schedule a restart instead.

Compact player summaries should use localized MiniMessage hover text for
domain terms or metrics whose meaning is not obvious from the label alone. The
visible line remains short; the hover explains what the value includes and how
it affects the operation. Never put a required action, warning, price, or
destructive consequence only in hover text. Keep the explanation in both
supported locales and cover the interactive tags with a focused localization
test.

## Tests and build

Controller tests live beside their production feature under
`src/test/kotlin/ru/arc/buildertools/`. The main end-to-end boundary is
`ArcBuilderMockBukkitJourneyTest`: it exercises the real command executor,
events, scheduler, previews, journal, inventory exchange, confirmation, and
undo while replacing only external persistence.

Allowed local verification:

```bash
./gradlew --no-daemon test shadowJar
python3 ../arc-core/scripts/verify_consumer_architecture.py .
```

Do not run `integrationTest`, Testcontainers, Docker, or the transitive
integration gate locally. The production artifact is
`build/libs/ArcBuilder-1.0.5.jar`.

## Adding another selection operation

Use this checklist instead of rediscovering the runtime:

1. Add a focused controller and host interface. The controller may plan exact
   changes but must not mutate the world.
2. Add the `BuilderPlanKind`, `BuilderFeature`, and `BuilderRootCommand` entries.
3. Wire the host and command branch in `BuilderToolsRuntime` through
   `newPlan` and `preparePlan`.
4. Declare the permission and umbrella child in `plugin.yml`.
5. Add both locale labels, help text, and any new validation/preview keys.
6. Write a failing player-visible or controller test first. Cover the exact
   block types included, excluded neighbors, no mutation before confirmation,
   and the relevant bounds or protection behavior.
7. Run the allowed local verification above and keep MySQL integration in CI.
8. Update this file if the ownership or transaction flow changed.

Deployment is owned by the sibling operations repository and remains a
separate fact from a successful build.

## Administrative instant construction

`BuilderInstantConstruction` finishes existing projects for holders of
`arcbuild.admin.construction`. It persists an additive nullable administrator ID
on the existing WORLD_PREPARED record before applying up to 4096 plan steps per
batch (an atomic paired block can also apply its companion). Most houses finish
in one batch. This is creative administration: remaining material debits and
unissued replacement outputs are waived, and no operation-progression event is
emitted. Existing in-flight resource receipts settle before switching modes.
The plan, original costs and steps remain immutable audit data.

The existing DurableRecordJournal, construction region locks, protection checks,
world/metadata adapter, and completion finalizer remain the owners. On restart,
instant WORLD_PREPARED is replayed by exact before/after state, with no resource
exchange. Cursor advancement is committed after the batch. Already-applied
steps and metadata repair are idempotent; unexpected world drift stops for
recovery. A metadata-only repair follows the existing ordinary construction
rule. Instant runs do not expose a misleading pause action.
