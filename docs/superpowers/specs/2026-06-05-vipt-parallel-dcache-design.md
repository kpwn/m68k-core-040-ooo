# VIPT Parallel Dcache Access (decouple the DTLB from the Dcache hit cone) — Design

**Status:** Draft (user-specified design; slice C of the current batch)
**Date:** 2026-06-05
**Parent:** the LS cluster + MMU ([[ls-cluster]]). Targets the current binding critical path.

## 1. Purpose & motivation

After the MMU went live, the binding full-core critical path is the **`exc-FSM/LS → DTLB translate → Dcache tag-compare/hit → dataMem`** cone (master is at a razor-thin +0.009ns / 250.6 MHz). The current D-side **serializes**: the LS EU's `XLATE` stage registers the translated paddr, and only then does the D-cache tag/access on that registered paddr. That serial translate→access is the limiter.

This slice realizes the **true VIPT** access (user-specified): the D-cache **indexes and reads in parallel with the DTLB** (the set index comes from the page-invariant virtual offset bits, available with no translation), and the **physical tag compare happens the NEXT cycle** against the DTLB's resolved physical address. A phys-tag **mismatch ⇒ drop the line and mark the access for retry as a miss** (refill). This removes the DTLB lookup from the Dcache hit/access cone — the cache read no longer waits on translation.

## 2. Scope

**In:**
- **Parallel index/read:** the D-cache `loadCmd`/access reads the data + the stored physical tag using the **virtual** set index (page-invariant bits) in the SAME cycle the DTLB lookup runs — no dependence on the translated paddr for the read.
- **Next-cycle phys compare:** register the cache's read-out {data, stored-phys-tag} and the DTLB's resolved physical address; **compare phys-tags the next cycle**. Match ⇒ hit (use the registered data). Mismatch (or TLB miss / invalid) ⇒ **miss**: drop the speculatively-read line and **retry the access as a refill** (the existing miss/refill path, now triggered by the next-cycle compare).
- Apply to the **load path** (the LS EU load + the exception-FSM load/vector-fetch) and keep the **store** write-through correct (the store still tags on the physical addr for hit-update; the parallel-read/compare structure applies to its hit-detect too, or the store stays as-is if not on the critical cone — measure).
- Remove the LS EU's serial `XLATE`-then-cache dependency for the read (the translate still happens, but in parallel with the cache index, not before it).
- **Correctness:** the mismatch→retry-as-miss path makes the parallel speculation safe (a wrong speculatively-read line is never used — it's dropped + refilled). Store→load forwarding (SQ) is unchanged. The drain-window + ack discipline unchanged.
- **Verification:** all existing LS/MMU/exception lock-step programs UNCHANGED (the parallel access is behavior-identical — same hit/miss results, possibly different latency). Honest MMU-live synth ≥250 with **comfortable margin** (target ≥258); the DTLB must leave the Dcache hit cone.

**Out:** the I-cache (already pre-translated-tag from the ITLB slice; revisit only if it becomes the limiter); cache-coherency/aliasing beyond the single-mapping VIPT-no-alias geometry (the geometry already guarantees no aliasing: virtualIndexBits ≤ pageBits).

## 3. Components & dataflow

```
cycle N   : set = vaddr[indexHi:offsetLo] (virtual, no translate)
            Dcache read: data[set][ways] + storedPhysTag[set][ways]   (parallel)
            DTLB lookup: vaddr -> ppn                                  (parallel)
            register {data_ways, storedPhysTag_ways, ppn, valid}
cycle N+1 : hit = any way's storedPhysTag == ppn-derived physical tag (registered compare)
            hit  -> select that way's registered data -> result/forward/completion
            miss (no match / TLB miss/invalid) -> drop, trigger refill (retry as miss)
```

This is the standard VIPT pipeline: the TLB is OFF the read path (parallel), and only the (registered) tag-compare depends on the TLB output — which is a shallow compare, not the deep `translate→index→read→hit` chain. The refill path is the existing one, now armed by the N+1 compare result.

## 4. Verification
- **Directed:** D-cache hit (parallel read + N+1 match), miss-on-mismatch → refill → hit; store-then-load forward unchanged; the exception-FSM frame stack/vector-fetch through the parallel path.
- **Lock-step:** ALL existing programs (memory, MMU, exceptions, traps, ITLB) UNCHANGED ×2 (behavior-identical; latency-agnostic whitebox).
- **Honest synth gate:** mmuEnable live, both TLBs+walkers inferred; **WNS comfortably ≥0 (target FMAX ≥258)**; the new critical path must be OFF the DTLB→Dcache cone. Report WNS+FMAX+critical-path + the delta from 250.6.

## 5. Open items
- Whether the store hit-detect needs the same parallel treatment or stays serial (only if it's on the critical cone — measure first).
- The exception-FSM's frame stores/vector load use identity supervisor physical accesses already (slice format-$7) — confirm they benefit or are already off the cone.
- Latency: the parallel read + N+1 compare may shift load latency by a cycle vs the current XLATE-then-read; lock-step is latency-agnostic so this is fine, but confirm the single-outstanding LS pipe + dynamic wakeup stay consistent.
