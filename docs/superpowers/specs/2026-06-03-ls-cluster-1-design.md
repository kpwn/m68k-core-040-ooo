# LS Cluster Slice 1 — Minimal Correct Load/Store Path + VIPT L1D — Design

**Status:** Draft for review
**Date:** 2026-06-03
**Parent spec:** `2026-05-31-m68k-040-ooo-architecture-design.md` (ch 4 clusters, ch 7 load-store / MMU). First slice of the LS cluster subsystem.
**Reference:** NaxRiscv `lsu/DataCache.scala` (VIPT L1D, `translatedAt/hitsAt/rspAt` 2-cycle load pipeline) — we borrow the **VIPT structure + FPGA-friendly BRAM arrays**, not its write-back/coherency complexity. Our policy is the much simpler **write-through / write-no-allocate / never-dirty**. [[project-040-ooo-overview]].

## 1. Purpose

Build the first **correct** memory path so the backend can execute loads and stores: an AGU + a VIPT L1D + a store queue with store→load forwarding + commit-time store drain, under identity translation, aligned accesses only. This unblocks the later decode slices that crack memory effective-addresses into load/store µops. "Correct, FPGA-clean, ≥250 MHz" — not yet "fast" (replay/optimistic latency, misalignment, real TLB, MMIO are later slices).

## 2. Scope

**In (LS-1):**
- **AGU**: virtual address = `base + displacement` (the µop carries base reg + disp); aligned-access assumption; boundary-cross detection reserved (always "no cross" this slice).
- **VIPT L1D**: `CacheGeometry(indexingPolicy = Vipt)`, 16-byte (128-bit) lines, default 8 KB / 4-way; BRAM data + tag/valid arrays; 2-cycle read pipeline (mirrors the I-cache); **write-through, write-no-allocate, never-dirty**; 128-bit AXI refill (one beat per line).
- **Byte-lane layer** at the cache edge: load align/zero-or-sign-extend by `size`, store formatting, big-endian↔LE swap (register file endian-neutral).
- **Store queue (SQ)**: entries allocated at execute (physical addr + data + size + robId), **store→load forwarding** on address overlap, **commit-order drain** to L1D-if-hit + memory, speculative-entry squash on mispredict flush (pointer rollback).
- **LS EU**: the execute pipe combining AGU → translate → L1D/SQ → writeback/allocate; integrates with a new IQ **LS issue port**, dispatch steering (`cluster == LS`), PRF writeback, and a sim whitebox obs.
- **Load wakeup = variant (A) dynamic completion**: the LS EU broadcasts a wakeup when the load actually completes (hit, or after a miss-fill); the IQ gains one dynamic wakeup source beside the static-latency-1 ALU path. No speculative wakeup, no replay.
- **Verification by direct µop injection** (no memory-decode yet): a backend harness injects LS µops into the LS port against a behavioral 128-bit memory; directed load/store/forward/commit-drain/miss-refill cases; synth gate.

**Out (later slices, designed-not-built):**
- **Variant (B) optimistic fixed-latency + replay** — a **fast-follow** slice behind the same LS-EU↔IQ wakeup interface; synth-compare FMax vs (A) and keep the winner.
- Misaligned / line- & page-crossing (two-access-slot µop), real banked DTLB + HW table-walk + page-fault/replay (replaces the identity stub), cache-mode/MMIO non-speculative loads, write-back L2.
- End-to-end Musashi lock-step over real load/store programs (arrives when a decode slice cracks memory EAs into these µops).

## 3. Background / current state

Exists: `IcachePlugin` (BRAM data, 2-cycle, uses `TranslationService`), `CacheGeometry` + `CacheIndexingPolicy.{Pipt,Vipt}` (VIPT already supported), `CacheMode`/`TranslationReq`/`TranslationRsp`, `IdentityTranslationPlugin` (VA==PA, always cacheable, never fault). Backend: 2 ALU EUs + branch EU + 3 PRFs; IQ has 3 issue ports (ALU0/ALU1/branch) with **static-latency-1** wakeup; ROB with commit + pointer-based recovery + the (3d-1b) freelist `commHead` rollback. **No D-cache, SQ, AGU, or LS EU.** A 128-bit AXI is the system bus width.

## 4. Architecture & dataflow

```
IQ LS-port ─▶ LS EU
  S0  AGU: va = base + disp ; L1D index = va[indexHi:offsetLo] (VIPT, untranslated)
      TranslationService.req(vpn) in PARALLEL
  S1  L1D tag/data read (BRAM) ; physical tag from TranslationService.rsp
  S2  hit compare (phys tag) │ SQ forward check (overlap) │ select:
        load  hit/forward → byte-lane extract → PRF writeback + dynamic wakeup
        load  miss        → refill (128-bit AXI beat) → fill way → complete
        store             → allocate SQ entry (phys addr, data, size, robId)
ROB commit ─▶ SQ drain (in order): write-through L1D-if-hit + memory (fixed latency)
mispredict flush ─▶ SQ squash speculative entries (pointer rollback)
```

### 4.1 LS EU pipeline + AGU
2–3 stage pipe shaped like `AluEuPlugin` (S0 read/AGU | S1 cache read | S2 hit/forward/writeback). The AGU computes `va = base + disp` (base from PRF int read port; disp immediate on the µop). Translation is requested at S0 with the VPN and consumed at S1/S2 (parallel with the VIPT read — the index uses untranslated page-offset bits so no dependency on translation). Aligned-only: the AGU's cross-boundary output is computed but always disabled this slice.

### 4.2 VIPT L1D
- Geometry: `CacheGeometry(cacheBytes=8192, lineBytes=16, ways=4, indexingPolicy=Vipt)` (parametric). 4-bit offset, 7-bit index, 21-bit physical tag. `virtualIndexBits = 11 ≤ 12` (4 KB page) ⇒ no VIPT aliasing.
- Arrays: data = BRAM (`128b × sets × ways`, or banked), tags+valid = LUTRAM/regs (small). 2-cycle read (index→data at S1, tag compare at S2), mirroring the I-cache's proven BRAM mapping.
- **Write-through / no-allocate / never-dirty:** store-hit updates the line in place + writes memory; store-miss writes memory only (no fill); a line is never dirtier than memory, so eviction/`CPUSH.DC` is invalidate-only, no copyback engine.
- **Refill:** load miss fetches the 16-byte line in **one 128-bit AXI beat**, fills the selected way (round-robin/LRU-lite replacement), completes the load.
- **Byte-lane layer:** load picks the `size` bytes at the line offset, zero/sign-extends per the µop; store formats the `size` bytes into the 128-bit lane + byte-enables; big-endian↔LE swap lives here (regfile/datapath endian-neutral).

### 4.3 Store queue + forwarding + commit-drain
- SQ = small ring of entries `{valid, robId, physAddr, data, size, committed}`, allocated at the store's execute (S2). Speculative until its store retires.
- **Forwarding:** a load at S2 checks all SQ entries older than it (by robId) for `physAddr`/size overlap; full-overlap → forward the SQ data (bypassing L1D/memory); the A7-relative store→load path is the hot case. Partial overlap or ambiguous (address not yet resolved) → this slice **stalls the load** until the older store resolves/drains (conservative; replay-based forwarding is a later perf slice).
- **Commit-drain:** when the ROB retires a store, its SQ entry is marked committed and drained **in commit order** — write-through to L1D (if hit) + memory, fixed-latency, no re-translation. Drain is a non-speculative, fault-free step (consistent with stores-visible-at-commit).
- **Flush rollback:** on a mispredict flush, SQ entries younger than the surviving commit point are squashed by a pointer reset (same discipline as the ROB tail / freelist `commHead`), never having touched memory.

### 4.4 Load completion / wakeup — variant (A)
Loads are variable-latency (hit ≈ fixed; miss = refill). The LS EU drives a **dynamic completion wakeup**: when the load result is ready it broadcasts `{robId/pdst}` to the IQ as a wakeup, and writes the PRF. The IQ gains a **second wakeup source** (dynamic, completion-timed) alongside the existing static-latency-1 ALU wakeup; an LS-dependent slot wakes on the dynamic broadcast. No dependent is speculatively woken, so no replay is needed. The LS-EU↔IQ interface (`completion`/`wakeup` ports) is the **bake-off seam**: variant (B) replaces only the wakeup timing + adds replay, leaving the substrate untouched.

## 5. Integration
- **IQ LS port:** add a 4th issue port (`issue(3)`) age-selecting LS-class slots (`context.uop.cluster === LS`), feeding the LS EU. (Dispatch already carries `cluster`.) Mirrors how the branch port was added.
- **Dispatch steering:** LS µops (loads/stores) carry `cluster = LS`; the IQ push routes them to the LS class.
- **ROB↔SQ:** the ROB's commit (retire) signals the SQ which robId(s) retired this cycle → drain. Flush (`doFlush`) squashes speculative SQ entries.
- **PRF + whitebox:** load writeback uses an int PRF write port + a sim `WbObs` (like the ALU EU); store writes no register (whitebox sees the store's mem addr/data at commit).

## 6. Bus / memory
128-bit AXI master for the D-side (refill reads + write-through writes). Sim: a behavioral 128-bit read/write memory (extend the I-cache's `Axi4ReadOnlySlaveAgent` pattern to read+write). **L2 deferred** — L1D writes through directly to the backing bus; inserting a write-back L2 later does not change the SQ/commit-drain structure.

## 7. Verification
- **Direct-injection backend harness** (memory decode does not exist yet): a Dut hosting LS EU + L1D + SQ + IQ LS port + behavioral memory; the test injects LS µops on the LS port (load/store with base/disp/size/data) and drives ROB-commit/flush signals — mirrors `BackendWhiteboxSpec`/`IqSinkPlugin`.
- **Directed cases:** load hit; load miss→refill→hit; store-then-load forward (same addr, incl. A7-relative); store commit-drain visible to a later load; mispredict flush squashes an uncommitted store (memory unchanged); byte/word/long size extract + big-endian order; 4-way set fill + replacement.
- **Determinism:** every sim run twice (seed-flakiness discipline; uninit regs `RegInit`, no `when`-gated Scala-var counts — see [[spinalhdl-sim-poke-gotchas]]).
- **Synth gate:** `GenFullCoreSynthVerilog` (now incl. LS EU + L1D + SQ) OOC ≥250 MHz; 0 critical warnings; L1D data → BRAM (RAMB36), multi-write arrays lower via the existing phase; report WNS + critical path. Record variant (A)'s FMax for the (B) comparison.

## 8. FMax / FPGA discipline
- L1D data array BRAM-backed (128-bit lines = clean RAMB width); tags small LUTRAM; 2-cycle pipeline keeps levels shallow (I-cache-proven).
- The **dynamic wakeup** broadcast is the FMax watch-point (a new IQ wakeup port = fanout into every slot's trigger match) — keep it a single registered broadcast; if synth shows it critical, register/pipeline it (and that datum feeds the bake-off vs variant B).
- Store→load forward compare is an address-match across SQ entries — keep the SQ small (e.g. 8 entries) so the comparator is shallow.

## 9. Open items
- L1D geometry defaults (8 KB/4-way/16 B) — parametric; revisit with synth.
- SQ depth (start 8) and whether int-PRF write contention with the ALU EUs needs an extra write port (the multi-write-lowering phase handles it; confirm at synth).
- Replacement policy (round-robin first; LRU-lite later).
- Exact ROB→SQ commit-signal shape (per-retired-robId vs a committed-store pointer) — finalize in the plan against the ROB's commit ports.
- Whether the LS EU shares the existing IQ or gets a local LS IQ (arch spec's per-cluster-IQ end-state) — LS-1 uses the existing IQ + a 4th port; local IQ is a later refactor.
