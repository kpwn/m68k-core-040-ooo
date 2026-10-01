# Experimental four-cycle dependent L1 load loop

Status: **specification only; default OFF**. This amends the optional L1
latency work described in the D-side non-blocking design spec. No RTL, test,
or scheduling change is authorized by this document alone. The experiment
targets a simple, aligned, cacheable longword `MOVEA.L (An),An` dependent
chain. Other instructions retain the existing path unless their safety is
proved separately.

## Measured starting point and cycle contract

The integrated P1/P3/probe-forward configuration measures 644 cycles for 128
steady 2 KiB chase hops, or 5.031 cycles per dependent issue. The relevant
current path is:

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

1. **T3 scheduling:** Permit the IQ's LS selector to recognize the already
   registered P3 speculative-pdst announcement in the same cycle it is
   broadcast, placing one dependent LS load in the *existing* LS issue
   register for T4. Reuse the ordinary LS slot priority, skid, flush, and
   other-dependency tests. The new combinational ready condition may remove
   only the dependency named by this announcement; it cannot clear an
   unrelated dynamic wait or static trigger. The existing registered
   `dynWaitAny` and `lsBusy` state remain authoritative for all other paths.
   The announcement may be false when C0 stalls or misses; it does not
   confirm data. A registered probe-hit bit could narrow false announcements,
   but cannot itself save a cycle through the existing registered IQ clear.
   Bringing its token/physical-tag/staleness qualification from D-cache
   through LS to IQ is optional only after timing evidence; the P3 context
   already carries a registered `pdst`.
2. **T4 live-data confirmation:** The LS EU may assert `liveLoadConfirm` only
   for the *actual* response that wins the aligned ring's live-head
   completion in T4: exact response RID/slot identity, valid+sent+not-done
   ring entry, terminal single-access load, no fault, poison, flush, previous
   writeback, privilege debt, or split merge, and an actual integer `pdst`
   wakeup. The same result value that `captureCompletionDesc` writes into
   `compData` must be available through a local LS operand bypass to its
   T4 `rdBase` and `rdIndex` reads. The IQ may treat a source as confirmed
   in T4 only when its physical tag equals this exact live `pdst` **and**
   the local data bypass is valid. The normal T4 `nextIntWake`, registered
   `lsBusy` clear, T5 `compValid`, PRF write, and ordinary bypass remain.

Both address operands are checked independently. For each of `psrcA` and
`psrcC`, either the normal `lsBusy` test already says the value is ready, or
the exact live-confirm tag matches and that operand takes the live data
bypass. If both depend on the same live `pdst`, both may use it; if they
depend on different pending producers, one response cannot release both.
`psrcB`/store data is never speculatively cleared and never uses this
shortcut. First-version eligibility also excludes stores, LEA, CCR restore,
auto-update, split, byte/word sign-extension, inhibited/device, privileged,
faulting, line-only, and other non-simple forms. The producer's translated
cacheability and successful translation must be known before treating a P3
announcement as eligible. A P2T pretranslation announce is *not* the first
implementation: it could put a consumer of a subsequently inhibited or
faulting load in the single LS issue register for a long time, hiding older
work and threatening progress.

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
flush, not infer safety from the low fault rate of a chase.

When the early hint is wrong, the dependent stays in the IQ LS issue
register under the existing `lsSpecBlocked` recheck until the real wakeup,
or is flushed on a fault. This is the existing replay/hold mechanism: the
experiment does not issue the consumer with guessed data. Bounded-progress
tests must also include a blocked LS port with older independent work, to
ensure the extra cycle of preselection does not create head-of-line deadlock.
The same-cycle `!lsSpecBlocked` exception applies **only** to the LS issue
register and only to A/C operands with the LS-local response-data bypass.
It must not clear global `lsBusy`, other IQ classes, ALU/branch/CPLX issue,
or a dependent that reads store data. Otherwise a non-LS consumer could issue
without any corresponding T4 data bypass.

## Cost and timing gate

No new data queue or copied cache line is proposed. Reusing P3 context, the
existing LS issue register, and the ring response requires no new payload
state. If a dedicated registered producer hint is necessary, budget roughly
one valid bit plus a six-bit `pdst`; any additional state needs a spec
amendment. Combinational cost is at least an LS-source tag compare for A and
C, two 32-bit operand selects, and an IQ same-cycle ready/priority bypass
across up to 16 slots. A resident-hit-only response qualifier may require
a one-bit field in the existing D-cache response service; it must have the
D-cache as its documented producer and may only narrow eligibility. There is
no claim of mapped LUT/Fmax improvement from this state estimate.

The new data path is D-cache registered S2 result → response-data mux →
RID/head match and LS completion selection → local 32-bit operand mux →
`s1Base`/`s1Index` flop. The new scheduling path is registered P3 `pdst` →
IQ per-slot A/C match → ready/priority selection → LS issue register. Both
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
  back-to-back independent hits, two different pending A/C sources, same
  source on A+C, store-data dependency, DTLB fault, bus fault, split,
  inhibited/privileged access, flush/poison, RID wrap, ROB/physreg reuse,
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
