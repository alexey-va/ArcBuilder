# Replacement to air

The replacement target now accepts AIR through the existing replacement plan.
The picker places air first for an empty query and searches Russian labels and
English material identifiers. Source candidates still come from the selection.

For each accepted survival STONE-to-AIR change, one removed world stone backs
the existing replacement refund of one STONE item. There is no target item
cost and no tool durability charge. This preserves replacement accounting;
native deconstruction may instead drop cobblestone and is a different flow.
Other source materials retain their existing canonical quantities, including
double slabs, candles and snow. Creative performs no item exchange.

Before this change AIR targets were rejected. Non-air replacement already
returned the same source item while consuming the chosen target item. The new
path removes that target cost because no block is placed; it does not add a
second refund or random drop. Undo consumes the frozen source refund before
restoring the exact old block state. Air provides no debit or refund. Cancelled
plans exchange nothing. Existing world/protection/custom-block/container/ore,
inventory-capacity and operation limits remain authoritative.

Direct Vault, premium tokens and player XP each remain zero. The committed
operation event excludes AIR target changes, so a removal does not become
placement progress. Existing native physics runs after commit; recovery must
not restore both a block and an already produced native drop. No new native
BlockBreak/BlockPlace event, Fortune multiplier, shop price or reward rate is
introduced.

Illustrative low/base/high activity is 100/500/2,500 safe stone removals per
active hour and 1/2/4 hours per player-day, with 1/5/20 participants. That gives
100/1,000/10,000 backed stone items per player-day and
700/35,000/1,400,000 per network-week for this newly supported target. These
are assumptions, not measured throughput, practical maxima or new limits.
A repeated restore/remove cycle must consume the previously returned item
when restoring the world; it cannot produce an unbacked second item.

The shared ledger was read on 2026-10-26 over the exact requested epoch-ms
window 1792431594878..1793036394880: 3,102 records, 27,967 operations and 22
players. Classified operation coverage was 96.41%, with 1,004 unclassified
records. This financial ledger does not measure builder item throughput or
prove the absence of indirect income. Current SELL remains an alternative
monetization path until its separately planned transition to contracts. No
SELL price, hypothetical contract/SHADOW income or item/currency exchange
rate is included. Actual use frequency and indirect sale proceeds remain
unknown; accumulation time and a meaningful farm-income ceiling cannot be
deduced from this change.

Decision: allow the requested selective removal while retaining the existing
per-source replacement accounting, safety and journal/undo exchange. Focused
local checks passed 81 distinct tests across scoped runs (search 8, controller
9, picker 6, command/journal journeys 58), with no errors or skips. They cover
AIR planning, cancellation, protection, refund-required undo and exact block
state restoration even when AIR is absent from configured replaceable materials.
Source publication, disk delivery, runtime activation and a real client
observation are separate evidence.

Calculation: [replace-air-economy-20261026.json](replace-air-economy-20261026.json).
