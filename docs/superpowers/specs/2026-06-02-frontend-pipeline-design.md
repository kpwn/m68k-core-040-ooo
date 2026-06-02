# Frontend Pipelining (decode→rename, rename→dispatch) — Design

**Status:** Draft for review
**Date:** 2026-06-02
**Parent spec:** `.../2026-05-31-m68k-040-ooo-architecture-design.md` (invariant #2 FMax 250 MHz / no high-fanout).
**Motivates:** the full-core synth gate (`memory/fpga-synth-multiwrite-mem.md`) found WNS −0.078 ns (245 MHz); critical path = a single **combinational** chain `FetchAlign.ibuf.count → decode → rename(async RAT) → dispatch → IQ-push trigger-init` (15 logic levels). The decode/rename/dispatch plugins each combinationally transform their input Stream to output (no register boundary), so the "stages" collapse into one cycle.
**Builds on:** the merged 3c full core (lock-stepped vs Musashi).

---

## 1. Purpose

Make decode, rename, and dispatch *real clocked stages* by inserting registered Stream stages at the **decode→rename** and **rename→dispatch** boundaries, breaking the long combinational dispatch chain so the full core clears 250 MHz. The backend-only synth already hit 328 MHz precisely because its input (`DecodeUopInputPlugin`) was registered — this gives rename the same registered boundaries on both sides.

## 2. Scope

**In:** register `DecodeStage`'s output (`DecodeUopService.uops`) and `RenameStage`'s output (`RenameUopService.uops`) with **full skid stages** (register forward valid/payload AND backward `ready`, no throughput loss). Re-verify all suites (the +2-cycle frontend latency must not break anything) and re-run the full-core synth + the Musashi lock-step.

**Out:** registering feed→decode (decode is light; not needed). Any other pipelining. Behavioral change (purely a timing/latency change — same functional results).

## 3. Design

**The boundaries today (both combinational):**
- `DecodeStage`: `uopsPort.payload(k) := SimpleDecodeUnit.decode(feed.payload(k))`, `uopsPort.valid := feed.valid`, `feed.ready := uopsPort.ready`. `uop1Valid` = `feed.valid && slot1Valid` (== `uopsPort.payload(1).valid`).
- `RenameStage`: `uopsPort.payload(k) := slot0/slot1` (combinational from async-read RATs + freelist + hazards), `uopsPort.valid := du.uops.valid && initDone && freeReady`, `du.uops.ready := initDone && uopsPort.ready && freeReady`. `uop1Valid` (== `uopsPort.payload(1).valid`).

**The change** — in each plugin, skid the combinational `uopsPort` before exposing it as the service:
- Build the combinational result into an internal `uopsComb : Stream[Vec[...]]` (the current `uopsPort` logic).
- `val uopsStaged = uopsComb.m2sPipe().s2mPipe()` (or the SpinalHDL primitive that registers both directions with full throughput — `.pipelined(m2s=true, s2m=true)` / a 2-deep skid). This is the stage register.
- `override def uops = uopsStaged`.
- `override def uop1Valid = uopsStaged.payload(1).valid` (the staged payload's slot-1 valid carries the registered slot1Valid — it equals the old `uop1Valid`, so no separate signal to register).

**Why both directions:** the `ready` (back-pressure) chain `dispatch.ready → rename.ready → decode.ready → ibuf` is also combinational; registering only forward would just move the critical path onto that backward chain. A full skid registers both; a 2-deep skid keeps throughput at 1 group/cycle.

**Rename state correctness:** rename updates the RATs/freelist on its INPUT `fire` (= `du.uops.fire`, gated by the staged output's ready via `uopsComb.ready`). The renamed result is read combinationally (async RAT) at the input cycle and registered into the skid. Intra-group slot bypass (slot1 reads slot0's dst) is combinational within the input cycle — unaffected. The `RenameCommitService` commit/flush ports (driven by the ROB) are separate and unaffected. So registering the output is functionally transparent (just +1 cycle of latency on the renamed-uop stream).

**Lock-step:** the +2 cycles of frontend latency are harmless — the whitebox lock-step is latency-agnostic (it joins writeback-by-robId with the commit stream; commits just retire a few cycles later). Same architectural results.

## 4. Verification
- `make test-fast` + `make test-verilator`: all green. DecodeStage/Rename/ROB/FetchAlign/lock-step specs must pass; if any asserted a specific cycle count it now sees +1/+2 — fix the test's wait (poll the relevant valid/fire), NOT the asserted values.
- **Lock-step** (`ExecuteLockStepSpec`): all 5 programs still green (deterministic) — proves the pipelining didn't change results.
- **Synth gate**: re-run `GenFullCoreSynthVerilog` OOC at 250 MHz (`synth/ooc_M68kFullCoreSynth.tcl`); confirm WNS ≥ 0 (≥250 MHz) post-synth and that the critical path is no longer the decode→rename→dispatch chain. Report the new WNS + critical path.

## 5. Files
| File | Change |
|---|---|
| `src/main/scala/m68k040/decode/DecodeStage.scala` | skid the output stream; `uops`/`uop1Valid` from the staged stream |
| `src/main/scala/m68k040/rename/RenameStage.scala` | skid the output stream; `uops`/`uop1Valid` from the staged stream |
| (tests) any spec with a hard-coded frontend cycle count | adjust the wait to poll (not the asserted values) |

## 6. Open items / deferrals (logged)
- **3d flush — the FE skids MUST be valid-cleared on mispredict (not "ignore for N cycles").** Two reasons: (1) the count of in-flight wrong-path µops is variable (depends on stalls), not a fixed N; (2) decisively, the `rename→dispatch` skid holds **already-renamed** µops — they have popped the freelist + written the spec-RAT — so a stale one would dispatch a wrong-path ROB/IQ entry if not invalidated; it is not inert. The fix is cheap: a **valid-bit clear** on the two skid stages, wired into the same flush broadcast that already does rename rollback (RAT location-bits → committed + freelist-pointer reset, O(1)) + ROB squash (tail:=head) + ibuf clear + fetch redirect. The rename rollback reclaims the pdsts those skid µops popped, so clearing the skid valids is consistent; it's one more low-fanout valid-clear, no extra cycles/fanout. **This slice adds a `flush` input to the skid stages (clears valid), tied off (False) now since flush is test-driven and the corpus is straight-line; 3d wires it to the real mispredict-flush signal.** (Use a flush-able skid — e.g. a stage whose valid reg clears on flush — not a plain `m2sPipe` that can't be cleared.)
- If decode→rename + rename→dispatch alone don't clear 250 post-synth, add feed→decode (decode is light, so unlikely). Re-synth decides.
- Post-route number still pending (post-synth gate only).
