# Free decorative display creation — 2026-10-21

The canonical economy inventory is `ruscrafting-ops/docs/knowledge/economy-inventory.md`.
This assessment covers only the new `arcbuild.display` permission and editor.

Before: no display editor. After: creation and editing cost zero coins, zero
premium tokens and zero materials. The held item supplies a visual prototype
without consumption or inventory copying. Editor mode grants no physical tool.
Permanent BlockDisplay, ItemDisplay and TextDisplay entities are decorations;
deletion and undo never turn them into inventory items, blocks or rewards.

Direct mint and burn before/after are independently zero in each unit: `vault`,
`tokens`, and `inventory_items`. The accompanying JSON calculator returns zero
weekly supply delta even at the explicitly hypothetical 10,000 creations per
player-day. That scenario establishes arithmetic only, not server capacity.
There is no earning multiplier, reward, redemption or resale-to-system path.
Player-authored decoration may have subjective value; no currency price is
assigned to it. Existing building costs and permissions remain authoritative
for ordinary construction. The planned EconomyShopGUI SELL-to-contract
transition is unaffected and future or SHADOW flows are not counted as income.

The editor requires its separate explicit permission, respects current world,
game-mode and build protection, and only operates on editor-marked objects.
Review creation, deletion, undo and cancellation for absence of item drops or
inventory grants. Runtime activation and ordinary-client verification are
separate from this source assessment.

Reproduce arithmetic:

```sh
python3 -B ../ruscrafting-ops/.agents/skills/balance-ruscrafting-economy/scripts/evaluate.py docs/display-editor-economy-20261021.json
```
