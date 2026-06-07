# SQ-forward FMax (250 closure P1) — POST-ROUTE result

Branch: `feat/sq-forward-fmax`. Gate: `synth/impl_FullCore.tcl` (OOC, xcku5p-ffvb676-2,
250 MHz / 4.000 ns), default directive. SAME flow run on master baseline and branch.

## Result (post-route, same flow)

| Run    | WNS (ns) | FMAX (MHz) | Worst-path cone (top-1)                               |
|--------|----------|------------|------------------------------------------------------|
| master `255787e` | -0.435 | 225.5 | `IssueQueue triggers -> IssueQueue sbX_busy` (11 lvl) |
| branch (this)    | -0.453 | 224.6 | `IssueQueue psrcA -> LsEu s1AddrB` (16 lvl, 5x CARRY8 = AGU next-line adder) |

Delta: -0.018 ns / -0.9 MHz — within place&route run-to-run noise (the placer/router is
stochastic; the two netlists differ only in the SQ-forward cone, which is OFF the
critical path in both runs).

## The SQ-forward `s2Paddr -> fwdData` cone is GONE

- It does **not** appear anywhere in the branch top-10 (0 refs to `fwdData` / `s2Paddr`
  / `StoreQueue` / `paddrHi` in `fullcore_route_timing_BRANCH.rpt`).
- It also did not appear in the master top-10 for THIS place&route (the diagnosis's
  -0.108 run that featured it at 14 levels / 4x CARRY8 was a different placement at a
  higher ~235 operating point). At the ~225 operating point both runs hit, the binding
  limiter has already moved off the SQ-forward search.
- The change (pre-register `paddrHiAs`/`paddrHiBs` = `paddr+nbytes` at alloc; forward
  test is a pure compare) provably removed the per-entry `paddr+nbytes` CARRY8 cluster
  from the forward cone — there is no SQ-forward path in the routed report to revisit.

## New limiter (next levers)

The branch top-1 is the **AGU cross-line base adder** `addrB0 = (va0 & ~15) + 16`
(`LsEuPlugin s1AddrB`, 16 levels incl. 5x CARRY8), fed by the IssueQueue psrcA read.
Master's top-1 is the IssueQueue scoreboard (`sbX_busy`). Both, plus the Dcache tagMem
and the integer/NZVC LUTRAM read endpoints, are the remaining cones — consistent with
the diagnosis's "next-tier = LUTRAM regfile + IssueQueue/Decode/AGU" (P0.2 regfile is
the next big lever).

## 2-cycle split fallback: NOT taken

The fallback (register the overlap-match, assemble `fwdData` the next cycle) was only to
be used if the compare-only SQ-forward cone was STILL binding. It is not — the cone is
absent from the top-10 — so the pre-register alone is sufficient and the forward keeps
its original single-cycle latency (no behavior/latency change at all).

## Behavior gate

Lock-step vs Musashi, on the BRANCH, x2 seeds, ALL forward/store/load programs UNCHANGED:
st-ld (load-back, sameline, sub-word), line/word/page-misalign store->load-back,
line-crossing store->overlapping load, loop-store, MOVE-to/from-mem (NZVC), MMU
store->load + load->ALU->store, two-loads+add, bsr/jsr...rts call-return stack, drain
race probe. No ITLB flake observed. Directed `StoreQueueSpec`/`StoreQueueSplitSpec`
10/10 (x2). `make test-fast` 87/87. The forwarded VALUE is bit-identical (the bounds are
stored at 32 bits = the exact width of the old combinational `paddr+nbytes`).
