# FetchAlign per-fetch outstanding tracking (Slice 1 of multi-outstanding fetch) — design

**Date:** 2026-06-08
**Status:** approved (brainstorm) — ready for implementation plan
**Branch (to be):** `feat/fetchalign-outstanding-tracking`

## Goal

Replace `FetchAlignPlugin`'s fragile **global** staleness/leading-drop registers
(`rspStale`/`dropPending`/`dropCount`, set at redirect-time) with a small **per-fetch
outstanding-record FIFO** (depth-parameterized, `FETCH_OUTSTANDING = 1` for now) whose
records carry their own `stale` + `drop`. This (a) **fixes the latent stale-fetch
misattribution deadlock** that blocks P1 (nested-bsr-during-I-cache-miss → wrong window
enqueued at a redirect target → `lenWords=0` → permanent flush stall), and (b) builds the
**multi-outstanding-capable FetchAlign side** so Slice 2 (I-cache hit-path pipelining) is a
depth bump rather than a rewrite.

This is Slice 1 of the user-approved full multi-outstanding-fetch effort. **Slice 2**
(make `IcachePlugin` non-blocking/pipelined + raise `FETCH_OUTSTANDING`) is a separate
spec, brainstormed after Slice 1 lands + measures.

## Context

`FetchAlignPlugin` is single-outstanding (`ic.cmd.valid := … && !fetchInFlight && …`).
Staleness and the leading-word drop are tracked in **global** regs set at redirect time:
`rspStale` (set on redirect with a fragile `!ic.rsp.valid` guard, cleared at response),
`dropPending`/`dropCount` (set on redirect, consumed at response). When redirects
interleave with a slow (I-cache miss) fetch, this metadata is **misattributed to the wrong
fetch window**: a stale wrong-path response slips through un-marked and is enqueued at the
redirect target. Verified: `nested bsr` lock-step PASSES on master, DEADLOCKS once P1's
+1 front-end cycle slides the second redirect into the bad alignment (`Simulation failed
at time=41030`). The I-cache itself is also single-outstanding (`cmdPort.ready :=
!inFlight && xlate.rsp.ready`), so Slice 1 gains no overlap yet — but it makes the
front-end **correct** and **ready** for Slice 2. See [[frontend-fmax-250-campaign]].

## Architecture

Introduce a parameter `FETCH_OUTSTANDING` (Int, =1 in Slice 1) and a FIFO of fetch
records. Record bundle:

```
case class FetchRecord() extends Bundle {
  val stale = Bool()        // a redirect happened after this fetch was issued -> discard its rsp
  val drop  = UInt(2 bits)  // leading words to drop on this fetch's rsp (target[2:1]); 0 for sequential
}
```

A `StreamFifo`/`Vec`-based queue of `FetchRecord`, depth `FETCH_OUTSTANDING`. Because
single-outstanding I-cache responses are **in order**, the record FIFO is matched
front-to-back with responses.

**State changes vs today:**
- Remove `rspStale`, `dropPending`, `dropCount` as redirect-set globals. Keep
  `fetchInFlight` semantics as `outstanding := records.occupancy` (count).
- Add `pendingDrop : Reg(UInt(2))` — the drop intent for the NEXT fetch to issue. Set by a
  redirect/resume/mispredict to `newPc(2 downto 1)`; cleared (→0) when a fetch issues
  (consumed). (A post-redirect fetch drops `target[2:1]`; the following sequential fetch
  drops 0.)

**Issue gate:** `ic.cmd.valid := started && (outstanding < FETCH_OUTSTANDING) &&
ibuf.io.push.ready && !stalled && !faultHold`. With `FETCH_OUTSTANDING = 1` this is exactly
the current `!fetchInFlight` behavior.

**On `ic.cmd.fire` (issue):** push a record `{ stale = redirectFiringThisCycle, drop =
pendingDrop }`, and set `pendingDrop := 0`. (`redirectFiringThisCycle` = any of
`redirect.valid` / `(resume.valid && stalled)` / `mispredictRedirect.valid` this cycle —
a fetch issued the same cycle as a redirect used the OLD `fetchPc` and is wrong-path, so
it must be born stale.)

**On a redirect / resume / mispredict:** set `stale := True` on **all queued records** (a
`FETCH_OUTSTANDING`-wide broadcast — trivial at depth 1, ≤4 at Slice-2 depths); set
`pendingDrop := newPc(2 downto 1)`; set `decodePc`/`fetchPc` and `ibuf.io.flush := True`
as today. **Drop** the `!ic.rsp.valid` guard and the "keep fetchInFlight" subtlety — the
per-record `stale` bit handles it uniformly with no timing gap.

**On `ic.rsp.valid` (response):** pop the front record. If `record.stale` → discard
(enqueue nothing), exactly as the old stale path. Else enqueue with `startWord :=
record.drop` (replacing the `dropPending ? dropCount : 0` logic). The fault path
(`rspFault`/`faultHold`) stays, gated on `!record.stale` (a stale response never faults).

**Why robust:** the drop and staleness travel **with each fetch** (captured at issue),
immune to later redirects overwriting global state. Any redirect after a fetch issues marks
that fetch's record stale → its response is discarded — no window mis-association, no
`lenWords=0` self-redirect. Per-record `stale` (vs a generation counter) avoids any
wrap/aliasing for a small FIFO.

**Single-outstanding preserved** (depth-1). No I-cache change. `decodePc`/`fetchPc`/
`stalled`/`started`/`faultHold`/`faultEmitted`/`faultPc` and the aligner/feed/fault-feed
logic are unchanged.

## Testing (gates, in order)

1. **New FetchAlign unit test** (`FetchAlignSpec` or a new `FetchAlignRedirectSpec`):
   reproduce **nested-redirect-during-miss** at the unit level — drive two `redirect`s in
   quick succession while an I-cache cmd is in flight against a memory model that delays the
   response (miss), and assert the post-redirect aligner output has the correct `pc` and
   `lenWords > 0` (NOT the stale window). This is the standalone repro of the deadlock.
2. **Existing `FetchAlignSpec`** — the 2-wide stream + complex-stall/resume tests; green
   (the resume path changed: `pendingDrop`/record on resume).
3. **Full redirect lock-step vs Musashi** (subsets, never the whole spec — OOM): the 30+
   that ride on FetchAlign — `call`/`jsr`/`rts`/`rtr`, **`nested bsr`**, `loop`, `bne`/Bcc,
   `DBcc`, `IRQ`, and the exception/trap redirects. All green, 0 diverged. `nested bsr` on
   master must now pass with the new tracking (it already passes; must STAY green).
4. **`make test-fast`** — full non-Verilator suite green.
5. **OOC FMax sanity** — front-end change, NOT the decode→ring critical path; FMax must be
   neutral (no regression). Not the gate, just a guard.
6. **P1 unblock confirmation:** after Slice 1 merges, rebase `feat/decode-push-register`
   (P1, commit `319a139`) onto it and re-run the P1 `nested bsr` lock-step — it must now
   PASS (the whole reason for this slice). This is verified in the P1 resume, not here, but
   called out as the success criterion.

## Non-goals

- No `IcachePlugin` change (Slice 2). The I-cache stays blocking single-outstanding, so
  Slice 1 yields **no fetch overlap / no IPC change yet** — it is correctness +
  forward-structure only.
- `FETCH_OUTSTANDING` stays 1 (raising it without the I-cache pipelining gains nothing and
  the I-cache would just stall `cmd.ready`).
- No change to the aligner, the complex-stall mechanism, the fault-hold delivery, or the
  decode/rename path.

## Risks

- **Delicate redirect machinery** — 30+ redirect lock-step tests + the documented
  same-cycle / response-coincident corners ride on this exact code. Mitigated by the unit
  repro + the full redirect lock-step suite + the explicit same-cycle rule (born-stale on
  redirect-this-cycle).
- **Resume vs redirect priority** — the existing priority (mispredict > redirect > resume)
  must be preserved; all three set `pendingDrop` + broadcast `stale`. Covered by the
  complex-stall/resume tests + DBcc/branch lock-step.
- **Fault interaction** — `rspFault`/`faultHold` must remain gated on `!record.stale` (a
  stale fault response is discarded, not held). Covered by the I-fetch-fault tests
  (`FetchFaultSpec`) + exception lock-step.
- **No-op risk** — at depth 1 this must be behaviorally identical to today for all
  currently-passing cases (only the buggy nested-during-miss case changes). The full
  redirect suite is the regression guard.
