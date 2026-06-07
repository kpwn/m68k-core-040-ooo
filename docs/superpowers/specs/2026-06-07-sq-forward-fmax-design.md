# Store-queue forward cone FMax (250 closure P1) — Design

**Status:** Draft (FMax-closure P1 — the current post-route limiter after P0.1).
**Date:** 2026-06-07
**Parent:** the post-route diagnosis (`analysis/postroute-250:synth/POSTROUTE_250_ANALYSIS.md`), the LS cluster ([[ls-cluster]]), [[synth-gate-every-slice]].

## 1. Purpose & motivation

After the D-cache BRAM conversion (P0.1, master ~235 post-route), the binding post-route cone is **`LsEuPlugin s2Paddr → fwdData`** — the store-queue forward overlap+age search (14 levels, the one genuine logic-depth cone per the diagnosis, with a `CARRY8` adder in it). The forward path computes each SQ entry's byte range and does an overlap + youngest-match reduce against the load's `s2Paddr`, combinationally, the same cycle.

The diagnosis's fix: **pre-register the per-entry address-range bounds at store ALLOC** (`aHi`/`bHi` = `paddr` and `paddr + nbytes`, for both the slot-A and the misalign slot-B), so the forward-compare path only does a *compare* against stored bounds — dropping the `CARRY8` adder + narrowing the cone. (Fallback if insufficient: split the forward into 2 cycles — latency-agnostic.)

## 2. Scope

**In:**
- **Pre-register the SQ entry range bounds at alloc:** when a store allocates into the SQ, compute + store its byte-range bounds (`lo = paddr`, `hi = paddr + nbytes`; plus the slot-B bounds for a split/misaligned store) into the SQ entry registers — instead of recomputing `paddr + nbytes` in the forward-compare path. The forward query then compares the load's `[s2Paddr, s2Paddr+loadBytes)` against the stored `[lo, hi)` bounds (overlap test) + the existing youngest-match age reduce — a compare, no adder.
- **Preserve forward correctness EXACTLY:** the overlap detection (full + partial overlap), the youngest-matching-store selection (age order), the forwarded-data byte assembly, the drain-resident-until-ACK window, two-slot misalign. Behavior identical (the value forwarded is the same; only the timing path shortens).
- **If pre-register alone doesn't clear the cone:** split the forward into 2 cycles (register the overlap-match result, assemble fwdData the next cycle) — latency-agnostic (the LS EU is single-outstanding + dynamic-completion; +1 forward latency is invisible to lock-step).
- **Verification:** ALL existing lock-step programs UNCHANGED ×2 (esp. store→load forward: `st-ld-sameline`, `st-ld-subword`, cross-line, the immediates, loop-store). **POST-ROUTE gate:** the `LsEu fwdData` / `s2Paddr→fwdData` cone OFF or shortened on the worst-path list; FMax up from ~235; report the new limiter (expected: the integer regfile, P0.2).

**Out:** the integer register file (P0.2 — the next + bigger LUTRAM block); changing the SQ depth / two-slot policy; the D-cache (done P0.1).

## 3. Dataflow

```
store alloc : SQ entry <= {data, strb, paddrLo=paddr, paddrHi=paddr+nbytes (REGISTERED),
                           slotB bounds, robId/age, ...}
load forward: overlap = (s2Paddr < entry.paddrHi) && (s2Paddr+loadBytes > entry.paddrLo)  // compare, no adder
              youngest-match reduce over entries -> fwdData byte-assemble
              (optionally registered: match in cycle N, fwdData in N+1)
```

## 4. Verification
- **Directed:** SQ forward full/partial overlap, youngest-of-multiple-matching, no-overlap (cache path), two-slot misalign forward — value identical to the pre-change behavior.
- **Lock-step (the gate), ×2:** ALL existing programs UNCHANGED (store→load same-line/sub-word/cross-line, loop-store, MMU stores, immediates, call-return stack stores/loads). ITLB seed flake → repro on baseline first.
- **`make test-fast`** + targeted verilator `-z` subsets.
- **POST-ROUTE gate (impl_FullCore.tcl):** report WNS/FMAX vs master (~235, same flow); the `s2Paddr→fwdData` cone off/shortened; report the new limiter. Up toward 250 (P0.2 regfile is the remaining big lever).

## 5. Open items
- Pre-register vs 2-cycle split: try pre-register first (no latency change); fall back to the split only if the compare-only cone is still binding (the diagnosis expects pre-register to drop the adder levels and clear it as the limiter).
- The slot-B (misalign) bounds must be pre-registered too (a split store forwards from both slots).
- Confirm the age/youngest reduce isn't itself the depth (it's a priority reduce over the SQ entries — if it's the residual after dropping the adder, the 2-cycle split registers the match before the byte-assemble).
