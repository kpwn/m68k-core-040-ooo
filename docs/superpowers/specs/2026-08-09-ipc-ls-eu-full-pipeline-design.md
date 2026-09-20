# IPC push: a genuine LS EU pipeline — replacing the one-µop-at-a-time FSM (design)

**2026-09-20 resident-latency experiment:** `docs/ls-hit-latency.md` specifies an
optional descriptor enqueue/send fall-through and guaranteed next-cycle integer
wakeup, retaining response allocation, all P4 permission checks and registered
data writeback. Both are disabled by default pending timing signoff; the
registered descriptor and wakeup paths described below remain the baseline.

**2026-09-20 memory-dependency amendment (implementation in progress):**
`docs/memory-dependencies.md` defines the replacement for NG1/NG3's strict LS
issue/completion ordering. It requires dispatch-time tracking, separate store
address/data readiness, physical-byte disambiguation and non-blocking retry.
The existing ordered path remains active until integration and its gates pass.
NG2's single LS issue port and NG4's in-order cache-response association remain.

**Socket VIPT configuration clarification (2026-09-20):** The socket already
uses the parallel DTLB/virtual-set-read path qualified by `DLoadProbeResolve`.
The former `earlyViptEnabled=false` setting disabled only physical-address hints
supplied at probe launch, not translated resolve or early-result consumption.
That parameter is now named `allowPretranslatedProbeHints`; the socket keeps it
false because the LSU does not have a translation at probe launch. The changed-VPN
integration regression exercises both settings and requires early consumption.
With warm nonidentity translations and alternating resident lines, both settings
measure eight cycles from LSU acceptance to completion, with one completion per
cycle: probe at +2, translated response at +3, cache command/early consume at +6,
completion at +8. This is a directed simulation measurement, not a board average;
renaming the hint control does not change the datapath or improve its latency.

**Status**: IMPLEMENTING. D-cache slices A/B/C, the D1 elastic LS front, and the
D2 four-entry tokenized VIPT-result queue are implemented and simulation-gated.
Accept-last P1/P2/P3/P4 stages, four aligned descriptors, and four early results
let warm same-page L1 hits issue, translate/probe, enqueue, command, and complete
at II=1. Only rare split accesses retain a serial replay FSM. A directed burst of
eight loads proves bubble-free turnover through every one of those boundaries,
including full-queue consume-and-replace. The pre-D2T seed-1 `load-stream`
checkpoint was 638 cycles under ideal memory and 769 under `l2:5:70`.  The final
tagged changed-VPN response boundary measures 667/821 cycles: 4.5%/6.8% slower
than that checkpoint, but still 79.6%/76.0% fewer cycles than the original
3270/3419 baseline while extending II=1 to changed resident VPNs. The routed
FMax/area gate is still pending because the shared Vivado window is occupied.

**2026-08-09 review amendment — binding corrections:**

1. The original L1D was VIPT *by address selection* but did not hide translation
   latency: it launched the virtual-set RAM only after `LsEuPlugin` had resolved
   and registered the physical address. Slice B now launches a tokenized
   `DLoadProbe{vaddr,token}` from the same registered boundary as the DTLB
   request. `DcachePlugin` holds the synchronous virtual-set result and later
   compares it with the physical tag from the matching resolved
   `DLoadCmd{vaddr,paddr,token}`. A directed real-DTLB test proves the two
   launches coincide and that the resolved command performs no redundant RAM
   read. Thus the implemented common path is latency-hiding VIPT.
2. The historical DTLB did not impose an unconditional one-translation-per-two-
   cycles limit: `hrMatch` could serve same-VPN traffic every cycle, but a changed
   VPN needed a registered-result retry. D2T replaces that live-value comparison
   with tagged elastic command/response Streams.  Resident changed-VPN commands
   now accept and respond at II=1; walks still backpressure the single walker.
3. The end-state target is II=1 for cacheable resident L1 hits, stores, and
   forwarded loads. Slice A reached II=2; the bounded replay added with slice C
   now permits one resolved all-hit load acceptance per cycle. A younger command
   accepted on the exact cycle an older S1 detects a miss is held in one replay
   slot and relaunched after refill, preserving untagged in-order responses.
   D1 now carries multiple aligned operations concurrently through P2/P3/P4.
   General hit-under-miss remains future work. Maintenance and page walks may
   remain serializing.
4. `DcachePlugin` no longer consumes the live translation response at
   cache-command acceptance. Translation faults terminate upstream and cancel
   the early probe; only a successfully resolved physical address, cache mode,
   and association token enter `DLoadCmd`. This removes the late-split
   liveness/fault-association blocker.
5. The late split was retained as a bounded checkpoint. Its structural Slice 1
   (`cc38cc8`) was ported onto the tokenized VIPT interface after item 4 removed
   the live-translation hazard; Slice 2 sets `earlyFree = true`. C3 replaces the
   single aligned-response slot with four in-order descriptors. Untagged cache
   responses retire against the oldest sent descriptor, while the split replay
   waits for the aligned ring to drain. D1 subsequently replaced the checkpoint's
   single-resident translate/resolve front with accept-last P1/P2/P3/P4 stages.
6. Faster consumers expose invalid producer cadence assumptions. Every Stream
   source must hold `valid` and its entire payload until `fire`; it may not rely
   on the consumer's former multi-cycle `ready` gap. D-cache II=1 exposed one
   such bug in the exception frame-load source (`RegNext(valid)` emitted a
   duplicate tail command). The exception source now uses a held command
   register, and the format-0 RTE test asserts exactly four load handshakes.
7. C2's first paired IPC evidence is positive. `load-stream` baseline→C2 cycles
   for seeds 1/2/3 are `3269/3275/3268`→`1836/1837/1835` under ideal memory and
   `3426/3428/3428`→`2071/1986/1989` under `l2:5:70`. On the current ten-kernel
   suite at seed 1, aggregate cycles move `12221`→`10658` (−12.8%) and
   `15659`→`14271` (−8.9%). This clears the predeclared IPC thresholds on the
   measured pair but is not final acceptance until the routed FMax/LUT gate.
8. D1 burst coverage found a remaining latency-mode bubble even though issue,
   translation, descriptor enqueue, and resolved L1 commands all run at II=1:
   four warm same-page hits completed on cycles `7,9,10,11`. The first command
   consumed the former one-entry early probe, while the following command fell back to
   the one-cycle-longer normal hit pipe. D2 therefore replaces the held full-line
   probe slot with four tokenized **extracted-result** entries. A shared two-stage
   probe-result pipe registers the selected 128-bit line once, then extracts only
   32 bits into the reserved entry. This keeps probe admission at II=1 without
   replicating 128-bit line storage per outstanding load. D2T launches every
   aligned resident probe unresolved and later qualifies that same synchronous
   read with `DLoadProbeResolve {token,paddr,cacheMode}` when the registered tagged
   translation returns. A late walk/miss resolution safely makes the probe
   unusable and the resolved command falls back to the ordinary read path.
9. D2 closes that bubble in the directed eight-load burst: issue, DTLB/VIPT
   launch, aligned-descriptor enqueue, resolved L1 command, and completion are
   each consecutive for all eight operations, and P2/P3/P4 are simultaneously
   occupied. The same test requires eight parallel VIPT launches, ordered ROB
   completion, and correct data. The seed-1 benchmark moves C3 `load-stream`
   `1829`→`638` cycles under ideal memory and `2030`→`769` under `l2:5:70`.
   Excluding `load-stream`, aggregate cycles are `8802` ideal and `12140` L2,
   respectively 20 and 60 cycles better than C3, so the gain is localized to the
   intended hot path without a hidden regression in the rest of the suite.  The
   later tagged D2T response boundary moves the focused seed-1 result to 667/821
   cycles while proving the same II=1 cadence across alternating resident VPNs;
   the full ten-kernel aggregate has not yet been rerun on that final combination.
10. A queued VIPT result is a data snapshot, not a coherence reservation. Any
   intervening D-cache array write to the same virtual set can make that snapshot
   stale before its resolved command arrives. Each of the four result entries is
   therefore marked ready-but-unusable when any real way write targets its set;
   same-cycle early consumption is gated by the same comparison. The token stays
   resident so the resolved command consumes it and falls back to the ordinary
   synchronous read without loss or reordering. This is a bounded four-entry by
   four-way set comparison, not another TLB/CAM or cache port, and unrelated-set
   stores do not disturb load cadence.
11. A completed memory-to-register MOVE derives NZVC from the returned value at
   the architectural access size: N is the sized sign bit, Z is the sized zero
   reduction, and V/C are zero. RTR remains the explicit exception and restores
   `result(3:0)`. Both forwarded completions and aligned/split descriptors use
   this rule; a descriptor must never substitute the result low nibble or a stale
   store-data-derived flag value.

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

**Relationship to the earlier late-split work**: the "LS EU late split"
(`docs/superpowers/specs/2026-08-09-ipc-ls-eu-pipeline-depth-design.md`, plan
`docs/superpowers/plans/2026-08-09-ipc-ls-eu-late-split-implementation-plan.md`)
was first explored on branch `feat/ls-eu-late-split` (Slice 1 at `cc38cc8`) and
is now integrated with early release. It is a measured checkpoint, not the
end-state architecture. §8 is the active staging recommendation.

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
- [8. Staging, and the relationship to the integrated late split](#8)
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

5. **The original D-cache load port had a hard II=3 ceiling independent of the
   LS EU.** That ceiling is removed: S1/S2 overlap first established II=2, and
   the bounded replay slot now establishes all-hit accept II=1 without adding a
   line buffer or multiple MSHRs. The historical `183 != 202` report was a test
   handshake error, not dirty-victim corruption; §3.2 records the reproduction.

---

<a name="1"></a>
## 1. Goals / Non-goals

### 1.1 Goals

- **G1** — Turn the LS EU into a genuine in-order pipeline: several LS µops
  resident in distinct stages simultaneously, with a new µop entering on
  (close to) every cycle, in place of today's
  `issuePort.ready := !busy && !s1Valid && !compValid` single-occupancy contract
  (`LsEuPlugin.scala:1158`).
- **G2** — Bring the initiation interval of **all three** common LS µop classes
  to 1: one store, forwarded load, or same-page L1D-hit load may enter each
  cycle when no real dependency or bounded-resource stall applies. (Measured
  baselines: LSU grounding entry, ledger; per-class II re-derived in §3.)
- **G3** — Accept **increased per-op latency** wherever it buys II, per the
  governing principle. The target end-state pipeline is 8–10 stages deep — i.e.
  *longer* than today's 9-cycle non-forwarded load latency, possibly by 1–2
  cycles — and that is the intended outcome, not a cost to be minimised.
- **G4** — Preserve, bit-for-bit, every architectural property the current FSM
  has: store→load forwarding (currently 100% effective, 25/25 RESOLVE hits in
  the `load/store` kernel), in-order LS memory ordering, precise exception
  delivery, wrong-path poisoning, split-access correctness, and the untagged,
  in-order `DLoadRsp` contract. Probe/command tokens are internal association
  metadata; they do not make responses out of order.
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
- **NG4** — General hit-under-miss, multiple MSHRs, or out-of-order D-side
  responses. A miss still blocks new cache commands once detected. The one
  younger command that can handshake on the same edge as miss detection is
  captured in a bounded replay register and relaunched after refill. Responses
  remain untagged and in acceptance order; response tagging/demux is deferred.
- **NG5** — Any change to `src/main/scala/m68k040/frontend/` or
  `src/main/scala/m68k040/decode/`. A separate fetch-directed-BTB effort owns
  those; zero interaction.
- **NG6** — Changing the StoreQueue's drain path or the copyback/precise-store
  machinery. The exception-unit cache arbiter remains serializing and unchanged;
  its already identity-physical frame/vector accesses bypass the tagged DTLB, so
  only the obsolete exception-translation wiring is removed.

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

### 3.1 Gatekeeper 1 — historical changed-VPN bubble, removed by tagged D2T

Before D2T, `DtlbPlugin.scala` served a **single, combinational** request/response
pair off one registered request. The hit path was served from a one-entry result
register by *value comparison*:

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

> **`hrMatch(N)` ⟺ cycle `N-1` produced a registered hit for the VPN presented
> on cycle `N`.**

A stream whose requests remained on the same VPN could therefore receive a valid
translation every cycle after warm-up. A changing VPN did not match the prior
registered result and needed a hold/retry cycle. (`permFault` was
additionally recomputed against the live `_req.write`/`_req.supervisor`, so
those access-class bits must be associated with the same pipeline token.)

The `!mmuEnable` identity arm (`:365-371`) and the DTT0/DTT1 transparent-
translation arm (`:372-379`) are combinational and need no settle — but the LS
EU imposes the same 2-cycle settle in *both* modes deliberately, via
`reqStale = reqReCaptured || xlateBArmSwitched` with
`reqReCaptured = RegNext(issuePort.fire || reqExcOverride)`
(`LsEuPlugin.scala:736-745`), so that "the +1-cycle alignment is identical in
both MMU modes" (`:670-672`).

**Historical decoupling blocker, now removed:** the D-cache used to read this
same live response at command acceptance:

```scala
loadCmdPort.ready := !inFlight && xlate.rsp.ready && !pendingStoreMiss && !maintBusyReg  // DcachePlugin:758
when(loadCmdPort.fire) { … ldS1Fault := xlate.rsp.fault … }                              // DcachePlugin:770
```

Those reads were only sound while the front held one `s1Valid` resident for the
entire cache transaction. They become cross-token hazards as soon as the
translate stage moves on to a younger µop. Slice B therefore removed
`DTranslationService` from `DcachePlugin` completely. A translation fault is
consumed by `LsEuPlugin` and cancels its early probe; only clean, registered
`{vaddr,paddr,cacheMode,token}` reaches the cache. AXI refill errors continue to
use `DLoadRsp.fault` and are distinct from MMU faults.

**Selected DTLB-II1 amendment (2026-08-09):** convert the D-side service into
two elastic Streams.  `DTranslationCmd` carries
`{vpn,write,supervisor,token}` and `DTranslationRsp` returns
`{ppn,cacheMode,fault,token}`.  The token is eight bits:
`{backendEpoch,splitPhase,robId[5:0]}`.  This is a D-side-only contract; the
independent I-side translation service retains its existing interface.

The DTLB classifies each accepted command at the existing banked lookup.  Its
geometry remains deliberately shallow: 32 total entries, four ways, two banks,
four sets per bank, and therefore only four tag comparisons in the selected
bank.  D2T must not increase the entry/way count, duplicate the lookup, or add a
fully associative response/context structure.  Any future depth change requires
separate miss-rate and post-route area/timing evidence.

An
identity, transparent-translation, or resident-TLB hit writes one elastic
response register; that response holds until consumed and may be consumed in
the same cycle the next lookup is accepted.  A miss captures the existing one
walker context and deasserts command ready until the walk produces the held
response.  Command readiness depends only on registered response/walker
capacity, never on the current CAM hit, so the deep `hitVec` cone still ends at
flops rather than feeding LSU/cache admission.

Each I/D walker also reserves capacity in its existing four-entry deferred U/M
write queue before launch.  A full queue may hold one captured cold miss, but it
must never overwrite an older architectural descriptor update.  Queue-full does
not feed resident-hit readiness or the TLB tag-compare cone.  On the D side,
`PFLUSHA` poisons an active pre-flush walk's later response, fill, and U/M
allocation, including an exact same-cycle `walker.done` collision.

The LSU adds one pruned translation-response context between today's P2 request
owner and P3 physical-address/SQ owner.  On an ordinary hit it consumes response
A into P3 while atomically launching P2 request B and replacing the response
context, yielding changed-VPN II=1.  The virtual-set L1D probe fires on the exact
accepted request cycle and retains the same ROB token.  The registered DTLB
response also emits a tokenized `DLoadProbeResolve {token,paddr,cacheMode}` Flow
on the aligned-load hit path.  That Flow qualifies the synchronous virtual-set
BRAM output against the physical tag before the cache stores the selected line;
it neither retains all raw ways nor performs a second array read.  A late resolve
safely records an unusable probe and falls back to the ordinary resolved command.
A miss holds only this
response context/P2 input; already-resolved P3/P4/cache descriptors continue.
Split accesses reuse the response context for their second translation and may
remain serial because they are explicitly off the aligned hot path.

A page-crossing split carries both halves' translation attributes independently.
`paddrA/cacheModeA` governs slot A and `paddrB/cacheModeB` governs slot B for
both load commands and StoreQueue drains.  A split store is precise when either
half is inhibited.  Applying A's cache mode to B is forbidden: adjacent virtual
pages may map to unrelated physical pages with unrelated cache attributes.

Flush kills the owned LSU request/response contexts and toggles the token epoch.
Any already-produced mismatched response is drained without side effects; a
speculative walk may still finish and fill the TLB, but its stale tagged response
cannot attach to a reused ROB id.  Translation faults and permission/cache-mode
metadata are consumed only with the matching context.  This changes the response
contract but does not add a second CAM, walker, PRF port, or cache port.

### 3.2 Gatekeeper 2 — D-cache load acceptance: original II=3, implemented all-hit II=1

This was explicitly brought into scope by the user. The following original
ceiling and failed-test history are retained because they explain the replay
guard and its regression coverage.

`DcachePlugin.scala:732`: `val inFlight = ldS1Valid || ldS2Valid`, consumed by
`:758`. Unrolled against `loadCmdPort.ready := False` (the default at `:229`,
raised **only** inside `fsm.IDLE`):

| cycle | `ldS1Valid` | `ldS2Valid` | `ready` |
|---|---|---|---|
| T | 0 | 0 | 1 → **accept** |
| T+1 | 1 | 0 | 0 |
| T+2 | 0 | 1 | 0 |
| T+3 | 0 | 0 | 1 → accept |

This was **II=3, or 0.333 loads/cycle sustained**, entirely independent of the
LS EU. Slice A changed the credit to `ldS1Valid`, allowing S1 to overlap the
prior S2 and establishing II=2.

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

**The follow-on was previously attempted and reported as a failure.** The
independent review reverted the extension in an isolated worktree — i.e. ran
exactly the `inFlight = ldS1Valid` / II=2 configuration — and recorded
(`.superpowers/sdd/progress-fmax-slice2-dcache.md:55-59`):

> *"proved it is load-bearing, not defensive: an existing regression test
> ("AXI-hazard regression … offset=0") then fails with real data corruption
> (`183 != 202`, a dirty victim's writeback lost)."*

The 2026-08-09 reproduction found that conclusion was caused by an invalid test
driver, not RTL corruption. The test asserted the eviction-triggering load's
`valid`, then combinationally observed the newly-earlier `ready` and deasserted
`valid` *before a sampling edge*. The request never fired. The reported 183 is
exactly the untouched deterministic preload byte; 202 is `0xCA`, the dirty data
that would only be written back if the missing load had actually caused an
eviction. Holding Stream `valid` through the `ready && valid` sampling edge
makes the original offset-0 case and the complete offset 0..4 sweep pass under
II=2. The corrected test also asserts the accept edge explicitly.

Two structural facts explain why the II=2 overlap is safe:

1. **It is not the S2 data path.** The same review's Minor finding #1
   established that `ldS2*` is *"a frozen snapshot with no live shared-resource
   read behind it"* — which is why `dcIdleForMaint`'s `!ldS2Valid` conjunct is
   invariant-hygiene, not corruption-prevention. So the term is not protecting
   the load's own registered data.

2. **Load/miss ordering is safe at II=2.** `loadCmdPort.ready` is raised only inside
   `IDLE`; a miss is detected at S1 (cycle T+1, `:777`) while the FSM is still
   in `IDLE`, and its `goto(EVICT_WR)`/`goto(REFILL)` takes effect at T+2. With
   `inFlight = ldS1Valid`, `ready` is already low at T+1 (S1 occupied) and low at
   T+2 (not in `IDLE`). **So no accept can ever land in the shadow of an
   unhandled miss** — precisely the hang the file already documents for the
   `pendingStoreMiss` case (`:744-750`: *"next cycle the FSM is servicing the
   store in REFILL and a load MISS has no handler … hanging the LS EU forever"*).
   The untagged in-order `DLoadRsp` contract survives too: two loads may be in
   S1/S2 but responses still leave in accept order, one per cycle.

The shared AXI4 write channels and read-port arbitration remain high-risk and
retain their complete regression sweep, but `ldS2Valid` was not protecting
them.

#### Implemented II=1 guard

II=1 creates exactly one new case: a younger command can handshake on the edge
where the older S1 first discovers a miss. Slice C captures that already-
accepted younger `DLoadCmd` in one replay register instead of launching its RAM
read. The blocking refill completes, then the command is replayed through the
normal path. This preserves untagged, in-order responses without a line buffer,
response tags, or multiple MSHRs. Directed tests prove three resident hits are
accepted and answered on consecutive cycles, and that the miss-shadow command
cannot answer ahead of the miss. General hit-under-miss remains out of scope.

The original one-entry early-probe metadata slot supported consume-and-replace,
but D1 increased probe→resolved-command distance enough that the slot could not
retain every consecutive result. D2 uses four tokenized result entries. A probe
reserves an entry and launches the virtual-set BRAM read; the following shared
stage registers the selected 128-bit way, and the next stage stores only the
size-extracted 32-bit value plus hit/usable metadata in that entry. Matching and
cancellation use the token. A matching proven-hit command consumes the extracted
result without another RAM read; a miss or unresolved translation consumes the
entry and takes the ordinary S1 path. The four-entry depth covers the fixed D1
probe-to-command distance and supports one enqueue plus one consume per cycle.

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
| Late split checkpoint | `LsEuPlugin` front/back split with the live-DTLB blocker fixed | store 4 / fwd load 6 / cache load ~4–5 |
| **D-cache slice A** | overlap S1 probe with prior S2 response; root-cause the historical `183 != 202` failure | cache-hit II=2 |
| **D-cache slice B** | DTLB + virtual-set read launched in parallel; typed token associates early and resolved requests | translation latency hidden on a usable early probe |
| **D-cache slice C0** | one miss-shadow command register and in-order replay | all-hit accept II=1; blocking miss remains ordered |
| **Full LS pipeline** | elastic LS stages feeding slices B/C0; in-order completion ownership | **1 common-case**, stalls only on real dependencies/resources |

**Slices A–C followed by the elastic LS stages are the recommendation, staged
and separately gated.**

Slice A is intentionally small and independently gateable, but II=2 is not the
destination. Slices B/C make the cache and translation path truly pipelined;
the LS-stage rewrite then exposes that capacity to real instruction streams.

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
| **P1** | S0→S1 reg | latch `{ctx, base, data, index}`; compute `va`; launch DTLB request **and** L1D virtual-set probe in parallel | `p1Ctx`, `p1Va`, `p1Data`, `p1Poison`, token |
| **P2** | DTLB/VIPT request | atomically fire tagged DTLB command and L1D virtual-set probe; accept-last replacement | request context, VA/addrB, token |
| **P2T** | tagged translation response | match held DTLB response by `{epoch,phase,robId}`; capture PA/cache mode or terminate a fault; response A may leave while request B launches | pruned execution context, token, split-A PA/cache mode |
| **P2B** | `XLATE_B` (`:1332-1352`) | split-access second-half translate | *reuses P2's registers* — see §4.5 |
| **P3** | `XLATE` (`:1357-1422`) | STORE: `sq.io.alloc` (+ `fastStore`/`deferCompletion`). LOAD: `sq.io.fwd` query → latch `fwd*` | `p3Ctx`, `p3Paddr/B`, `p3Cmode`, `p3StoreData`, `p3Poison` |
| **P4** | `RESOLVE` (`:1432-1463`) | LOAD: `fwdHit` → data, else capture `llReg` | `p4Ctx`, `p4FwdHit/Data/Stall`, `p4Poison` |
| **P5** | cache result/replay admission | accept the already-probed L1D result or occupy the bounded miss-shadow replay register; enqueue its completion descriptor | resolved command + replay state, descriptor FIFO tail |
| **P6/P7** | D-cache compare/extract pipeline | physically tagged way compare and registered extraction; one result per cycle | existing in-order D-cache stage registers |
| **P8** | `WAIT`/`WAIT_A`/`WAIT_B` + `comp*` (`:1484-1568`, `:762-827`) | consume `loadRsp`; mark the matching in-order descriptor ready; merge cross halves; retire the oldest ready descriptor into the single `comp*` stage | completion descriptor FIFO head, `lineA`, `aDone` |

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
| DTLB command/response backpressure | P2/P2T | page walk, response slot held, or downstream P3 unavailable | rare on resident hits; walk/miss dependent |
| `sq.io.full` | P3 | today's `WAIT_SQ` | rare |
| `fwdStall` re-query loop | P4 | overlap with an older uncommitted store | workload-dependent |
| `dcache.loadCmd` not ready | P5 | `inFlight`, refill in progress, `pendingStoreMiss`, `maintBusyReg` | miss-rate-dependent |
| completion descriptor FIFO full | P5 | bounded response/completion association capacity | only when the front outruns completion |
| oldest completion descriptor not ready | P8 | hit latency / refill; younger ready entries remain queued | every cache load at the head |
| split-access second pass | P2B / P8 | §4.5 | ~1 in N accesses |

### 4.3 P1 translation/cache drive at II = 1

P1 launches one token per cycle. The same token drives the DTLB request and the
virtual set index. For a resident hit stream, tagged DTLB responses and
synchronous L1D outputs remain available every cycle after warm-up even as the
VPN changes. A page walk may stop translation admission, but already
resolved cache hits and independent non-memory LS results continue wherever
their owned resources permit.

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

This requires an explicit **bounded in-order completion-descriptor FIFO**. The
previous wording's single `p8Ctx` is insufficient: a cache hit has multiple
cycles of response latency, so one waiting P8 context would back-pressure P5
and make II=1 impossible. Every P5 admission allocates one FIFO entry in issue
order. Non-cache results enter ready; an aligned cache load enters not-ready.
Accepted cache loads also advance a cache-response pointer, and each untagged
`DLoadRsp` marks exactly that oldest outstanding cache-load entry ready. P8
retires only a ready FIFO head. Thus several cache hits may be resident and
responses may arrive on consecutive cycles, while all completion and PRF/SQ
side effects remain in LS issue order. Queue full, not ordinary hit latency, is
the common-path backpressure condition.

The queue depth is initially **four**: enough to cover the current registered
launch plus D-cache S1/S2 response distance and the one-entry miss shadow,
without multiplying the 365-bit full `IqContext`. Entries carry only the proven
completion descriptor (the current `BkCtx` fields plus result/fault readiness).
A directed test must fill all four entries with consecutive aligned hits and
observe one completion per cycle after warm-up. If the measured cache latency
or mutation test proves four insufficient, increase only from measured need.

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
- `DcachePlugin`'s two live `xlate.rsp` reads (historically `:758`, `:770`).
  They are removed; translation faults terminate and cancel upstream, while a
  clean resolved `DLoadCmd` carries registered VA/PA/cache mode plus its token.
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

**H3 — DTLB liveness through `DcachePlugin` (found by Slice 1; now closed).**
See §3.1. Without the fix: a hang (accept delayed past the translate stage's
tenure by `pendingStoreMiss`/`maintBusyReg`) or a silent spurious vector-2 fault
from a younger translation. *Implemented guard*: `DcachePlugin` has no
`DTranslationService` dependency. Faulting translations terminate in the LS
front and cancel their tokenized probe; only clean, registered
`{vaddr,paddr,cacheMode,token}` commands enter the cache. AXI refill faults
remain on `DLoadRsp.fault`. *Tests*: real-DTLB parallel launch/early consume,
SQ-forward cancellation, translation-fault/cross regressions, and a delayed
older miss overlapped by a younger store.

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

**H8 — exception-unit cache arbitration (existing, tightened).** The exception
sequencer overrides `dcache.loadCmd`/store while `excActive`. Its frame and vector
accesses are identity-physical by the existing exception design and do not enter
the tagged DTLB. It is serializing, so the LS pipe must be **drained** before it
runs. Today that is implicit (single occupancy + the ROB's drain). With N µops
resident it must be explicit: a `pipeEmpty` signal (`!p1Valid && … && !p8Valid`)
that the exception path already effectively waits on via ROB drain. The exception
load source must remain a held Stream source so a faster cache cannot accept a
duplicate tail command. *Test*: an exception raised with 4+ LS µops resident,
asserting no younger LS side effects and exact vector/frame cache handshakes.

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

**H13 — split-load cache faults (PRE-EXISTING gap; fixed in C3).** The old
`WAIT_B` ignored `loadRsp.payload.fault` for the second half, and the same audit
showed `WAIT_A` would capture a faulted first-half line and continue to slot B.
C3 terminates on either fault: slot A reports `vaddr`, slot B reports `addrB`,
both with `atc=false`, and neither produces a register result. The completion
collision guard includes a faulting `WAIT_A` because the front may already hold
a younger µop. *Test*: physical bus errors injected independently on each half.

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
## 8. Staging, and the relationship to the integrated late split

### 8.1 Current checkpoint: **late split integrated; continue toward elastic stages**

Four grounded reasons, in order of weight:

1. **Slice 1 is the first structural step of this design and is integrated.**
   `cc38cc8` split the LS EU into a front FSM owning
   `s1*`/`busy` and a back FSM owning `llReg`/the cache access, with
   `earlyFree = false` making it provably cycle-identical (IPC bit-identical on
   **54/54** rows — 9 kernels × 3 seeds × 2 memory models; lock-step fail list
   byte-identical). That is exactly the P5–P8 stage group of §4.1, with its
   `bkCtx` being exactly P8's context register.

2. **Slice 2 now exercises the intended reuse.** `earlyFree = true` releases
   S1 after a non-forwarded-load handoff. A deterministic delayed-refill test
   proves a younger store reaches SQ allocation while the older back load is
   still pending, then proves the older untagged response remains correctly
   attributed. §6.2 still warns that the region can lose 6.55 MHz and 4785 LUTs to a
   one-term change with no added flops, and that no static analysis predicts it.
   The post-route gate remains mandatory before this checkpoint is accepted.

3. **The former DTLB-liveness blocker is closed at the interface.** The cache
   consumes a resolved physical command and association token, never the live
   translation response. This is stronger and simpler than keeping a back-stage
   DTLB override alive.

4. **The next step remains the elastic front pipeline.** The checkpoint permits
   two LS operations in flight but the front still admits only one resident µop,
   and a second non-forwarded load stalls at `RESOLVE` while `bkBusy`. The final
   target remains same-page all-hit II=1, not permanent acceptance of II≈4–5.

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
| **A — implemented** | **D-cache S1/S2 overlap** | `inFlight = ldS1Valid`; repair the pre-edge-valid test bug; prove two hits in flight and accept II=2 | `DcachePlugin`, `DcacheSpec` | cache suite; AXI hazard offsets; maintenance regressions; `test-fast` |
| **B — implemented** | **Parallel VIPT probe** | launch DTLB request and virtual-set RAM read from the same registered boundary/token; remove live `xlate.rsp` reads at cache accept; cancel on forward/fault/squash | services, `LsEuPlugin`, `DcachePlugin`, DTLB tests | real-DTLB coincident launch; early-read consume; SQ-forward cancel; fault/cross regressions; post-route pair pending |
| **C0 — implemented** | **Bounded miss-shadow replay** | accept all-hit commands at II=1; capture the one younger command accepted on older-S1 miss detection and replay it after refill | `DcachePlugin`, service token | sustained hit II=1; in-order miss replay; cache regressions; area/FMax pending |
| **C2 — implemented checkpoint** | **LS front/back early free** | capture complete `bkCtx`/`llReg` at `RESOLVE`, keep one untagged cache back slot, release the front for one younger translate/resolve op | `LsEuPlugin`, LS test | delayed cold miss overlap; completion collision/cross/poison/RTE/cache regressions; `load-stream` −43.9%/−41.2% cycles (3 seeds ideal/L2); seed-1 aggregate −12.8%/−8.9%; area/FMax pending |
| **C3 — implemented checkpoint** | **Aligned-load descriptor queue** | replace the aligned hot-path `BK_IDLE/LAUNCH/WAIT` single slot with a four-entry in-order command/response descriptor queue; add hit-only early-probe consume-and-replace; retain `WAIT_A/WAIT_B` only as a pipe-draining split-access replay; close split-half bus-fault handling | `LsEuPlugin`, `DcachePlugin`, LS/cache tests | focused cache/LS/RTE suite 105/105; full/backpressure/order/flush and both split-half bus faults pass; phase-local `test-fast` 133/134 with only the independently reproduced stale predecode oracle, subsequently corrected; seed-1 `load-stream` 1836→1829 ideal and 2071→2030 L2; area/FMax pending |
| **C1 — optional** | **General hit-under-miss** | response tags + bounded miss state sufficient to keep accepting independent hits during refill | cache/service/LS files | hit-under-miss; queue-full backpressure; area/FMax |
| **D1 — implemented, simulation-gated** | **Elastic LS front** | replace the single-resident `IDLE/XLATE_B/XLATE/RESOLVE/WAIT_SQ` control with accept-last P1 AGU, P2 registered DTLB/VIPT, P3 registered physical-address/SQ-query, and P4 registered forward/resolve stages; each cut owns a pruned context and valid bit | `LsEuPlugin`, LS tests | eight same-page aligned loads advance issue→P4 and complete at II=1; changed-page/walk and queue-full backpressure; store→load order; older precise replay priority; flush; split regressions; seed-1 IPC pair passes |
| **D2 — implemented, simulation-gated** | **Tokenized VIPT result queue** | replace the one held RAM result with four tokenized extracted-result entries fed by one shared selected-line/extract pipeline; qualify the synchronous read with the later `DLoadProbeResolve`; fall back safely when unresolved or miss | cache service, `LsEuPlugin`, `DcachePlugin`, tests | eight warm same-page hits launch probes and complete consecutively; full-queue consume-and-replace, cancel-all, unresolved/miss fallback; D-cache 49/49, focused LS 24/24, RTE 1/1; phase-local `test-fast` 133/134, followed by combined-branch 138/138 after the stale oracle corrections; area/FMax pending |
| **D2T — implemented, simulation-gated** | **Tagged DTLB response pipeline** | elastic D-side command/response Streams; eight-bit epoch/phase/ROB token; one pruned LSU response-context stage; same-cycle response-A/request-B turnover; retain the existing 32-entry/4-way/2-bank TLB and one walker | cache translation types, DTLB/identity plugins, `LsEuPlugin`, MMU/LS tests | eight alternating nonidentity resident VPNs prove consecutive request/response/probe/cache/early-consume/completion with exact tags/data, zero walker reads, and a tagged nonresident fault; permission/miss/split/flush regressions and `test-fast`; post-route pair pending |
| **D3** | **Uniform completion FIFO** | extend C3's cache association ring into the uniform in-order completion FIFO of §4.4 if D1's remaining early-completion arbitration or latency-sensitive kernels justify it | `LsEuPlugin` | IPC suite; lock-step/corpus; post-route pair |
| **E** | **Context pruning** | prove non-use, shrink carried tokens | LS/cache files | LUT/FF delta; no functional change |

Every slice ends with a hard correctness and `test-fast` gate; B–D additionally
need paired post-route evidence when the PM-serialized implementation window is
available. A removed a disproven blocker; B and C0 establish real common-path
cache capacity, and C2 opens bounded LS overlap before D makes admission elastic.
C1 is not a prerequisite for
the bounded, in-order elastic LS pipeline and should be justified separately
against area and workload miss behavior.

For D1, P2 itself is the registered DTLB request boundary: `{valid,vaddr,vpn,
write,supervisor,robId}` and its pruned execution context advance atomically.
The D-cache `DLoadProbe` is driven from those same P2 flops in the same cycle as
the DTLB lookup, retaining latency-hiding VIPT without a live AGU→TLB/cache cone.
A different VPN may hold P2 until the DTLB's registered hit result catches up or
the walk completes; a same-page hit stream may consume and replace P2 every
cycle. Split accesses hold P2 only for their second translation, then continue
through the ordinary P3/P4 cuts and serialize solely at the existing split
replay handoff. Flush invalidates every unlaunched front stage in one edge;
already-launched cache descriptors remain poisoned and drain as specified for
C3.

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
predicted vs 3269 measured (0.9%). The cache-side II=3 ceiling has now been
removed. C2 also removes the older cache tail from the front's occupancy. C3
replaces the untagged back-load slot with four ordered descriptors, but still
provides only one younger front slot. D1/D2 now provide the measured II=1 row:

| | `load-stream` II | `load-stream` cyc | `load/store` | `mixed` | ideal aggregate |
|---|---|---|---|---|---|
| baseline | 9 | 3270 | 1045 | 560 | **9268** |
| historical D-cache II=3 projection | **3** | ~1080 | ~650 | ~430 | **~6500** = **+43%** |
| slice-A cache II=2 projection | **2** | ~720 | ~620 | ~420 | **~6100** = **+52%** |
| C2 measured, seed 1 (current 10-kernel suite) | bounded front/back | **1836** | **1034** | **559** | **10658 vs 12221 baseline = −12.8% cycles** |
| C3 measured, seed 1 (current 10-kernel suite) | four aligned descriptors | **1829** | **1034** | **559** | **10651 ideal; 14230 L2 vs C2 14271** |
| D1/D2 pre-D2T measured, seed 1 | **1, same-page** | **638 ideal / 769 L2** | **1034 / 1515** | **550 / 1074** | **9440 ideal / 12909 L2** |
| D2T final focused pair, seed 1 | **1, changed-VPN proven** | **667 ideal / 821 L2** | not rerun | not rerun | full ten-kernel aggregate not rerun |

The D-cache is no longer the common-hit acceptance bottleneck. C2 made the first
material IPC gain by overlapping the cache tail, C3 removed the aligned
response-slot serialization, and D1/D2 now feed and retire resident hits at the
cache's all-hit II=1 limit. At the pre-D2T checkpoint, relative to C3, seed-1
`load-stream` cycles improved 65.1% ideal and 62.1% L2 and the full aggregate
improved 11.4% and 9.3%. The final tagged-D2T focused pair retains 63.5%/59.6%
cycle reduction versus C3 and 79.6%/76.0% versus the original seed-1 baseline.
Realistic-memory refill time still limits the benefit, so both memory models
remain mandatory. General hit-under-miss remains a distinct lever; resident
changed-VPN translation and VIPT consumption are already proven at II=1.

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

### 8.4 Why general hit-under-miss remains optional

Common all-hit acceptance is already II=1 without response tagging. General
hit-under-miss would add response association/demux and more miss state inside
the #1 failing-endpoint family. Its benefit depends on real miss behavior and
available independent work, whereas its area/FMax and correctness surface are
unconditional. Implement it only after the elastic LS pipeline is measured and
shows refill blocking to be the next material bottleneck.

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

The former `cross-line store after cross-line load drains BOTH slots` failure is
fixed and is now an active regression, not a baseline exception. Any recurrence
is a real failure in the H5 hazard family. The randomized generator also carries
an explicit cross-line load/store/load template; do not quarantine it merely to
make a sweep green.

### 9.2 New directed tests

| test | covers | form |
|---|---|---|
| `LsPipelineOrderSpec` | H1 | store→load same address at every pipeline distance 1..8; assert forward hit at each; hold a same-set VIPT snapshot across a store array write and require ordinary-read fallback to updated data; collect-then-assert |
| `LsPipelineXlateSpec` | H3, H4 | must-fail-first: delayed accept via `pendingStoreMiss`/`maintBusyReg` with a *faulting* younger VPN resident; and `compFaultSup` correctness |
| `LsPipelinePoisonSpec` | H6, H7 | flush injected at each stage independently; assert zero SQ allocs / zero ROB completions for squashed µops; assert the pendMem↔SQ pairing invariant |
| `LsPipelineSplitSpec` | H12, H5 | cross-line and cross-**page** splits with younger µops resident behind them; the `xlateBArm` freshness waveform |
| `LsPipelineExcSpec` | H8 | exception raised with 4+ LS µops resident |
| D-cache rate/replay tests in `DcacheSpec` | H9, H10, §3.2 | three resident hits accepted/responded at II=1; younger command accepted on an older S1 miss is replayed after refill and cannot respond out of order; retain the complete AXI-hazard offset sweep and maintenance-vs-drain-miss regression |
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

**10.5 Tagged `DLoadRsp` / general hit-under-miss.** Deferred, not rejected.
All-hit acceptance already reaches II=1 through the one-entry miss-shadow replay
described in §3.2, while responses remain untagged and in order. Tags, demux,
and additional miss state are only required to let independent hits bypass an
active refill. The SoC crossbar remains one outstanding read per master port,
so this must be justified by post-elastic-pipeline workload measurements rather
than by the common-hit initiation interval.

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

- the D-cache's former live `xlate.rsp` reads had to be deleted for any
  pipelining; slice B has now removed the dependency entirely;
- the DTLB's old registered `hrMatch` path supported same-VPN II=1; D2T now adds
  tagged response turnover for changing-VPN II=1 without another CAM;
- the original D-cache load port cap of **II=3 / 0.333 loads per cycle** was
  independent of the LS EU; slices A/C0 now provide all-hit acceptance II=1.

The earlier `183 != 202` blocker is now understood: the load never handshook
because the test dropped `valid` before an edge when `ready` became earlier.
The corrected handshake and all five AXI-hazard offsets pass with S1/S2 overlap.
This does not reduce the severity of the real AXI and array-port hazards already
covered by those tests; it removes a false reason to preserve II=3.

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
found by the late-split review and are now implemented in C2 — direct evidence
that the design-then-review discipline found this class before early release
was enabled.

**The IPC case is strong but suite-sensitive.** +23–28% realistic-memory
aggregate is a large number, and 35% of the suite's ideal-memory cycles come
from a kernel added expressly to measure this lever. Report per-kernel, report
the aggregate with and without `load-stream`, and treat `call-return` as the
adjudicator of whether uniform in-order completion (§4.4) needs the §10.4
retreat.

**Implementation checkpoint.** D-cache S1/S2 overlap, all-hit II=1 acceptance,
one-entry in-order miss-shadow replay, live-DTLB decoupling, and a four-entry
tokenized parallel virtual-set result queue are implemented. The elastic LS front
releases cache loads into a four-entry aligned descriptor queue; untagged responses
remain associated in order, and only split accesses use `WAIT_A/WAIT_B`. Directed
simulation covers eight consecutive warm hits and coincident DTLB/VIPT launches,
full-queue consume-and-replace, miss replay ordering, exact token association,
unresolved-probe fallback, cancel-all, flush poisoning, and both split-half bus
faults. Tagged D2T coverage additionally alternates resident VPNs at II=1 and
checks permission association, clean-miss serialization, epoch reuse, and real
cross-page split translations. Post-integration coverage also requires a queued
probe crossing an older same-line store to fall back, and requires
memory-to-register MOVE flags to come from the sized returned value. The phase-local `test-fast`
result was 133/134 with only the independently reproduced stale
`PredecodeRefSpec` EOR/CMPM expectation; after that oracle and the subsequently
exposed line-0 framing drift were corrected, the combined branch passes 138/138.
Both seed-1 IPC memory models pass and quantify the common-path gain. Remaining
gates are broader multi-seed IPC and paired routed FMax/LUT measurements. General
hit-under-miss remains a separate future lever; resident changed-VPN DTLB and
VIPT turnover are now II=1.
