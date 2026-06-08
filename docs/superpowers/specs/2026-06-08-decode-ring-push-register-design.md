# Decode→ring push register (front-end FMax, P1) — design

**Date:** 2026-06-08
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/decode-push-register`

## Goal

Clear the post-route front-end FMax limiter — the intra-`DecodeStage` decode→`MicroOpQueue`
ring-write cone (WNS −0.907, ~204 MHz, 77% route, ~13.5k-cell ring fan-out) — by making
the decode and ring-write stages **self-contained**: register the push payload between the
`MicroOpAssembler`/pack/stash output and the queue write, so the deep decode cone ends at a
register and the ring write consumes only well-defined registered signals. **Keep the flop
ring** (no RAM/LVT — see Decision below). Measure post-route; escalate *within flops* only
if needed.

## Context

After the shifter deep-pipeline merge (`02a004e`), the post-route limiter is
`_zz_DecodeStage_logic_fed_payload_packets_*_words_0_reg → DecodeStage_logic_queue/ring_*`:
~14 logic levels = **~7-level `MicroOpAssembler` decode cone + ~6-level positioned-write
broadcast**, logic 1.11 ns / **route 3.78 ns (77%)**, ~8.8k failing endpoints (every
`ring_<slot>_<field>` reached by the same cone). The full analysis is on branch
`analysis/frontend-fmax` (`docs/analysis/2026-06-08-frontend-fmax-analysis.md`).

Two structural facts drive it: (a) the deep `assemble` cone feeds the ring write
combinationally in one cycle (no register between); (b) `MicroOpQueue.ring` is a
`Vec.fill(16)(Reg(DecodedUop))` (~197 b/slot) written by a compacted barrel push
`ring(tail+v) := uops(v)`, which synthesizes to a 4→16 broadcast.

### Decision: keep flops, do NOT move the ring to a Mem/LVT

Lowering the 4-write ring to a `Mem` (via the repo's `MultiPortWritesSymplifier`) uses the
**LVT path (`RamAsyncMwMux`) = one full bank per write port** → the 16×197 storage is
**~4× replicated into SLICEM distributed RAM**, vs the flop ring's `1 × (16×197) ≈ 3,150
FF`. FFs are abundant on `xcku5p` (~216k); SLICEM is far scarcer and 4× replication of a
wide buffer raises **area and congestion** — pushing route delay up, the opposite of the
goal. So the storage stays flops; the fix is the registered stage boundary (P1) plus, if
needed, flop-only area/fanout reduction (depth, then width).

## Architecture — P1: register the decode→ring push

In `DecodeStage.logic` the push is produced combinationally today:
`a0 = assemble(fed.packets(0))`, `a1raw = assemble(fed.packets(1))` → stash/pack →
`queue.io.push.{uops(0..3), count, valid}`, with `fed.ready := !stashValid &&
queue.io.push.ready`.

P1 inserts a 1-deep registered skid between the produced push and the queue write:

1. **Produce a push Stream.** Define `case class PushPayload { uops: Vec(DecodedUop,4);
   count: UInt(3 bits) }`. The existing assemble/stash/pack logic drives a
   `pushProduced : Stream[PushPayload]` (valid = `stashValid || fed.valid`; payload = the
   packed uops + `totalCount`) instead of driving `queue.io.push` directly.
2. **Register it (flushed).** `pushReg = PipeStage(pushProduced, pipeFlush)` — the same
   1-deep m2s `PipeStage` (with flush) already used for the `fed` boundary. The deep
   `assemble` cone now ends at `pushReg`'s input register.
3. **Drive the queue from the register.** `queue.io.push.valid := pushReg.valid`,
   `queue.io.push.count := pushReg.payload.count`, `queue.io.push.uops :=
   pushReg.payload.uops`, `pushReg.ready := queue.io.push.ready`. The ring write in cycle
   N+1 is now a **shallow, register-driven** broadcast (per-slot enable compare + 4:1 data
   mux from registers) — no decode logic; registered drivers let the placer cluster the
   sources near the ring.
4. **Backpressure + stash.** `fed.ready := !stashValid && pushProduced.ready` (was
   `queue.io.push.ready`). The stash's "group consumed" condition keys off
   `pushProduced.fire` / `pushProduced.ready` (the produce side advancing) instead of
   `queue.io.push.ready`. The stash registers (`stashValid/stashUops/stashCount`) and their
   `when(pipeFlush) stashValid := False` are unchanged.
5. **Flush.** `pipeFlush` squashes `pushReg` (a wrong-path group held in it) via the same
   `PipeStage` flush that already squashes `fed` and the queue — so a mispredict/exception
   redirect clears the new stage too.

`MicroOpQueue` itself is **unchanged** (flop ring, same `io.push`/`io.pop`/`flush`
contract). Blast radius = `DecodeStage.scala` only.

### Latency / IPC

P1 adds **+1 front-end cycle** on the decode→queue path. It is throughput-neutral: the
`MicroOpQueue` is the decode→rename skid and the core is front-end-bound (the queue rarely
fills), so steady-state 2-µop/cycle drain is unaffected. Correctness is latency-agnostic
(whitebox lock-step joins by robId). IPC is a gate (below), not an assumption.

## Adaptive escalation (in-slice, measurement-driven, FLOP-only)

Build P1, then run the OOC worst-path + post-route gate. Decision gate:
- Worst path no longer the decode→ring cone → **stop at P1**; record the new limiter.
- Still the registered broadcast into the ring (`pushReg → ring_*`) → escalate, in order:
  1. **Depth 16→8** — halves the broadcast targets and the ring flop area (≈1,576 FF).
     Gate: IPC must hold (the skid is rarely full; confirm `IpcBenchSpec` unchanged). Trivial
     change (`new MicroOpQueue(depth = 8)`), but verify the `freeSlots >= 4` push gating and
     the 2-wide pop still leave usable occupancy.
  2. **Slim the ring payload** — the 197 b is dominated by 5×32-b fields
     (`pc/nextPc/imm/branchDisp/faultAddr`); narrow/share them to cut both the broadcast and
     the flop area. Last resort: touches the decode→rename contract + every consumer. Its own
     mini-spec if reached.
Log which branch was taken. **No RAM/LVT at any step.**

## Testing (gates, in order)

1. `MicroOpQueueSpec` — unchanged (the queue contract/storage is untouched by P1); must
   still pass (regression guard).
2. Decode lock-step + EU-stub: `DecodeStageSpec`, `DecodeCrackPipeSpec`, `CrackLoadSpec`,
   `CrackStoreSpec`, `MemRmwDecodeSpec` — the 3-µop stash/serialize + crack paths through the
   new register. Green.
3. Full lock-step vs Musashi (subsets, never the whole spec — OOM): a representative sweep
   incl. branch/redirect (flush squashes `pushReg`), loop, call/return (RTR 3-µop crack),
   mem-RMW (3-µop crack), IRQ. Green, 0 diverged.
4. `IpcBenchSpec` — dependent/independent-ALU + aggregate unchanged (the +1 push cycle is
   throughput-neutral). A material drop = the skid assumption is wrong → investigate.
5. `make test-fast` — full non-Verilator suite green.
6. OOC FMax + worst-path + **utilization** report — confirm the decode cone is off the
   push path, the ring is still flops (no unexpected RAM), FMax up from ~204.
7. **POST-ROUTE gate** (`synth/impl_FullCore.tcl`) — the authoritative number. Report
   honestly; if P1 alone misses 250, take the adaptive escalation before merge or report and
   let the user decide.

## Non-goals

- No RAM/LVT for the ring (flops stay — the area/congestion argument above).
- No change to `MicroOpQueue`'s storage or `io` contract in P1 (depth is the only queue knob,
   and only as escalation step 1).
- No decode→rename contract change in P1 (only as escalation step 2, payload-slim, which would
   get its own spec).
- No change to the FetchAlign→DecodeStage `fed` boundary, the fast ALU path, or rename.

## Risks

- **Stash / 3-µop serialize interplay** with the new register — the RTR/mem-dest-RMW crack
  and the deferred-slot1 replay must stay correct across the added skid. Covered by the crack
  lock-step (CrackLoad/CrackStore/MemRmw) + call/return + the decode specs.
- **Flush coverage** — both `stash` and `pushReg` must be squashed on redirect (same
  `pipeFlush`); a missed flush = wrong-path µops reach rename. Covered by branch/redirect +
  call/return lock-step.
- **Backpressure correctness** — the produce-side `pushProduced.ready` skid must not drop or
  duplicate a group when the queue stalls (queue full). Covered by the queue-full path in the
  decode/lock-step tests; the `PipeStage` m2s register is the proven `fed`-boundary pattern.
- **P1 may not clear 250 alone** — the residual registered broadcast into 16 slots. Mitigated
  by the adaptive flop escalation (depth → width), measured.
