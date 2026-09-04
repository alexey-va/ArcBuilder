# Persistent Construction Projects Implementation Plan

**Goal:** Turn confirmed construction books into restart-safe projects that consume player materials incrementally from authorized nearby inventories.

**Architecture:** `BUILD_BOOK` is routed to a dedicated project controller backed by `DurableRecordJournal`; ordinary builder plans keep their atomic inventory/journal flow. A small resource gateway owns exact player/container exchange and asks Lands for `INTERACT_CONTAINER` at every container mutation.

**Tech Stack:** Kotlin 2.3, Paper/Purpur 1.21.11, Java 25, arc-core durable records and scheduling, LandsAPI 6.26.18, MockBukkit/Kotest.

**Spec:** `docs/superpowers/specs/2026-08-29-persistent-construction-and-builder-content-design.md`

## Global Constraints

- Only `BUILD_BOOK` changes transaction ownership; all other builder operations retain current semantics.
- Wilderness containers are allowed; protected containers require Lands `INTERACT_CONTAINER` for the project owner UUID.
- Never drop project resources or overwrite an externally changed block.
- Player-facing text exists in Russian and English.
- Local verification excludes MySQL integration and Docker.

---

### Task 1: Lands container-access boundary

**Files:**
- Modify: `src/main/kotlin/ru/arc/hooks/lands/LandsHook.kt`
- Create: `src/test/kotlin/ru/arc/hooks/lands/LandsContainerAccessPolicyTest.kt`

**Interfaces:**
- Produces: `fun canOpenContainer(playerId: UUID, block: Block): Boolean`

- [ ] **Step 1: Write the failing policy test** covering wilderness `true`, own/allowed Land `true`, and foreign denied Land `false`; the mutation it catches is replacing `INTERACT_CONTAINER` with build permission or treating every adjacent chest as wilderness.
- [ ] **Step 2: Run** `./gradlew --no-daemon test --tests '*LandsContainerAccessPolicyTest'` and verify the expected missing-boundary failure.
- [ ] **Step 3: Implement** the UUID-based `Flags.INTERACT_CONTAINER` check while preserving `canModify` unchanged.
- [ ] **Step 4: Re-run the focused test** and verify PASS.
- [ ] **Step 5: Commit** `test + LandsHook` as `feat: enforce builder container access`.

### Task 2: Durable project domain and journal

**Files:**
- Create: `src/main/kotlin/ru/arc/buildertools/BuilderConstructionProjectDomain.kt`
- Create: `src/main/kotlin/ru/arc/buildertools/BuilderConstructionProjectStore.kt`
- Create: `src/test/kotlin/ru/arc/buildertools/BuilderConstructionProjectDomainTest.kt`
- Create: `src/test/kotlin/ru/arc/buildertools/BuilderConstructionProjectStoreTest.kt`

**Interfaces:**
- Produces: `BuilderConstructionProject`, `BuilderConstructionProjectState`, `BuilderConstructionProjectStore.commit`, `transition`, `loadAll`, and `acknowledge`.

- [ ] **Step 1: Write failing domain tests** for state transitions, cursor bounds, pending-output bounds, immutable plan identity, and terminal/non-terminal classification.
- [ ] **Step 2: Run the two focused test classes** and verify they fail because the project types do not exist.
- [ ] **Step 3: Implement the minimal validated domain** with states `PREPARED`, `ACTIVE`, `WAITING_MATERIALS`, `WAITING_OUTPUT_SPACE`, `RECOVERY_REQUIRED`, `COMPLETED`, and `CANCELLED`.
- [ ] **Step 4: Implement the journal wrapper** at `data/construction-projects`, including exact predecessor reconciliation on uncertain writes.
- [ ] **Step 5: Re-run focused tests** and verify PASS.
- [ ] **Step 6: Commit** as `feat: persist construction projects`.

### Task 3: Exact incremental resource exchange

**Files:**
- Create: `src/main/kotlin/ru/arc/buildertools/BuilderConstructionResources.kt`
- Create: `src/test/kotlin/ru/arc/buildertools/BuilderConstructionResourcesTest.kt`

**Interfaces:**
- Consumes: `LandsHook.canOpenContainer(UUID, Block)` and `BuilderItemAmount`.
- Produces: `BuilderConstructionResourceGateway.findSources`, `takeExact`, and `storeExact` with classified success/missing/space-denied results.

- [ ] **Step 1: Write failing tests** for nearby online inventory priority, wilderness chest use, denied foreign-Land chest exclusion, double-chest deduplication, exact-item matching, full-output pause, and no partial mutation on failure.
- [ ] **Step 2: Run** `./gradlew --no-daemon test --tests '*BuilderConstructionResourcesTest'` and verify the missing gateway failure.
- [ ] **Step 3: Implement bounded shell scanning** for chest, trapped chest, and barrel only; exclude every denied container before exposing its inventory.
- [ ] **Step 4: Implement snapshot-then-commit exact exchange** so a failed take/store leaves all inventories unchanged.
- [ ] **Step 5: Re-run the focused tests** and verify PASS.
- [ ] **Step 6: Commit** as `feat: supply construction from nearby containers`.

### Task 4: Project scheduler and restart recovery

**Files:**
- Create: `src/main/kotlin/ru/arc/buildertools/BuilderConstructionProjectController.kt`
- Create: `src/test/kotlin/ru/arc/buildertools/BuilderConstructionProjectControllerTest.kt`

**Interfaces:**
- Consumes: project store, resource gateway, block safety, CoreProtect bridge, book `playerMaterials`.
- Produces: `prepare`, `activate`, `tick`, `recover`, `cancel`, `status`, and `close`.

- [ ] **Step 1: Write failing controller tests** for start with zero materials, bottom-up placement, paid material without item consumption, pause/resume on one missing material, pause/resume on output capacity, offline chest progress, restart from cursor, and external-block `RECOVERY_REQUIRED`.
- [ ] **Step 2: Run the focused controller test** and verify failure because the controller is absent.
- [ ] **Step 3: Implement project preparation and activation** with a durable barrier before book/world mutation.
- [ ] **Step 4: Implement bounded tick execution** with exact per-change input/output and durable cursor advancement.
- [ ] **Step 5: Implement startup recovery and cancellation** without world drops or guessed overwrites.
- [ ] **Step 6: Re-run controller tests** and verify PASS.
- [ ] **Step 7: Commit** as `feat: resume incremental construction projects`.

### Task 5: Runtime, book ledger, commands, and messages

**Files:**
- Modify: `src/main/kotlin/ru/arc/buildertools/BuilderToolsRuntime.kt`
- Modify: `src/main/kotlin/ru/arc/buildertools/BuilderBookLifecycle.kt`
- Modify: `src/main/kotlin/ru/arc/buildertools/BuilderToolsConfig.kt`
- Modify: `src/main/resources/modules/builder-tools.yml`
- Modify: `src/main/resources/modules/auto-build.yml`
- Modify: `visual-preview.yml`
- Modify: `src/test/kotlin/ru/arc/buildertools/ArcBuilderMockBukkitJourneyTest.kt`

**Interfaces:**
- Consumes: project controller.
- Produces: build-book confirmation, `/builder book status`, project progress messages, and existing ledger consume-on-activation recovery.

- [ ] **Step 1: Write failing journey tests** proving confirmation succeeds without all materials or output slots and proving no build-book failure mentions tool durability.
- [ ] **Step 2: Run the focused journey tests** and verify the current `errors.inventory` rejection.
- [ ] **Step 3: Route `BUILD_BOOK` confirmation to project activation** while leaving every other plan on `startJournaledOperation`.
- [ ] **Step 4: Move registered-book claim/commit to activation** and reconcile a crash between durable project preparation and ledger commit.
- [ ] **Step 5: Add dedicated RU/EN messages and status fields** and wire visual-preview coverage.
- [ ] **Step 6: Run focused journey and lifecycle tests** and verify PASS.
- [ ] **Step 7: Commit** as `feat: start durable book construction`.

### Task 6: Verification and architecture documentation

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `README.md`

- [ ] **Step 1: Update architecture docs** with project ownership, access policy, persistence barriers, and recovery states.
- [ ] **Step 2: Run** `./gradlew --no-daemon test shadowJar` and require zero failures.
- [ ] **Step 3: Run** `python3 ../arc-core/scripts/verify_consumer_architecture.py .` and require `status=ok`.
- [ ] **Step 4: Run** `./scripts/render-visual-preview` and require full key coverage with no automatic wraps.
- [ ] **Step 5: Commit** as `docs: describe persistent construction projects`.
