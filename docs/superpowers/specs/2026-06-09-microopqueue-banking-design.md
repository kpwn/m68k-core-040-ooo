# MicroOpQueue ring banking (structural congestion cut) — design

**Date:** 2026-06-09
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/microopqueue-banking`

## Goal

Cut the structural congestion of the `MicroOpQueue` ring — the post-route hotspot
(92–94 % in every level-5 congestion window, 74–83 % LUT; `analysis/floorplan`) — by
**banking the flop ring by `addr mod 4`** so the 4-wide compacted barrel write collapses
from a **16 × (4:1 × 197 b) write crossbar** into a **single `tail[1:0]` rotate** (4 wide
muxes) + cheap per-bank row-enables, and the two 16:1 read muxes factor into 4:1∘4:1.
**Flops, depth (16), width (197 b), and the `io.push`/`io.pop`/`flush` contract are
unchanged** — a pure storage-layout refactor (NOT the LUTRAM/LVT approach, which 4×-
replicates storage into scarce SLICEM). Validated against `MicroOpQueueSpec`.

## Context

`MicroOpQueue` (`decode/MicroOpQueue.scala`) is a 4-write/2-read register file:
`ring = Vec.fill(16)(Reg(DecodedUop))` (197 b/slot), compacted write
`when(pushFire){ for v<count: ring(tail+v) := uops(v) }`, reads `ring(head)`/`ring(head+1)`,
`head`/`tail`/`count` pointers, flush = pointer reset. SpinalHDL lowers the dynamic-index
write into, per slot i: a 4:1 data mux (`uops(i−tail)`) + an enable — i.e. a
**16-wide 4:1×197 b write crossbar** + **two 16:1×197 b read muxes**. That mux fabric (not
the flops) is the congested LUT mass. The floorplan v1 pblock co-located the structure
(+17 MHz) but the fabric remains dense — reducing it structurally lifts FMax AND relieves
the congestion the pblock fights.

## Architecture

Parameters: `banks = 4` (matches the 4-wide push), `rows = depth/banks = 4`. Bank index =
`addr[1:0]`, row index = `addr[3:2]` (a pure re-mapping of the same 0..15 ring addresses).

- **Storage:** `bankRegs = Seq.fill(banks)(Vec.fill(rows)(RegInit(DecodedUop zero)))` —
  the SAME 16×197 flops, partitioned. (No new storage; no replication.)
- **Write (the crossbar → rotate):** a compacted push writes `addr = tail+v` for
  `v ∈ [0,count)`. For `v=0..3` the banks `(tail+v) mod 4` are a rotation of `{0,1,2,3}` by
  `tail[1:0]`. So **bank b receives `uops[(b − tail) mod 4]`** — compute `rotData(b)` as one
  4:1 mux over the 4 push µops selected by `tail[1:0]` (4 wide muxes total, shared across
  banks). Per bank b: `vForBank(b) = (b − tail[1:0]) mod 4`; write iff `pushFire &&
  (vForBank(b) < count)`; row = `(tail + vForBank(b))[3:2]`; `when(writeEn(b))
  bankRegs(b)(rowFor(b)) := rotData(b)`. (Per-bank: 1 shared rotated data + a 1-of-4 row
  demux — no per-slot data mux.)
- **Read (16:1 → factored):** `headBank=head[1:0]`, `headRow=head[3:2]`;
  `head1=(head+1)`, `head1Bank=head1[1:0]`, `head1Row=head1[3:2]`. Read each bank's
  `bankRegs(b)(headRow)`/`(head1Row)` then bank-select (4:1) — `io.pop.payload(0) =
  select(headBank, perBankAtHeadRow)`, `payload(1) = select(head1Bank, perBankAtHead1Row)`.
- **`head`/`tail`/`count`/`pushN`/`popN`/`freeSlots`/`io.push.ready`/`io.pop.valid`/
  `pop1Valid`/flush logic is UNCHANGED** (operates on the flat 0..15 addresses; banking is
  only the physical layout + the write/read muxing).
- **No bypass needed:** as today, a just-pushed slot is not readable until the next cycle
  (`count` gates `io.pop.valid`); the empty-queue push+pop case is still gated by `count`.

## Testing (gates, in order)

1. **`MicroOpQueueSpec` — extend with a randomized stress test** (the de-risk): random push
   counts (0–4) and random pop-ready over many cycles + periodic flush, compared against a
   Scala reference FIFO (the model the existing FIFO-order test implies). This MUST exercise
   every `tail mod 4` / `head mod 4` alignment and ring wrap-around — where a banking
   rotate/addressing bug would surface. The existing FIFO-order + flush tests stay green.
2. **Decode lock-step + crack specs** — `DecodeStageSpec`, `DecodeCrackPipeSpec`,
   `CrackLoadSpec`, `CrackStoreSpec`, `MemRmwDecodeSpec` (DecodeStage drives the queue).
3. **Full lock-step vs Musashi** (subsets, never the whole spec — OOM): loop/call/RMW/IRQ/
   bne/nested-bsr — the queue is on every instruction's path. 0 diverged.
4. **`make test-fast`** — green.
5. **Measurement — COMPOSED with the DecodeStage pblock** (per the sequencing): gen netlist,
   run the impl flow WITH the floorplan pblock (the v1-sized `SLICE_X36Y0:X75Y104` region,
   fixed to capture the FULL DecodeStage — add cells after `opt_design` / pblock the
   hierarchical cell, ≈20k captured). Confirm (a) the ring congestion drops (no longer the
   level-5 hotspot in `report_design_analysis -congestion`), (b) post-route FMax up vs the
   211 MHz pblock-only point and the 195 baseline. Then a separate run layers **P1**
   (`feat/decode-push-register`) on top to measure all three levers together.

## Non-goals

- No payload-slim (the 197 b width reduction) — a separate structural lever / spec.
- No depth change (16 stays).
- No RAM/LVT (flops stay — the area/congestion argument from the prior decision).
- No `io` contract change. `MicroOpQueue` internals only; `DecodeStage` untouched.

## Risks

- **Rotate / bank-addressing correctness** — `(b − tail) mod 4`, the per-bank row, and the
  read bank-select must be exact across all `tail`/`head` alignments + wrap. Mitigated by the
  randomized stress test (the primary de-risk) + the FIFO-order/flush tests + decode/full
  lock-step.
- **Synthesis actually banks** — confirm the OOC/post-route shows the write fabric shrank
  (the ring congestion drops); if Vivado re-flattens, the explicit `bankRegs` structure + the
  shared `rotData` should prevent the 16-wide crossbar. Verify in the congestion report.
- **No FMax/IPC regression elsewhere** — contract-preserving + latency-identical (same
  single-cycle push/pop), so lock-step + IPC unaffected; the measurement confirms FMax moves
  the right way.
