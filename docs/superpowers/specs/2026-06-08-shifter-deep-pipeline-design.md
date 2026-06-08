# Shifter deep-pipeline (FMax) — design

**Date:** 2026-06-08
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/shifter-deep-pipeline`

## Goal

Pipeline the line-E barrel shifter across one (possibly two) extra register stage(s)
so the now-isolated FMax limiter — the ~22-level `s1Ctx_uop_size → Shifter →
s2ShiftNzvc/s2ShiftRes` cone left by the ALU fast/slow split (merged `7aa9b74`) — is
split roughly in half, moving the full-core toward the 250 MHz post-route gate. Line-E
shifts/rotates move from latency-2 to latency-3 (lat-4 only if measurement demands it).

## Context

After the ALU fast/slow split, the OOC worst path is entirely **inside `AluEuPlugin`'s
slow path**: `S1` registers operands, then computes the *whole* combinational
`Shifter(cmd): ShiftRsp` in one cycle and registers the result/flags into `s2Shift*`
FFs. That single combinational shifter (variable barrel shifts for the result, separate
wide `shl`/`shr` shifts for each op's carry flag, the rotate networks, and the ROX
`rmod` subtract-reduce) is ~22 logic levels — a register-bounded, in-plugin path (not a
cross-module cone), so the standing registered-boundary rule is already satisfied; this
slice is purely about depth.

**Why low-risk:** `Shifter` (`src/main/scala/m68k040/execute/Shifter.scala`) is a
self-contained combinational function, and it already has a dedicated unit test
`ShifterSpec` (`src/test/scala/m68k040/execute/ShifterSpec.scala`) that checks it
branch-for-branch against the Musashi mirror `ShiftRef` (`.../execute/ShiftRef.scala`)
across ops/sizes/counts/X. We can split the function into two phases and prove
combinational equivalence in isolation — independent of, and before, any pipeline edit.

## Architecture

Three pieces, in dependency order.

### 1. Shifter 2-stage API (the heavy lift, validated standalone)

Refactor `Shifter.apply(cmd: ShiftCmd): ShiftRsp` into two pure phases plus a thin
intermediate bundle:

- `case class ShiftStage1()` — the registered midpoint: carries the cmd fields still
  needed downstream (`shiftOp`, `dirLeft`, `size`, `isImm`, `xIn`, `count`, `src`,
  `srcMsb`, `mask`/`sizeBits` or recomputed cheaply) **plus** every *variable-shift
  output*: the result barrel-shifts (`asrArea.res`, `lsrArea.res`, `lshArea.resW`, the
  ror/rol/roxr/roxl rotated words) and the *flag-shift words* (each op's `shl(...)`/
  `shr(...)` 66-bit values whose bit-8 becomes C, and the ROX ring `res`). The cut is
  **after all variable shifts, before bit-indexing / compares / muxing**, because the
  flag computations also contain deep variable shifts — they must be in stage 1, not
  stage 2.
- `Shifter.stage1(cmd: ShiftCmd): ShiftStage1` — computes the above.
- `Shifter.stage2(s1: ShiftStage1): ShiftRsp` — the shallow half: `flBit` (bit-8)
  extraction, N/Z/V compares, the per-op `when(countZ)/when(count<size)/edge`
  selection, the `switch(sel)` op mux, and the final `& mask`.
- `Shifter.apply(cmd) = stage2(stage1(cmd))` — preserves the existing signature so all
  current callers and `ShifterSpec`'s combined-form assertions are unchanged.

If measurement (piece 2 below) shows one half is still deep, stage 2 is further split
into `stage2a`/`stage2b` (lat-4) — but stage 1 holds the only genuinely deep shifts, so
the expectation is the imbalance, if any, is within stage 1 and we'd instead split
stage 1. The plan keeps the API shaped so a third phase is a localized addition.

### 2. EU slow path → S1 → S2 → S3 (lat-3)

In `AluEuPlugin` slow path (currently S1 computes the full shifter, S2 writes back):

- **S1:** operands already registered (`s1Src1`/`s1Src2`/`s1X`/`s1Ctx`). Build
  `shiftCmd` (unchanged) and compute `Shifter.stage1(shiftCmd)`. Register its
  `ShiftStage1` into new `s2Stage1` FFs (`RegNext`, guarded by `s2Valid`).
- **S2:** compute `Shifter.stage2(s2Stage1)` → register `result`/`{n,z,v,c}`/`xOut`
  into `s3Shift*` FFs (renamed from today's `s2Shift*`). Also pipe `s2Ctx`→`s3Ctx`,
  `s2Src1`→`s3Src1` (the merge source).
- **S3:** the size-merge (`s3Src1` upper-byte/word preserve + `s3ShiftRes`), the slow
  writeback ports (`intWs`/`nzvcWs`/`xWs`), the slow bypass ports, and the completion —
  all move from the old S2 to S3.

`s2Valid`/`s3Valid` are the `RegNext` chain of `s1Valid && isSlow`.

### 3. Wakeup + single-outstanding hazard (lat-3)

- **`aluSlowWakeup`** (dynamic-completion, mirrors `lsWakeup`/`cplxWakeup`) fires from
  the new **final** stage (S3) instead of S2. The IQ's `aluSlowWait` /
  `aluSlow{Int,Nzvc,X}Busy` mechanism is latency-agnostic (a dependent is held until the
  broadcast lands), so the only change is "broadcast one stage later." No IQ logic
  change beyond confirming the busy-set at push still pairs with the later clear.
- **Single-outstanding stall** — today `issuePort.ready := !(s1Valid && isSlow)` stops a
  fast op entering S1 while a slow op occupies S1 (heading to S2's single completion
  port). With S1→S2→S3 the slow op now occupies three stages and completes one cycle
  later, so the stall must hold until the slow op has vacated the stage that would
  collide on the shared slow write/completion ports. Extend the stall to cover the
  deeper occupancy (e.g. gate on `s1Valid&&isSlow || s2Valid || (s3Valid for the
  port-collision cycle)` as the port analysis requires). Keep it single-outstanding
  (shifts are rare; throughput is irrelevant; simplest + safest).

## Adaptive measurement loop (in-slice)

Per the approved approach: implement the clean 2-stage API + lat-3, then run the OOC
worst-path report. **Decision gate inside the slice:**
- If the new worst path is no longer the shifter (some other arc dominates) → stop at
  lat-3; that other arc is a separate future slice.
- If a *single shifter stage* still shows a deep half (worst path stays
  `…→ s2Stage1` or `s2Stage1 →… → s3Shift*` with high levels) → split that stage again
  (lat-4) within this slice before the post-route gate.
Log which branch was taken.

## Testing (gates, in order)

1. `ShifterSpec` — extend to assert `stage2(stage1(cmd))` ≡ `ShiftRef` across the same
   op/size/count/X sweep (the standalone de-risk). The combined `apply` assertions stay.
2. Shift lock-step vs Musashi — ASL/LSL/LSR/ASR/ROL/ROR/ROXL/ROXR (.B/.W/.L, imm+reg)
   now lat-3, plus the 3 slow-ALU dependent-chain tests (SHIFT int / NZVC / back-to-back)
   which exercise the lat-3 wakeup.
3. `IpcBenchSpec` — dependent-ALU / independent-ALU IPC unchanged (shifts are rare and
   the fast path is untouched; shift-heavy isn't a benchmark kernel).
4. `AluFastSlowSpec` + `IqAluSlowSpec` + `BackendWhiteboxSpec` (the slow-path/EU-stub
   harnesses) — green; update any cycle-precise expectations for the extra stage.
5. `make test-fast` — full non-Verilator suite green.
6. OOC FMax + worst-path report — shifter cone halved, FMax up from 175.
7. **POST-ROUTE gate** (`synth/impl_FullCore.tcl`) — the real FMax number; this is the
   authoritative gate per the standing rule. Report honestly; do not merge below it
   without explicit direction.

## Non-goals

- No change to the fast ALU path, the IQ wakeup *mechanism* (only the stage it fires
  from), the LS/CPLX/branch EUs, or the Shifter's Musashi-exact flag behavior.
- No pipelined (multi-outstanding) slow path — single-outstanding stays.
- Not chasing 250 unconditionally: if lat-4 still misses post-route, that's an honest
  result to report, not a reason to weaken anything.

## Risks

- **Cut imbalance** — stage 1 (all variable shifts) may dominate. Mitigated by the
  adaptive loop: measure, and if so, split stage 1. ShifterSpec proves correctness of
  whatever cut we pick.
- **Cycle-precise harness expectations** — `BackendWhiteboxSpec`/`AluFastSlowSpec` may
  encode the lat-2 timing; update them for lat-3 (the wbObs registration already lands
  results at completion+1, so adjust the settle/observation window, not the asserts'
  values).
- **Hazard stall** — getting the extended single-outstanding condition exactly right
  (no collision on the shared slow ports, no deadlock). Covered by the dependent-chain
  lock-step + a new back-to-back-shift lock-step.
