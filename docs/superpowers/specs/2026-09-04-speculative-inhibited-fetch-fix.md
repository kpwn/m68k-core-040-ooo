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

### 2.6 The MMU table walker — noted, not fixed

The audit's secondary finding stands untouched, exactly as scoped: the walker launches
from a fully speculative point (`LsEuPlugin.scala:2522`), its D-side launch omits `umFlush`
(`DtlbPlugin.scala:322`) and its I-side launch has no flush term at all
(`ItlbPlugin.scala:251`), `TableWalker.scala:40-47` has no abort port, and descriptor reads
are fixed 16-byte line-aligned with no `MmioCover` arm (`TableWalker.scala:111,114`). It
was never shown to reach MMIO and nothing here changes it. It did not fall out of this
work naturally, and it is a different fix (a flush term plus an abort port) in a different
module.

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

`src/main` changed, so this was actually run rather than argued.

<!-- FUZZ RESULT -->

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
