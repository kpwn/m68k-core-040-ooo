# Multiply (MULU/MULS, full 040) — Design

**Status:** Accepted; feature implementation landed, 2026-08-09 II=1 throughput amendment in progress.
**Date:** 2026-06-06
**Parent:** the divider ([[exception-subsystem]] CPLX cluster), the decode matrix ([[decode-matrix-framework]]).

## 1. Purpose

Integer multiply MULU/MULS (all 68040 forms) on the CPLX cluster (the DivEu's home), reusing the divider's multi-cycle EU + 2nd-dest-crack patterns. No trap (no MUL-by-zero). Lock-stepped vs Musashi. **Gated on POST-ROUTE** (`impl_FullCore.tcl`) — the OOC-synth gate is unreliable (~15-25 MHz optimistic); real master baseline ≈ 240-243 post-route; MUL must NOT regress it.

## 2. Scope

**In:**
- **MULU.W / MULS.W** (`0xC0C0`/`0xC1C0` — line C, opmode 3/7): 16×16→32. `Dn[15:0] × ea.W → Dn[31:0]`. N/Z from the 32-bit product; V=0; C=0.
- **MULU.L / MULS.L** (`0x4C00 | ea`, ext word): 32×32. The ext word: bit11 = signed (MULS), bit10 = 64-bit-result, bits 14:12 = Dl, bits 2:0 = Dh.
  - `ea,Dl` (32×32→32): `Dl = (ea × Dl)[31:0]`. **V set if the full 64-bit product doesn't fit 32 bits** (signed: not a sign-extension of bit31; unsigned: high 32 ≠ 0); N/Z from the low 32; C=0.
  - `ea,Dh:Dl` (32×32→64): `Dh:Dl = ea × Dl` (full 64-bit product). V=0 (can't overflow); N/Z from the full 64-bit; C=0.
- **Multiplier datapath** (`execute/MulCore.scala`): a DSP48-mappable, four-stage
  A/B/M/P-style pipeline inferred from registered `a * b`.  Unsigned and signed
  forms share one 33×33 chain.  The boundary contract is fixed latency and
  **initiation interval 1**: a new independent multiply may fire every cycle.
  Only **2 source operands** are carried; the divider-only third-source cone is
  not extended.
- **EU integration:** retain the existing single CPLX issue port, dynamic CPLX
  wakeups, PRF write port, and ROB completion port.  MUL has a pruned descriptor
  pipeline and result FIFO independent of the divider's occupied context, so MUL
  may accept every cycle while DIV iterates.  CHK/CMP2/DIV results and MUL results
  are losslessly arbitrated before the existing registered completion stage.
  This explicitly amends the older INT-cluster placement in the parent document;
  the parent is updated in the same phase.  The purpose is to avoid duplicating
  the DSP chain or adding IQ/PRF/ROB ports.
- **64-bit association:** the MUL µop writes Dl and deposits Dh in a ROB-id-keyed
  high-product stash.  The trailing MULHI µop reads only the matching entry.
  A single global high-product latch is forbidden once more than one multiply can
  be in flight.  Both register and memory-source cracks carry a real low-result
  dependency so MULHI cannot occupy the CPLX issue port before its producer lands.
- **Flags:** N/Z from the result (32-bit for .W/.L32, 64-bit for .L64); V = overflow (.L32 only); C always 0; X unaffected.
- **Verification:** lock-step vs Musashi — MULU/MULS .W, .L32 (incl. overflow→V), .L64 (Dh:Dl) with normal/signed/edge operands (0, max, sign-boundary); N/Z/V step-for-step. ALL existing UNCHANGED. **POST-ROUTE gate** (impl_FullCore.tcl): report WNS/FMAX; must not meaningfully regress master's ~243 (default-directive) baseline.

**Out:** the trap family (MUL doesn't trap); MAC/EMAC (CPU32/ColdFire); FPU multiply.

## 3. Components & dataflow

```
MUL.W   : decode (line C opmode 3/7) -> mul uop {signed, 16x16} -> CplxEU (DSP) -> Dn[31:0], N/Z
MUL.L32 : decode (0x4C00, ext bit10=0) -> mul uop {signed, 32x32} -> Dl[31:0] + V(overflow), N/Z
MUL.L64 : decode (0x4C00, ext bit10=1) -> [mul uop -> Dl] + [mulhi crack -> Dh]  (2-dest like DIVREM)
CplxEU  : DSP-inferred a*b (4-stage, II=1) -> result FIFO -> shared completion arbiter
          iterative DIV remains separately occupied; MULHI reads ROB-keyed Dh
```

## 3.1 Throughput and buffering contract (2026-08-09 amendment)

- The arithmetic pipeline accepts eight consecutive independent MUL starts in
  eight consecutive cycles when not flushed and when its bounded result credits
  are available.  Its `done` pulses are consecutive and remain in start order.
- Only a pruned descriptor travels with the product: ROB id, integer/flag
  destinations and write enables, architectural destination for observation,
  size, signedness, and 64-bit-result marker.  A full `IqContext` shift register
  is forbidden for area/routing reasons.
- Accepted-but-not-yet-arbitrated MULs consume explicit result credits.  The
  result FIFO must have enough reserved capacity for every product already in
  the non-stallable DSP pipeline; an assertion proves that a valid product never
  meets a non-ready FIFO input.
- The legacy CHK/CMP2/DIV path holds one completed result until the arbiter takes
  it.  The arbiter emits at most one result per cycle through the existing Flow
  completion interface; it may delay completion but may never overwrite, drop,
  or duplicate either source.
- A backend flush blocks same-cycle issue and output, clears all MUL descriptor
  valids, result credits/FIFO state, and ROB-keyed high-product valid bits.  The
  iterative divider may finish internally, but its poisoned result remains
  suppressed.  Clearing all fixed-pipeline valids is the per-operation poison
  mechanism because the redirect contract squashes the whole backend window.
- CPLX dependency wakeup retains a consumer until *all* busy CPLX operands have
  woken.  A single `cplxWait` bit may be cleared on the first matching wake only
  after recomputing that no other source remains busy that cycle.

## 4. Verification
- **Directed arithmetic:** retain `MulCore` vs a Scala reference over random +
  edge operands.  Add a dense eight-operation stream with consecutive `start`
  and `done`, exact fixed latency, unique association, and at least four
  simultaneously outstanding operations.
- **Integrated protocol:** a handshake-correct CPLX source holds payload until
  `fire`.  Tests cover a dense mixed MUL stream, a completion collision with an
  active DIV, bounded result backpressure, same-cycle flush at every MUL stage,
  immediate ROB/pdst/pNZVC id reuse, and two overlapping `.L64` cracks with
  distinct high products.  Every accepted ROB id completes exactly once.
- **IQ dependency:** two outstanding CPLX producers feed the two operands of one
  consumer.  The first wake must not release it; the second must.  Include
  same-cycle wake+push and flush clearing.
- **Lock-step (the gate), ×2:** MULU/MULS .W, .L32 (normal + overflow), .L64 (Dh:Dl) vs Musashi — product + N/Z/V step-for-step. ALL existing UNCHANGED (ITLB seed flake → repro on baseline first).
- **`make test-fast`** + targeted verilator subsets (`-z`, avoid OOM).
- **POST-ROUTE synth gate** (`vivado -mode batch -source synth/impl_FullCore.tcl` after `GenFullCoreSynthVerilog`): report POSTROUTE WNS/FMAX; run the SAME flow on master for an apples-to-apples baseline (place&route has variance); MUL must not meaningfully regress it (~243 default). Confirm the multiply inferred DSP48 (not LUT-built) via the util report (DSP count > 0). Also report the quick OOC number as a sanity proxy.

## 5. Physical acceptance still open

- Confirm Vivado maps the four intended A/B/M/P register layers into DSP48E2
  internal registers (not a LUT multiplier plus fabric delay registers).
- Compare DSP/LUT/FF counts, CPLX issue/result endpoints, WNS, and post-route FMax
  against the pre-amendment branch.  The simulation landing is provisional until
  this shared serialized gate can run.
