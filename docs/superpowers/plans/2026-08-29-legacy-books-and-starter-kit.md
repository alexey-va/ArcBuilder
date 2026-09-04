# Legacy Books and Starter Kit Implementation Plan

**Goal:** Expose the shared legacy schematic library safely in ArcBuilder and make the CMI start kit issue a compatible first-home construction book.

**Architecture:** A tracked system-book catalog whitelists legacy building keys and immutable schematic digests. ArcBuilder accepts catalog-backed physical books without misrepresenting them as player-authored MySQL instances; CMI continues issuing the book through its native kit API.

**Tech Stack:** Kotlin/Paper, WorldEdit schematics, YAML, ARC CMI kit ops API, ruscrafting-ops deployment tooling.

**Spec:** `docs/superpowers/specs/2026-08-29-persistent-construction-and-builder-content-design.md`

## Global Constraints

- The runtime schematic root remains `../ARC/schematics`.
- Legacy NBT is accepted only through the reviewed catalog and exact schematic digest.
- Never edit serialized CMI `Kits.yml` by hand.
- Re-list both production symlink targets before issuing a book.

---

### Task 1: Catalog domain and validation

**Files:**
- Create: `src/main/kotlin/ru/arc/autobuild/SystemBuildBookCatalog.kt`
- Create: `src/main/resources/modules/system-build-books.yml`
- Create: `src/test/kotlin/ru/arc/autobuild/SystemBuildBookCatalogTest.kt`

**Interfaces:**
- Produces: `SystemBuildBookDefinition` and `SystemBuildBookCatalog.resolve(BuildBookData)`.

- [ ] **Step 1: Write failing tests** for a valid reviewed entry, unknown building rejection, digest mismatch rejection, path traversal rejection, and duplicate ID rejection.
- [ ] **Step 2: Run the focused test** and verify the missing catalog failure.
- [ ] **Step 3: Implement strict YAML loading and digest verification** below `BuilderStoragePaths.schematicsRoot()`.
- [ ] **Step 4: Re-run the focused test** and verify PASS.
- [ ] **Step 5: Commit** as `feat: add system build book catalog`.

### Task 2: Legacy book compatibility

**Files:**
- Modify: `src/main/kotlin/ru/arc/autobuild/BuildBook.kt`
- Modify: `src/main/kotlin/ru/arc/buildertools/BuilderToolsRuntime.kt`
- Modify: `src/test/kotlin/ru/arc/autobuild/BuildBookTest.kt`
- Modify: `src/test/kotlin/ru/arc/buildertools/ArcBuilderMockBukkitJourneyTest.kt`

**Interfaces:**
- Consumes: system-book catalog.
- Produces: catalog-backed `BuildBookData` classification and one-use physical-book project activation.

- [ ] **Step 1: Write failing tests** proving the known `viking.schem` legacy item is accepted, arbitrary legacy NBT is rejected, and the exact book is consumed once at project activation.
- [ ] **Step 2: Run focused tests** and verify the current `playerCreated` rejection.
- [ ] **Step 3: Add an explicit system-book classification** without weakening registered player-book validation.
- [ ] **Step 4: Route valid system books through the project engine** with no MySQL instance claim.
- [ ] **Step 5: Re-run focused tests** and verify PASS.
- [ ] **Step 6: Commit** as `feat: support reviewed legacy construction books`.

### Task 3: Populate catalog from the old building index

**Files:**
- Modify: `src/main/resources/modules/system-build-books.yml`
- Modify: `README.md`

- [ ] **Step 1: Resolve the shared schematic catalog** and collect exact names/SHA-256 for the old Denizen entries (`house_2`, `house_3`, `house_5`-`house_8`, `starter_house`, `viking_house`, `viking_house_2`, `samurai_1`, `samurai_2`, `pagoda_2`, `amogus_1`, `samurai_house_1`, `clear16x16x16`, and `beacon_1`) plus the live `viking.schem` starter key.
- [ ] **Step 2: Add only files that exist and decode successfully**; classify destructive clear schematics as admin-only rather than player books.
- [ ] **Step 3: Run catalog tests and `shadowJar`** and require PASS.
- [ ] **Step 4: Commit** as `content: migrate legacy building catalog`.

### Task 4: CMI start kit preview and mutation

**Files:**
- Read/refresh after write: `runtime-files/snapshots/classic/plugins/CMI/Kits/Kits.yml`
- Read/refresh after write: `runtime-files/snapshots/classic_survival/plugins/CMI/Kits/Kits.yml`
- Use definition: `docs/plans/onboarding-first-foothold-start-kit.json`

- [ ] **Step 1: Read live `start` kits on both nodes** through `arc_ops_content_read` and preserve all unrelated fields/items.
- [ ] **Step 2: Preview the full updated definition** on both nodes with the `viking.schem` book and model data `12151`; require native CMI ItemSpec success.
- [ ] **Step 3: Upsert separately on `classic` and `classic_survival`** using the explicitly authorized production mutation.
- [ ] **Step 4: Read both live kits back** and verify equivalent book custom data, item count, command list, and enabled state.
- [ ] **Step 5: Pull the two recovery snapshots** with `./scripts/mc runtime pull --apply cmi-kits-spawn cmi-kits-survival`, validate YAML, and commit the snapshot update.
