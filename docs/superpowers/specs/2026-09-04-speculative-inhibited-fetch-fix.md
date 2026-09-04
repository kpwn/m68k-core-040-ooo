# Speculative instruction fetch into cache-inhibited (device) space — the fix

Date: 2026-09-04
Branch: `fix/ispec-inhibited-fetch` (worktree; **not merged**)
Base: `ca4b901` on `fmax-closure-fanout`
Commits: `d615b8f` (the fix), `dac18f1` (the committed synth-gate launcher)

Companion / prerequisite reading: `docs/superpowers/specs/2026-09-04-speculative-inhibited-mmio-audit.md`
(the audit that located the defect and built the failing test), carried into this branch
unchanged.

---

## 1. The defect

MC68040 UM §3.1.2/§4: a cache-inhibited page denotes a **device**. A device read has an
architecturally visible side effect — read-to-clear status, FIFO pop, interrupt
acknowledge — so it may not be performed speculatively, because a squash cannot un-do it
at the device. `LsEuPlugin.scala:2182-2229` states that rule in-source and enforces it for
D-side loads (`p4LaunchOk`'s inhibited arm waits for the ROB head). **Nothing enforced it
on the I side.**

The mechanism, at `ca4b901`:

- `IcachePlugin.scala:780` put `s0Cacheable` **inside the hit expression**
  (`s1HitVec = s0Cacheable && validsQ(w) && (tagQ(w) === s0Ppn)`), so an INHIBITED fetch
  was forced to *miss*, and a miss is forced to *fill*.
- Cache mode only reappeared at `IcachePlugin.scala:2507`, `doAllocate = missCacheable &&
  !mshrPoison(installIdx)`, which runs in the predecode dwell — **after both R beats have
  already returned**. INHIBITED suppressed the *array install*, not the *bus transaction*.
- `IcachePlugin.scala:2316-2317` hardwired `len=1 / size=5` — a 64-byte INCR burst — with
  no I-side `MmioCover` equivalent (the D side has one).
- Fetch is unconditionally speculative; a mispredict only marks the *response* stale
  (`FetchAlignPlugin.ringStale`), it does not prevent the request.

One 64-byte burst spans four Quadra 53C96 registers, including the **read-to-clear
Interrupt Status at +0x50**.

The audit's `spec-mmio I-side THE RULE` test failed on unmodified RTL with exactly one
such wrong-path burst at `0x50000000`.

---

## 2. Design decisions

### 2.1 Where cacheability becomes known — and where the gate goes

Cacheability is available **combinationally at the accept cycle**:
`IcachePlugin.scala:257-258`, `lookupCacheable = xlate.rsp.cacheMode =/= INHIBITED`, the
same live ITLB response that already feeds the `s0Cacheable` capture. There is no
latency problem to solve; the defect was purely that the value was *consulted* one stage
too late.

The gate is therefore placed on **`cmdPort.ready`** — the Stream accept boundary — rather
than on the S1 miss dispatch:

```scala
val inhibitedSpecBlock = !lookupCacheable && !nonSpecFetch
cmdPort.ready := xlate.rsp.ready && !setBlocked && !s1Unresolved && pfAcceptOk &&
                 !inhibitedSpecBlock
```

**Why not an S1 hold, which is what the D-side analogy suggests.** The plugin already has
a hold (`when(s1Unresolved) { ... otherwise { s0Valid := True } }`), and reusing it was
the first design considered. It does not work, and the reason is a property of
FetchAlign rather than of the I-cache: **a command that has fired owes a response
forever.** `FetchAlignPlugin` retires ring slots on responses and, on a redirect, only
marks them `ringStale` (`FetchAlignPlugin.scala:246`, `:608`) — it never abandons an
outstanding request. A held wrong-path inhibited fetch would therefore have to be either

- **(a) launched anyway** once the machine drained. But blocking the frontend is exactly
  what *causes* the machine to drain, so this converts "speculative device read" into
  "speculative device read, a few hundred cycles later" — no fix at all; or
- **(b) abandoned with a synthesised response.** That is silent instruction-byte
  corruption the instant the staleness reasoning behind it is wrong, and `FetchRsp`
  carries no tag (FetchAlign attributes by ring head), so nothing downstream would catch
  it.

Refusing the command at the Stream boundary has neither problem. Nothing has fired, no
response is owed, and a redirect simply changes `cmdWindowPc` underneath the un-accepted
command — `ic.cmd.payload.pc` is re-evaluated combinationally every cycle
(`FetchAlignPlugin.scala:585-602`) and `ic.cmd.valid` does not depend on `ic.cmd.ready`
(explicitly noted at `FetchAlignPlugin.scala:556`), so there is no combinational loop and
no new stale-request path. The design already tolerates an arbitrarily long low `ready`:
`setBlocked` and `!s1Unresolved` both do it today.

### 2.2 What the right behaviour is: hold, not fault

Two candidate behaviours were on the table.

**Suppress and fault** — refuse the fetch outright and raise a fault if the architectural
path really enters device space — is what ARMv8 does (instruction fetch from Device memory
is CONSTRAINED UNPREDICTABLE and most implementations fault) and is defensible in the
abstract. It was **rejected** here: the MC68040 *permits* executing from a cache-inhibited
page (real 68040 hardware performs non-burst fetches from CI space), this core's whole
purpose is to be a drop-in for one, and a fault would convert a legal-if-pathological
program into a crash. `IcacheSpec`'s inhibited-fetch test executes exactly such a fetch,
and turning that into a fault would have been a silent compatibility break dressed up as a
correctness fix.

**Hold until non-speculative** is what was implemented, and it is the direct analogue of
`p4LaunchOk`. An architectural fetch from a device page still happens; it is merely
serialised.

The cost is that instruction execution *from* a device page becomes very slow — roughly
one fetch window per full machine drain. That is the correct trade: it is the same
throughput characteristic the D side already accepts for inhibited loads, and code that
executes from device space is pathological by construction.

### 2.3 "At the ROB head", re-expressed for something with no ROB entry

An instruction fetch cannot wait to be the ROB head, because it is what *creates* ROB
entries. `top/SpeculativeFetchGate.scala` computes the equivalent predicate:

> **the whole machine, from the aligner to the ROB, holds nothing.**

The argument that this is *sufficient* is by elimination over redirect sources. `fetchPc`
can only turn out wrong-path if something redirects it, and every redirect source in this
core is an instruction older than the fetch:

| source | held in | term |
|---|---|---|
| fetch-time BTB/RAS prediction | an opword the aligner can already frame | `!res.slot0Valid`, `!predictPending` |
| FTQ / directed-fetch retarget | the fetch-target queue, the held target | `ftqCount === 0`, `!targetHoldValid`, `!ftqMismatchPending` |
| decode-side resume | `DecodeStage.ucComplexResumeValidReg` | in `decodeQuiet` |
| Tier-1 branch reschedule (`BranchEuPlugin`) | a live ROB entry | `!earlyPend`, `!earlyFire`, `!earlySuppressFe` |
| retire-time mispredict / exception | a live ROB entry | `count === 0`, `!flushing` |

If none of those holds anything, every instruction ever fetched has retired, so `fetchPc`
is by elimination the architectural successor of the last retired instruction.

The full predicate spans four plugins (`FetchAlignPlugin`, `DecodeStage`, `RenameStage`,
`RobPlugin`) and is wired once, from `SpeculativeFetchGate.wire(host)`, called from each
full-core wiring plugin next to the existing `lsEu.robHeadValidIn` line. Two non-obvious
terms are load-bearing:

- **`RenameStage.uopsStaged`** (`RenameStage.scala:470`) is a real 1-deep register between
  decode and ROB allocation, so `rob.count` lags `du.uops.fire` by at least one cycle.
  Without this term there is a window in which a fully renamed *branch* exists with an
  **empty ROB** — i.e. the predicate would say "architectural" about a fetch a
  not-yet-allocated branch is about to invalidate.
- **`MicroOpQueue`** is read as `queue.io.pop.valid` (`MicroOpQueue.scala:109`, exactly
  `count =/= 0`). Its `count` register is not a port and must not be reached into.

Two terms were deliberately **left out**, and both would have been deadlocks:

- **`ibuf.io.cnt === 0`.** A 68k instruction can straddle two fetch windows, leaving words
  resident that cannot yet form a uop. Requiring an empty IBuf would deadlock exactly
  there — the fetch needed to complete the instruction is the one being blocked.
  `!res.slot0Valid` is the correct term: it says the aligner cannot currently frame an
  instruction, which is simultaneously "more words are genuinely needed" and "nothing
  resident can redirect".
- **`pendingDrop === 0`.** `pendingDrop` is a leading-word drop intent consumed *by* the
  next fetch firing, so requiring it to be zero would deadlock any inhibited fetch that
  follows a redirect to an odd-word address.

**Shape.** The wide reduction is registered; only `ringCount === 0` stays live:

```scala
val drainedQ = RegNext(frontendQuiet && decodeQuiet && renameQuiet && robQuiet) init False
ic.logic.nonSpecFetch := drainedQ && (fl.ringCount === 0)
```

Registering the rest is safe because on any cycle following a fully drained one, the only
thing that can have re-filled the machine is a fetch response — which requires
`ringCount =/= 0` on the drained cycle, a contradiction. `ringCount === 0` must stay live
or the cycle immediately after an inhibited fetch is accepted would still see the stale
registered `True` and let a *second*, genuinely speculative run-ahead fetch through.

### 2.4 Default `True`, and why that is not a weakening

`nonSpecFetch` is declared `allowOverride` with `:= True`. Nine standalone I-cache DUTs
(`IcacheSpec`, `IcachePrefetchSpec`, `IcacheOrderOracleSpec`, …) drive `cmdPort` from a
directed probe rather than from a speculating frontend, so for them the default is the
*truth*, not an escape hatch — and they need no wiring change at all. A default of `False`
would instead **hang** every standalone test that fetches an inhibited line. The six DUTs
that do contain a frontend (`FullCoreSynth`, `SocketTop`, `ExecuteLockStepSpec.FullCoreDut`,
`FuzzDut`, `IpcBenchSpec`, and `SocketTop`'s reuse of `BackendWiringPlugin`) all override
it. `GenSynthVerilog` does not, and does not need to: it uses `IdentityTranslationPlugin`,
which never reports INHIBITED.

### 2.5 Burst width — deliberately unchanged

An AXI4 read carries **no byte enables**, so every byte a read *covers* is a byte the device
sees read; `MmioCover.scala`'s doc comment states this is why a downstream fix for reads is
impossible in principle, and why the D side derives an exact byte cover for INHIBITED
accesses. An earlier revision of this branch mirrored that here, narrowing an inhibited
demand fill to a single 32-byte beat at the half-line base. **That was removed on purpose.**

The reason is that the two sides do not have the same problem. A D-side load names a
specific 1/2/4-byte operand, so "exact" is well defined and the cover is the right answer.
An instruction fetch names no operand: it is a stream, `FetchRsp` delivers a fixed 64-bit
window plus a predecode, and `classifyBeat` needs the three words *following* each word it
classifies. Fetching less than a whole beat leaves those lookahead words undefined *inside*
the beat, where — unlike the beat-END case, which `classify` already refuses to guess at,
marking `ambiguousLine` for `Aligner.scala:63-65` to redo — nothing detects it, and the
result is silent instruction mis-framing.

More decisive than the mechanics: **the core cannot tell MMIO from any other cache-inhibited
memory.** All it has is the page attribute. And no real program executes from MMIO. So a
*non-speculative* inhibited fetch is a pathological case that does not need to be made safe:
once speculation is gated, any inhibited fetch that still reaches the bus was architecturally
demanded by the program, and the burst is then the program's problem rather than the core's.

Equivalently, and worth stating in one line because it is the load-bearing judgement: **the
I-cache does not honour INHIBITED as a *caching* policy, and that is intended. It honours it
as a *speculation* policy, which is the property that actually protects devices.**

So an inhibited demand fill is still an ordinary 2 × 32-byte INCR burst at the 64-byte line
base, identical to a cacheable one, and `IcacheSpec` now asserts that shape explicitly so
that "unchanged" is pinned rather than merely unmentioned.

### 2.5b Compliance against the invariant, per requester

The rule is: **no AR leaves the core for a cache-inhibited address on a path that may be
squashed.** Every requester that can drive an AR is enumerated with a verdict.

| Requester | Complies? | Evidence |
|---|---|---|
| **I-side demand fetch** | **YES (this change)** | `inhibitedSpecBlock` refuses the command at `cmdPort.ready` using the LIVE `xlate.rsp.cacheMode`, i.e. cacheability is resolved before anything is accepted, so no MSHR is allocated and no AR can be armed. Measured: `spec-mmio I-side THE RULE` 0 window ARs, and the revert control puts them back. |
| **I-side prefetcher, steady state** | YES | Frontier seeded only from a resolved, non-faulting, **cacheable** demand (`IcachePlugin.scala:1457-1467`) and clamped to that demand line's own 4 KiB physical page (`:1294-1295`); rule P1, "an INHIBITED page is never prefetched". |
| **I-side prefetcher, "cycle T" residual** | **narrowed, not closed** | See below. |
| **D-side loads** | YES | `LsEuPlugin.scala:2182-2229` `p4LaunchOk`; audited exhaustively on every path to `DcachePlugin.scala:1877`, and measured by the audit's `spec-mmio D-side` probe with a positive control. |
| **D-side stores / SQ drain** | YES | Non-speculative by construction: drains at the SQ head with `committed(head)` (`StoreQueue.scala:426`); inhibited stores never allocate. |
| **Exception-unit loads** | YES | `excLoadCmdValid => excActive` (`LsEuPlugin.scala:2881`); the exception is already committing. |
| **MMU table walker** | out of scope — see §2.6 | Walk addresses land in page-table memory, not at a device. |

**The prefetcher's cycle-T residual.** `IcachePlugin.scala:2153-2167` documents, and
`IcachePrefetchSpec`'s "P1 residual (cycle T)" test *proves and bounds*, a one-cycle window
in which an accepted command whose live verdict is FAULT **or INHIBITED** can allocate at
most ONE speculative line — inside `pfDemandLine`'s own 4 KiB page — and read it over AXI.
Because the window is seeded only from a cacheable demand, reaching a genuinely inhibited
line requires a live same-page cacheability transition (an ATC flush or descriptor rewrite
landing between the seed and the fetch).

This change **narrows** that residual's INHIBITED arm without closing it: post-fix, an
inhibited command can only be accepted at all when `nonSpecFetch` holds, so cycle T now
additionally requires a fully drained machine with a stale-but-valid frontier in the same
page. **I did not prove it unreachable, and I am not claiming it is.** Closing it properly
runs into the three rejected closures the existing comment enumerates (all of which either
put the live translation verdict back into the allocator enable cone — the exact arc M4
exists to delete — or kill the prefetcher outright), so it is a real scoping decision and
belongs in its own change.

### 2.6 The MMU table walker — raised, considered, ruled out

The walker has the *shape* of a violation and it is worth recording why that shape does not
matter, so the next reader does not re-derive it. `TableWalker.issueRead()`
(`TableWalker.scala:107-118`) has no cacheability term and no input carrying one; its only
`CacheMode` (`rCmode`, `:66`/`:209`/`:249`) is the mode extracted from the leaf descriptor
for the *translated* page, not for the addresses the walker itself reads. It launches from a
fully speculative point (`LsEuPlugin.scala:2522`, no ROB-head gate; `DtlbPlugin.scala:322`
omits `umFlush`; `ItlbPlugin.scala:251` has no flush term), it has no abort port (`:40-47`),
and it reads a fixed 16-byte line-aligned block.

**None of that is a route to a side-effecting device.** A walk address is the root pointer
(`SRP`/`URP`) plus VA bits, or a descriptor's next-level pointer — it therefore lands in
**page-table memory**, which is normal cacheable memory. Speculating about *which* walk to
start does not make the walk touch a device. The resting assumption, stated explicitly
because it is the thing being relied on: **the page tables are well-formed**, i.e. every
descriptor's next-level pointer addresses real memory. That is the OS's responsibility, and
a core cannot defend against malformed tables in any case.

Ruled out of scope by the project owner. Not investigated further, and no verdict is offered
beyond the above.

---

## 3. The test that pinned the defect

`IcacheSpec.scala:819` read, in full:

```scala
assert(arAfterInhibited == arAfterWarm + 1, s"inhibited fetch must refill once: …")
```

and nothing else. **That assertion encoded the defect.** It asserted only that a bus
transaction *happened*, and was silent on the question that decides whether that transaction
is legitimate for a page which by definition names a device: **was the fetch architectural,
or a wrong-path run-ahead a redirect is about to squash?**

It could not have asked, because until this change the I side had no answer to give.
`s0Cacheable` was folded into the HIT expression, so INHIBITED forced a miss and therefore
forced a fill, and cache mode did not reappear until `doAllocate` in the predecode dwell —
after both R beats had already returned. INHIBITED suppressed the array install and nothing
else.

It was **not** quietly edited. Three things now stand where one did:

1. **The count assertion is kept**, and is now explicitly the *positive control*: a fetch
   this DUT makes with `nonSpec = True` really is architectural (`cmdIn` is a directed
   probe), so it really must reach the bus.
2. **The burst shape is asserted unchanged** — `len=1`, `size=5`, at the 64-byte line base —
   with a same-run check that ordinary cacheable fills have that shape too. This is what
   pins §2.5's decision, so "unchanged" is a stated property rather than an omission.
3. **A negative control is added**, which is the new invariant itself: the same inhibited
   fetch, with `nonSpec` poked false, must not even be accepted at `cmdPort.ready` and must
   issue **no AR at all** over 200 cycles; then releasing the gate — and changing nothing
   else — must launch exactly that one held fill and return correct data. Without the second
   half, the first would pass just as happily if the DUT had simply wedged.

Making (3) possible needed a poke point, so the spec's `Dut` gained a small
`SpecGateProbePlugin` that drives `nonSpecFetch` from a `RegInit(True)` — the same
poke-a-register pattern the file already uses for `prefetchEnable`, and for the same stated
reason. The default is `True`, so every other test in the file is unaffected.

The full-machine half of the property — that a *genuinely* wrong-path fetch produces no AR —
is pinned by the audit's `spec-mmio I-side` suite, which needs a speculating frontend and
therefore cannot live in this standalone DUT.

---

## 4. Verification

All runs in this session, on this host, against the pristine baseline worktree `wt-ibase`
(detached `ca4b901`) for arithmetic reconciliation.

### 4.1 The sibling's I-side test: failing before, passing after

Reused verbatim from `investigate/spec-mmio-readclear` (no edits), so its control-negative
and control-positive stay meaningful.

| test | `ca4b901` (audit's measurement) | with the fix |
|---|---|---|
| `spec-mmio I-side CONTROL-NEGATIVE` | PASS, 0 window ARs | **PASS**, 7→6 total ARs, 0 window ARs |
| `spec-mmio I-side CONTROL-POSITIVE` (window CACHEABLE) | PASS, **5** window ARs | **PASS**, **5** window ARs — `0x50000000, 40, 80, c0, 100` |
| `spec-mmio I-side THE RULE` (window INHIBITED) | **FAIL**, 1 wrong-path burst at `0x50000000` | **PASS**, **0** window ARs |
| `spec-mmio D-side THE RULE` | PASS | **PASS** (unchanged; D side was already clean) |

The control-positive is the load-bearing one: it is unchanged at 5 ARs, so the frontend
*still* runs ahead across the boundary and the detector *still* sees it. The RULE test's
pass is therefore a real negative, not a suppressed frontend.

### 4.2 Revert control — the fix proven by removing it

Worktree `wt-ictrl`, branch `measure/ispec-netname-control` (`ba30f8a`), rebased onto the
final fix commit. `SpeculativeFetchGate`'s last line becomes `ic.logic.nonSpecFetch := True`
and nothing else changes, so `inhibitedSpecBlock` is constant-false and the added
`cmdPort.ready` term constant-folds away. Run on the SAME build as the numbers above:

```
[spec-mmio-ctl-neg] total I-side ARs=7, ARs in 0x50000000..0x50FFFFFF=0        PASS
[spec-mmio-ctl-pos] total I-side ARs=6, ARs in window=5, first=0x50000000, 40, 80, c0, 100   PASS
[spec-mmio-rule]    total I-side ARs=3, ARs into the INHIBITED window=2, addrs=0x50000000
  *** FAILED *** SPECULATIVE DEVICE READ: 2 wrong-path 64-byte AXI burst read(s) …
[spec-mmio-dside]   total D-side ARs=0, ARs into the INHIBITED window=0        PASS
```

The defect returns on exactly the one test, with both controls still correct in the same
run — so the RULE test's pass on the fix tree is a real negative, not a suppressed frontend.
The failure message's "64-byte" is now literally accurate, the burst having deliberately
been left alone (§2.5).

Because the two arms differ only in that one expression, this same worktree doubles as the
net-renaming control for the synth gate: FIX-vs-CTRL is the real cost of the added
`cmdPort.ready` term, CTRL-vs-BASE is SpinalHDL line-number churn.

### 4.3 Suites

| run | baseline (`ca4b901`, this session) | with the fix | verdict |
|---|---|---|---|
| `make test-fast` (`fastTest`) | 341 run / 341 pass (brief's number) | **341 run, 340 pass, 1 fail** | the known `RobPluginSpec debugPcApply` seed flake; `testOnly m68k040.rob.RobPluginSpec` re-run **44/44 pass**. Effectively 341/341. |
| `ExecuteLockStepSpec` | 505 pass on a clean tree; 509 run / 508 pass / 1 fail with the audit tests added | **509 run, 509 pass, 0 fail** | **zero regressions**, and the one previously-failing test now passes |
| `m68k040.ls.* m68k040.cache.*` | **300 run, 289 pass, 11 fail** (matches the brief's "11 by name") | 300 run, 288 pass, **12 fail** | see below |

**The 12th ls/cache failure is a flake in a DUT this change cannot reach.** The 11 baseline
failures reproduce by name exactly. The extra one is `LsEuSpec` "load miss refills then
writes PRF + fires completion". `LsEuSpec`'s DUT contains **no `IcachePlugin` at all**
(`src/test/scala/m68k040/ls/LsEuSpec.scala:27` — `LsEuPlugin` only), so no I-side change can
structurally affect it. Re-running `testOnly m68k040.ls.LsEuSpec` on the fix tree fails a
**different** test of that suite ("cross-line store after cross-line load drains BOTH
slots", which appears in neither the baseline nor the first fix run) and passes "load miss
refills" — i.e. the suite is seed/ordering-dependent, with one wandering failure among 7
tests.

### 4.4 200-seed fuzz

`src/main` changed, so this was actually run rather than argued — the standing count is not
unchanged by construction here.

```
FUZZ_SEED_START=0 FUZZ_SEED_COUNT=200 FUZZ_MINIMIZE=0 \
  sbt 'testOnly m68k040.fuzz.FuzzLockStepSpec'

[fuzz] sweep done: 200 seeds, 3 divergences, 0 generator failures
[fuzz]   seed=80  [STEP] idx=79 reg D5: dut=0x00000000 oracle=0x0000007e
[fuzz]   seed=109 [STEP] idx=64 pc: dut=0x7bca4112 oracle=0x4080013e
[fuzz]   seed=127 [STEP] idx=70 pc: dut=0x3d973c90 oracle=0x4080015e
```

**3 divergences, on exactly the standing seeds 80 / 109 / 127.** No new divergence, none
fixed, no generator failures. (The suite reports `failed 1` because its assertion demands
zero divergences; the count and the seed set are the measurement.) The launcher is
committed as `run_fuzz200.sh`.

### 4.5 Post-route synth gate

Gated post-route (not OOC), `POSTROUTE_ROUNDS=9`, xcku5p-ffvb676-2-e, one arm at a time
under the shared `flock` mutex, launcher committed as `synth/run_ispec_gate.sh`.
Numbers are `SIGNOFF_200MHZ_*`, re-derived at a real 5.000 ns, plus the **`clk`** intra-clock
row of `synth/fullcore_route_timing.rpt` — not the `timing_summary.rpt` headline.

| arm | netlist md5 | `SIGNOFF_200MHZ_WNS_NS` | `SIGNOFF_200MHZ_RESULT` | `clk` intra-clock WNS / TNS / failing endpoints |
|---|---|---|---|---|
| BASE (`ca4b901`) | `506d3e0b8c39ca278bf3f90b18f030bd` | **+0.001** | `MET_200` | **+0.001** / 0.000 / **0 of 168088** |
| FIX (`7f3a45f`) | `b2f6b052e51c98b9bc2d2e6c57fb0391` | **-0.171** | `FAILED_AT_200` | **-0.171** / -76.668 / **1176 of 168465** |
| CTRL (`ba30f8a`, gate forced inactive) | `8319b19e8dc84e7b25eb29975728d471` | <!-- CTRL RESULT --> | | |

**The FIX arm does not meet 200 MHz.** Reported as measured, not explained away.

What the report says about *where* it fails, which is the reason the CTRL arm exists:

- **The gate's own logic does not appear in the timing report at all.** `grep -c` for
  `nonSpecFetch` / `inhibitedSpecBlock` / `SpeculativeFetchGate` across
  `fullcore_route_timing.rpt` returns **0**. The added `cmdPort.ready` term is not on any
  reported failing path.
- **The worst path is somewhere else entirely, and it is not the same family as BASE's.**
  Every one of the FIX arm's top-10 failing paths is
  `RobPlugin_logic_exc_fsFrameBase_reg[7]/C` → `DcachePlugin_logic_s0Payload_lineData_reg[*]/R`
  (13 logic levels). BASE's worst path is a completely different family,
  `LsEuPlugin_logic_sq/robIds_4_reg[0]/C` → `IssueQueuePlugin_logic_lines_*_triggers_reg[10]/CE`.
  The two arms are not failing on the same arc.

That pattern — the winner changing between several near-tied families rather than one arc
degrading — is exactly what
`docs/superpowers/memory/fmax-resilience-postmortem-2026-08-19.md` describes for this design
at 5 ns, and it is why a bare FIX-vs-BASE delta cannot be attributed. Hence the CTRL arm:
same tree, same net names, same SpinalHDL line numbers, gate expression forced to `True` so
it constant-folds away. **CTRL-vs-BASE is placement/line-number churn; FIX-vs-CTRL is the
real cost of the added term.**

---

## 5. Finding (NOT part of this change): speculative bus errors are already deferred to commit

Asked separately: does a speculative fetch that receives an AXI error response raise an
exception before that fetch is architecturally committed? **No. This invariant already
holds, and it holds by exactly the mechanism the project owner described as the intended
design.** Reported as a clean negative; nothing here was changed.

### 5.1 The mechanism that already exists

The path is I-cache -> µop cracker -> ROB, and the exception is created as a **µop**, not as
a fetch-path side effect:

1. **The I-cache turns a bus error into fetch DATA, not an exception.** `FetchRsp` carries
   `fault` plus `atc` (`IcacheTypes.scala:99-104`), and task #211 made `atc` distinguish the
   two causes explicitly: `True` = ITLB/MMU translation fault, **`False` = a physical AXI
   bus error (SLVERR/DECERR) on a refill**. So "this fetch failed" is already a data bit
   travelling with the fetch, which is the crux of the proposed design.
2. **The cracker emits an exception-throwing µop instead of decoded instructions.**
   `MicroOpAssembler.scala:2768-2780`: `when(pkt.fault)` it emits **one** µop with
   `faulted := True`, `faultVector := 2` (access fault, format-$7), `sswInstr := True`
   (program-space SSW), and `faultAtc := pkt.faultAtc`. Its own comment states the intent:
   "emit a single faulted op µop that DELIVERS the format-$7 access fault (vector 2) **at
   retire**".
3. **The exception materialises only at the ROB head.** `RobPlugin.scala:975`:
   `faultRetire = headReady && (faultedStore(h0) || privViolation) && excIdle`, and every
   fault field feeding the exception unit is read at `h0` (`:1935-1963`). A wrong-path
   faulted µop is therefore squashed by the ordinary flush like any other wrong-path µop.

So the fetch proceeds freely, the error is carried as data, and the exception enters the
normal precise-exception machinery — no speculation term in the fetch path at all. There is
nothing to build.

### 5.2 `ringStale` suppresses ERROR delivery, not only data

Asked specifically. `FetchAlignPlugin.scala:665`:

```scala
val rspFault = ic.rsp.valid && ic.rsp.payload.fault && !rspStaleHead && !faultHold
```

`rspStaleHead = ringStale(ringHead) || predictFire` (`:657`). The fault is gated on the same
staleness term as the data, so a response for a fetch already known to be wrong-path
delivers **neither** data nor fault. This is belt-and-braces on top of §5.1 — it drops the
fault earlier, when the redirect has already resolved; §5.1 covers the case where it has not.

### 5.3 Translation faults behave identically

Same field, same µop, same retire gate: `pkt.fault` is raised for either cause and
`pkt.faultAtc` only selects the SSW bit. A wrong-path fetch into an unmapped page produces a
wrong-path faulted µop that is flushed. No separate path exists, so there is no separate gap.

### 5.4 The three design questions, answered against what the code does today

1. **Does the poison bit persist in the cached line?** There is no poison bit, because an
   errored fill **never allocates**: `IcachePlugin.scala:1796` is
   `when(refillErr) { goto(FAULT) }`, which bypasses INSTALL/PREDECODE entirely. So no stale
   poison can outlive the condition and there is no `CINV`/`CPUSH` interaction to get wrong.
   A later architectural fetch of the same address re-misses and re-raises naturally, which
   is the desired behaviour, at the cost of re-issuing the failing transaction each time.
2. **Allocate-with-poison, or don't allocate?** The design already chose "don't allocate",
   and it is the simpler of the two: it needs no array bit, no maintenance-op semantics, and
   no way for a poisoned line to be hit. Allocating with poison would only buy avoiding the
   repeat bus transaction to an address that is failing anyway. **Recommend leaving it.**
3. **Partially-poisoned line (error on one beat, not the other)?** Cannot arise as a
   distinct case: `IcachePlugin.scala:2537` sets `mshrErr(rIdx)` on *any* errored beat and
   `:2543` ORs it into `refillErr`, so one bad beat fails the whole fill and the FSM goes to
   FAULT. The cracker never sees a partial line.

### 5.5 One correction to the brief

`EXC_UOP_INJECT` **does not exist in this repository** — `grep -rn EXC_UOP_INJECT` over
`Makefile`, `build.sbt`, `synth/` and `src/` returns nothing. The injection machinery being
looked for is not behind a define; it is the unconditional `when(pkt.fault)` arm in
`MicroOpAssembler` described above.

---

## 6. Not claimed

- **This does not fix the Mac boot.** The mechanism is proven to exist and to fire, and it
  is now prevented, but nobody has shown the frontend is actually steered into
  `0x50F0Fxxx` during boot. That remains an `axi_i` bus-capture question — `axi_i` is a
  separate port from `axi_d`, so it is cleanly separable — and it is still the decisive
  experiment.
- **This is a bug fix on its own merits regardless of the boot outcome.** The 68040
  requires that cache-inhibited accesses not be performed speculatively; they were.
- A legitimate, non-speculative inhibited fetch **still issues an ordinary 64-byte line
  burst**, and the I-cache still does not treat INHIBITED as a caching policy. Both are
  deliberate. See §2.5.
- The table walker is untouched, and no verdict on it is offered. See §2.6.
- The prefetcher's documented same-page cacheability-transition residual
  (`IcachePlugin.scala:2094-2144`, pinned by `IcachePrefetchSpec.scala:866+`) is unchanged.
  It is bounded to one line and cannot cross a 4 KiB page, so it cannot reach a device page
  from a cacheable one under page-granular attributes; it is noted, not addressed.
- No board, no SD card, no JTAG lease was used.
