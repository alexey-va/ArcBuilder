# Builder Shop and Book Icons Implementation Plan

**Goal:** Publish identical comprehensive building shops on spawn and survival and replace both construction-book textures with native 32x32 pixel art.

**Architecture:** The ops repository owns two byte-identical split EconomyShopGUI section/shop files. Spawn ItemsAdder `contents/` remains the only texture source; normal pack publication mirrors and activates it on survival.

**Tech Stack:** EconomyShopGUI-Premium YAML, ItemsAdder 4.0.18, PNG RGBA pixel art, repository image finalizer and resource-pack publication flow.

**Spec:** `docs/superpowers/specs/2026-08-29-persistent-construction-and-builder-content-design.md`

## Global Constraints

- Shop files are byte-identical across `classic` and `classic_survival`.
- Spawn ItemsAdder content is canonical; do not maintain a manual survival overlay.
- Icons are redrawn at 32x32, never mechanically upscaled or antialiased.
- Run `/iazip` only on spawn after reviewed asset deployment.

---

### Task 1: Generate and validate the building catalog

**Files:**
- Create in ops: `classic/plugins/EconomyShopGUI-Premium/sections/Building.yml`
- Create in ops: `classic/plugins/EconomyShopGUI-Premium/shops/Building.yml`
- Create in ops: `scripts/tools/validate_building_shop.py`

**Interfaces:**
- Produces: a validator that reports material validity, duplicate slots, page gaps, price bounds, and family coverage.

- [ ] **Step 1: Write a failing validator fixture/test** with a duplicate slot, missing pale-oak stair, invalid material, and sell price above buy price.
- [ ] **Step 2: Run the validator test** and verify failure because the validator is absent.
- [ ] **Step 3: Implement the validator** against the pinned Paper material list and explicit required building families.
- [ ] **Step 4: Populate filled shop pages** using reused prices first and recipe-derived prices for absent variants.
- [ ] **Step 5: Run the validator** and require zero errors plus complete pale-oak coverage.
- [ ] **Step 6: Commit** as `content: add comprehensive building shop`.

### Task 2: Mirror and validate both shops

**Files:**
- Create in ops: `classic_survival/plugins/EconomyShopGUI-Premium/sections/Building.yml`
- Create in ops: `classic_survival/plugins/EconomyShopGUI-Premium/shops/Building.yml`

- [ ] **Step 1: Copy the two validated canonical files** to survival through the repository patch, not through live copy commands.
- [ ] **Step 2: Verify byte equality** for both pairs and run `./scripts/mc validate`.
- [ ] **Step 3: Commit** as `content: sync building shop across servers`.

### Task 3: Create two 32x32 book textures

**Files:**
- Modify in ops: `classic/plugins/ItemsAdder/contents/arc/resourcepack/arc/textures/builder/book_draft.png`
- Modify in ops: `classic/plugins/ItemsAdder/contents/arc/resourcepack/arc/textures/builder/book_active.png`
- Create in ops: `docs/assets/itemsadder/builder-book-32x32-reference.png`

- [ ] **Step 1: Use the existing 16x16 textures as labeled visual references** and generate one artistic reference sheet with the built-in image tool; it is not the final PNG.
- [ ] **Step 2: Redraw both icons on a real 32x32 pixel grid** with transparent background, one-pixel contours, separate silhouettes, and at most twelve colors.
- [ ] **Step 3: Validate** PNG size `32x32`, RGBA, non-empty alpha bounds, transparent corners, palette limit, no semitransparent antialias pixels, and existing model JSON references.
- [ ] **Step 4: Render native-size and nearest-neighbor previews** and reject unreadable or mechanically doubled silhouettes.
- [ ] **Step 5: Commit** as `visuals: redraw construction books at 32x32`.

### Task 4: Production deployment and readback

- [ ] **Step 1: Deploy the four EconomyShopGUI YAML files to both nodes** with a recorded goal and reload via the supported `sreload` path.
- [ ] **Step 2: Read logs after reload** and require no invalid material, duplicate section, or page/slot warning.
- [ ] **Step 3: Deploy only canonical spawn ItemsAdder texture sources** with `--no-reload`.
- [ ] **Step 4: Run `/iazip` on spawn** and let ARC mirror contents/storage plus `/iareload` to survival.
- [ ] **Step 5: Read back the published pack hash and both node states** and confirm the same active resource-pack identity.
