# IPC push: a genuine LS EU pipeline — replacing the one-µop-at-a-time FSM (design)

**Status**: DESIGN / SCOPING ONLY. No RTL written, no RTL modified, no test
modified, no Vivado launched by this pass. Every netlist number below is
read-only reuse of the existing routed checkpoint `synth/fullcore_routed.dcp`
(2026-08-09 08:05, 221.828 MHz) and the census reports produced from it
(`synth/census_*.rpt`, `synth/lscensus_*.rpt`).

**Driving directive (user, verbatim)**: *"LS EU needs to be a pipeline, not a
one-at-a-time FSM."*

**Governing principle (user, verbatim)**: *"better to have one extra latency
cycle in a pipeline that can do real work than static fsm that does one thing
over several cycles."*

That principle is binding on every tradeoff in this document. Where a design
point trades per-op latency for initiation interval (II), this spec takes the
II. Where it does **not** — and there is exactly one such place, §4.5 — the
trade is called out explicitly with its reason.

**Binding initiative constraint (user, verbatim)**: *"push ipc as high as
reasonably possible without blowing up lut count or crashing fmax."*

**Relationship to work already in flight**: the "LS EU late split"
(`docs/superpowers/specs/2026-08-09-ipc-ls-eu-pipeline-depth-design.md`, plan
`docs/superpowers/plans/2026-08-09-ipc-ls-eu-late-split-implementation-plan.md`)
is being implemented **right now** on branch `feat/ls-eu-late-split`
(worktree `…-worktrees/ls-split-work`, Slice 1 landed as `cc38cc8`). This
document does **not** supersede it. §8 is the staging recommendation and it is
the section to read first if you only read one.

---

## Contents

- [0. What this document is, and what changed since the late-split spec](#0)
- [1. Goals / Non-goals](#1)
- [2. What "a pipeline, not an FSM" concretely means here](#2)
- [3. The three throughput gatekeepers — measured and structural](#3)
- [4. Architecture: the in-order LS pipeline](#4)
- [5. Hazard analysis (H1–H14)](#5)
- [6. FMax treatment and the mandatory census gate](#6)
- [7. Area](#7)
- [8. Staging, and the relationship to the in-flight late split](#8)
- [9. Verification plan](#9)
- [10. Alternatives considered](#10)
- [11. Honest risk assessment](#11)

---

<a name="0"></a>
## 0. What this document is, and what changed since the late-split spec

The late-split spec answered a narrower question: *can we stop the D-cache
access from holding S1 hostage?* Its answer — a front FSM and a back FSM with
one handoff at `RESOLVE`→`LAUNCH` — is a **2-slot handoff**, not a pipeline.
Two µops can be in flight; the third waits. It buys non-forwarded load II
9 → ~4–5 and buys nothing for stores or forwarded loads.

The user's directive is larger: *multiple* µops in *distinct* stages, a new µop
entering close to every cycle. This document scopes that.

Four things are established that the earlier spec did not have:

1. **A genuinely pipelined EU already exists in this codebase and is the
   template.** `AluEuPlugin.scala:197` —
   `issuePort.ready := !((s1Valid && isSlow) || s1aValid || s1a2Valid || s1bValid || s2Valid || s3Valid)`
   — is high **every cycle** on the fast path. Its S0→S1 boundary is an
   unconditional `RegNext` chain (`:152-158`), it accepts back-to-back, and its
   slow path (a 6-deep S1/S1a/S1a2/S1b/S2/S3 chain, grown one FMax stage at a
   time) is single-outstanding *only because those ops are rare*. `BranchEuPlugin.scala:117`
   is `issuePort.ready := True`, literally constant. So "an EU that accepts one
   µop per cycle" is not a new idea here — the LS EU and the Div EU
   (`DivEuPlugin.scala:111`) are the only two that do not.

2. **The IQ imposes no rate limit whatsoever.** `IssueQueuePlugin.scala:466`
   pipes every issue port through `m2sPipe(collapsBubble = false, …)`, which
   makes `selPorts(k).ready` *be* the EU's `ready`, verbatim and
   combinationally. If the LS EU's ready is high every cycle, the IQ selects,
   registers, and delivers an LS µop every cycle. There is no credit counter, no
   occupancy timer, no per-port busy flag to change. **Nothing outside the LS
   corridor needs to change to feed a 1/cycle LS pipeline.**

3. **The Slice-1 review's DTLB-liveness finding generalises into the central
   structural constraint of the whole design** (§3.1). It is not a corner case
   to patch; it is the reason a "pipelined LS EU" is not an `LsEuPlugin.scala`
   project.

4. **`DcachePlugin` is the #1 failing-endpoint family and the FMax relief
   available from perfecting the entire D-side corridor is exactly 0.00 MHz**
   (`synth/census_family.rpt`; late-split plan Task 1, gate G-L4). This lever
   can only *lose* FMax. Its acceptance case must rest on IPC + LUT +
   FMax-neutrality, and no task may gate success on MHz movement.

5. **The D-cache load port has its own hard II = 3 ceiling, independent of
   anything the LS EU does** (`DcachePlugin:732,758`) — 0.333 loads/cycle. Per
   explicit user direction that ceiling is **in scope** for this effort, so
   §3.2 grounds it, §8.2 slices it as real work (4a investigation → 4b RTL), and
   §3.4/§8.3 carry both the with- and without- columns. Crucially, the exact
   relaxation needed has **already been attempted once and corrupted data**
   (`183 != 202`, a lost dirty-victim writeback) — see §3.2 and §11.

---

<a name="1"></a>
## 1. Goals / Non-goals

### 1.1 Goals

- **G1** — Turn the LS EU into a genuine in-order pipeline: several LS µops
  resident in distinct stages simultaneously, with a new µop entering on
  (close to) every cycle, in place of today's
  `issuePort.ready := !busy && !s1Valid && !compValid` single-occupancy contract
  (`LsEuPlugin.scala:1158`).
- **G2** — Bring the initiation interval of **all three** LS µop classes down
  toward the structural floor: store 4 → target 2, forwarded load 6 → 2,
  non-forwarded L1D-hit load 9 → 2–3. (Measured baselines: LSU grounding entry,
  ledger; per-class II re-derived in §3.)
- **G3** — Accept **increased per-op latency** wherever it buys II, per the
  governing principle. The target end-state pipeline is 8–10 stages deep — i.e.
  *longer* than today's 9-cycle non-forwarded load latency, possibly by 1–2
  cycles — and that is the intended outcome, not a cost to be minimised.
- **G4** — Preserve, bit-for-bit, every architectural property the current FSM
  has: store→load forwarding (currently 100% effective, 25/25 RESOLVE hits in
  the `load/store` kernel), in-order LS memory ordering, precise exception
  delivery, wrong-path poisoning, split-access correctness, and the untagged
  in-order `DLoadRsp` contract.
- **G5** — FMax-neutral (≥ 219 MHz against the 221.828 MHz baseline) and LUT
  growth ≤ +1.5% device-wide. These are the late-split spec's own §8.4
  thresholds and they carry over unchanged.

### 1.2 Non-goals

- **NG1** — Out-of-order LS execution. LS issue stays strictly oldest-first
  (`IssueQueuePlugin.scala:315-337`); the pipeline stays strictly in-order. No
  memory-order buffer, no load-store disambiguation, no speculative
  store-bypass. The `ohLoldest` comment at `:315-334` documents the exact
  "dropped store" corruption that in-order LS issue exists to prevent; this
  design does not reopen it.
- **NG2** — More than one LS µop *issued* per cycle. §10.3 rejects a wider IQ→LS
  port. The pipeline is 1-wide and deep.
- **NG3** — Out-of-order *completion* among LS µops. §4.4 makes completion
  uniform and in-order on purpose, and that decision is what removes the need
  for a completion arbiter, a 6th ROB completion port, and a second Int/NZVC/X
  PRF write-port set.
- **NG4** — Hit-under-miss in the D-cache, and *tagged* multi-outstanding D-side
  loads. A miss stalls the whole LS pipe. `DLoadRsp` is untagged and the response
  has no flow control (`DcachePlugin.scala:60`); in-order blocking is what keeps
  that contract valid. Response tagging + demux is §10.5, deferred.
  **This is NOT the same as relaxing `inFlight = ldS1Valid || ldS2Valid`**, which
  *is* in scope (§3.2, slice 4b): that keeps responses untagged, in accept order,
  one per cycle, and keeps misses blocking — it only lets a second load's S1
  overlap the first load's S2, which is precisely the follow-on the S1a/S1b split
  spec itself flagged.
- **NG5** — Any change to `src/main/scala/m68k040/frontend/` or
  `src/main/scala/m68k040/decode/`. A separate fetch-directed-BTB effort owns
  those; zero interaction.
- **NG6** — Changing the StoreQueue's drain path, the copyback/precise-store
  machinery, or the exception-unit cache arbitration. All three are consumers of
  the LS EU's interfaces, not of its internal stage structure.

---

<a name="2"></a>
## 2. What "a pipeline, not an FSM" concretely means here

Today's LS EU is a 9-state `StateMachine` (`LsEuPlugin.scala:1220-1229`:
`IDLE / XLATE_B / XLATE / RESOLVE / LAUNCH / WAIT / WAIT_A / WAIT_B / WAIT_SQ`)
in which **the state IS the µop**. `busy` (`:1153`) means "a µop is somewhere in
this FSM", `s1Ctx`/`s1Base`/`s1Data`/`s1Index` are *the* µop's context, and
`issuePort.ready` is the negation of occupancy.

A pipeline inverts that: **the stage is the resource, the µop is the payload.**
Each stage owns a `valid` bit and a context register; each stage advances when
the stage ahead of it can take its payload. This codebase already has the exact
idiom, hand-rolled, in three places — and uses **no** library helper for it
(`m2sPipe` appears exactly once in the whole design, at the IQ boundary; there
is no `s2mPipe`, no `haltWhen`, no `StageableP`/`CtrlLink`, no `StreamFifo`
anywhere in execute or cache):

| Existing pattern | Where | Shape |
|---|---|---|
| Unconditional `RegNext` chain + a `ready` held low for the whole window | D-cache load S0/S1/S2 (`DcachePlugin.scala:258-346`, `:732`, `:758`) | forward path always advances; backpressure is a single combinational bit, producer holds-and-retries |
| `RegInit(False)` valid, `:= False` default, explicit re-assert to hold, last-assignment-wins arbitration | D-cache **store** S0/S1/S2 (`:452-510`, `:1470-1479`) | genuinely accepts back-to-back — the file states `stS1Valid`/`stS2Valid` "can be True on every consecutive cycle indefinitely — there is no structural 1-cycle bound" (`:573-577`) |
| Consume-then-accept ordering so a same-cycle accept overrides a same-cycle consume | I-cache `lookupTick` (`IcachePlugin.scala:689-798`) | gives depth-2 accept with **no** skid buffer: consume of `tValid` written first, `when(cmdPort.fire){ tValid := True }` written last |

The D-cache **store** pipe is the closest structural precedent for what the LS
EU should become: three stages, each with its own valid+payload registers, a
hold-and-retry handoff between S1 and S2 that reads only low-fanout control bits
(`:1470-1479`), and no library primitive. **The design in §4 is that pattern,
generalised to the LS EU's stage list.**

---

<a name="3"></a>
## 3. The three throughput gatekeepers — measured and structural

A pipelined LS EU is bounded by the slowest of three shared resources it does
not own. Each is characterised below with its citation and its cost to change.
**This section is the load-bearing scope finding of this document**: two of the
three live outside `LsEuPlugin.scala`, so a "pipelined LS EU" is a three-cluster
project, not a one-file one.

### 3.1 Gatekeeper 1 — the DTLB: **one translation per 2 cycles**, and it is a shared live port

`DtlbPlugin.scala` serves a **single, combinational** request/response pair off
one registered request. The hit path is served from a one-entry result register
by *value comparison*:

```scala
hrValid := False
when(mmuEnable && _req.valid && tlbHit) {          // ~:405
  hrValid := True;  hrVpn := _req.vpn;  hrPpn := tlbEntry.ppn; …
}
val hrMatch = hrValid && (hrVpn === _req.vpn)      // ~:417
… elsewhen(hrMatch) { _rsp.ready := True; _rsp.ppn := hrPpn; _rsp.fault := permFault(hrWp, hrSup) }
```

Unrolling: `hrValid(N)` reflects cycle `N-1`'s condition and `hrVpn(N)` is
`_req.vpn(N-1)`, so

> **`hrMatch(N)` ⟺ the same VPN was presented on both cycle `N-1` and cycle `N`.**

**A changing VPN never matches.** Under MMU-on with a TLB hit, the DTLB
therefore delivers at most **one translation per two cycles**, no matter what
the LS EU does. (`permFault` is additionally recomputed against the *live*
`_req.write`/`_req.supervisor`, so those must be held with the VPN too.)

The `!mmuEnable` identity arm (`:365-371`) and the DTT0/DTT1 transparent-
translation arm (`:372-379`) are combinational and need no settle — but the LS
EU imposes the same 2-cycle settle in *both* modes deliberately, via
`reqStale = reqReCaptured || xlateBArmSwitched` with
`reqReCaptured = RegNext(issuePort.fire || reqExcOverride)`
(`LsEuPlugin.scala:736-745`), so that "the +1-cycle alignment is identical in
both MMU modes" (`:670-672`).

**Second, harder problem: the D-cache reads this same live port.** This is the
Slice-1 review's finding, and it generalises:

```scala
loadCmdPort.ready := !inFlight && xlate.rsp.ready && !pendingStoreMiss && !maintBusyReg  // DcachePlugin:758
when(loadCmdPort.fire) { … ldS1Fault := xlate.rsp.fault … }                              // DcachePlugin:770
```

Those are the **only** two live DTLB consumptions inside `DcachePlugin`
(grep-confirmed: `xlate.` appears at `:73, :75, :157, :245, :291, :293, :758,
:770, :776`; all but `:758`/`:770` are comments). They work today only because
`reqDrvValid := s1Valid && (isLoad || isStore)` (`LsEuPlugin.scala:654`) and the
front holds `s1Valid` for the *entire* lifetime of the cache access. In a
pipeline the translate stage has moved on to a **younger** µop by the time an
older µop's cache command is accepted, so both reads become wrong:
`loadCmdPort.ready` gates on a stranger's `rsp.ready` (deadlock if it is low
while a walk runs) and `ldS1Fault` latches a stranger's `permFault` (silent
spurious vector-2 on a good load).

**Both reads are removable, and removing them is strictly a simplification.** A
load reaching the cache-command stage has, by construction, *already* resolved
its translation cleanly — `IDLE` only advances past translation on
`xlateReady && reqMatch && !xlateFault` (`:1267-1310`), so `ldS1Fault` is
provably always `False` for an LS-EU-originated load today. The fix is to carry
the resolved fault bit on `DLoadCmd` (the paddr is already carried) and delete
both live reads. That is a `DcachePlugin.scala` edit **inside the design's #1
failing-endpoint family**, and it is a hard prerequisite for *any* pipelining —
including, per the Slice-1 review, the late split's own `earlyFree = true`.

**Cost to remove the 2-cycle floor entirely** (i.e. to reach 1 translation/cycle):
convert `hr*` from a value-compare into a genuine 2-stage pipeline — register
`{vpn, write, supervisor, robId}` alongside the lookup result and answer with a
pipeline-valid bit instead of `hrVpn === _req.vpn`. Structurally small (it
*removes* a 20-bit comparator) but it changes the `rsp.ready` contract that four
consumers read, in the MMU cluster, and the walker/miss backpressure has to be
re-expressed per-request. **Not recommended in the first pass** — see §8.

### 3.2 Gatekeeper 2 — the D-cache load port: **a hard II = 3 ceiling, and the II = 2 relaxation has already been tried and it corrupted data**

**This is in scope for this effort** (explicit user direction: *"d-cache ii=3
ceiling can be changed to accommodate for fully-pipelined goal"*). It is
scoped here as real, intended, separately-gated work — §8.2 slice 2 — not as
someone else's problem.

`DcachePlugin.scala:732`: `val inFlight = ldS1Valid || ldS2Valid`, consumed by
`:758`. Unrolled against `loadCmdPort.ready := False` (the default at `:229`,
raised **only** inside `fsm.IDLE`):

| cycle | `ldS1Valid` | `ldS2Valid` | `ready` |
|---|---|---|---|
| T | 0 | 0 | 1 → **accept** |
| T+1 | 1 | 0 | 0 |
| T+2 | 0 | 1 | 0 |
| T+3 | 0 | 0 | 1 → accept |

**II = 3, i.e. 0.333 loads/cycle sustained — a hard ceiling on the D-cache's own
port, entirely independent of the LS EU.** No amount of LS-EU pipelining can
exceed it. Any throughput claim in this document that ignores this is wrong, and
§3.4/§8.3 are written against it.

#### What the term is, and is not, protecting

The S1a/S1b split spec introduced it as a deliberately conservative choice, in
its own words:

> *"extend this to `inFlight = ldS1Valid || ldS2Valid`, preserving the EXACT
> single-outstanding invariant — a load now simply takes 3 cycles … A more
> aggressive pipelined variant (allow a new load's S1 to run concurrently with
> the previous load's S2, since S2 no longer touches the shared BRAM read port
> or AXI channels and could in principle overlap) is NOT part of this slice —
> flagged as a possible follow-on for more throughput, explicitly out of scope
> here."*

**But that follow-on has since been attempted and it failed.** The independent
review of that slice reverted the extension in an isolated worktree — i.e. ran
exactly the `inFlight = ldS1Valid` / II = 2 configuration this design wants —
and recorded (`.superpowers/sdd/progress-fmax-slice2-dcache.md:55-59`):

> *"proved it is load-bearing, not defensive: an existing regression test
> ("AXI-hazard regression … offset=0") then fails with real data corruption
> (`183 != 202`, a dirty victim's writeback lost)."*

**Treat this as the governing fact: the exact relaxation this design needs is
known to corrupt data today.** It is not a one-character delete and it is not a
free follow-on.

Two structural facts narrow where the corruption must come from:

1. **It is not the S2 data path.** The same review's Minor finding #1
   established that `ldS2*` is *"a frozen snapshot with no live shared-resource
   read behind it"* — which is why `dcIdleForMaint`'s `!ldS2Valid` conjunct is
   invariant-hygiene, not corruption-prevention. So the term is not protecting
   the load's own registered data.

2. **It is not load/miss ordering either.** That axis is safe by construction at
   II = 2, and the argument is worth stating because it is what makes II = 2 the
   *right* target rather than II = 1: `loadCmdPort.ready` is raised only inside
   `IDLE`; a miss is detected at S1 (cycle T+1, `:777`) while the FSM is still
   in `IDLE`, and its `goto(EVICT_WR)`/`goto(REFILL)` takes effect at T+2. With
   `inFlight = ldS1Valid`, `ready` is already low at T+1 (S1 occupied) and low at
   T+2 (not in `IDLE`). **So no accept can ever land in the shadow of an
   unhandled miss** — precisely the hang the file already documents for the
   `pendingStoreMiss` case (`:744-750`: *"next cycle the FSM is servicing the
   store in REFILL and a load MISS has no handler … hanging the LS EU forever"*).
   The untagged in-order `DLoadRsp` contract survives too: two loads may be in
   S1/S2 but responses still leave in accept order, one per cycle.

By elimination, the `183 != 202` corruption is a **cadence interaction with the
shared physical AXI4 write channels and the shared BRAM read-port arbiter** —
the region this file already needed **three** revisions to get right
(`:386-433`: revision 1 shared AXI registers → misattributed write; revision 2
`storeWantsAxi` alone → *"a one-directional gate on a problem that is
symmetric"*, confirmed by a live trace; revision 3 `evictAxiPairOpen` +
`pendingWtKickoff`). A faster load cadence changes (a) how often store-S1 loses
the read-port arbiter to a load (`:1470-1479`, load priority via
`loadUsesPort`), hence when store-S2's write-through AXI kickoff fires, and
(b) when `EVICT_WR` is entered relative to a store's open aw/w pair — including
`evictAwDone`/`evictWDone`, which are reset **only** on actually entering
`EVICT_WR` (`:818-826`, itself a previously-found real bug).

**This spec does not guess the root cause and must not.** Root-causing that
exact regression is the **first, gated task** of slice 2 (§8.2), with an
explicit HALT: if the term turns out to be protecting something structural
rather than a fixable hole in the revision-3 AXI gating, the D-cache relaxation
is dead and the system target drops to II = 3 (§3.4 carries both columns).

#### Below II = 2

II = 1 requires accepting a younger load *while an older one may still miss*,
which reintroduces exactly the "a load MISS has no handler" hang above. Closing
it needs either a **nack/replay** path (the LS EU's P5 already holds-and-retries
`loadCmd` until `.fire`, but `.fire` has by then already been consumed, so a
nack is a new protocol signal) or **response tagging** on `DLoadRsp` with a
demux (§10.5). Both are materially larger than the II = 2 relaxation.

**Convergence worth noting**: the DTLB floor (§3.1) is *also* 2. Two independent
gatekeepers land on the same number, which is why **II = 2 is this design's
honest system target** and why chasing II = 1 (§8.4) would require lifting both
of them for ≈ +4–5% aggregate.

### 3.3 Gatekeeper 3 — the shared completion stage

One `comp*` register set (`LsEuPlugin.scala:762-827`) drives one ROB completion
port (`FullCoreSynth.scala:192`, `rob.logic.completion` is `Vec.fill(5)`,
`RobPlugin.scala:134`), one Int PRF write + bypass, one NZVC write + bypass, one
X write + bypass (`:159-171`). With N µops in flight, several could want to
complete in the same cycle. The existing precise-store deferred replay already
arbitrates two writers via `liveCompletionFires` (`:835`, `:1663`).

Adding a second completion path costs: a 6th ROB `completion` Flow (a 6th write
port into the 64-entry `completes` bit array, in the design's **#2** failing
family at 1122 endpoints), a second Int PRF write+bypass port pair (the audit
already names Int-PRF write-port count as the congestion epicentre), and second
NZVC/X ports. **This design refuses that cost** — see §4.4.

### 3.4 Resulting floor table

| target | needs | II floor per LS µop |
|---|---|---|
| Today | — | store 4 / fwd load 6 / cache load 9 |
| Late split (in flight) | `LsEuPlugin` + the §3.1 D-cache DTLB decoupling | store 4 / fwd load 6 / cache load ~4–5 |
| **Full pipeline, phase 1a** | + `LsEuPlugin` restructure, D-cache port **unchanged** | store 2 / fwd load 2 / **cache load 3** (§3.2 ceiling) |
| **Full pipeline, phase 1b** | + §3.2 relaxation (`inFlight = ldS1Valid`), *contingent on root-causing `183 != 202`* | **2, all classes** |
| Full pipeline, phase 2 | + §3.1 DTLB pipelining, + §10.5 tagged `DLoadRsp`/nack | 1 |

**Phase 1a + 1b is the recommendation, staged and separately gated.**

Note the split between 1a and 1b, which is deliberate and matters for risk:
**phase 1a needs no D-cache load-port change at all.** Stores and forwarded
loads go 4/6 → 2 purely from the LS-EU restructure, and non-forwarded loads go
9 → 3 — i.e. the LS EU stops being the constraint and hands the bottleneck to
the D-cache port. That alone is most of the win (§8.3), and it is measurable
before touching `DcachePlugin`'s `inFlight` at all. Phase 1b then removes the
last cycle **if and only if** the `183 != 202` root cause proves fixable.

Phase 2 buys II 2 → 1 for ≈ +4–5% aggregate ideal-memory IPC across the MMU
cluster and the D-cache response protocol; §8.4 recommends against it.

---

<a name="4"></a>
## 4. Architecture: the in-order LS pipeline

### 4.1 Stage map

Naming keeps today's semantics so the mapping is auditable. Each stage `Pk` owns
`pkValid : RegInit(False)` plus a context register set; **the state machine is
deleted** and replaced by per-stage valid bits.

| stage | today's equivalent | work | context it owns |
|---|---|---|---|
| **P0** | IQ `m2sPipe` + S0 (`:264-307`) | PRF reads (`rdBase`/`rdData`/`rdIndex`), `base0`/`data0`/`idxTerm0` mux | none (combinational, already exists) |
| **P1** | S0→S1 reg + the `reqMatch` settle cycle (`:1199-1207`, `:1242-1266`) | latch `{ctx, base, data, index}`; compute `s1Va`, `s1AddrB`, cross-detect; **drive `reqDrv*`** | `p1Ctx`, `p1Base`, `p1Data`, `p1Index`, `p1Poison` |
| **P2** | `IDLE`'s translate consume (`:1267-1310`) | consume `xlate.rsp`; latch `s2Paddr`/`s2Cmode`/fault; decide `twoAccess` | `p2Ctx`, `p2Va`, `p2AddrB`, `p2Paddr`, `p2Cmode`, `p2Fault`, `p2XlateSup`, `p2Poison` |
| **P2B** | `XLATE_B` (`:1332-1352`) | split-access second-half translate | *reuses P2's registers* — see §4.5 |
| **P3** | `XLATE` (`:1357-1422`) | STORE: `sq.io.alloc` (+ `fastStore`/`deferCompletion`). LOAD: `sq.io.fwd` query → latch `fwd*` | `p3Ctx`, `p3Paddr/B`, `p3Cmode`, `p3StoreData`, `p3Poison` |
| **P4** | `RESOLVE` (`:1432-1463`) | LOAD: `fwdHit` → data, else capture `llReg` | `p4Ctx`, `p4FwdHit/Data/Stall`, `p4Poison` |
| **P5** | `LAUNCH` (`:1469-1479`) | drive `dcache.loadCmd` off `llReg`; hold until `.fire` | `llReg` (exists today, 135 flops) + `p5Ctx` |
| **P6/P7** | D-cache `ldS1`/`ldS2` | *inside `DcachePlugin`* — no LS-EU registers | — |
| **P8** | `WAIT`/`WAIT_A`/`WAIT_B` + `comp*` (`:1484-1568`, `:762-827`) | consume `loadRsp`; merge cross halves; drive the single `comp*` stage | `p8Ctx` (the late split's `bkCtx`), `lineA`, `aDone` |

Depth from issue to completion: **9 stages**, vs. today's 9-cycle
*non-forwarded-load latency*. So the deepest class costs ~0–1 extra latency
cycles; the *shallow* classes (LEA, store, forwarded load) cost **+3 to +5**
latency cycles because §4.4 makes them drain through the full depth. Per the
governing principle, that is the intended trade: they go from II 4/4/6 to II 2.

### 4.2 Advance discipline — hold-and-retry, house pattern

Following the D-cache store pipe (`DcachePlugin.scala:1470-1479`) rather than
inventing elasticity:

```scala
// per stage k, canonical form
pkValid := False                                  // default-clear
when(pkValid) {
  when(pkCanAdvance) { p(k+1)Valid := True; p(k+1)Ctx := pkCtx; … }
  otherwise          { pkValid := True }          // hold, retry next cycle
}
when(p(k-1)Advances) { pkValid := True; pkCtx := p(k-1)Ctx; … }   // written LAST
```

Two properties, both deliberately copied from existing code:

- **Accept written last** so a same-cycle accept overrides a same-cycle
  consume — the `IcachePlugin.lookupTick` trick (`:689-798`) that yields
  depth-N accept with **zero skid buffers**.
- **Hold-and-retry, never drop**, reading only low-fanout control bits — the
  same discipline as `refillWriteHold` (`DcachePlugin:592`) and
  `storeDrainRefillHold` (`:642`), whose comments say "delay, never drop".

`issuePort.ready` becomes `!p1Valid || p1CanAdvance`. **This is a structural
change to the exact cone the `compValid` experiment perturbed** (§6.2) and is
the single largest FMax risk in the design.

Stall sources, all of which back-pressure the whole pipe in order:

| stall | stage | reason | frequency |
|---|---|---|---|
| DTLB not ready / settle | P2 | walk in progress, or the §3.1 2-cycle floor | every µop (the floor); rare (walk) |
| `sq.io.full` | P3 | today's `WAIT_SQ` | rare |
| `fwdStall` re-query loop | P4 | overlap with an older uncommitted store | workload-dependent |
| `dcache.loadCmd` not ready | P5 | `inFlight`, refill in progress, `pendingStoreMiss`, `maintBusyReg` | miss-rate-dependent |
| `loadRsp` not arrived | P8 | hit latency / refill | every cache load |
| split-access second pass | P2B / P8 | §4.5 | ~1 in N accesses |

### 4.3 What P1's translate drive looks like at II = 2

At phase-1 targets the DTLB request register must hold one µop's VPN for two
consecutive cycles (§3.1). Concretely, **P1 is a 2-cycle-occupancy stage** and
P2 consumes on the second: `p1Sub` (a 1-bit counter) selects "drive" vs
"consume-and-advance". Every *other* stage accepts 1/cycle. That is a genuine
pipeline with exactly one 2-cycle stage — the shape a µop stream sees is
II = 2, throughput-limited by a resource outside the LS EU. The alternative
(P1 1-cycle, DTLB pipelined) is phase 2.

`reqStale`'s three event terms (`:736-745`) collapse: with a per-stage discipline
the "does `reqReg` correspond to what is being presented" question is answered by
*which stage owns the request*, not by a freshness heuristic. `reqExcOverride`
must still steal the port (the exception sequencer is serializing and the LS pipe
is drained when it runs), and the corresponding `RegNext(reqExcOverride)` term
must survive — that is the term whose omission the Lever-A design doc's own
closure argument missed (`:696-703`).

### 4.4 Uniform in-order completion — the decision that avoids a second write port

**Every LS µop drains through P8 and completes there.** A LEA, a store, and a
forwarded load do not complete early at P1/P3/P4; they carry their result
forward and complete at the same stage a cache load does.

Consequences, all good:

- **Exactly one `comp*` writer per cycle.** No arbiter. No 6th ROB completion
  port. No second Int/NZVC/X PRF write port. The `AluEuPlugin` precedent is
  exact: its fast (S1) and slow (S3) writebacks **share one physical PRF write
  port** via `sharingKey = wbKey` (`:110`, `:129-136`), justified by a 4-step
  mutual-exclusion proof at `:94-101`. Here the mutual exclusion is stronger and
  simpler — one stage, one writer, by construction.
- **Completions stay in LS program order**, which preserves every existing
  ordering assumption in the ROB/whitebox join.
- **The precise-store deferred replay keeps working unchanged**: it already
  arbitrates against `liveCompletionFires` (`:1663`) and retries on collision.
  With one live writer instead of an FSM's several, that arbitration gets
  *simpler*, not harder.
- Cost: +3 to +5 cycles of latency on stores / LEA / forwarded loads. Under the
  governing principle this is the correct trade, and it is also the same trade
  the D-cache S1a/S1b split already made and measured: +1 uniform latency cycle
  on **every** D-cache hit bought +8.35 MHz and cost −0.8% IPC, net ≈ +4%
  (`.superpowers/sdd/progress-fmax-slice2-dcache.md`).

**Honest caveat, and the one place this design trades against the principle**:
a longer store/LEA latency lengthens the dependency chain for a consumer of a
stack-push's A7 or an EA-auto's An. Those consumers wake on the *dynamic*
`lsWakeup` broadcast (`:1136`), so correctness is unaffected — but a
`bsr`/`rts`-heavy kernel could see the latency back. `call-return` executes 180
LS µops and is **never** back-pressured by the LS EU (0.0% `valid && !ready`,
LSU grounding entry), so it is latency-sensitive and II-insensitive: it is the
one kernel that could regress. **`call-return` must be reported separately in
the IPC gate** and is the adjudicator for whether uniform completion needs an
early-out for stores (§10.4).

### 4.5 Split (cross-line / cross-page) accesses — pipeline-stalling replay

Split accesses need a second translation (`XLATE_B`) and, for loads, a second
cache access with a merge (`WAIT_A`/`WAIT_B`). They are the one class that
cannot be a straight-through pipeline pass.

**Decision: keep them as multi-pass, pipe-stalling replays**, not cracked µops.
A split µop occupies P2 twice (second pass with `xlateBArm`) and P5/P8 twice
(`llReg.bDone` selecting slot B), holding everything behind it. Rationale:

- They are rare, and every alternative is worse: cracking at decode changes the
  µop stream (and the whitebox commit join) for an ISA-visible-latency
  non-issue; a parallel split datapath duplicates the AGU and doubles the SQ
  alloc port.
- The `llReg.bDone` housekeeping bug the late-split spec found (§5.5 there:
  `IDLE`'s unconditional `llReg.bDone := False` at `:1241` would clear a live
  cross-load's `bDone` mid-flight once the front is freed) becomes **worse** in
  a full pipeline, because P2 is occupied by a younger µop for the whole of an
  older split load's second cache pass. The fix is the same and must be adopted
  from the late split: relocate the clear into the stage that owns `llReg`.

### 4.6 What is deleted

- The `StateMachine` (`:1220-1594`) and all 9 states.
- `busy` and `s1Valid` as global occupancy flags (`:1153`), and therefore the
  `issuePort.ready` expression at `:1158` in its current form.
- `reqStale`/`reqFresh`/`reqMatch` as a freshness heuristic (`:736-745`),
  replaced by stage ownership; the `reqExcOverride` term survives in a new form.
- `DcachePlugin`'s two live `xlate.rsp` reads (`:758`, `:770`), replaced by a
  fault bit on `DLoadCmd`.
- `poisoned` as a single sticky bit (`:1184-1189`), replaced by a per-stage
  poison bit that travels with the µop.

---

<a name="5"></a>
## 5. Hazard analysis (H1–H14)

Each row states the hazard, why it does not exist today, the guard, and the test
that must kill it. Rows marked **NEW** are hazards this design creates.

**H1 — store→load forwarding order (NEW exposure, existing guard).**
Today an older store allocates into the SQ at *its* `XLATE`, a younger load
queries at *its own* `XLATE` ≥ 1 cycle later, and single-occupancy makes that
trivially true. In the pipeline the guarantee comes from two facts that must
both be stated as **invariants**: (a) LS issue is oldest-first
(`IssueQueuePlugin.scala:315-337`, `ohLoldest & lsReady`), and (b) **`sq.io.alloc`
and `sq.io.fwd.query` are both at stage P3 and nowhere else.** Given (a) and (b),
an older store is always at a stage ≥ P3 when a younger load reaches P3, so its
entry is visible. Moving alloc to a later stage than the query stage would
silently reintroduce the "dropped store" race the IQ comment at `:315-334`
documents. *Test*: a directed store→load-same-address chain at every pipeline
distance 1..8, asserting a forward hit at each; plus the existing
`store -> forwarded load -> ALU consumer (end-to-end)` LS spec.

**H2 — SQ-full stall at P3 (existing, generalised).** A store held at P3 by
`sq.io.full` blocks everything behind it. Deadlock-free by the same argument as
today's `WAIT_SQ` (`:1570-1575`): the resident stores are strictly older, commit
in ROB order, and drain hold-until-ack. *Test*: a >8-store MOVEM burst followed
by a load, asserting forward progress.

**H3 — DTLB liveness through `DcachePlugin` (NEW, MAJOR — the Slice-1 finding).**
See §3.1. Without the fix: a hang (accept delayed past the translate stage's
tenure by `pendingStoreMiss`/`maintBusyReg` ⇒ `loadCmdPort.ready` stuck low with
the ROB head parked on that load) or a silent spurious vector-2 fault (a
stranger's `permFault` latched into `ldS1Fault`). *Guard*: carry the resolved
fault on `DLoadCmd`; delete both live reads. *Test*: must-fail-first — force a
`pendingStoreMiss` to delay a load's accept by ≥ 2 cycles while a younger µop
occupies the translate stage with a **faulting** VPN; assert the older load
completes normally.

**H4 — `xlate.req.supervisor` staleness (NEW, already found by the late split).**
`compFaultSup` (`:977`) and `suppressForLaterPrivCheck` (`:1523`) read the *live*
`xlate.req.supervisor` at P8. Once P1/P2 belong to a younger µop that is the
wrong value. *Guard*: capture into `p2XlateSup` and carry it to P8 (the late
split's `bkCtx.xlateSup`, adopt verbatim). *Test*: mutation-killed, per the
late-split plan.

**H5 — `llReg.bDone` clobber (NEW, already found by the late split).** §4.5.
*Guard*: relocate the clear to the `llReg`-owning stage. *Test*: mutation-killed.

**H6 — per-stage poison (NEW).** `poisoned` (`:1184-1189`) is one sticky bit for
one µop. With N in flight, a flush must poison **all** resident µops. Also adopt
the plan-found handoff form `pkPoison := poison || sqFlushSig` — `poisoned` is a
Reg and reads True only from the *next* cycle, so a same-cycle flush would
otherwise let a µop advance unpoisoned. A poisoned µop must still traverse every
stage (to keep in-order completion and to release the stage) but perform **no**
side effect: no `sq.io.alloc`, no `captureCompletion`, no `faultCompletion`.
*Test*: flush injected at each stage independently, asserting zero SQ allocs and
zero ROB completions for the squashed µops, and no wedge.

**H7 — the `deferCompletion` same-cycle flush gate (existing, must be preserved).**
`when(!sqFlushSig) { pendMem(pendPush) := e; … }` (`:1100-1104`) exists because
the separate rollback is emitted *before* the FSM's increment and silently
loses. In a pipeline the alloc site moves out of a `StateMachine` body into a
plain component statement, which **changes the elaboration order that comment
depends on**. The gate must be re-derived, not transcribed. *Test*: the existing
`bsr-loop-mispredict` / `tmp1_reuse_loop_then_crack*` hang cluster is the
regression signal; the pendMem↔SQ lock-step pairing invariant must get its own
assertion.

**H8 — exception-unit cache/TLB arbitration (existing, tightened).** The
exception sequencer overrides `dcache.loadCmd` and `reqDrv*` (`:1750-1802`)
while `excActive`. It is serializing, so the LS pipe must be **drained** before
it runs. Today that is implicit (single occupancy + the ROB's drain). With N
µops resident it must be explicit: a `pipeEmpty` signal (`!p1Valid && … && !p8Valid`)
that the exception path already effectively waits on via ROB drain, plus the
surviving `RegNext(reqExcOverride)` stale term (§4.3). *Test*: an exception
raised with 4+ LS µops resident, asserting the sequencer's own vector fetch gets
the right translation.

**H9 — untagged `DLoadRsp` in-order contract (existing, must not break).**
`loadRspPort` is a `Flow` with no id and no ready (`DcachePlugin.scala:60`); P8
attributes each response to the oldest resident cache access. Safe **only**
while the D-cache stays single-response-in-flight and misses close the accept
port (`:229`/`:758`, ready raised only in `IDLE`). Relaxing `inFlight` to
`ldS1Valid` (§3.2) keeps this: two loads can be in S1/S2 but responses still
leave in accept order, one per cycle. *Test*: back-to-back L1D hits at every
distance, asserting response-to-µop pairing.

**H10 — `dcIdleForMaint` (existing).** `:1224` lists every load-pipe stage as a
conjunct of "genuinely idle". Any change to `inFlight` must be mirrored there —
the S1a/S1b slice's own review made exactly this point. *Test*: the existing
DcacheSpec maintenance-vs-drain-miss deadlock regression.

**H11 — the static `sbX` trigger for RTR's CCR-restore load (PRE-EXISTING,
aggravated).** `IssueQueuePlugin.scala:413-419` states that ports 2/3/4 need no
`aluSlowHandoff`-style guard because their producers use dynamic wakeups —
"*The one known exception is an LS X-producer — RTR's CCR-restore — which still
records a STATIC `sbX` trigger; that is a pre-existing, separate gap of the same
class*". A static latency-1 trigger assumes the producer executes at a fixed
offset from select. **Deepening the LS pipe makes that assumption more wrong.**
This is not created here, but it is the one place where LS depth is *not*
latency-agnostic. *Action*: confirm by directed test whether an RTR CCR-restore
followed immediately by an X-reader is exposed; if so, convert it to the
existing `lsNzvcWakeup`-style dynamic path (small, mechanical) **before** the
depth changes. **Do not assume lock-step latency-agnosticism covers this.**

**H12 — split-access second pass (existing, generalised).** §4.5. A split µop
re-occupies P2 and P5/P8. The `xlateBArm`/`xlateBArmSwitched` freshness term
(`:713-725`) — whose off-by-one the Lever-A design doc got wrong and a directed
cycle-level test caught (it would have reintroduced the `d22a949`
split-not-translated bug for cross-**page** splits) — must be re-derived under
stage ownership. *Test*: the existing `LsEuCrossSpec` freshness waveform tests,
plus a cross-page split with a younger µop resident behind it.

**H13 — `WAIT_B` ignores `loadRsp.payload.fault` for a cross load's second half
(PRE-EXISTING gap).** Flagged by the Slice-1 review. Not created here; must not
be silently inherited as "by construction". *Action*: fix or document explicitly.

**H14 — `IDLE`'s defensive `otherwise` double-completion (PRE-EXISTING, found by
the Slice-1 review).** `:1314-1316` lacks the explicit `busy := False;
s1Valid := False` that the `leaAddr`/`xlateFault` arms have. Unreachable today
(every LS-cluster µop is LEA/load/store) but the whole `busy`-defaulting
invariant it relies on is deleted by this design. *Action*: the arm disappears
with the FSM; assert unreachability with a decode-level sweep instead.

---

<a name="6"></a>
## 6. FMax treatment and the mandatory census gate

### 6.1 The netlist as it stands (read-only, `synth/census_*.rpt`, 2026-08-09 08:05)

Baseline: **221.828 MHz**, WNS **−0.508 ns**, **4512** failing endpoints, TNS
−462.267 ns, **103,740 CLB LUTs (47.82%)**, 47,343 FF (10.91%), CLB occupancy
**67.81%**.

Failing-endpoint families (`synth/census_family.rpt`, byte-identical in
`synth/lscensus_family.rpt`):

| family | endpoints | worst | median |
|---|---|---|---|
| **DcachePlugin** | **1260** | −0.454 | −0.118 |
| RobPlugin | 1122 | −0.395 | −0.063 |
| IcachePlugin | 467 | −0.303 | −0.054 |
| RasPlugin | 364 | −0.238 | −0.079 |
| **LsEuPlugin** | **262** | −0.328 | −0.070 |
| IssueQueuePlugin | 172 | −0.349 | −0.100 |
| FetchAlignPlugin | 157 | **−0.508** (= global WNS) | −0.201 |

`DcachePlugin` + `LsEuPlugin` = **33.7% of all failing endpoints**, and the two
worst *startpoints* in `LsEuPlugin` are `s1Ctx_uop_size_reg` (235 endpoints) and
`s1Ctx_uop_eaDelta_reg` (155) — the #2/#3 failing startpoints in the entire
design.

`pb_dcache` occupancy (`synth/lscensus_pb_dcache_util.rpt`, and note
`synth/floorplan_dcache.xdc` adds cells by `NAME =~ *LsEuPlugin_logic*`, so
**every new LS EU flop lands inside this box unconditionally**):

| resource | assigned | total used | available | util |
|---|---|---|---|---|
| CLB LUTs | 23,957 | 29,358 | 43,680 | **67.21%** |
| CLB Registers | 9,684 | 16,124 | 87,360 | 18.46% |
| CARRY8 | 159 | 175 | 5,460 | 3.21% |

Flops are a non-issue (18.5%). **LUTs are the constraint** — the box is at
67.21% versus 47.82% device-wide.

### 6.2 Two measured facts that must govern every decision here

1. **The relief cap is 0.00 MHz** (late-split plan Task 1, gate G-L4): the worst
   slack outside `DcachePlugin`/`LsEuPlugin` is `FetchAlignPlugin` at −0.508 ns,
   which **is** the global WNS. Perfecting the entire D-side corridor moves WNS
   by exactly zero. **No task may gate success on MHz movement; any MHz
   "improvement" observed is placement noise.**

2. **A one-term, logic-*reducing* change in this exact cone cost −6.55 MHz and
   +4785 LUTs.** The `compValid` experiment (ledger, "REJECTED at the FMax
   gate"): removing one AND term from `issuePort.ready` produced a generated-
   Verilog diff of **exactly one line**, and measured WNS −0.508 → −0.645,
   FMax 221.83 → 215.28, TNS −462 → −1726 (3.7×), failing endpoints 4512 → 9136
   (2.0×), LUTs 103,740 → 108,525 (+4.61%). Mechanism: placement/optimization
   equilibrium, in the IQ select cone. `census_congestion.rpt` reports **no
   congestion window above level 5**, so congestion reports **cannot** predict a
   recurrence — only a paired post-route run can.

**§4.2 changes `issuePort.ready` structurally.** Not one term — its whole shape.
That is a far larger perturbation than the one that cost 6.55 MHz. This is
stated as the design's principal risk, not buried.

### 6.3 MANDATORY prerequisite gate — before any RTL

Extend `synth/census.tcl` (already parameterised by the late-split Task 1) with
probes for the **new** structures and run it **read-only on the existing
`synth/fullcore_routed.dcp`** — a 46-second checkpoint open, not a synthesis.
Four questions with pre-stated fail-actions:

- **G-P1** — do any failing paths *end* at `LsEuPlugin_logic_busy*`,
  `s1Valid*`, `compValid*`, or `*selPorts_3*`? Task 1 answered **0 / 0 / 0 / 0**
  for the current shape. Re-confirm on the then-current HEAD. *If non-zero: the
  §4.2 restructure is landing on a live endpoint — halt and re-scope.*
- **G-P2** — where do `s1Ctx_uop_size` (235) and `s1Ctx_uop_eaDelta` (155) end?
  Task 1's answer: 218 of 235 end at `RobPlugin faultAddrStore` via the front's
  own `captureFault` arc, and **all 155** of `eaDelta` end at the store-queue
  data lanes. **Both belong to stages this design keeps.** *Expectation stated up
  front: the restructure will NOT reduce this fanout; do not claim it will.*
- **G-P3** — `pb_dcache` LUT headroom. At 67.21%, a `compValid`-scale device-wide
  LUT blow-up (+4785) landing here pushes the box to ~78%. *Fail-action: if the
  first FMax gate shows > +2% LUTs, stop before the next slice.*
- **G-P4** — a `-through` probe on the proposed new per-stage valid nets, run
  against a **Slice-1-shaped** netlist once one exists, to check the stage-valid
  chain has not become a new fanout hub.

### 6.4 Gating discipline

Every slice ends with a **paired, back-to-back, uncontended** post-route run
(`vivado -mode batch -source synth/impl_FullCore.tcl`, xcku5p-ffvb676-2-e, 4.0 ns)
in `git worktree`-isolated trees, base re-measured (never quoted), with WNS /
FMax / TNS / failing endpoints / CLB LUTs / CLB registers / occupancy reported.
≥ 20 GB free, no concurrent Vivado, never alongside a corpus sweep
(`tools/fuzz/ported-sweep-parallel.sh` peaks at ~3 GB RSS **per shard**).
`ExecuteLockStepSpec` and `IpcBenchSpec` must be **serialized** — both compile a
DUT named `FullCoreDut` into `<worktree>/simWorkspace/FullCoreDut` and
SpinalHDL's `allocateWorkspace` de-duplicates only *within* a JVM.

---

<a name="7"></a>
## 7. Area

Per-stage context, sized from real field widths (`IqContext` = `RenamedUop` 359
+ `robId` 6 = **365 bits** — the late-split spec's own correction of an earlier
"200–300" guess):

| stage | context | flops |
|---|---|---|
| P1 | `p1Ctx` (365) + base/data/index (96) + poison | ~462 |
| P2 | pruned ctx + `va`/`addrB`/`paddr`/`paddrB` (128) + cmode/fault/sup/poison | ~200–560 |
| P3 | pruned ctx + paddr/B + storeData (32) + poison | ~150–470 |
| P4 | pruned ctx + `fwd*` (34) + poison | ~120–440 |
| P5 | `llReg` — **exists today**, 135 flops | 0 new |
| P8 | `bkCtx` — the late split's ~42-flop descriptor | ~42 (already in flight) |

**Unpruned: ~1400–1600 new flops. Pruned: ~600–800.** Pruning matters and is
tractable: `RenamedUop`'s 359 bits are dominated by six 32-bit `pc`/`nextPc`/
`imm`/`branchDisp`/`faultAddr`/`predTarget` fields, most of which the LS back
half never reads (the late-split spec proved this for the back stage). Each
pruned field needs a non-use proof, so **pruning is a slice of its own**, not a
free optimisation.

Flops are cheap here: `pb_dcache` registers are at 18.46%, and 1600 flops is
~1.8% of the box's register sites and +3.4% device-wide FF (47,343 → ~49,000).

**LUTs are the real risk and are not estimable from flop counts.** The
`compValid` measurement is the proof: **zero** added flops, **+4785 LUTs**. Any
LUT projection in this document would be dishonest. The number comes from the
gate, and the gate comes early and often.

---

<a name="8"></a>
## 8. Staging, and the relationship to the in-flight late split

### 8.1 Recommendation: **finish and land the late split first. Do not redirect it.**

Four grounded reasons, in order of weight:

1. **Slice 1 already is the first structural step of this design, and it is
   already verified.** `cc38cc8` split the LS EU into a front FSM owning
   `s1*`/`busy` and a back FSM owning `llReg`/the cache access, with
   `earlyFree = false` making it provably cycle-identical (IPC bit-identical on
   **54/54** rows — 9 kernels × 3 seeds × 2 memory models; lock-step fail list
   byte-identical). That is exactly the P5–P8 stage group of §4.1, with its
   `bkCtx` being exactly P8's context register. Discarding it discards a
   *verified* stage boundary.

2. **The late split is the cheapest possible test of this design's central
   unknown.** §6.2 says the region can lose 6.55 MHz and 4785 LUTs to a
   one-term change with no added flops, and that no static analysis predicts it.
   Slice 1's post-route gate — ~176 lines of RTL, `issuePort.ready` **textually
   unchanged**, one file — is a canary for whether restructuring this cone costs
   FMax. **That gate is still in flight.** Committing ~1000+ lines across three
   clusters before reading it would be exactly the mistake the `compValid`
   rejection was supposed to teach.

3. **The DTLB-liveness fix is a strict subset of this design's, and it is being
   solved now.** The Slice-1 review's blocker (H3) must be fixed before
   `earlyFree` flips; its fix (hold the registered DTLB request from the back
   stage, with a matching `RegNext(bkXlateOverride)` term in `reqStale`) is the
   *narrow* form of §3.1's decoupling. Landing it under the late split's
   already-written must-fail-first test plan is strictly cheaper than
   re-deriving it inside a larger diff.

4. **The late split delivers the single largest marginal II win in the whole
   roadmap.** Non-forwarded load II 9 → ~4–5 on `load-stream`, the kernel that
   is 35% of the suite's ideal-memory cycles and ~99% LS-EU-bound. Everything
   after it is smaller per unit of risk.

**The honest cost of this ordering** — stated because it is real: the full
pipeline **replaces the late split's front FSM entirely** (`IDLE`/`XLATE_B`/
`XLATE`/`RESOLVE`/`WAIT_SQ` become stages P1–P4). So Slice 2's four directed
front-side specs will need revisiting, and the `earlyFree` elaboration flag
becomes vestigial. Roughly **40% of the late split's implementation survives
verbatim** (the back stage, `bkCtx`, the poison handoff, the `comp*` arbiter,
the `llReg.bDone` relocation, the DTLB decoupling); the front half is rework.
That is a real but acceptable price for a verified structural checkpoint and a
measured FMax reading on the exact cone at issue.

### 8.2 Proposed sequence

| # | slice | scope | files | gate |
|---|---|---|---|---|
| **0** | *(in flight)* late split Slices 1–2 | front/back FSM, `earlyFree`, H3/H4/H5 fixes | `LsEuPlugin`, `DcachePlugin` (H3) | its own §8.4 thresholds |
| **1** | **Census + H11** | §6.3 read-only census on the then-current netlist; resolve the RTR/`sbX` static-trigger gap | `IssueQueuePlugin` (only if H11 is live) | G-P1..G-P4; **halt** on G-P1 |
| **2** | **Front pipelining, structurally inert** | replace the FSM with P1–P4 stages, but keep a 1-deep occupancy interlock so cycle counts are **bit-identical**. The Slice-1 trick, applied to the front. | `LsEuPlugin` | IPC **bit-identical** (mandatory); post-route pair |
| **3** | **Open the pipe → phase 1a (II 2 / 2 / 3)** | drop the interlock; per-stage poison (H6); H7 re-derivation; H12. **No `DcachePlugin` port change.** | `LsEuPlugin` | 8-seed × 2-model IPC; corpus; post-route pair |
| **4a** | **D-cache port: root-cause `183 != 202`** | reproduce the AXI-hazard failure under `inFlight = ldS1Valid`; root-cause it against the revision-1/2/3 history (`:386-433`) and the `evictAwDone` reset site (`:818-826`). **Investigation only, no RTL.** | — | **HALT** if the term proves structurally load-bearing rather than a fixable AXI-gating hole |
| **4b** | **D-cache port relaxation → phase 1b (II = 2 all classes)** | `inFlight = ldS1Valid` + the 4a fix; mirror in `dcIdleForMaint` (`:1224`); re-prove the AXI-hazard regression **and** the maintenance-vs-drain-miss deadlock regression | `DcachePlugin` | full post-route pair; corpus fail-name list; `DcacheSpec` name-identical |
| **5** | **Context pruning** | prove non-use, shrink P2–P4 contexts | `LsEuPlugin` | LUT/FF delta; no functional change |
| **6** | *(evaluate only)* **II = 1** | DTLB pipelining (§3.1) + tagged `DLoadRsp`/nack (§10.5) | `DtlbPlugin`, `DcachePlugin` | **do not implement without a fresh IPC re-measurement** — see §8.4 |

Slices 2, 3, 4b each end with a hard GO/NO-GO. Slice 2 is the direct analogue of
late-split Slice 1: a structural change with a *provable* zero behavioural delta,
whose only purpose is to price the FMax/LUT cost before any IPC is chased.
**Proof before payoff**, per this project's standing discipline.

**Slice 3 before slice 4 is deliberate, not incidental.** Ordering the LS-EU
restructure first means slice 3's IPC measurement *directly measures* how much
of the win the D-cache ceiling is withholding: at phase 1a the LS EU is no longer
the constraint, so the residual `load-stream` cycles are the D-cache port's
II = 3, quantified rather than assumed. That number is the honest business case
for slice 4 — and if slice 4a's HALT fires, slice 3 has still landed the
majority of the win and the initiative degrades gracefully instead of failing.
Slice 4a is an investigation gate with no RTL precisely because the region has
already defeated two independently-designed fixes (`:386-433`).

### 8.3 Projected IPC

Baselines re-measured on `39f2e49` (late-split Task 2, 8 seeds, both models):

| kernel | zero mean | l2:5:70 mean |
|---|---|---|
| **load-stream** | **3269.75** | **3418.62** |
| load/store | 1045.38 | 1522.38 |
| mixed | 560.38 | 1082.25 |
| call-return | 2344.50 | 2965.88 |
| **AGGREGATE** | **9268.00** | **12692.75** |

`load-stream` is 360 non-forwarded loads (6 per iteration × 60) at II 9 ⇒ 3240
predicted vs 3269 measured (0.9%). Its projection is **gated by §3.2**, and the
two phases must be reported separately:

| | `load-stream` II | `load-stream` cyc | `load/store` | `mixed` | ideal aggregate |
|---|---|---|---|---|---|
| baseline | 9 | 3270 | 1045 | 560 | **9268** |
| **phase 1a** (no D-cache change) | **3** (D-cache ceiling) | ~1080 | ~650 | ~430 | **~6500** = **+43%** |
| **phase 1b** (`inFlight = ldS1Valid`) | **2** | ~720 | ~620 | ~420 | **~6100** = **+52%** |

So the D-cache port relaxation is worth roughly **+6 percentage points** on the
ideal-memory aggregate on top of phase 1a — real, but **phase 1a carries ~85% of
the total win without touching `DcachePlugin`'s load port at all.** That ratio is
why slice 3 precedes slice 4 (§8.2) and why a slice-4a HALT is survivable.

Under realistic memory `load-stream` is only 4.9% memory-model-sensitive, so the
saving carries nearly 1:1: **12693 → ~9900 (phase 1a, +28%) → ~9500 (phase 1b,
+34%)**.

**Three honest caveats, all mandatory to carry into any report of these numbers:**

- **The aggregate is suite-composition-sensitive.** `load-stream` is 35% of the
  suite's ideal-memory cycles *and was added specifically to measure this lever*.
  A reader could reasonably call the aggregate self-serving. **Report per-kernel
  first, aggregate second**, and report the aggregate both with and without
  `load-stream`.
- **`call-return` is the risk kernel** (§4.4): latency-sensitive, II-insensitive,
  never back-pressured by the LS EU today. It must be reported separately and it
  must not regress more than 2%.
- **These are ideal-memory-dominated numbers.** A real workload with a real miss
  rate spends its LS time in refills, where II is irrelevant. The
  `IPC_MEM=l2:5:70` column is the honest one.

### 8.4 Why slice 6 (II = 1) is probably not worth doing

II 2 → 1 saves a further ~360 cycles on `load-stream` and ~100 elsewhere ≈
**+4–5% ideal aggregate**. The cost is a change to the DTLB response contract
(four consumers, MMU cluster) plus tagging `DLoadRsp` and demuxing responses
inside the #1 failing-endpoint family — the largest correctness surface in the
roadmap for the smallest marginal gain, on a design where the FMax relief
available is 0.00 MHz. **Recommendation: scope slice 6 as an evaluation only.**
It is also the only slice that literally satisfies "a new µop every cycle"; §4.3
is honest that phase 1 delivers a pipeline with one 2-cycle stage, and that the
2 cycles are imposed by `DtlbPlugin`, not by the LS EU.

---

<a name="9"></a>
## 9. Verification plan (binding)

### 9.1 Baselines — re-measure, never quote

On the exact HEAD each slice branches from, in an isolated worktree:
`ExecuteLockStepSpec` (expect 390/394; **compare names, never counts**), LS unit
specs `m68k040.ls.* + DcacheSpec` (**flaky band 6–8 failures** — the Task-2
baseline recorded 8 where an earlier run recorded 6–7; name-list comparison is
mandatory), ported corpus via `ported-sweep-parallel.sh 4` (891 tests, 63 fails),
`IpcBenchSpec` 8 seeds × {`zero`, `l2:5:70`}, and a post-route FMax/LUT pair.

Note the pre-existing LS-spec failure
`cross-line store after cross-line load drains BOTH slots` — the **same hazard
family as H5**. It fails at baseline; do not read a post-change failure there as
new without checking the baseline name list.

### 9.2 New directed tests

| test | covers | form |
|---|---|---|
| `LsPipelineOrderSpec` | H1 | store→load same address at every pipeline distance 1..8; assert forward hit at each; collect-then-assert |
| `LsPipelineXlateSpec` | H3, H4 | must-fail-first: delayed accept via `pendingStoreMiss`/`maintBusyReg` with a *faulting* younger VPN resident; and `compFaultSup` correctness |
| `LsPipelinePoisonSpec` | H6, H7 | flush injected at each stage independently; assert zero SQ allocs / zero ROB completions for squashed µops; assert the pendMem↔SQ pairing invariant |
| `LsPipelineSplitSpec` | H12, H5 | cross-line and cross-**page** splits with younger µops resident behind them; the `xlateBArm` freshness waveform |
| `LsPipelineExcSpec` | H8 | exception raised with 4+ LS µops resident |
| `DcacheLoadPortRateSpec` | H9, H10, §3.2 (slice 4b) | back-to-back L1D hits at II = 2, response-to-µop pairing in accept order; **the `AXI-hazard regression … offset=0` test that failed `183 != 202` under this exact configuration is the primary gate**, alongside the maintenance-vs-drain-miss deadlock regression (H10) |
| `LsRtrXWakeupSpec` | H11 | RTR CCR-restore immediately followed by an X-reader |

Every guard gets a **mutation** (deliberately reintroduce the bug in RTL,
confirm RED, revert, confirm GREEN) — a plain failing-test-first ordering is not
reachable for hazards that only exist once the pipe is open, exactly as the
late-split plan concluded. Every "exhaustive" sweep must **collect all
mismatches and assert once at the end**, never abort at the first — the standing
`PredecodeWordSpec` lesson.

### 9.3 Accept / reject thresholds, stated in advance

**Accept** requires all of: `load-stream` ≥ 40% better under **both** memory
models; aggregate ≥ +15% under `l2:5:70`; **no kernel worse than 2%** (read
against `load/store`'s known 4.4% seed spread and `call-return`'s latency
exposure); FMax ≥ **219 MHz**; CLB LUTs within **+1.5%**; ported corpus fail-name
list **identical**; lock-step fail-name list **identical**.

**Reject / revert** at: FMax < **217 MHz**, or LUTs > **+2.5%**, or any new
name in either fail list.

**No task may gate success on MHz movement** (§6.2 fact 1).

---

<a name="10"></a>
## 10. Alternatives considered

**10.1 Keep the late split and stop there.** Rejected against the directive: a
2-slot handoff is not a pipeline, buys nothing for stores or forwarded loads,
and leaves II at 4–6 for two of three µop classes. Retained as slice 0.

**10.2 Jump straight to the full pipeline and abandon the late split.**
Rejected — §8.1. Discards a verified structural checkpoint and forgoes the
cheapest available reading of this design's principal risk. The FMax canary is
worth more than the ~40% rework it costs.

**10.3 Widen the IQ→LS port to 2 µops/cycle.** Rejected, unchanged from the
late-split spec §9.6. It doubles the `selPorts_3` select cone — the net measured
at fanout 155 / 0.702 ns route, and the neighbourhood the `compValid` change
disturbed — for a benefit a 1-wide deep pipe already delivers. It would also
require a second SQ alloc port and break the `ohLoldest` in-order-issue argument
(H1).

**10.4 Early-out completion for stores / LEA / forwarded loads** (complete at
P3/P4 instead of draining to P8). Rejected as the *default* because it
reintroduces a multi-writer `comp*` arbiter, and — if the arbiter ever needs a
second port — a 6th ROB completion port and a second Int/NZVC/X PRF write-port
set, in the #2 failing family and the known PRF-port congestion epicentre.
**Held as the designated retreat if `call-return` regresses** (§4.4): a
store-only early-out with a strict "front yields to back" arbiter (the late
split already built exactly that arbiter) is a bounded, pre-authorised fallback.

**10.5 Tagged `DLoadRsp` / multi-outstanding D-side loads (II = 1).** Deferred,
not rejected — and distinct from slice 4b, which only removes the `|| ldS2Valid`
term. This is the only route *below* 2 cycles/load. The D-cache's own comment
(`:722-731`) says the structure would tolerate an S1/S2 overlap, and the MSHR
design's slice D2 already sketches response tagging — but that design marks D2
"E2E? **NO** — contingent on §11.Q3" because the SoC crossbar is one outstanding
read per master port, and §3.2 shows II = 1 additionally needs a nack/replay
path to avoid the "a load MISS has no handler" hang. Worth ≈ +4–5% (§8.4). Not
now.

**10.6 Crack split accesses into two µops at decode.** Rejected — §4.5. Changes
the µop stream and the whitebox commit join for a rare case, to remove a stall
that costs a handful of cycles.

**10.7 Use a library pipeline helper** (`m2sPipe` chain, `StageableP`,
NaxRiscv-style `CtrlLink`). Rejected on codebase-consistency grounds. There is
exactly one `m2sPipe` in the design and its comment records that the *default*
`collapsBubble = true` was **wrong** here and had to be disabled
(`IssueQueuePlugin.scala:437-447`). The LS/D-cache corridor uses zero library
primitives; every stage is a hand-rolled `RegInit(False)` valid with a `:= False`
default and an explicit hold. §4.2 follows that.

**10.8 Reclaim the `reqMatch` settle cycle by driving the VPN from S0.**
Rejected, unchanged: it recreates the bypassed-ALU → AGU-adder → `reqReg` arc
that FMax Lever A removed (3.193 ns / 56% of a −1.779 ns post-route path). §3.1's
DTLB pipelining is the *correct* way to remove that cycle, and it is deferred to
slice 6 rather than swapped for a known-bad arc.

---

<a name="11"></a>
## 11. Honest risk assessment

**The single biggest risk is FMax/LUT placement equilibrium, and the exposure is
asymmetric: there is provably zero upside.** G-L4 measured the relief available
from perfecting the *entire* D-side corridor at **0.00 MHz** — `FetchAlignPlugin`
holds the global WNS. Meanwhile the same corridor has already demonstrated a
**−6.55 MHz / +4785 LUT** loss from removing a *single AND term* with zero added
flops, via placement equilibrium that no congestion report can predict
(`census_congestion.rpt`: nothing above level 5). This design restructures
`issuePort.ready` entirely and adds 600–1600 flops into a pblock already at
67.21% LUT occupancy. **The lever can only lose on the metrics the user made
binding.** This is why slices 2/3 exist as inert, provable-zero-delta
checkpoints, and why §9.3's reject threshold is stated before any RTL.

**The biggest scope finding is that this is not an `LsEuPlugin.scala` project.**
All three throughput gatekeepers are partly or wholly outside it:

- the D-cache's live `xlate.rsp` reads (`DcachePlugin:758,770`) must be deleted
  for *any* pipelining — the Slice-1 review found this and it blocks the late
  split too;
- the DTLB's `hrMatch` value-compare imposes a hard 1-translation-per-2-cycles
  floor (`DtlbPlugin:405,417`) that no LS-EU change can lift;
- the D-cache load port itself caps at **II = 3 / 0.333 loads per cycle**
  (`DcachePlugin:732,758`), also entirely independent of the LS EU.

Phase 1 therefore delivers a genuine pipeline whose *one* 2-cycle stage is
bottlenecked by another cluster, and phase 2 (removing that) is judged not worth
its correctness surface (§8.4). **Anyone reading "a new µop every cycle" as the
deliverable should read §3.1, §3.2 and §4.3 first.**

**The second-biggest risk, and a genuinely nasty one, is that the D-cache
relaxation this design needs has already been tried and it corrupted data.**
Reverting `|| ldS2Valid` — exactly the II = 2 configuration of slice 4b — made
the `AXI-hazard regression … offset=0` test fail with a real lost dirty-victim
writeback (`183 != 202`,
`.superpowers/sdd/progress-fmax-slice2-dcache.md:55-59`). §3.2 eliminates the S2
data path and the load/miss-ordering axis as causes, leaving a cadence
interaction with the shared AXI4 write channels and the read-port arbiter — a
region that already defeated **two** independently-designed fixes before
revision 3 landed (`DcachePlugin:386-433`). This spec deliberately refuses to
guess the mechanism: slice 4a is an **investigation-only gate with an explicit
HALT**. The mitigation is structural, not optimistic — slice 3 (phase 1a) is
ordered first and carries ~85% of the win without touching that port at all, so
a HALT costs ~6 aggregate percentage points rather than the initiative.

Framing this correctly matters for a second reason: it is the same failure shape
as the DTLB-liveness finding that just bit the narrower late-split work — a
cross-file dependency the owning spec's own enumeration could not see. That has
now happened twice in this corridor. **Any future slice here should begin by
enumerating what reads the LS EU's shared resources from outside
`LsEuPlugin.scala`, not by enumerating `LsEuPlugin.scala`.**

**The correctness surface is wide but well-mapped.** Fourteen hazards, of which
five are genuinely new (H1's exposure, H3, H4, H5, H6), three are pre-existing
gaps this design aggravates or must not inherit silently (H11, H13, H14), and
the rest are existing invariants generalised. Two of the new ones (H4, H5) were
already found and are already being fixed by the in-flight late split — direct
evidence that the design-then-review discipline is finding this class before it
lands, and an argument for keeping that work rather than restarting.

**The IPC case is strong but suite-sensitive.** +23–28% realistic-memory
aggregate is a large number, and 35% of the suite's ideal-memory cycles come
from a kernel added expressly to measure this lever. Report per-kernel, report
the aggregate with and without `load-stream`, and treat `call-return` as the
adjudicator of whether uniform in-order completion (§4.4) needs the §10.4
retreat.

**Effort.** Realistically a multi-session effort: 8 slices, ~1000–1400 lines of
RTL across `LsEuPlugin.scala` (the bulk), `DcachePlugin.scala` (the H3 DTLB
decoupling + the slice-4b load-port relaxation) and — only if H11 is live —
`IssueQueuePlugin.scala`; seven new directed specs with mutation coverage; and
**four** paired post-route gates plus one investigation-only gate (4a). Slices
0–3 are the near-term work and carry ~85% of the win; slice 4 closes the
D-cache ceiling; slices 5–6 are optional.

**Not implemented. No RTL written. §6.3's census is a hard prerequisite for
slice 1, and slice 0 (the in-flight late split) must land its own FMax gate
before slice 3 is dispatched.**
