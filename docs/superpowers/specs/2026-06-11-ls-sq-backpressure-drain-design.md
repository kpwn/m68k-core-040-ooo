# LS store-queue back-pressure + BehavioralMem write-ordering — design

**Date:** 2026-06-11
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/ls-sq-backpressure`

## Goal

Fix the two real, pre-existing shared-LS bugs that the merged MOVEM slice (`18d8ea0`) was
the first instruction to stress (long store bursts + immediate store→load). MOVEM's own FSM
is correct; its lock-step tests are currently capped at ≤8 registers + a refill workaround.
This slice removes those caps. **Acceptance test:** the full 15-register
`MOVEM.L D0-D7/A0-A6,-(A7)` + `MOVEM.L (A7)+,D0-D7/A0-A6` round-trip (+ the `st-ld-drain`
probe) pass stably, lock-step vs Musashi.

## Bug 1 — store-queue silent store-drop (REAL DUT bug)

`StoreQueue` (`src/main/scala/m68k040/ls/StoreQueue.scala`, depth 8) takes `io.alloc` as a
**`Flow`** (valid+payload, no `ready`). The alloc block (`StoreQueue.scala:218`,
`when(io.alloc.valid && !io.flush){ valids(tail):=True; …; tail:=tail+1; count:=count+1 }`)
has **no full-guard and no back-pressure**. A burst of >8 in-flight (allocated, not-yet-drained)
stores overwrites the oldest live entry and corrupts `count` → a dropped/corrupt store. A
15-register `MOVEM.L` issues 15 stores far faster than the hold-until-ack drain frees entries.

### Fix — SQ back-pressure to LS-EU store execution
- `StoreQueue` exposes `io.full := (count === depth)` (and asserts internally that alloc never
  fires when full — a sim guard, `assert(!(io.alloc.valid && io.full))`, to catch regressions).
- The **LS-EU** (`execute/LsEuPlugin.scala`) gates a store's SQ-alloc on `!sq.io.full`: when a
  store reaches the alloc point and the SQ is full, the EU enters a **`WAIT_SQ` stall state**
  (mirrors the misalign `WAIT_A`/`WAIT_B` FSM) and holds until `!io.full` (an entry drains),
  then allocs and completes. The stall lives INSIDE the LS-EU pipeline (it deasserts the EU's
  acceptance of the next issue), so `io.full` does NOT enter the IQ select/ready cone — no FMax
  hit on the sensitive issue→operand path (same discipline as the `lsBusy` push-only read).
- **Deadlock-freedom:** the MOVEM store µops have sequential robIds and commit **incrementally**
  in ROB order; each commit lets that store drain (hold-until-ack), freeing an SQ entry for the
  stalled younger store. The stalled store is always strictly younger than the committing/draining
  ones, so forward progress is guaranteed (no circular wait). Document + test this (a >8-store
  single MOVEM must not hang).

## Bug 2 — store→load-same-line drain race (TEST-HARNESS bug, DUT is correct)

`BehavioralMem` (`src/test/scala/m68k040/ls/BehavioralMem.scala`) is not a correct AXI slave:
the **write data is applied by a `StreamMonitor` on `axi.w`** (line 97 → `update()` →
`applyBeat` → `mem.write`), **decoupled** from the **B (write-ack) response driven by the stock
`Axi4WriteOnlySlaveAgent`** (line 60). There is no ordering guarantee that the bytes are applied
to the backing `SparseMemory` before B fires. The DUT correctly pops the SQ entry on the AXI-B
ack (the drain-ack fix) and forwards until then — but once popped, a younger load that MISSES
L1D (write-through / write-no-allocate → the line was never cached) refills via the read agent
(`readByte → mem.read`), and if that read lands between the B-ack and the StreamMonitor's
`mem.write`, it reads **stale** memory. On real hardware a correct AXI slave applies the write
before asserting B, so the DUT logic is correct; only the sim memory violates the contract.

### Fix — make BehavioralMem honor write-before-ack
Apply the per-byte write strobes to the backing memory **before / atomically with** the B
response, instead of in a decoupled monitor. Concretely (implementer picks the cleanest of):
- Override/extend `Axi4WriteOnlySlaveAgent` so the buffered (aw,w) beats are applied in the
  same callback that drives `b.valid`/produces the B response — i.e. apply on the W-beat (or at
  burst completion) and ensure the read agent cannot observe pre-write memory for an address the
  DUT considers acked. The invariant: **once the DUT sees B for a write, any subsequent refill
  read of that address returns the written data.**
- Keep the existing `pokeByte/peekByte/poke128/peek128` helpers and the shared-SparseMemory
  semantics unchanged; this is purely a write-apply *ordering* fix.
- Add/strengthen `BehavioralMemSpec` to assert the ordering (a write whose B has been observed is
  visible to an immediately-following read of the same/overlapping address).

## Testing (gates, in order)

1. **`BehavioralMemSpec`** — the new write-before-ack ordering assertion passes; existing
   read/write/strobe behavior unchanged.
2. **`StoreQueueSpec`** — add a full→stall→drain→accept case (alloc presented while full is held
   off / asserted-against; an entry drains → alloc proceeds); existing forward/drain/flush green.
3. **Lock-step vs Musashi** (the payoff):
   - The `st-ld-drain` probe (`ExecuteLockStepSpec.scala:1914`) — stable (no flake) over repeats.
   - **Restore the full-strength MOVEM tests** (revert the ≤8 caps from `18d8ea0`): the
     **15-register `MOVEM.L D0-D7/A0-A6,-(A7)` + `(A7)+` round-trip** (predec store + postinc
     reload of all 15 + A7 + checkMem of the full stack image) — must pass, 0 diverged. Restore
     the `(An)+` / `.W` tests to multi-register WITHOUT the refill workaround (the harness fix
     makes the immediate store→load correct). Keep the RTR-frame test but drop its read-back
     workaround comment if it now passes without it.
   - Re-run a representative non-MOVEM lock-step subset (RMW, move-chain, predec/postinc) — no
     regression from the SQ back-pressure.
4. **`make test-fast`** — green (the LS-EU `WAIT_SQ` must not perturb the common path).
5. **≥200 MHz full-core synth gate** — reported. The back-pressure is a `count===depth` compare
   feeding an LS-EU FSM stall (NOT the IQ select cone); expect FMax-neutral (D-cache→DTLB still
   the limiter). If `io.full` regresses FMax, register it.

## Non-goals

- Deepening the SQ (back-pressure is the correct general fix; depth stays 8 unless the gate
  forces otherwise).
- Store-to-load forwarding changes, write-allocate L1D, L2 — out of scope.
- MOVEM continuation / format-$7 mid-instruction fault restart (faults still flagged-only).
- Making the DUT robust against an incorrect AXI slave (rejected in brainstorm as
  over-engineering — the harness is fixed to honor the AXI contract instead).

## Risks

- **Back-pressure deadlock** if MOVEM store µops did NOT commit incrementally (e.g. if commit
  were gated on whole-instruction completion). Mitigation: the per-µop ROB-order commit is the
  existing behavior; the plan MUST include a >8-store single-MOVEM no-hang test (the round-trip
  covers it) and a guard/timeout in the lock-step cap.
- **FMax**: `io.full` on the issue cone would regress the sensitive path. Mitigation: keep it in
  the LS-EU FSM (push-only / execute-stage read), mirroring the `lsBusy` push-only discipline;
  register if needed. Gate confirms.
- **Harness fix masking a DUT bug**: if, after the BehavioralMem fix, the round-trip STILL fails,
  bug 2 was (partly) a DUT issue after all — STOP and re-root-cause (don't weaken the test). The
  write-before-ack invariant test in `BehavioralMemSpec` isolates the harness behavior from the DUT.
- **Stale decode/predecode specs** — unaffected (no decode change), but run `OperationDecoderSpec`/
  `PredecodeWordSpec` if any shared file is touched.
