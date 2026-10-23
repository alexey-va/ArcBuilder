# MOVE material accounting — 2026-10-24

MOVE shifts safe existing blocks by one cardinal block, including vertical
movement. It snapshots source states before deriving one combined change map,
so overlapping source and destination cells do not clone blocks. Occupied
destinations outside the movable source reject the whole preview; unsafe blocks
stay in place. Coupled blocks such as doors move only when both safe matching
parts are captured; an isolated half stays in place, preventing native drop
duplication. Confirm, rollback/recovery and undo use the existing journal.

Before this change the action was unavailable. Afterwards direct mint/burn is
still separately 0 Vault, 0 premium tokens, 0 player XP and 0 inventory items.
The world material multiset is conserved before the existing post-commit vanilla
physics path; MOVE has empty material costs/rewards and does not emit the
construction reward event. Physical support changes can trigger vanilla drops,
as with ordinary building, so movement must retain existing unsafe/resource-block
protection and must not provide a second item refund after such drops.

The 1/100/10000 moves per player-day cases in the accompanying JSON are stress
assumptions, not measured activity or capacity. They all produce zero direct
currency or inventory issuance. There is no new price, progression purchase,
reward multiplier or income stream; existing hourly limits still apply.
EconomyShopGUI SELL and the planned transition to contracts are unchanged.

Focused regressions must cover overlap in both directions, vertical movement,
occupied/unsafe destinations, protection and unchanged item costs/rewards.
Source checks, server-file delivery, runtime activation and native-client
acceptance remain separate evidence.
