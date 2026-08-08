# FMax closure, "Lever D": L0-late-select speculative BTB slot-1 lookup (design)

## Context

Part of FMax-closure round 3/4 (see `.superpowers/sdd/progress-fmax-levers-2026-08-08.md`).
A netlist-grounded investigation of "Frontend Lever C" (structural
register split, `docs/superpowers/specs/2026-08-08-fmax-frontend-leverc-register-split-design.md`)
found that even with all three round-3 levers landed, the design is
projected to cap around ~186MHz because of two path families neither Lever
C nor any prior lever touches:

1. **The `headPtr` feedback loop** (`headPtr -> L0 -> BTB RAM read ->
   slot1ValidOut -> io_shift -> headPtr`), -1.289 to -1.368ns.
2. **`fed.packets -> pushReg`**, -1.268ns — confirmed by this lever's own
   grounding pass to be a SEPARATE, independent root cause (touches no
   `BtbPlugin` cell at all — an earlier hypothesis that it shared the BTB
   RAM's cause was checked and found FALSE; it's `MicroOpAssembler`'s
   immediate cone instead, needing its own separate lever — see Non-goals).

This spec covers ONLY family 1 (the `headPtr` loop and its shared cells
with Lever C's own residual `slot1Valid` floor). Family 2 is a distinct
5th lever, tracked separately.

## The mechanism (source-cited, netlist-confirmed cell-by-cell)

`BtbPlugin` (`src/main/scala/m68k040/frontend/Btb.scala`) is a 128-entry
direct-mapped `Mem` with THREE `readAsync` ports. Slot-0's read address is
`decodePc` (a plain register). **Slot-1's read address is
`decodePc + 2*L0`** (`Aligner.scala:229`) — synthesized as a SEPARATE
replicated LUTRAM read port (`BtbPlugin_logic_mem_reg_r2_*`).

This address-computation asymmetry is the whole story. Both ports share
the same RAM, the same tag-compare structure, the same downstream
consume cone — but slot-0's port closes at **-0.594ns** while slot-1's
closes at **-1.368ns**, purely because slot-1's address needs `L0`
(itself several levels deep, per this session's earlier frontend
grounding) before the RAM read can even start.

**Delay budget** (confirmed via `report_timing -through` cell-by-cell
tracing, not estimated): the `RAMD64E` read cell itself is only
**0.419ns of the 5.271ns loop (7.9%)** — the RAM is nearly innocent. The
real cost is the **`L0 -> address` arc (1.187ns)** and the **24-bit
tag-compare (0.776ns)** that must complete before the read (and its
downstream consume) can resolve.

**Confirmed exhaustively**: all 16,400 enumerated `headPtr -> headPtr`
loop paths pass through a `BtbPlugin` memory cell (zero non-BTB paths in
the loop). Frontend Lever C's own residual `fed.slot1Valid` floor
(-0.943ns, from that spec's own grounding) traverses the **IDENTICAL
physical cells** as this loop (same CARRY8, same `ADDRC3` net, same
`RAMC`) — so a fix here directly improves BOTH the loop AND Lever C's own
residual floor, not just one family.

## The fix: read speculatively off registered `decodePc`, select `L0` late

Instead of computing `decodePc + 2*L0` and using THAT as the BTB read
address (requiring `L0` before the read can start), read ALL candidate
BTB entries `L0 could plausibly select (L0 ∈ 1..9, i.e. every legal
`lenWords` value) off the ALREADY-REGISTERED `decodePc` alone — a
speculative, address-independent-of-L0 lookup — and use `L0` only to MUX
the precomputed `predTaken`/target bit AFTER the fact, at the point where
the result is consumed, not before the RAM read.

**This is bit-identical in final result** (the same entry that would have
been read by `decodePc + 2*L0` is among the 9 candidates read
speculatively; `L0` selects which of the 9 precomputed results to use,
producing the identical final value) — a pure reordering of WHEN `L0` is
consumed (after 9 parallel reads instead of before 1 selected read), not
a change to WHAT is computed.

**Cost**: ~9x the read ports/comparators for the speculative candidates
(~550-700 additional LUTs, ~+0.3% area) — a genuine area cost, but **NO
register added inside the `headPtr` loop itself** (`L0`'s late-select
mux is purely combinational, off the loop's own registers), so this
lever costs **ZERO IPC / zero loop-throughput impact** — the loop still
advances every cycle exactly as today, just with less combinational depth
on its critical arc.

**Projected effect** (from the grounding pass's measurement, not pure
estimate): loop family -1.289ns -> approximately -0.6 to +0.3ns;
`slot1Valid` floor -0.943ns -> approximately +0.36 to +0.69ns.

## Rejected alternatives (netlist-disconfirmed, do not revisit)

- **Sync-BRAM conversion** (registering the BTB read output, mirroring
  this project's own D-cache/I-cache async-to-sync-read FMax precedent):
  disconfirmed for this specific RAM. Unlike the D-cache/I-cache cases, a
  sync-read here needs the ADDRESS a cycle EARLY — but this RAM sits
  inside the `headPtr -> L0 -> ... -> headPtr` FEEDBACK LOOP itself, so
  "a cycle early" means "before this same loop iteration has produced the
  address," which the loop's own structure cannot supply without adding a
  real stall/extra iteration. Rejected on this basis, not costed further.
- **Redundancy removal** (mirroring Frontend Lever A / Lever U1's
  pattern): disconfirmed — synthesis has already pruned any dead/
  duplicate target-field computation in this specific cone; there is no
  redundant work left to remove here.
- **Register relocation** (mirroring Slice 1's `p0Live` pattern):
  disconfirmed — no existing register sits at a useful relocation point
  for this specific arc.

## Non-goals

- **`fed.packets -> pushReg` (-1.268ns)** — confirmed by this lever's own
  grounding to be an INDEPENDENT root cause (`MicroOpAssembler`'s
  immediate cone, zero shared cells with `BtbPlugin`). This needs its own
  separate design pass ("Lever E" or similar), tracked separately in
  `.superpowers/sdd/progress-fmax-levers-2026-08-08.md`.
- The three in-flight round-3 levers (Frontend Lever C's own register
  placement, LS/ROB Lever C's write-enable fanout fix, Lever U1's
  ucPendPkt reuse) — this is a fourth, parallel, independent effort.
- Any change to BTB prediction ACCURACY/semantics — this lever is a pure
  timing restructuring; the predicted target/taken bit for any given PC
  must be bit-identical before and after.

## Verification requirements (binding)

- `~/sbt/bin/sbt compile` clean.
- **Bit-identical prediction proof**: a directed (ideally exhaustive over
  the BTB's 128-entry address space, matching this round's established
  precedent of large exhaustive sweeps where tractable) test comparing
  the OLD (`decodePc + 2*L0` addressed) read result against the NEW
  (9-way speculative + late-`L0`-select) read result, for every reachable
  `(decodePc, L0)` combination. This is THE load-bearing verification for
  this lever — it must be a genuine proof, not a sampled spot-check.
- Existing BTB/branch-prediction test coverage (locate via grep — this
  project's established practice) must pass unchanged.
- `ExecuteLockStepSpec` full suite: expect 390/394.
- Full ported test corpus (~870 tests): zero new regressions. Targeted
  branch-prediction/redirect-heavy ported tests run explicitly before the
  full sweep (this is exactly the area a BTB timing bug would manifest as
  — wrong-target mispredicts, not just slow FMax).
- OOC-synth-only AND the real post-route gate, delta reported explicitly
  on BOTH the `headPtr` loop family AND Lever C's own `slot1Valid` floor
  (both should improve, per the shared-cells finding). **Per this
  session's own repeatedly-relearned lesson: this lever alone is NOT
  expected to clear 200MHz** — even combined with all three round-3
  levers, the design is projected to cap around ~189.8MHz due to the
  separate `fed.packets -> pushReg` family. Do not judge this task's
  success on a standalone top-line number; the real accept/reject gate is
  the eventual 5-lever combined measurement.

## Open questions for the plan-writing pass

- Confirm exact live line numbers in `Btb.scala`/`Aligner.scala` before
  editing.
- Confirm the exact set of "9 candidate `L0` values" — is `L0`'s legal
  range genuinely 1..9 (`WINDOW-1`?), or does it need to cover 0..9 or
  some other range? Re-derive from `Aligner.scala`'s own `L0`/`WINDOW`
  definitions, do not assume the grounding report's "L0 ∈ 1..9" phrasing
  is the final word without checking against live source.
- Re-verify the exact disconfirming reason for the "sync-BRAM conversion"
  rejected alternative (noted as needing re-verification above) from the
  grounding report (`.../scratchpad/fmax-btb-ram-grounding-report.md`)
  before finalizing the plan.
