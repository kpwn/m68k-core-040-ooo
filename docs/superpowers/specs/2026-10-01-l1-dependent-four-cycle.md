# Experimental four-cycle dependent L1 load loop

Status: **bounded implementation experiment authorized; default OFF**. This
amends the optional L1 latency work described in the D-side non-blocking design
spec. The user authorized a focused oldest-only scheduling and live-head
confirmation experiment with directed safety tests and matched latency gates;
physical timing remains a separate acceptance gate. The experiment
targets a chain whose producer is a simple, aligned, cacheable longword load
and whose consumer is a fused, first-and-last `MOVEA.L (An),An` with one
address-base source (`psrcA`) and no index, `psrcC`, or store-data source.
The consumer's own effective-address cacheability is not known at IQ select;
it must still pass through ordinary AGU, translation, and memory barriers.
Other instructions retain the existing path unless their safety is proved
separately.

## Measured starting point and cycle contract

The isolated agent72 P1 matched branch measured 644 cycles for 128 steady
2 KiB chase hops, or 5.031 cycles per dependent issue. The integrated
`97b0ba49` P1 × probe-miss matrix subsequently reproduced 644/128 with P1
enabled, both with and without the probe-miss stage. Its four arms passed
under the same `l2:5:60:4096` model and seed 17; results are recorded in
`/tmp/codex-agent59-combined-r2-matrix-results.json` and the non-blocking
design spec's integrated latency section. This remains simulation evidence,
not physical timing signoff. The relevant current path is:

| Cycle relative to producer issue | Existing event |
| --- | --- |
| T0 | Producer fires from the LS IQ issue register; LS captures its operand. |
| T1 | P1 atomically launches DTLB translation and the virtual-set probe. |
| T2 | Registered DTLB response meets the cache probe read; LS advances to P3. |
| T3 | P3 fast-enqueues/falls through to the cache command; the registered probe line can supply an early hit. P3 broadcasts speculative `pdst` wakeup. |
| T4 | D-cache's registered S2 hit response reaches the aligned ring. The live head response captures `compData` and announces the real wakeup. The dependent is selected into the IQ LS issue register. |
| T5 | `compValid` drives the integer PRF write and existing bypass. The IQ's registered `lsBusy` clear permits the dependent to issue and capture the bypassed operand. |

The relevant source boundaries are `LsEuPlugin.scala` P1 request/resolve
(~4614/4523), P3 speculative wake (~4026), ring fall-through (~1803), live
head response and completion (~1689/3230), D-cache S2 response
(`DcachePlugin.scala` ~1843), and IQ `dynWaitAny`/`lsSpecBlocked`
(`IssueQueuePlugin.scala` ~205/~1092). Nax's three-cycle load pipe is not a
measurement of this full issue-to-issue loop.

The experiment's target is **T4 dependent issue with the correct operand**,
reducing a matched steady 2 KiB chase from about 5 to about 4 cycles/hop.
Moving a wakeup signal alone does not meet this contract: `compData` and the
existing PRF bypass are not available until T5.

## Boundary and data contract

Communication remains through services. The D-cache's existing load-response
service supplies the data to the LS EU. The LS EU alone produces any new
`liveLoadConfirm` service signal for the IQ; the IQ never reads D-cache or LS
implementation registers. If a `Global` key is needed, document the LS EU as
its sole producer. The new signal is a **qualified scheduling/confirmation
hint**, not a second architectural completion, PRF write, ROB completion, or
replacement for the ordinary `wakeup` pulse.

The first experiment has two separately gated parts:

1. **T3 scheduling:** Permit only the **oldest occupied LS slot** selected by
   the existing `ohLoldest` mask to recognize the registered P3
   speculative-pdst announcement in the same cycle it is broadcast, placing
   that dependent load in the *existing* LS issue register for T4. Do not OR
   the hint into `slot.ready` or change ordinary relaxed ready selection.
   The special candidate requires `psrcAValid`, exact tag match, the eligible
   A-only consumer form, no LS skid occupant, zero static triggers, and an
   individually checked dynamic-wait vector whose only remaining bit is
   `LS_A`; **after removing that one bit, its residual must be zero**. If the
   ordinary relaxed selector already has a ready LS candidate, do not
   displace it with this speculative candidate. In
   particular, no B/C, NZVC, slow, or other wait can be silently cleared by
   a registered `dynWaitAny` bypass. Reuse ordinary LS slot fire/compaction,
   skid, and flush behavior. The existing registered `dynWaitAny` and
   `lsBusy` state remain authoritative for all other paths.
   The announcement may be false when C0 stalls or misses; it does not
   confirm data. A registered probe-hit bit could narrow false announcements,
   but cannot itself save a cycle through the existing registered IQ clear.
   Bringing its token/physical-tag/staleness qualification from D-cache
   through LS to IQ is optional only after timing evidence; the P3 context
   already carries a registered `pdst`.
2. **T4 live-data confirmation:** The LS EU may assert `liveLoadConfirm` only
   for the *actual granted completion* that wins the aligned ring's live-head
   completion in T4: exact response RID and still-live slot lifecycle,
   valid+sent+not-done
   ring entry, terminal single-access load, no fault, poison, flush, previous
   writeback, privilege debt, or split merge, and an actual integer `pdst`
   wakeup. The same result value that `captureCompletionDesc` writes into
   `compData` must be available through a local LS operand bypass to its
   T4 `rdBase` read. The IQ may treat the base source as confirmed
   in T4 only when its physical tag equals this exact live `pdst` **and**
   the local data bypass is valid. A physical tag matches only the
   consumer's operand; it does not establish response ownership. The confirm
   must depend on the already-selected completion grant and ring identity,
   **never** on the current `issue.fire`, `s1Ready`, `alignedCanEnq`, or IQ
   selection, which would create a ready/confirmation feedback loop. The
   normal T4 `nextIntWake`, registered
   `lsBusy` clear, T5 `compValid`, PRF write, and ordinary bypass remain.

The first version checks only `psrcA`: it may be released at T4 only when
the exact live-confirm tag matches, and `rdBase` takes that same response
data. The fused first-and-last consumer must have no `psrcC`/index and no
`psrcB`/store-data source. Indexed loads, two-source address forms, stores,
LEA, CCR restore, auto-update, split, byte/word sign-extension, privileged,
line-only, and other non-simple forms are excluded. A fault discovered later
suppresses live confirmation and takes the existing precise-fault path. The
**producer's** successful translation and cacheable mode are known in P3
before its scheduling announcement. The **consumer's** target may later
translate to inhibited/device memory; this shortcut changes only when its
base operand is captured, never the consumer's translation, ordering, or
barrier checks. A P2T pretranslation announce is *not* the first
implementation: it could put a consumer of a producer that subsequently
resolves inhibited or faulting in the single LS issue register for a long
time, hiding older work and threatening progress.

`liveLoadConfirm` must have exactly one producer: the LS EU's ring completion
arbiter. It is invalid when the D-cache's single response port is occupied
by another RID or when the expected hit response is delayed. A later miss
response, a queued/parked ring response, or simultaneous completion-stage
contention takes the existing path in the first version. No response is
duplicated or consumed early. A flush suppresses the hint and clears any
preselected consumer via the existing IQ flush; a fault never publishes a
data confirm and retains precise exception handling. A poisoned response
may drain its ring slot but never bypasses into a newly reused ROB/physical
destination. Directed tests must force RID wrap and ROB/physreg reuse across
flush, not infer safety from the low fault rate of a chase. Reuse existing
poisoned-slot retention and RID lifecycle; add a generation register only if
a directed counterexample proves those checks insufficient.

When the early hint is wrong, the dependent stays in the IQ LS issue
register under the existing `lsSpecBlocked` recheck until the real wakeup,
or is flushed on a fault. This is the existing replay/hold mechanism: the
experiment does not issue the consumer with guessed data. Bounded-progress
tests must include a delayed producer and an older LS slot whose B source
becomes ready while a younger load receives the speculative hint. The
oldest-only gate must leave the younger in the IQ, let the older proceed,
and then drain/replay the younger with checked data. An earlier speculative
younger occupant could add head-of-line delay; a permanent cycle is not
established because the existing P4 replay may recover. Measure the delay
and prove bounded progress instead of claiming a deadlock without a trace.
The same-cycle `!lsSpecBlocked` exception applies **only** to the LS issue
register and the eligible `psrcA` operand with the LS-local response-data bypass.
It must not clear global `lsBusy`, other IQ classes, ALU/branch/CPLX issue,
or a dependent that reads store data. Otherwise a non-LS consumer could issue
without any corresponding T4 data bypass.

## Cost and timing gate

No new data queue or copied cache line is proposed. Reusing P3 context, the
existing LS issue register, and the ring response requires no new data-payload
state. The exact fused simple-An eligibility is not in `IqHot`: its `lastOfInstr`,
`isMovea`, `eaAuto`, size and immediate live in the cold LUTRAM record. Reading
that record in the T3 oldest-select cone would add a RAM read and selection
level. This experiment therefore permits one predecoded eligibility bit per
16 IQ hot slots, copied into the existing LS issue/skid hot registers (18 bits
of nominal metadata state). The bit is decoded once at push from the full
dispatch record; it does not widen the data bypass. A separate P3-only
speculative tag is a wire sourced by the LS EU's registered P3 context, not a
new tag register. The D-cache response service gains one `residentHit` bit.
A legacy cold miss can refill and **relaunch its original command through
S1→S2**, so `ldS2Resp` alone does not establish resident-hit provenance.
This experiment permits two one-bit provenance registers alongside existing
S1 and S2 state: fresh accepted S1 command versus replay/shadow, then S2
carry. The accepted direct early-probe-hit arm sets S2 provenance explicitly.
The D-cache drives `residentHit` true only for a fresh S2 L1-hit response
that wins its response port; the LS confirm rejects refill, replay and bus-
fault responses even when they match a live ring head.
No RID/ROB generation register is budgeted unless poisoned-slot retention
fails a directed test. Any other state needs a spec amendment. Combinational
cost is at least an LS-source tag compare for A,
one 32-bit base-operand select, and an IQ same-cycle oldest-only eligibility
check (one candidate slot rather than 16 independent ready bypasses). A resident-hit-only response qualifier may require
a one-bit field in the existing D-cache response service; it must have the
D-cache as its documented producer and may only narrow eligibility. There is
no claim of mapped LUT/Fmax improvement from this state estimate.

The new data path is D-cache registered S2 result → response-data mux →
RID/head match and LS completion selection → local 32-bit operand mux →
`s1Base` flop. The new scheduling path is registered P3 `pdst` →
IQ `ohLoldest` slot's A/residual-wait check → LS issue register. Both
cross previously protected timing boundaries. The old unsplit D-cache
S1-to-response path was about 13 levels and failed routed timing; the old
ALU-bypass-plus-AGU chain failed at -2.375 ns. Do not move address addition
back before `s1Base`. A functional 4-cycle trace is insufficient to enable
this option without mapped and routed path evidence.

## Acceptance before RTL handoff or default change

- Spec and implementation stay default OFF. Assert one live confirm per
  response and equality between the bypass value, eventual `compData`, and
  checked architectural data. Trace T0 issue through T4 confirm/data/consumer
  issue and T5 PRF write on a steady, data-dependent 2 KiB chain. Matched
  OFF/ON source, seed, memory model, and options must show a full-cycle
  reduction; report hops, cycles, and exact signal edges.
- Force miss, delayed/refused response, wrong RID, response-port contention,
  back-to-back independent hits, delayed producer with an older LS B-source
  becoming ready before a younger hinted load, indexed/C-source and two-source address
  forms (must be excluded), store-data dependency (must be excluded), DTLB
  fault, bus fault, split, inhibited **producer** and inhibited **consumer**
  target, privileged access, flush/poison, RID wrap, ROB/physreg reuse,
  and SQ-forward collision. Each must either complete with checked data or
  take the existing hold/flush path. A mutant that skips the live-data
  confirmation must fail checked data rather than compilation.
- Run focused correctness/lockstep and `make SBT=~/sbt/bin/sbt test-fast`,
  then a matched 2 KiB/64 KiB chase and independent four/eight-chain sweep
  with retirement and data checked. Do not call it an IPC gain from the
  dependent chain alone; report any LS-port hold or MLP regression.
- Compare same-config synthesized LUT/FF counts and worst endpoints, then
  routed 5.000 ns timing with the option ON. Default enablement requires
  nonnegative routed WNS and no new critical clock-enable/ready path through
  IQ selection or response bypass. If the response path cannot close,
  keep the default OFF and do not release T4 consumers speculatively.

## Experimental evidence and remaining safety scope

On the same agent79 source, `l2:5:60:4096`, seed 17, NB4/ring8, LS OoO,
fused MOVEA, P1/P3, speculative wake, early line forward, and the NB
early/eager/preselect/dynamic/probe-miss options, the 2 KiB chase measured
644/128 = 5.031 cycles/hop with this option OFF and 517/128 = 4.039 ON.
The 64 KiB chase measured 61598/4096 = 15.039 in both arms; the ON run
recorded 128 live resident confirmations and 128 actual bypass fires for
the 2 KiB chain, and zero confirmations for the 64 KiB chain. Logs:
`/tmp/codex-agent79-fourcycle-final-{off,on}.log`. This is a dependent
simulation result, not mapped or routed timing evidence.

The focused shipping-core trap fixture forces a cold first read, then runs
eight fused MOVEA loads around a three-line pointer ring, takes TRAP #0/RTE,
and runs eight more. It checks the distinct expected pointer after each
eight-load phase and a PASS sentinel, while counting actual LS operand-bypass
fires before and after the exception. With the 60-cycle DRAM model it passed
with 7 fires before and 7 after, 4 nonresident responses, 15 live resident
hits, and bounded completion (`/tmp/codex-agent79-fourcycle-trap-r2.log`).
This establishes real data-dependent activation across a completed exception;
it does **not** force a trap on the same edge as an outstanding response or
prove stale RID/physical-register reuse safe in every timing alignment.
The broader wrong-RID, fault, split, response-contention and cancellation
matrix above remains a separate acceptance gate. The option stays default
OFF until those cases and physical timing are reviewed.
