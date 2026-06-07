# D-cache dataMem/tagMem async-LUTRAM → sync-BRAM (250 closure P0.1) — Design

**Status:** Draft (FMax-closure slice P0.1 — highest leverage). User: "closure now (P0.1 first)."
**Date:** 2026-06-07
**Parent:** the post-route diagnosis (`synth/POSTROUTE_250_ANALYSIS.md` on `analysis/postroute-250`), the LS cluster D-cache ([[ls-cluster]]), [[synth-gate-every-slice]].

## 1. Purpose & motivation

The FullCore is **congestion + LUTRAM-bound, not logic-bound** (device 25% LUT / 3% BRAM, yet worst paths 70-90% route, ~42k router overlaps). The diagnosis proved the async-read distributed-RAM (LUTRAM) memories are the endpoints of every critical cone, and the D-cache `dataMem`/`tagMem` are the #1 offender: the `readAsync` in the store-RMW old-line read + the tag hit-detect force LUTRAM (~2432 + 384 LUTs of RAMD64E) with the **worst net in the design** (`fo=1032` dataMem read-address). The ONE sync-read memory (I-cache dataMem) maps to BRAM and never appears in any critical path — direct proof.

This slice converts the D-cache `dataMem` + `tagMem` to **synchronous-read only**, so Vivado infers BRAM (RAMB36) instead of LUTRAM — removing ~2800 LUTRAM cells of congestion, the `fo=1032` net, and the `exc→dataMem` cone. Highest single-change leverage toward real 250 post-route. (Regfile = P0.2 next; SQ-forward = P1.)

## 2. Scope

**In:**
- **All D-cache RAM reads become synchronous (registered).** Today: `dataMem` has a `readSync` (load data) AND a `readAsync` (store-RMW old-line, in the S1 merge); `tagMem` has `readAsync` (load + store hit-detect, combinational). Convert: the store-RMW old-line read → `readSync` launched in store-S0 (extend the existing S0/S1 store pipeline, commits b5aa589/2cdc4b8); the tag reads → `readSync` (registered hit-detect — the hit is resolved one cycle later, in S1, alongside the registered data). Result: `dataMem`/`tagMem` have ONLY sync reads → BRAM.
- **Port budget = simple-dual-port BRAM (1 write + 1 sync-read) per way.** The cache is SINGLE-OUTSTANDING (one access at a time), so the load data-read and the store-RMW old-line-read are mutually exclusive → they SHARE one sync-read port. The write port carries refill + store-write (already muxed). Same for `tagMem` (1 write = refill tag-write; 1 sync-read = shared load/store hit-detect). Confirm BRAM inference (util: RAMB36 count UP, RAMD64E for dataMem/tagMem → 0).
- **Preserve all D-cache behavior:** load hit/miss/refill/replay, store RMW + write-through + ACK, SQ-forward window, the DTLB-hit register (just merged), single-outstanding interlock, refill-vs-store-write priority, VIPT virtual-index/physical-tag. The registered hit-detect shifts hit/miss resolution +1 cycle (latency-agnostic — lock-step absorbs it; the load/store FSM already has S0→S1).
- **Verification:** ALL existing lock-step programs UNCHANGED ×2 (load/store/cross-line/SQ-forward/MMU/loop-load/loop-store/the new immediates) — behavior-identical. **POST-ROUTE gate (the real measure):** the `exc→dataMem` + dataMem cones OFF the worst-path list; the `fo=1032` net gone; FMax UP meaningfully toward 250 (report the new limiter — expected to become the regfile cone, the P0.2 target). Confirm BRAM inference + LUTRAM reduction in the util report.

**Out:** the integer register file (P0.2), the SQ-forward 14-level cone (P1), the I-cache (already BRAM) and the TLBs (separate; revisit if they surface), MMIO/L2.

## 3. Dataflow

```
load   : S0 (vaddr-index) launch dataMem.readSync + tagMem.readSync -> regs
         S1 hit = (storedTag_reg == ppn-tag) && valid ; data_reg -> response   (hit resolved S1)
store  : S0 latch payload + launch dataMem.readSync(oldLine) + tagMem.readSync(hit) -> regs
         S1 hit_reg ? merge(oldLine_reg, storeBytes) -> dataMem.write ; write-through latch
refill : dataMem.write / tagMem.write (BRAM write port; priority over store-write, unchanged)
```
The shared sync-read port is arbitrated load-vs-store by the single-outstanding FSM (only one active). dataMem/tagMem now infer BRAM (no async read).

## 4. Verification
- **Directed:** load hit (S1-resolved), miss→refill→replay, store RMW (S0 read / S1 merge+write), store-then-load same line (SQ-forward + the +1 hit latency), cross-line.
- **Lock-step (the gate), ×2:** ALL existing programs UNCHANGED (load/store/SQ-forward/MMU demand-paging/cross-line/loop-load/loop-store/immediates/call-return) — behavior-identical, latency-agnostic. ITLB seed flake → repro on baseline first.
- **`make test-fast`** + targeted verilator `-z` subsets.
- **POST-ROUTE gate (impl_FullCore.tcl, the real measure):** gen + place&route; report WNS/FMAX vs master (~230-243 baseline, same flow). Expect a meaningful jump (the diagnosis's highest-leverage change). Confirm: dataMem/tagMem inferred as RAMB36 (util), RAMD64E count dropped by ~the dataMem/tagMem cells, `fo=1032` net gone, `exc→dataMem` off the worst-path list. Report the NEW limiter (likely the regfile — P0.2). It's OK if P0.1 alone doesn't reach 250 (P0.2+P1 follow) — the gate is a clear improvement + the D-cache cones gone.

## 5. Open items
- BRAM port mapping: 128-bit × 64-set × 4-way dataMem → 4 simple-dual-port BRAMs (1W+1R); 21-bit tag × 64 × 4 → small BRAMs (or LUTRAM is fine for tag if it's tiny — but the diagnosis flagged tagMem readAsync as on the perf cone, so make it sync too). Confirm the SpinalHDL `Mem` with only `write` + `readSync` infers BRAM (no `readAsync`/`readWriteSync` mixing that blocks it).
- The shared read-port arbitration (load vs store-RMW): the single-outstanding FSM guarantees mutual exclusion — drive the one `readSync` address/enable from whichever access is active. Confirm no cycle needs both reads.
- The registered hit-detect +1 cycle: confirm it composes with the DTLB-hit register (the load presents the registered paddr; the tag compare uses the registered stored-tag vs the registered ppn — both available in S1). No double-latency surprise on the LS-EU side (it's single-outstanding + dynamic-completion).
- Valids/victim are RegInit Vecs (not Mem) — unaffected; only dataMem/tagMem change.
