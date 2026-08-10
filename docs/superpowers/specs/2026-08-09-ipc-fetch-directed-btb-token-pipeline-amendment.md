# Fetch-directed FTB registered-token pipeline — binding amendment

Date: 2026-08-09. Status: **REGISTERED-TOKEN AND REGISTERED-FALLBACK RTL
IMPLEMENTED AND SIMULATION GATED; COMPLETE CORPUS AND FLOORPLANNED PHYSICAL
ACCEPTANCE PENDING (250-MHz GOAL, 200-MHz HARD FLOOR).**

This document is the binding correction to
`2026-08-09-ipc-fetch-directed-btb-design.md`. It supersedes that document's
sections 2.2, 2.4, 2.5, 2.7, 2.8, 6.1, 6.3, 9.3, and every proposal to delete
the decode-time slot-1 BTB fallback. The old document remains the owner of the
measured problem, FTB entry semantics, splice/confirm correctness argument,
flush taxonomy, and rejected alternatives except where explicitly changed here.

The corresponding executable plan is
`docs/superpowers/plans/2026-08-09-ipc-fetch-directed-btb-token-pipeline-plan.md`.
The older implementation plan remains blocked and non-normative.

## 1. Corrections made by this amendment

The reviewed design had three P0 faults:

1. `ftbResFresh := RegNext(fetchPc) === fetchPc` rejects every result on an
   uninterrupted II=1 stream because `fetchPc` advances by eight every command.
2. Deleting the slot-1 decode-time BTB path removes valid fallback coverage for
   FTB misses, collisions, second branches, and cross-window branches.
3. Comparing enabled and disabled *fetch/feed PC traces* is not an architectural
   oracle: a useful predictor is supposed to change the speculative trace.

This amendment removes value-based freshness entirely, retains both existing
decode-time BTB ports in phase 1, and replaces the invalid differential with
association, framing, target-gap, mutation, and retired-trace proofs.

It also corrects two undersized structures in the reviewed design:

- a four-entry FTQ is not sufficient for the legal run-ahead envelope; and
- same-cycle FTQ pop credit would add a decode-ready-to-fetch-address path.

The amended FTQ is a 32-entry, one-write/async-head LUTRAM with no same-cycle
pop dependency in the fetch application cone.

## 2. Registered lookup and exact association

### 2.1 Services

New bundles contain only plain SpinalHDL fields.

```scala
case class FetchPlanToken() extends Bundle {
  val ringSlot = UInt(2 bits)
  val seq      = UInt(8 bits)
}

case class FtbLookupCmd() extends Bundle {
  val windowPc = UInt(32 bits)
  val token    = FetchPlanToken()
}

case class FtbLookupRsp() extends Bundle {
  val windowPc = UInt(32 bits)
  val token    = FetchPlanToken()
  val hit      = Bool()
  val brWordOff= UInt(2 bits)
  val brLen    = UInt(4 bits)
  val target   = UInt(32 bits)
  val brType   = UInt(2 bits)
}

trait FtbLookupService {
  def lookupCmd: Flow[FtbLookupCmd]
  def lookupRsp: Flow[FtbLookupRsp]       // fixed cmd+1 result
  def clearOne:  Flow[UInt]               // branch PC; mismatch liveness
}

case class GshareWindowRsp(idxBits: Int) extends Bundle {
  val token   = FetchPlanToken()
  val taken   = Vec(Bool(), 4)
  val phtIdx  = Vec(UInt(idxBits bits), 4)
}

trait GshareWindowService {
  def windowCmd: Flow[FtbLookupCmd]
  def windowRsp: Flow[GshareWindowRsp]
}
```

`FtbPlugin` is the sole provider of `FtbLookupService`; `GsharePlugin` is the
sole provider of `GshareWindowService`; `RobPlugin` remains the sole provider of
`BtbUpdateService`. No new `Global` key is required if the FTB/FTQ sizes are
constructor parameters. If configuration keys are chosen instead, `ParamPlugin`
must be documented as their one producer.

`brLen` remains four bits end to end, matching `DecodePacket.lenWords` and the
new `BtbUpdate.len`. An out-of-scope long control form must fail the widened
in-window check; truncating it into a three-bit zero/small value could otherwise
turn a safe decline into a false application.

Both lookup commands pulse exactly on `ic.cmd.fire` and carry the same token.
Both providers accept one command per cycle and register one result for the
following cycle. FetchAlign asserts that their valid bits and tokens agree.

### 2.2 Ring token

Each outstanding-ring entry gains:

- `ringKeep : UInt(3 bits)`, initialized to four on issue;
- `ringPlanSeq : UInt(8 bits)`, written from a monotonically incrementing
  `planSeq` on issue.

The query for a fired fetch carries `{ringTail, planSeq}`.  FetchAlign also
delays that issued pair locally by one cycle.  The two providers must return
the exact same pair at fixed latency C+1.  Their returned token equality is a
mandatory assertion, not a functional input to the application mux; the local
C+1 issued slot names the ring record.  A result is functionally live only when
all of these hold:

```text
ftb.result.valid && gshare.result.valid
local issued-result valid
local issued slot is legal
!ringStale(local issued slot)
!any architectural/decode redirect this cycle
```

This is safe because it is a structural latency proof, not a freshness guess.
The ring record is allocated with the lookup command in C.  FTB and gshare
return in C+1, while the earliest resident I-cache response for that command is
C+2.  The record therefore cannot retire or be recycled before its lookup
result arrives.  A current-cycle redirect still closes the pre-edge stale-bit
window.  The locally delayed valid must equal each provider valid, and both
returned tokens must equal the local delayed `{slot,seq}`.  The sequence
comparison to `ringPlanSeq(local slot)` remains active as an assertion so a
provider latency change, duplicate response, token corruption, or future
ring-lifetime change fails loudly; putting the equality back in the functional
mux is not an acceptable substitute for revisiting this proof.

The 2026-08-10 five-ID physical gate makes this distinction binding for FMax.
At 250 MHz its routed limiter was
`FetchAlign.ringPlanSeq -> applyNow -> I-cache command -> ITLB/tag hit ->
Icache.pfNextPa`, 7.614 ns and 24 logic levels, for WNS -3.633 ns.  The
ring-sequence equality existed only to prove an impossible C+1 recycle and
therefore must terminate at the assertion rather than drive target selection,
I-cache readiness, or prefetch state.

No result is compared with the live `fetchPc`. Sequential PCs may change every
cycle and lookup/application remains II=1.

## 3. Cycle contract

For a command for window `W` fired in cycle `C`:

| cycle | action |
|---|---|
| C | allocate ring slot S with sequence Q; pulse FTB and four-way gshare lookups for `{W,S,Q}` |
| C+1 | registered results for `{W,S,Q}` arrive; either decline or atomically apply the prediction; the command issued in this cycle is target `T` when applied, otherwise the sequential candidate |
| C+2 | the result for the C+1 command arrives; the same turnover repeats |
| C+3 | resident response for W consumes ring S using its already-final `ringKeep` |

The target in C+1 comes from a registered FTB result through a narrow application
predicate and one PC mux. The FTB and PHT arrays are *after* the command address
and terminate at the next result registers. There is never a combinational
table-output-to-table-output loop and never two RAMs in series.

`cmdWindowPc` and `cmdDrop` are selected together:

```text
selectedTarget = pendingTargetValid || applyNow
cmdWindowPc    = selectedTarget ? align8(target) : fetchPc
cmdDrop        = selectedTarget ? target[2:1]    : pendingDrop
```

This is required because a C+1 target command may fire in the same cycle the
prediction arrives; using only the registered `pendingDrop` would attach the
wrong leading-word drop to the target ring record.

If the I-cache cannot accept C+1, `{target,drop}` is captured in one held pending
slot. No newer fetch fires, so no newer FTB result can overtake it. The result's
ring truncation and FTQ push still happen once; decode may safely stall at the
splice until the held target is fetched.

## 4. Atomic application

`applyNow` requires:

- both fixed-C+1 FTB/gshare results and a live locally delayed issued slot;
- FTB tag hit;
- unconditional branch, or the selected registered PHT direction is taken;
- `brWordOff >= ringDrop(local issued slot)`;
- the complete learned instruction lies in the window
  (`brWordOff.resize(5) + brLen.resize(5) <= 4`);
- no external/test-resume redirect, quiesce, fault hold, mismatch suppression,
  or pending target;
- FTQ not full (a defensive condition; §5 proves legal run-ahead cannot fill it).

Decode-local redirects (`predictFire` from the retained BTB/RAS fallback and
`ftqMismatch` from FTQ confirmation), plus the registered internal
`mispredictRedirect` action used by commit recovery and registered complex
resume, deliberately do **not** enter the combinational application predicate.
The decode-local actions are formed after the aligner from the live IBuf head.
The internal action is registered, but the 2026-08-10 five-ID route proved that
using it as an application veto still formed a 24-level
`ROB doFlush/exception state -> FTB result -> ITLB/I-cache -> target hold` cone.
Feeding any of these actions back into `applyNow`, and then through the next
I-cache command/FTB lookup enable, creates a same-cycle control loop with no
throughput or correctness benefit.

A registered FTB result may therefore physically apply in the same cycle as one
of those older decode-local redirects or the registered internal redirect. This
is a kill-after-apply collision, not an architectural application:

- the redirect/FTQ-flush priority clears head, tail, count, and held-target state
  at the edge;
- every pre-redirect ring entry is marked stale;
- any cache command accepted in the collision cycle is born stale and its
  lookup token cannot become live;
- the redirect PC wins the fetch/decode PC updates.

At most one disposable cache command is spent on this rare collision. The
internal redirect already permits one old-path command in its action cycle and
marks it born stale; selecting the physical FTB target instead does not widen
that bound. Correct prediction cadence and redirect latency are unchanged.
Mismatch recovery pays the one registered cycle defined in §6. The external
boot/test redirect and the standalone test-resume input remain in the narrow
application block predicate; their result-time collision contract remains "no
physical application".

One `applyNow` event performs exactly once:

1. `ringKeep(slot) := (brWordOff.resize(5) + brLen.resize(5)).resized`;
2. push `{brPc,brLen,target,phtIdx,isCond}` to the FTQ;
3. select or hold the aligned target for the next cache command;
4. increment an application counter used by the non-vacuity gates.

It does **not** flush the IBuf, stale older ring entries, or mutate the GHR.
Every older window precedes the predicted branch; the predicted window is
truncated at the branch end; the next issued window is the target. Thus no
younger fall-through window exists to invalidate.

The phase-1 gshare lookup uses the current speculative GHR but does not shift it
at fetch. A confirmed conditional shifts the GHR at decode using the applied
taken direction. This preserves the existing decode-order update point for
uncovered branches, avoids a fetch-vs-decode write collision, and keeps fallback
behavior structurally intact. Younger fetch predictions may use history that is
one or more covered branches old; that is an accuracy limitation to measure, not
a correctness issue.

## 5. FTQ sizing and representation

The legal maximum number of fetched predictions ahead of decode is bounded by:

- three outstanding ring windows; plus
- at most twenty one-word windows resident in the 20-word IBuf.

Therefore the phase-1 FTQ depth is 32 and elaboration requires
`ftqDepth >= RING + BUF_WORDS + 1`. It is a one-write, async-head memory with
head/tail/count registers; flush resets the pointers/count and does not clear the
array. This is smaller and more robust than a wide FF Vec.

No same-cycle pop credit feeds `applyNow`. The sizing invariant proves full is
unreachable for the legal run-ahead envelope; simulation asserts it. If the
invariant is later changed, fetch simply declines an FTB prediction while full
and the retained decode predictor handles it.

## 6. Decode confirmation and fallback

The original splice clamp and confirm-or-flush argument remain binding, with
these changes:

- the FTQ is populated one cycle after fetch issue, from the associated result;
- `slot1WouldFtq` is ORed with today's `slot1WouldPred` and
  `slot1WouldRasPred`; it does not replace either;
- both existing decode-time BTB lookup ports remain in phase 1;
- a confirmed FTQ conditional shifts GHR once at decode and stamps the carried
  PHT index; the ordinary decode-time shift for that slot is suppressed;
- `ftqPast = ftqValid && ftqDiff(31)`, not the identically-false reviewed form;
- mismatch recovery invalidates the one FTB entry and sets one-shot
  `ftbSuppress` exactly as in the parent design.

The clamp remains:

```text
spliceWords = wordDistance(decodePc, ftq.brPc) + ftq.brLen
availEff    = ftqNear ? min(ibuf.avail, spliceWords) : ibuf.avail
```

`availEff` drives both the aligner and `p0LiveReg` extension-valid flags. A
prediction at slot 1 is deferred to slot 0, then confirmed. No packet may use a
post-splice word. On exact `{pc,len,simple}` confirmation, the slot is stamped
predicted-taken and decode jumps to the FTQ target without flushing already
fetched target bytes. On disagreement, only packets made exclusively from
genuine pre-splice bytes may fire; the existing redirect/flush machinery
recovers before any post-splice byte is decoded.

### Registered mismatch recovery

Mismatch detection terminates at a dedicated register boundary. The live FTQ
head and aligner may form only `mismatchDetect` and capture
`{restartPc,offendingBranchPc}`; they do not directly drive `fetchPc`, FTB clear,
ring staleness, lookup enables, or flush-state clock enables.

For a detector event in cycle C:

- a too-short/overshooting real packet that fired in C is the last permitted
  packet and `restartPc` is its fall-through;
- starvation/past-head cases fire no packet and capture the current `decodePc`;
- cycle C+1 blocks both decode feed and new I-cache commands, pulses the
  registered mismatch action, clears the captured FTB entry, flushes FTQ/IBuf
  and stale ring state, and restarts at the captured PC;
- a correct FTQ confirmation never enters this path and retains its existing
  II=1 cadence.

An external, resume, or commit redirect coincident with detection cancels the
pending recovery because that higher-priority action already discards the FTQ.
A decode-time fallback prediction does not cancel it: the malformed FTB entry
must still be cleared, and the registered mismatch action stales any speculative
target fetch issued in the intervening cycle. Higher-priority architectural
redirects coincident with the registered action still win the final PC and clear
one-shot suppression.

### Registered decode-fallback action

Physical checkpoints 2 and X103 prove that the remaining live
`DecodeStage.ucPendValid -> fallback prediction -> ITLB CAM -> issFire` family
must terminate at a register. This boundary changes control timing, not target
command timing. For a fallback BTB/RAS prediction detected while the branch
packet fires in cycle C:

- cycle C captures only `{target,drop}` and a one-cycle pending bit. GHR/RAS
  bookkeeping remains associated with the branch's real `feed.fire` in C;
- cycle C may issue its ordinary sequential I-cache command. It is recorded in
  the ring normally, then made stale by the registered action before its
  three-cycle cache response can be consumed;
- cycle C+1 blocks decode feed, flushes the old IBuf and FTQ, marks every
  pre-action ring entry stale, and selects the captured target directly as the
  I-cache command candidate;
- the target command is therefore still permitted to fire in C+1, exactly the
  same command cycle as the old immediate state-update implementation. The
  action supplies registered IBuf-room credit because the IBuf is flushed on
  that edge; it does not invent ring credit or bypass a full outstanding ring;
- if the command fires, the newly allocated target ring entry is explicitly
  marked live after the blanket stale operation, `fetchPc` advances to
  `alignedTarget + 8`, and `pendingDrop` clears. If it cannot fire, `fetchPc`
  remains `alignedTarget` and the captured drop is retained for the later
  command. No second target-hold structure is required;
- an I-cache response observed in the action cycle is killed explicitly even
  though its ring stale bit changes only at the edge. This is mandatory for an
  older fault response as well as ordinary data.

The pending action is not installed when an external/resume/commit redirect or
registered FTQ-mismatch detector wins cycle C. If one of those higher-priority
actions arrives in C+1, the fallback target command may be physically issued
but is born stale; it must not be re-marked live, and the higher-priority PC,
flush, and suppression state win. A coincident registered FTB `applyNow` is
allowed to pulse but is killed by the fallback FTQ flush, and the C+1 command
multiplexer prioritizes the captured fallback target over that younger physical
result.

This design removes all live decode/predecode terms from I-cache valid/address,
ITLB lookup, FTB/gshare lookup, and ring write enables. Only the small registered
pending bit and captured target/drop reach those controls. It adds roughly 35
FFs and shallow selection/gating; it adds no predictor RAM, CAM, cache port, or
latency to the fetch-directed II=1 path.

### SMC fault model

Phase 1 retains the existing architectural self-modifying-code contract:
software must execute the required I-cache maintenance after changing code.
Both ordinary and maintenance invalidation clear the FTB as well as the BTB.
Without that maintenance, stale I-cache/predecode content is already outside the
supported contract. This is the deliberately narrow fault model permitted by the
frontend audit; no new opword classifier is inserted into the decode critical
loop. Wrong targets and wrong branch kinds on otherwise maintained code are
verified by BranchEU and recover through the existing commit redirect.

## 7. Flush and priority

Every path that currently flushes the IBuf or marks all ring entries stale also:

- clears FTQ count/head/tail;
- clears the held target;
- makes any same-cycle physical lookup application architecturally unobservable
  through final flush/stale priority.

Priority is unchanged: commit mispredict, external redirect, and complex resume
win over the architectural effects of fetch-plan application. A decode-time
BTB/RAS prediction for an earlier branch captures a registered action; that
action wins at C+1 unless one of those architectural actions or a registered
FTQ-mismatch recovery is active. An FTQ
mismatch detector first captures a recovery token; its registered action wins on
the following edge and flushes the younger FTQ state. Neither decode-local
action must suppress the physical `applyNow` pulse because its kill priority
makes that pulse unobservable. The same is true for the registered internal
commit/complex-resume redirect: its final PC, IBuf flush, ring-wide stale writes,
FTQ reset, and held-target clear all retain later-assignment priority. External
and standalone resume inputs retain the stronger physical veto. FTB application
itself is not a redirect and does not set `ringStale`.

I-cache invalidation, maintenance invalidation, reset, and debug/architectural
frontend flush clear all FTB valids. A same-cycle update/invalidate collision is
resolved in favor of invalidation.

## 8. Area and FMax contract

Phase 1 does not delete the measured slot-1 BTB cone; correctness and coverage
take priority. Estimated new storage is:

- FTB payload: about 7.8 Kib plus 128 valid bits;
- four additional registered PHT read views: about 16 Kib of replicated LUTRAM
  in the pessimistic inference shape;
- FTQ: about 2.6 Kib in LUTRAM;
- ring tokens/results/pending state: under 200 FF.

No BRAM, DSP, cache port, predictor write port, or decode/ROB bandwidth is added.
The binding area gate is no more than +1.0% full-core LUTs and +2,500 FF, with
zero BRAM/DSP increase. The standing post-route optimization goal is 250 MHz;
200 MHz is the hard deployment/acceptance floor. Keep the existing 4-ns
constraint for both closure work and comparable endpoint census data.

An area-budget breach is a **review checkpoint, not an automatic rejection**.
Before deleting or materially reshaping the feature, report the exact
LUT/LUTRAM/FF/BRAM/DSP delta, affected pblock capture/utilization/congestion,
which structures account for the increase, measured IPC benefit, and the
available reductions (entry count, table representation, replication, or
staging). Pause for the project owner's direction when that tradeoff is real.

The two paths to inspect explicitly are:

1. registered FTB result → apply predicate/PC mux → I-cache command and next FTB
   address; and
2. FTQ async head → `availEff`/slot defer → existing decode/IBuf shift loop.

The live IBuf head / aligner / decode-local redirect cone must terminate in the
redirect state flops. It must not feed `applyNow`, `ic.cmd.fire`, the FTB/gshare
lookup enables, or fetch-ring metadata write enables in the same cycle.

The first recovery route removed that live decode-to-application feedback and
moved the limiting family to FTQ mismatch recovery. At the same 4-ns constraint,
the exact final result improved from WNS -4.303 ns / 120.438 MHz to WNS
-2.880 ns / 145.349 MHz. TNS is -38,062.918 ns across 49,340 failing setup
endpoints; hold is clean at WHS +0.014 ns. The new worst path is the asynchronous
FTQ head through mismatch classification into `fetchPc` (6.861-ns data delay,
29 logic levels, 69% routing), with sibling endpoints in FTB valid clears. This
is evidence for a second registered mismatch-recovery boundary, not for putting
the decode-local application feedback back.

Area remains a healthy review result rather than a rejection trigger: 111,786
CLB LUTs (51.52%; 102,889 logic and 8,897 LUTRAM), 50,396 registers (11.61%),
26 BRAM tiles (5.42%), and four DSP48E2s (0.22%). Compared with the immediately
preceding final-functional route, this cut is +2,228 logic LUTs, unchanged
LUTRAM, -104 registers, unchanged BRAM, and unchanged DSP. The implementation
flow now emits an explicit optimized post-synthesis timing/utilization report
before applying either pblock so the next pass can separate RTL depth from
floorplan/route loss.

The registered-mismatch recovery route uses generated-Verilog MD5
`35c9bc31a14ba1a6aff9e65b7c4dfb89`. It improves the same 4-ns floorplanned
checkpoint again to WNS -2.447 ns / 155.111 MHz, TNS -31,052.055 ns across
48,965 failing endpoints, with hold clean at +0.020 ns. This is +0.433 ns over
the immediately preceding -2.880-ns route. Area also improves to 109,217 CLB
LUTs (50.34%; 100,320 logic and 8,897 LUTRAM), 50,507 registers, 26 BRAM tiles,
and four DSP48E2s. Thus neither global area nor the DSP reshape is the limiter.

The mismatch family is gone from the top census. The new worst path is
`DecodeStage.ucPendValid` through the live decode/IBuf fallback-prediction cone,
`applyNow`, the ITLB CAM, and `issFire` to fetch-ring/I-cache enables: 6.342 ns,
21 levels, and 67% route. It crosses the right edge of `pb_decode` (X87) and
lands around X99--X103. The routed region is 96.78% occupied (5,284/5,460 CLBs).
`pb_dcache` remains structurally over-subscribed: 5,910 parent-assigned CLBs for
5,460 sites and 6,422 in-region CLBs (117.62%). A paired exact-netlist expansion
of both right edges to X103 reduced decode occupancy to 93.60% and D-cache
occupancy to 102.30%, but regressed route WNS to -2.558 ns / 152.486 MHz. The
worst path remained the same 21-level family and grew to 6.456 ns with 70.3%
route. X103 is therefore rejected. A saved-checkpoint no-floorplan control is
the remaining placement diagnostic; regardless of that geometry result, the
measured fallback-prediction action above is now the selected RTL boundary. The
II=1 fetch-directed path and its C+1 target command remain unchanged.

The current branch's floorplanned route is a hard prerequisite. The checkpoint
completed at 173.430 MHz (WNS -1.766 ns at the 4-ns constraint), below the
200-MHz deployment floor, after the intentionally broad LSU/D-cache/CPLX/ALU
throughput campaign. The project owner explicitly selected one bounded
exception to the usual stop rule: finish only the already-scoped DSP-register
reshape and this registered-token FTB, then perform a consolidated timing,
area, and floorplan recovery pass. This does not waive either target: 250 MHz
remains the optimization goal and 200 MHz remains the deployment floor. No
additional broad feature RTL is authorized before recovery.

The checkpoint also proves this is not a global-area failure: full-device use is
54.37% LUT, 11.54% register, 5.42% BRAM, and 0.22% DSP. It is, however, a local
floorplan failure: `pb_dcache` contains 6,394 parent-assigned CLBs despite only
5,460 available sites, and the physical region is at 122.60% CLB occupancy once
non-assigned occupants are included. The final recovery pass must therefore
consider pblock rebalance as well as RTL endpoint work. After the inert table
slice and after enabling application, run paired floorplanned routes and report
WNS/TNS, failing families, LUT/FF/BRAM/DSP, and pblock health.

## 9. Binding verification

Tests must prove events, association, and architectural results—not merely that
no error was observed.

1. **Registered II=1 lookup.** Eight changing window PCs on consecutive cycles;
   eight results exactly one cycle later with exact `{slot,seq,pc}`. Mutation to
   live-PC freshness must fail.
2. **Target cadence.** A trained window W fires in C and its target T fires in
   C+1; W+8 never fires. Require nonzero `applyNow`, FTQ push, and ringKeep update.
3. **Backpressure.** Result arrives while `ic.cmd.ready=0`; ringKeep/FTQ update
   once, target/drop remain stable, and exactly one target command later fires.
4. **Ring reuse/turnover.** Full-ring response plus replacement issue; require
   the fixed-C+1 provider result while its named record is still resident, the
   sequence/token assertion true, and application only to that record.  A
   redirect or stale record is ignored.  Deliberately delaying, corrupting, or
   mis-associating either provider must trip the assertion rather than silently
   decline a prediction.
5. **Redirect collisions.** Redirect at query, result, pending-target, response,
   and confirm phases; no stale FTQ entry, GHR shift, truncation, or target issue.
   An external/test redirect at result time must physically veto application.
   A registered internal redirect at result time must instead exercise
   `applyNow && ftqPush && ftqFlush`: with cache ready low its temporary hold is
   cleared at the edge; with ready high its one accepted target command and the
   older source record are both stale, and only the recovery response may feed.
6. **Framing guard.** Parent design's A1–A10 matrix, mutations for clamp, past,
   length, overshoot, starvation dwell, one-entry clear, and suppression. Require
   detector C → registered action C+1 exactly, with no feed or I-cache command in
   C+1 and with the captured recovery/clear PCs unchanged by the live FTQ head.
7. **Fallback.** FTB miss, direct-map collision, second branch, cross-window
   branch, FTQ defensive-full, and return all exercise the existing slot-0/slot-1
   BTB/gshare/RAS behavior. For every taken fallback, require branch feed/detect
   in C, exactly one registered action in C+1, no decode feed in C+1, and target
   command fire in C+1 when ring/cache ready. Prove the C sequential command and
   any C+1 old response are stale, while the C+1 target slot is live and returns
   exactly once. Hold ring/cache backpressure and prove the captured target/drop
   remain exact with no duplicate command. Application-disabled cycles remain
   baseline-identical.
8. **Architectural oracle.** Compare retired macro PC/op/register/memory traces,
   not speculative feed PCs, after correct prediction and every mismatch class.
9. **Performance.** Paired pinned-seed ideal and `l2:5:70` IPC, per kernel first;
   require target-gap reduction and no >2% regression in any uncovered/control
   kernel before considering aggregate gain.
10. **Physical.** Paired floorplanned route against the 250-MHz goal and 200-MHz
    hard floor, plus explicit timing-family, area, and pblock deltas.

`make SBT=~/sbt/bin/sbt test-fast` remains mandatory before every handoff. Full
Verilator/corpus and Vivado runs remain PM-serialized.

Implementation evidence for the registered fallback boundary:

- `FetchDirectedFtbSpec`: 11/11, including exact detector C/action C+1,
  action-cycle target command, unaligned drop, coincident physical application,
  old-response kill, live target response, and stable exact target/drop through
  cache-command backpressure;
- mutation-sensitive collision bookkeeping checks the registered FTQ state only
  after the action edge rather than weakening the flush requirement; and
- mandatory `make SBT=~/sbt/bin/sbt test-fast`: 147/147 tests across 154 suites.
- pinned seed-1 `branchy`, `hot-loop`, and `call-return` are cycle-, event-, and
  IPC-identical to the pre-retime final checkpoint under both ideal and
  `l2:5:70` memory.

The fresh generated-netlist synthesis and floorplanned route remain the physical
acceptance gate; the saved-checkpoint X87/X103/no-floorplan comparison describes
the pre-fallback placement baseline only.
