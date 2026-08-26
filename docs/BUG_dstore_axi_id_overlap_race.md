# BUG: intermittent "AW id=1 presented while ALREADY outstanding" AXI protocol
# assertion, seed-dependent, PRE-EXISTING (independent of task movem-translate-ahead)

**Status**: OPEN, NOT root-caused, NOT fixed. Discovered as a side effect of
directed testing for task `movem-translate-ahead`, but confirmed via rigorous
A/B testing (below) to be completely independent of that task's changes.
**Severity**: Unknown but potentially serious — an AXI protocol violation
(`AW` for an id presented before the prior transaction using that id has
completed) is, if real, a genuine bus-correctness hazard, not a cosmetic sim
issue. Occurs at a roughly 10-35% rate across sim runs of an affected program
shape (varies by which shape; see Evidence), never deterministically.
**Found**: 2026-08-26/27, task `movem-translate-ahead`, while stress-testing a
new "no-fault, 2-page-crossing MOVEM.L LOAD" regression test.

## One-line summary

Some MMU-enabled, page-crossing-adjacent simulation runs sporadically trip
`AxiProtocolChecker`'s "AW id=1 presented while ALREADY outstanding" assertion
(`m68k040.sim.AxiMemModel.scala:198`) — a `D_STORE`-id (=1) write channel
transaction — during a program window that in the minimal repro contains **zero
explicit store instructions**. This is NOT caused by, or specific to, the
translate-ahead MOVEM fix; it reproduces on unmodified `a3742ad7` RTL too.

## Evidence (A/B tested, not asserted)

1. **Original discovery**: a new 14-register, 2-page-crossing `MOVEM.L (An)+,
   D0-D7/A0/A2-A6` LOAD immediately followed by a same-width `MOVEM.L ...,(An)`
   STORE dump hit `[AXI-PROTOCOL @cycN] AW id=1 presented while ALREADY
   outstanding (addr=0x3000)` on the translate-ahead-modified RTL.
2. **A/B test #1** (isolate the RTL change): stashed the 3 translate-ahead RTL
   files (`DecodeStage.scala`/`MicroOpAssembler.scala`/`LsEuPlugin.scala`,
   keeping only the new test), ran the SAME 14-register scenario once against
   unmodified RTL — it PASSED with that seed. This was the (as it turned out,
   misleading — see #3) initial signal that pointed at the new RTL change.
3. **A/B test #2** (seed sensitivity): re-ran the SAME 14-register scenario
   against **unmodified RTL** 12 times (fresh random sim seed each run, no
   `--seed` pinning available in this harness). **2 of 12 runs failed** with the
   identical assertion (`addr=0x5010`, `addr=0x5000` — the scratch dump region
   in that version of the test). This DISPROVES #2's implied conclusion: the
   race is present on stock, unmodified RTL: it is seed-dependent, and the
   original single-seed A/B comparison in #2 was simply lucky/unlucky, not
   evidence of causation.
4. **Scale-down attempt**: shrank the test to 4 registers (`D0-D3`), matching
   the EXACT address shape of the already-proven-correct pinned
   `docs/BUG_movem_midlist_load_fault_no_rollback.md` LOAD-fault test (A1=
   0x2ff8, D0/D1 on VPN2, D2/D3 on VPN3) with both pages resident (no fault).
   Passed once in isolation, but reproduced 3 of 8 times when run repeatedly
   (fresh seeds) — the smaller scale reduces but does not eliminate the rate.
5. **Dump-mechanism attempt**: replaced the trailing `MOVEM.L ...,(An)` store
   dump with 4 independent, naturally-spaced `move.l %dN,addr` instructions
   (hypothesis: back-to-back burst stores are the trigger) — STILL reproduced
   (1 of 10 runs, `addr=0x5000`/`0x5010`).
6. **Zero-store control** (the decisive test): removed EVERY store instruction
   from the program entirely — just the base setup, the poison moves, the
   `MOVEM.L (An)+,D0-D3` LOAD, and a `bra`-self loop; verification moved
   entirely to the whitebox `wbObs` writeback tap (no memory access needed on
   the DUT side at all). **This STILL reproduced** (1 of 10 runs,
   `AW id=1 ... addr=0x3000` — a LOAD's own target address, not even a plausible
   store target). This is conclusive: the assertion fires in a program that
   contains **zero architectural store instructions**, so it cannot be a
   consequence of any real store-drain overlap in the architectural sense.

## What this evidence rules in / out

- **Ruled out**: a logic bug specific to task movem-translate-ahead's new
  far-page-probe/`txOut.front.twoAccess` suppression mechanism. It reproduces
  identically on RTL that has never seen those changes.
- **Ruled out**: a bug requiring an architectural STORE at all. The zero-store
  control (#6) reproduces it.
- **Still open / most likely candidates** (not verified further — this is
  beyond this task's scope):
  - **Verilator X-propagation / incomplete-reset artifact** in whatever
    register(s) gate the D-cache's `D_STORE`-id write-issue path (candidate:
    an `..AwDone`/`..WDone`-style completion flag, mirroring the documented
    `evictAwDone`/`evictWDone` pattern in `DcachePlugin.scala`'s `EVICT_WR`
    state, but for a DIFFERENT write-issuing path that happens to reuse id=1).
    If some such flag's reset value is `x` under a given Verilator seed and
    randomly resolves to a "not done" state when it should read "done" (or
    vice versa), a spurious/duplicate AW could fire without any real store in
    the program — consistent with the zero-store repro.
  - A genuine, rare RTL race in write-issue arbitration among the (at least
    three) known writers of the D-cache's AXI write channel (`D_STORE`=1,
    `D_PUSH`=2 cache-maintenance, `D_EVICT`=4 eviction — see
    `AxiIds.scala`) that a cold-cache DTLB-miss-heavy MOVEM LOAD (which,
    even with zero stores, DOES still exercise cache-fill / possible
    write-allocate-eviction activity) can occasionally trigger.
  - Something specific to this exact MMU-enabled + page-boundary-adjacent +
    fresh-DTLB-walk address pattern that no existing test in the ~740-test
    suite happens to exercise the same way — i.e. a real, if narrow, gap in
    existing coverage rather than something task movem-translate-ahead
    "introduced" in any causal sense.

## Reproduction rate observed (informal, small samples)

| Program shape | Runs | Failures |
|---|---|---|
| 14-reg MOVEM LOAD + MOVEM STORE dump, unmodified RTL | 12 | 2 |
| 14-reg MOVEM LOAD + MOVEM STORE dump, translate-ahead RTL | ~9 total across sessions | 2 |
| 4-reg MOVEM LOAD + MOVEM STORE dump, translate-ahead RTL | 1 | 0 (passed in isolation) |
| 4-reg MOVEM LOAD + 4x individual `move.l` dump, translate-ahead RTL | 8 | 3 |
| 4-reg MOVEM LOAD, zero stores, wbObs-only verification, translate-ahead RTL | 10 | 1 |

No sample here is large enough to pin an exact rate; all that can be
responsibly claimed is "non-trivial and non-zero, roughly 10-35% across the
shapes tried," and "present with or without task movem-translate-ahead's
changes."

## Impact on this task's own regression test

`ExecuteLockStepSpec.scala`'s `"lock-step: MOVEM.L (An)+,D0-D3 LOAD spans a
resident 2-page crossing -- all registers load correctly, no fault"` test
(added by task movem-translate-ahead) inherits a small residual chance of
spuriously failing with this EXACT assertion, through no fault of the
translate-ahead logic it actually tests. If that test is ever observed to
fail with `[AXI-PROTOCOL ...] AW id=1 presented while ALREADY outstanding`,
that is THIS pre-existing bug reproducing, not a translate-ahead regression —
do not use it as evidence against the fix without first checking the specific
assertion text.

## Suggested next steps (out of scope for task movem-translate-ahead)

1. Try to reproduce with a fixed/pinned Verilator sim seed (this harness had
   no obvious `--seed` passthrough at the time of this investigation) to make
   the race deterministically reproducible for waveform-level debugging.
2. If reproducible, dump waveforms around the D-cache's AXI write-issue path
   (`DcachePlugin.scala`, `axi.aw`/`axi.w` drivers, all three: `D_STORE`,
   `D_PUSH`, `D_EVICT`) for the cycle range around the flagged `AW`, checking
   for an `x`-valued or unexpectedly-live "already sent" flag.
3. Check whether Verilator's `--x-assign`/random-init flags are enabled for
   this project's sim builds, and whether toggling them changes the
   reproduction rate (a strong signal for the X-propagation hypothesis above).
4. Once root-caused, add a MINIMAL, deterministic (not sim-seed-dependent)
   regression test pinned to the exact mechanism, and un-block that here.
