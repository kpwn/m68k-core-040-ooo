# IPC push: a genuine LS EU pipeline — replacing the one-µop-at-a-time FSM (design)

**Status**: IMPLEMENTING. D-cache slices A, B, and the bounded replay part of C
are implemented and simulation-gated as described below. The elastic LS-stage
rewrite is still pending. The routed FMax/area gate has not yet been run.

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
2. The DTLB does not impose an unconditional one-translation-per-two-cycles
   physical limit. `hrMatch` can serve a same-VPN stream every cycle after
   warm-up. A changed VPN needs the registered hit-result gap; a walk needs
   backpressure. The current LS `reqStale` policy nevertheless inserts a settle
   cycle for every new resident µop. That policy must be replaced by tagged
   pipeline validity, not elevated into an architectural floor.
3. The end-state target is II=1 for cacheable same-page L1 hits, stores, and
   forwarded loads. Slice A reached II=2; the bounded replay added with slice C
   now permits one resolved all-hit load acceptance per cycle. A younger command
   accepted on the exact cycle an older S1 detects a miss is held in one replay
   slot and relaunched after refill, preserving untagged in-order responses.
   General hit-under-miss remains future work. Maintenance and page walks may
   remain serializing.
4. `DcachePlugin` no longer consumes the live translation response at
   cache-command acceptance. Translation faults terminate upstream and cancel
   the early probe; only a successfully resolved physical address, cache mode,
   and association token enter `DLoadCmd`. This removes the late-split
   liveness/fault-association blocker.
5. The late-split branch is a canary, not a prerequisite. Slice 1 exists only on
   `feat/ls-eu-late-split` at `cc38cc8`; its FMax attempt did not finish and its
   `earlyFree` flip is blocked. No task may execute the original one-line Task 6
   until the live-translation hazard is removed.
6. Faster consumers expose invalid producer cadence assumptions. Every Stream
   source must hold `valid` and its entire payload until `fire`; it may not rely
   on the consumer's former multi-cycle `ready` gap. D-cache II=1 exposed one
   such bug in the exception frame-load source (`RegNext(valid)` emitted a
   duplicate tail command). The exception source now uses a held command
   register, and the format-0 RTE test asserts exactly four load handshakes.

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
was explored on branch `feat/ls-eu-late-split` (Slice 1 at `cc38cc8`). It is a
reference/canary, not the end-state architecture. §8 is the active staging
recommendation.

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

### 3.1 Gatekeeper 1 — the DTLB: same-page II=1 is possible; changing-page requests need replay today

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

> **`hrMatch(N)` ⟺ cycle `N-1` produced a registered hit for the VPN presented
> on cycle `N`.**

A stream whose requests remain on the same VPN can therefore receive a valid
translation every cycle after warm-up. A changing VPN does not match the prior
registered result and currently needs a hold/retry cycle. (`permFault` is
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

**Cost to make all hit patterns II=1:** convert `hr*` into a genuine tagged
pipeline — register `{vpn, write, supervisor, token}` alongside the lookup
result and answer with pipeline-valid/nack rather than requiring the next live
request to retain the same VPN. This changes the response contract, but it is
the intended end state. The first implementation may exploit same-page II=1
while replaying a changed-page request; it must not impose a blanket two-cycle
occupancy counter.

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
| Late split canary | `LsEuPlugin` front/back split with the live-DTLB blocker fixed | store 4 / fwd load 6 / cache load ~4–5 |
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
| **P2** | translated/tag-result alignment | align `xlate.rsp` with synchronous tag/data outputs; translation faults cancel upstream; changed-page or walk responses hold/retry | `p2Ctx`, `p2Va`, `p2Paddr`, `p2Cmode`, `p2Poison`, token |
| **P2B** | `XLATE_B` (`:1332-1352`) | split-access second-half translate | *reuses P2's registers* — see §4.5 |
| **P3** | `XLATE` (`:1357-1422`) | STORE: `sq.io.alloc` (+ `fastStore`/`deferCompletion`). LOAD: `sq.io.fwd` query → latch `fwd*` | `p3Ctx`, `p3Paddr/B`, `p3Cmode`, `p3StoreData`, `p3Poison` |
| **P4** | `RESOLVE` (`:1432-1463`) | LOAD: `fwdHit` → data, else capture `llReg` | `p4Ctx`, `p4FwdHit/Data/Stall`, `p4Poison` |
| **P5** | cache result/replay admission | accept the already-probed L1D result or occupy the bounded miss-shadow replay register | resolved command + replay state |
| **P6/P7** | D-cache compare/extract pipeline | physically tagged way compare and registered extraction; one result per cycle | existing in-order D-cache stage registers |
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

### 4.3 P1 translation/cache drive at II = 1

P1 launches one token per cycle. The same token drives the DTLB request and the
virtual set index. For a same-page hit stream, the DTLB registered hit result
and synchronous L1D outputs remain available every cycle after warm-up. A VPN
transition that cannot match receives a retry/nack and is held or replayed;
younger admission is governed by bounded token capacity rather than a global
two-cycle counter. A page walk may stop translation admission, but already
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
| **A — implemented** | **D-cache S1/S2 overlap** | `inFlight = ldS1Valid`; repair the pre-edge-valid test bug; prove two hits in flight and accept II=2 | `DcachePlugin`, `DcacheSpec` | cache suite; AXI hazard offsets; maintenance regressions; `test-fast` |
| **B — implemented** | **Parallel VIPT probe** | launch DTLB request and virtual-set RAM read from the same registered boundary/token; remove live `xlate.rsp` reads at cache accept; cancel on forward/fault/squash | services, `LsEuPlugin`, `DcachePlugin`, DTLB tests | real-DTLB coincident launch; early-read consume; SQ-forward cancel; fault/cross regressions; post-route pair pending |
| **C0 — implemented** | **Bounded miss-shadow replay** | accept all-hit commands at II=1; capture the one younger command accepted on older-S1 miss detection and replay it after refill | `DcachePlugin`, service token | sustained hit II=1; in-order miss replay; cache regressions; area/FMax pending |
| **C1 — optional** | **General hit-under-miss** | response tags + bounded miss state sufficient to keep accepting independent hits during refill | cache/service/LS files | hit-under-miss; queue-full backpressure; area/FMax |
| **D** | **Elastic LS stages** | replace front/back one-at-a-time state with owned valid/context stages; uniform completion reservation | `LsEuPlugin` | IPC suite; lock-step/corpus; post-route pair |
| **E** | **Context pruning** | prove non-use, shrink carried tokens | LS/cache files | LUT/FF delta; no functional change |

Every slice ends with a hard correctness and `test-fast` gate; B–D additionally
need paired post-route evidence when the PM-serialized implementation window is
available. A removed a disproven blocker; B and C0 establish real common-path
cache capacity before D opens the LS issue path. C1 is not a prerequisite for
the blocking, in-order elastic LS pipeline and should be justified separately
against area and workload miss behavior.

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
removed, but the LS one-at-a-time FSM still hides that capacity. Therefore the
following remain analytical projections until slice D opens LS admission:

| | `load-stream` II | `load-stream` cyc | `load/store` | `mixed` | ideal aggregate |
|---|---|---|---|---|---|
| baseline | 9 | 3270 | 1045 | 560 | **9268** |
| historical D-cache II=3 projection | **3** | ~1080 | ~650 | ~430 | **~6500** = **+43%** |
| slice-A cache II=2 projection | **2** | ~720 | ~620 | ~420 | **~6100** = **+52%** |
| implemented cache capacity + elastic LS target | **1** | ~360 plus fill | to measure | to measure | **must be benchmarked** |

The D-cache is no longer the common-hit acceptance bottleneck. Do not claim an
IPC win from slices A–C0 alone: with the current LS FSM, requests still arrive
far below the cache's new capacity. The gain becomes measurable only when the
elastic LS stages can present consecutive loads. Under the realistic memory
model, refill time still limits the benefit, so both memory models remain
mandatory.

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
- the DTLB's registered `hrMatch` path supports same-VPN II=1 but needs tagged
  replay/pipelining for changing-VPN II=1;
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
already found and are already being fixed by the in-flight late split — direct
evidence that the design-then-review discipline is finding this class before it
lands, and an argument for keeping that work rather than restarting.

**The IPC case is strong but suite-sensitive.** +23–28% realistic-memory
aggregate is a large number, and 35% of the suite's ideal-memory cycles come
from a kernel added expressly to measure this lever. Report per-kernel, report
the aggregate with and without `load-stream`, and treat `call-return` as the
adjudicator of whether uniform in-order completion (§4.4) needs the §10.4
retreat.

**Implementation checkpoint.** D-cache S1/S2 overlap, all-hit II=1 acceptance,
one-entry in-order miss-shadow replay, live-DTLB decoupling, and the parallel
virtual-set probe are implemented. Directed simulation covers consecutive hits,
miss replay ordering, real-DTLB coincident launch, early-result consumption,
and probe cancellation on SQ forwarding. The remaining architectural work is
the elastic LS-stage rewrite, followed by IPC and paired routed FMax/LUT gates.
