# Move into occupied blocks

The new occupied-target path uses the existing deconstruction owner. It removes
only safe, permitted destination blocks outside the movable source snapshot,
queries their ordinary drops with Silk Touch removed, and reserves the same
pooled tool durability. Moved source blocks remain exact block states and yield
no items. Air and configured replaceable plants yield no items or tool wear.
Containers, block entities, custom blocks, ores and other unsafe targets still
reject the move. Creative moves yield no items and consume no tools.

Before this change an occupied target rejects MOVE. Players can already clear
the same safe target with DECONSTRUCT, then MOVE. The new path combines those
actions into one existing journal transaction; it does not increase a drop
quantity, Fortune multiplier, price, payout, size or hourly limit. The supplied
calculation models a deterministic stone-to-cobblestone example, not the expected
value of every native loot table. Native drop composition, Fortune and tool
suitability remain the existing deconstruction rules.

Illustrative low/base/high scenarios use 100/1,000/10,000 safe displaced stone
blocks per player-day and 1/5/20 participants. These are assumptions, not measured
activity or a new daily cap. At 100/500/2,500 displaced blocks per active hour and
1/2/4 active hours, the combined path yields 100/1,000/10,000 cobblestone per
player-day and 700/35,000/1,400,000 per network-week, with the same planned
durability points. Each item is backed by one removed target block. The existing
two-command route has the same output and wear for those targets. No cash value,
collection speed, accumulation time or practical farm throughput is inferred.

The hourly threshold is unchanged, but combining the operations counts each
target once. For a simple non-overlapping N-block source and N occupied targets,
DECONSTRUCT then MOVE counts 3N changes, while combined MOVE counts 2N. At an
unchanged hourly allowance H, this accounting ceiling is floor(H/3) versus
floor(H/2) displaced targets (up to 1.5 times as many). Overlap and unchanged
interior states depend on the exact plan; native action rate, tools, world
material and inventory still constrain real throughput. This is the deliberate
convenience change, not a drop multiplier or unbacked item source.

Vault, premium tokens and player XP each have direct before/after delta zero.
MOVE still emits no construction-reward event. SELL remains the current
alternative monetization path until its separate planned transition to contracts;
no SELL income or future SHADOW income is counted here. World blocks, items,
durability, XP and currencies are separate units. No buy/process/sell arbitrage
is introduced: native drops replace removed world blocks, and undo consumes the
exact frozen drops before restoring the original preimages. It does not refund
successful tool wear, matching existing deconstruction.

Planning and cancellation mutate neither world nor inventory. Confirmation
rechecks state, protection, permissions, exact tools and inventory capacity.
The shared durability barriers, rollback and recovery snapshots cover source,
targets, tool wear and output together; retry never rerolls committed output.
Even a visually unchanged target remains in the transaction so target drift
cannot create a refund without preserving the moved block.

Decision: reuse the existing deconstruction material flow without changing its
unit economics. The only throughput change is fewer player steps; its measured
frequency and indirect sale income remain unknown. Focused planner and journaled
journey tests cover displaced refunds, overlap, protection, tools, inventory,
undo, cancellation and recovery. Disk delivery, runtime activation and ordinary
player behavior must be reported separately; the requested delivery forbids a
restart.

Calculation: [move-deconstruction-economy-20261025.json](move-deconstruction-economy-20261025.json).
