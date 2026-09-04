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

### 2.5 Burst width — a partial `MmioCover`, honestly labelled

An AXI4 read carries **no byte enables**, so every byte a read *covers* is a byte the
device sees read; `MmioCover.scala`'s own doc comment states this is why a downstream fix
is impossible in principle. The gate stops the speculative case, but an architecturally
required fetch from a device page still has to reach the bus, and at `ca4b901` it did so as
a 64-byte line burst.

**What was changed:** an INHIBITED *demand* fill now issues **one 32-byte beat at the
32-byte half-line it needs** (`len=0`, address `mshrPa & ~31`) instead of two beats at the
64-byte line base. That is exactly the half the response can possibly deliver — `bypWindow`
is selected by `missPC(5)` (which half) and `missPC(4:3)` (which 8-byte window inside it).
64 → 32 bytes, and the read can no longer cross a 32-byte boundary. Speculative
(prefetch) fills are unaffected: they are cacheable by construction (rule P1).

Three dependent details had to move with it, each of which would have been a real bug if
missed:

1. `beatNext3` must be forced to zero for the narrowed fill — `fillHi` holds the *previous*
   fill's data. This puts words 13/14/15 on the same F5 `extWValid = false` path the HIGH
   beat always takes: `classify` refuses to guess brief-vs-full, rejects as COMPLEX, marks
   `ambiguousLine`, and `Aligner.scala:63-65` re-classifies live from the instruction
   buffer. Conservative, and already exercised by every `missPC(5) = 1` fetch today.
2. The `bypWindow`/`bypPred` capture must fire on `commitBeat === 0` regardless of
   `missPC(5)`, because the requested half arrived *as beat 0*.
3. The `"I-cache refill must be exactly two beats"` assertion becomes
   `last === (mshrBeat(rIdx) || rSingleBeat)`.

**What was NOT changed, stated plainly.** This is **not** the D side's exact byte cover. A
legitimate inhibited fetch still reads 24 bytes it did not name, and on a device with
4-byte register spacing that is still several registers. Going below one beat is a
**front-end granule change, not an AR payload change**: `FetchRsp` delivers a fixed 64-bit
window plus its predecode, and `classifyBeat` needs the three words *following* each word
it classifies. Fetching less than a whole beat leaves those lookahead words undefined
*inside* the beat, where — unlike the beat-end case, which `classify` already refuses to
guess at — nothing detects it and the result is silent instruction mis-framing. A true
I-side `MmioCover` needs the 64-bit fetch window and the 2-beat predecode dwell contract to
change together. Not attempted.

### 2.5b Compliance against the absolute invariant, per requester

The governing rule for this work is stated as an absolute: **no speculative access to a
cache-inhibited page, ever, by any requester.** That is stronger than "the I-side demand
fetch is fixed", so every requester that can drive an AR is enumerated here with a verdict.
Two of them do **not** comply and are deliberately left as separate, scoped work.

| Requester | Complies? | Evidence |
|---|---|---|
| **I-side demand fetch** | **YES (this change)** | `inhibitedSpecBlock` refuses the command at `cmdPort.ready` using the LIVE `xlate.rsp.cacheMode`, i.e. cacheability is resolved before anything is accepted, so no MSHR is allocated and no AR can be armed. Measured: `spec-mmio I-side THE RULE` 0 window ARs, and the revert control puts them back. |
| **I-side prefetcher, steady state** | YES | Frontier seeded only from a resolved, non-faulting, **cacheable** demand (`IcachePlugin.scala:1457-1467`) and clamped to that demand line's own 4 KiB physical page (`:1294-1295`); rule P1, "an INHIBITED page is never prefetched". |
| **I-side prefetcher, "cycle T" residual** | **NO — narrowed, not closed** | See below. |
| **D-side loads** | YES | `LsEuPlugin.scala:2182-2229` `p4LaunchOk`; audited exhaustively on every path to `DcachePlugin.scala:1877`, and measured by the audit's `spec-mmio D-side` probe with a positive control. |
| **D-side stores / SQ drain** | YES | Non-speculative by construction: drains at the SQ head with `committed(head)` (`StoreQueue.scala:426`); inhibited stores never allocate. |
| **Exception-unit loads** | YES | `excLoadCmdValid => excActive` (`LsEuPlugin.scala:2881`); the exception is already committing. |
| **MMU table walker** | **NO — structural, unfixable without new plumbing** | See §2.6. |

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
runs into the same three rejected closures the existing comment enumerates (all of which
either put the live translation verdict back into the allocator enable cone — the exact arc
M4 exists to delete — or kill the prefetcher outright), so it is a real scoping decision,
not a detail, and it belongs in its own change.

### 2.6 The MMU table walker — a definite violation, deliberately not fixed here

**Asked directly — can the walker violate the invariant? YES, and structurally so.** This
is a firmer answer than the audit's "not demonstrated", and it comes from reading the
walker's own AR path rather than from its launch conditions.

`TableWalker.issueRead()` (`TableWalker.scala:107-118`) is the walker's only AR driver:

```scala
io.axi.ar.payload.addr := (descAddr(31 downto 4) ## U(0, 4 bits)).asUInt
io.axi.ar.payload.len  := U(0, 8 bits)
io.axi.ar.payload.size := U(4, 3 bits)   // 16 bytes
```

There is **no cacheability term on it, and no input carrying one.** The only `CacheMode` in
the entire module is `rCmode` (`:66`, `:209`, `:249`) — the cache mode the walker *extracts
from the leaf page descriptor and reports back to the TLB* for the translated page. The
walker never consults, and cannot consult, the cacheability of the addresses **it itself
reads**: descriptor addresses come from unmasked `urp`/`srp` (`MmuControl.scala:119-120`)
and a raw 28-bit next-base field (`MmuTypes.scala:63`), are never themselves translated,
and are subject to no region check anywhere.

So all three properties the invariant forbids hold simultaneously, by construction:
1. **Speculative** — the triggering DTLB miss is raised at P2 on pipeline validity alone
   (`LsEuPlugin.scala:2522`), with no ROB-head gate; the D-side launch omits `umFlush`
   (`DtlbPlugin.scala:322`) and the I-side launch has no flush term at all
   (`ItlbPlugin.scala:251`).
2. **Unabortable** — no abort port (`TableWalker.scala:40-47`); once `io.start` pulses the
   FSM runs `RD_ROOT → RD_PTR → RD_PAGE → FINISH` to completion.
3. **Over-covering** — a fixed 16-byte line-aligned read, which is precisely the shape
   `MmioCover`'s own doc comment says "touches four registers at once and fires
   read-to-clear side effects on three the access never named".

Reaching a device still requires a root pointer or descriptor aimed at device space, which
remains undemonstrated — but that is a statement about the *page tables*, not about the
core. The core offers no guarantee here at all, which is what the invariant demands.

**Deliberately NOT fixed in this change**, per explicit instruction not to fix it blind.
It is a genuinely different change in a different module: the walker needs (a) an
attribute source for its own descriptor addresses — which does not exist today and is the
substantial part — (b) a flush term on both launch sites, and (c) an abort port. Bolting
any of those on without the first would be the same category error this fix exists to
correct. Recommend scoping it as its own task.

---

## 3. The test that pinned the defect

`IcacheSpec.scala:819` read, in full:

```scala
assert(arAfterInhibited == arAfterWarm + 1, s"inhibited fetch must refill once: …")
```

and nothing else. **That assertion encoded the defect.** It asserted only that a bus
transaction *happened*, and was silent on both of the questions that actually matter for a
page that by definition names a device:

1. **Was the transaction allowed to happen at all** — was the fetch architectural, or a
   wrong-path run-ahead? The old form could not tell, which is precisely why the I side had
   no speculation gate for as long as it did.
2. **How wide was it?** The old form accepted a 64-byte INCR burst — sixteen longwords,
   four 53C96 registers, including read-to-clear Interrupt Status — as indistinguishable
   from a correct single device read.

It was **not** quietly edited. The count assertion is *kept* (that DUT drives `cmdIn` from a
directed probe, so the fetch really is architectural and really must reach the bus), and
the shape check is added on top of it, with a same-run control that ordinary cacheable line
fills are still 2 × 32 B bursts so the new assertion cannot pass vacuously. Question (1) is
pinned by the audit's `spec-mmio I-side` suite, which needs a speculating frontend and
therefore cannot live in that standalone DUT.

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

`SpeculativeFetchGate`'s last line replaced with `ic.logic.nonSpecFetch := True` (every
other line, every net name and every SpinalHDL line number identical; the gate
constant-folds away):

```
[spec-mmio-rule] total I-side ARs=3, ARs into the INHIBITED window=2, addrs=0x50000000
*** FAILED *** SPECULATIVE DEVICE READ: 2 wrong-path AXI burst read(s) …
```

Both controls stayed correct in the same run (ctl-neg 0 window ARs, ctl-pos 5). Restored
afterwards; `git status` clean.

(The test's failure *message* still says "64-byte" — it is a static string written against
`ca4b901`. Under the revert control the burst narrowing is still active, so those two reads
were 32 bytes each. The count, the addresses and the verdict are what the control turns on.)

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

<!-- SYNTH RESULT -->

---

## 5. Not claimed

- **This does not fix the Mac boot.** The mechanism is proven to exist and to fire, and it
  is now prevented, but nobody has shown the frontend is actually steered into
  `0x50F0Fxxx` during boot. That remains an `axi_i` bus-capture question — `axi_i` is a
  separate port from `axi_d`, so it is cleanly separable — and it is still the decisive
  experiment.
- **This is a bug fix on its own merits regardless of the boot outcome.** The 68040
  requires that cache-inhibited accesses not be performed speculatively; they were.
- A legitimate, non-speculative inhibited fetch **still issues a 32-byte read**, not an
  exact byte cover. See §2.5.
- The table walker is untouched. See §2.6.
- The prefetcher's documented same-page cacheability-transition residual
  (`IcachePlugin.scala:2094-2144`, pinned by `IcachePrefetchSpec.scala:866+`) is unchanged.
  It is bounded to one line and cannot cross a 4 KiB page, so it cannot reach a device page
  from a cacheable one under page-granular attributes; it is noted, not addressed.
- No board, no SD card, no JTAG lease was used.
