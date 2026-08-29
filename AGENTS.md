# AGENTS.md — ArcBuilder

Standalone Kotlin/Paper plugin for survival-friendly Builder Tools and construction books.

- Read `ARCHITECTURE.md` before changing commands, selections, plans, persistence,
  or runtime ownership.

- Target Purpur/Paper 1.21.11, Java 25, Kotlin 2.3.0, and arc-core 2.1.2.
- Builder gameplay and Bukkit adapters live here; shared lifecycle, locale, SQL, one-time-use, player-state, logging, metrics, and scheduling use arc-core.
- Never require WorldGuard. Lands is optional and wilderness/global land is buildable; inside a Land, use Lands' real build permission.
- Unsafe containers, Bedrock, Slimefun, ItemsAdder custom blocks, and tile entities are skipped and summarized once; ordinary falling blocks are safe.
- All previews are player-only native BlockDisplay scenes with explicit cleanup. Selection, clipboard, and operation bounds use distinct stable colors.
- Copy stores the player's anchor relative to the selected cuboid. Paste anchors at the player's current block position, applies rotation around that anchor, then previews before confirmation.
- Player-facing text belongs in `modules/builder-tools.yml`; GUI item text is non-italic.
  Keep compact domain terms and summary metrics visually short, but explain
  every potentially unfamiliar term or value with localized MiniMessage hover
  text (for example cost, return, included materials, copy price, state, and
  offsets).
  Hover help supplements visible critical information; it never hides a
  required action, warning, price, or destructive consequence.
- Locally run only `./gradlew --no-daemon test shadowJar` and the arc-core consumer verifier.
  Never run `integrationTest`, Testcontainers, Docker, or a transitive integration gate locally.
  The MySQL suite belongs to the required GitHub Actions CI job.
