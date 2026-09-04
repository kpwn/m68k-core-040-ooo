# Two-tier branch reschedule — design, implementation and measurement

**Date:** 2026-09-04
**Repo / branch:** `m68k-core-040-ooo`, worktree branched from `fmax-closure-fanout` @ `87d4089`
**Implements:** item 1 of `2026-09-04-naxriscv-architecture-comparison.md` §8
**Reference:** `/home/qwertyoruiop/m68k-ooo-v2/thirdparty/NaxRiscv` — **read-only, not modified**
**Supersedes as a *conclusion*, not as a *record*:** `2026-09-03-early-flush-ipc-ab-measurement.md`.
That document's REVERT verdict on the *degenerate committed-PC early flush* stands
unchanged and is not reopened here. What this document changes is the follow-on
sequencing advice: the refetch-latency term it named as dominant and unreachable
turns out to be reachable, and it is reached without a checkpoint.

**Status:** IMPLEMENTED and MEASURED. Three commits on this worktree branch, not
merged to `fmax-closure-fanout`.

---

## 0. TL;DR

* **Mechanism split in two, as NaxRiscv does.** Tier 1, at branch-EU resolution:
  redirect fetch to the branch's *own* resolved target and **freeze** rename.
  Tier 2, at retire: every state rollback, unchanged.
* **Tier 1 asserts no flush.** It drives exactly three things — the FetchAlign
  fetch redirect, the pre-rename decode skid/MicroOpQueue clear, and the RAS
  predictor checkpoint restore. `UmWriteQueue.io.flush`, `StoreQueue.io.flush`,
  `IssueQueue.flushPort`, `RatTable.rollback`, `Freelist.flush`, `tail := head`
  and `DivEu.cplxFlush` all keep the retire-gated `doFlushReg` as their sole
  source. The M-bit data-loss direction stays closed by construction.
* **The 69 `DIVERGED[HANG]` events do not reproduce.** `BsrFlushSkipSpec` **17/17**,
  the four `flush_younger_*` corpus tests **4/4**, all six directed
  mispredict/flush tests pass, `fastTest` **337/337**.
* **Measured IPC.** `branchy` **0.508 → 0.518 (+1.97 %)**; the purpose-built
  `deep-backlog` kernel **0.440 → 0.485 (+10.23 %)**; 13-kernel aggregate
  **0.644 → 0.652 (+1.24 %)**. Two kernels regress ~3.5 % (§6.3) for a reason
  that is understood and is *not* the mechanism's core action.
* **A methodological finding that cost this session a full vacuous measurement
  round, recorded because it will bite the next person: the backend wiring exists
  in FOUR copies** — `top/FullCoreSynth.scala` plus one each in `FuzzDut.scala`,
  `ExecuteLockStepSpec.scala` and `IpcBenchSpec.scala`. Landing the mechanism in
  the synthesis copy alone produced a **bit-identical A/B and a full green test
  sweep**, both meaningless. §7.

---

## 1. What was actually wrong with the reverted attempt

`archive/early-flush-eu-resolution` did the **Tier-2 action at Tier-1 time**. Its
own in-tree comment states the constraint it was working around:

> `RatTable.io.rollback` / `Freelist.io.flush` are each a SINGLE GLOBAL "restore
> to the committed shadow" operation … with no per-branch or per-robId granularity.

Given that, an early `flushing` can only resume at the **committed** PC, because
that is the only point the global rollback is consistent with. Hence: the
mispredicting branch is itself discarded and re-executed, older in-flight work is
discarded and re-executed, mispredict counts double (`branchy` 22 → 46), and the
StoreQueue is flushed at a non-retire-gated instant, which is the documented wedge
class that produced the 69 hangs.

NaxRiscv never does that. At its early port it changes **two** things — the PC, and
whether rename is allowed to advance. Neither touches RAT, freelist, ROB, LSU or EU
state. The global rollback still happens at commit, where "restore to committed" and
"restore to just-after-the-branch" are the same state by construction.

**The constraint the reverted attempt was fighting is real. The fix is to stop
firing the rollback early, not to make the rollback selective.** A selective
rollback (the 581-line per-branch checkpoint design) is a different, larger project
and is still sequenced after this one.

---

## 2. The design as landed

Three files, ~120 lines of comment and ~25 lines of logic.

### 2.1 `rob/RobPlugin.scala` — the Tier-1 area

Placed as one contiguous block immediately after the `doFlushReg` / `flushPcReg`
assignments, so every signal it reads is already in scope and it assigns nothing
that any other block also assigns (deliberate, given this codebase's three recorded
SpinalHDL last-assignment-wins incidents).

```scala
val earlyPend  = RegInit(False)   // sticky "rename is frozen"
val earlyFire  = RegInit(False)   // 1-cycle frontend redirect pulse
val earlyRobId = Reg(UInt(robIdW bits)) init 0
val earlyPcReg = Reg(UInt(32 bits))     init 0

val bcAge      = (branchCompletion.payload.robId - head).resize(count.getWidth)
val curAge     = (earlyRobId - head).resize(count.getWidth)
val bcInFlight = bcAge < count
val earlyArm   = branchCompletion.valid && branchCompletion.payload.mispredict &&
  bcInFlight && !flushing && !excActive && !coreHalted && !debugQuiesceActive &&
  (!earlyPend || (bcAge < curAge))
earlyFire := earlyArm
when(earlyArm) { earlyPend := True; earlyRobId := …robId; earlyPcReg := …nextPc }
when(flushing) { earlyPend := False }

val earlyHit = branchRedirect && earlyPend && (h0 === earlyRobId) &&
  (nextPcRd0 === earlyPcReg) &&
  !exc.redirectValid && !debugRecoverEnter && !debugPcApply
val earlySuppressFe = RegNext(earlyHit) init False
```

Four things in that block earn their keep:

* **`bcInFlight`.** `BranchEuPlugin` has no flush input (`FullCoreSynth.scala:195-196`
  wires `eu.flush` only on the ALU EUs), so a wrong-path branch squashed by an
  earlier Tier-2 flush can still report a completion one or two cycles later.
  Today that is harmless — alloc-reset overrides a stale completion — but it must
  not be allowed to steer the fetch PC. `bcAge < count` rejects any robId not
  currently resident.
* **Oldest-wins (`bcAge < curAge`).** Modelled on `CommitPlugin.scala:118-144`.
  Without it a *younger* branch resolving after an older one would steal the
  redirect. With it, a later-resolving *older* branch correctly re-steers.
* **`nextPcRd0 === earlyPcReg`.** A fail-safe, not a functional requirement: the two
  are the same value by construction (one `nextPcMem` write, one read). If they
  ever differ, the suppression declines and the frontend takes today's full flush.
* **`earlyPend` is cleared by a *registered* assignment**, so it still reads True
  *during* the `flushing` cycle. That is load-bearing — see §2.3.

### 2.2 `rename/RenameStage.scala` — the freeze

```scala
val allocHalt = Bool(); allocHalt.allowOverride; allocHalt := False
uopsPort.valid := du.uops.valid && initDone && freeReady && !allocHalt
du.uops.ready  := initDone && uopsPort.ready && freeReady && !allocHalt
```

This is one extra term on the **existing** `freeReady` gate, chosen deliberately
over inventing a new stall path: that gate is already proven to handle mid-macro
stalls, and its own comment already explains why the term must appear on *both* the
output valid and the input ready (otherwise dispatch can fire while `du.uops` does
not, and the same packet is dispatched twice).

**Deadlock argument.** `earlyPend` is set only for a branch that is resident in the
ROB with `mispredictStore` set. Retire is in-order and requires nothing from rename,
so that branch necessarily reaches the head and fires `branchRedirect` — or an older
exception/debug event fires first. Either way `flushing` asserts and clears
`earlyPend`. There is no path in which the freeze outlives its own release condition.

### 2.3 `top/FullCoreSynth.scala` — the wiring, and the suppression

```scala
val earlyFire  = rob.logic.earlyFire
val feSuppress = rob.logic.earlySuppressFe && !excActive
val feFlush    = (doFlush && !feSuppress) || excActive || earlyFire

iq.flushPort                      := doFlush || excActive     // UNCHANGED
host[RenameStage].logic.pipeFlush := doFlush || excActive     // UNCHANGED
decodeUop.pipeFlush               := feFlush                  // Tier 1 + suppression
host[RenameStage].logic.allocHalt := rob.logic.earlyPend      // the freeze

faRedir.valid   := (doFlush && !feSuppress) || earlyFire || frontendResume.valid
faRedir.payload := Mux(doFlush && !feSuppress, flushPc,
                   Mux(earlyFire, rob.logic.earlyPcReg, frontendResume.payload))

val rasCheckpointRestore = (doFlush && !feSuppress) || earlyFire || fa.logic.ftqMismatch
```

**The suppression is the whole point, and it is easy to under-rate.** An early PC
redirect that is followed by an ordinary Tier-2 frontend flush buys nothing: the
frontend refetches, then gets flushed again and refetches a second time. The benefit
only materialises if the correct-path bytes fetched during the freeze **survive**
the rollback. So Tier 2 skips its own `mispredictRedirect` and its own decode-skid
flush exactly when it is the retirement of the branch Tier 1 already redirected for.

That is also precisely why `earlyPend` must still be asserted on the `flushing`
cycle. With the decode-skid flush suppressed, `du.uops.valid` can be high on the
rollback cycle; renaming against a RAT/freelist that is being restored the same
cycle would corrupt the mapping. Today's design is protected from this only
incidentally, by the skid flush killing `du.uops.valid`. The freeze replaces that
protection deliberately.

**RAS.** Moved to Tier 1 (with Tier-2 suppression) because the RAS is a pure
predictor and its restore belongs at the instant the wrong-path frontend is
discarded. Restoring again at Tier 2 under `feSuppress` would undo the *legitimate*
correct-path pushes made during the freeze — the same undo-too-much mistake in
miniature. Structurally this mirrors NaxRiscv restoring branch history on its early
port (`prediction/HistoryPlugin.scala:135`).

---

## 3. How Tier 1 avoids asserting a flush, stated exhaustively

The hard constraint from `2026-09-04-naxriscv-architecture-comparison.md` §7.5(2):
`UmWriteQueue` discards *uncommitted* entries on `io.flush`
(`UmWriteQueue.scala:111-118`). That is conservative-safe **only because today's
`io.flush` is retire-gated** — a needed M (modified) descriptor write belongs to an
instruction that has already retired, is therefore marked `committed`, and survives.
Fire that flush earlier and an M write for a still-to-retire instruction can be
dropped: a dirty page later evicted as clean, silent data loss. M is a correctness
bit; U is a hint; the dangerous direction is always *losing* an M.

Tier 1 satisfies this by never appearing in any flush expression. The complete list
of ROB-sourced flush consumers, with their drivers after this change:

| consumer | driver | changed? |
|---|---|---|
| `DtlbPlugin.umFlush` / `ItlbPlugin.umFlush` → `UmWriteQueue.io.flush` | `doFlush` | **no** |
| `LsEuPlugin.sqFlush` → `StoreQueue.io.flush` | `doFlush \|\| excEnteringSq` | **no** |
| `IssueQueueService.flushPort` (and `eu0/eu1.flush`) | `doFlush \|\| excActive` | **no** |
| `RenameStage.logic.pipeFlush` (rename→dispatch skid) | `doFlush \|\| excActive` | **no** |
| `DivEuPlugin.cplxFlush` | `doFlush \|\| excActive` | **no** |
| `rc.flushPort` → all 5 `RatTable.rollback` + all 5 `Freelist.flush` | `flushing` (ROB-internal) | **no** |
| ROB `tail := head; count := 0` | `flushing` | **no** |
| `FetchAlign.mispredictRedirect` | `(doFlush && !feSuppress) \|\| earlyFire \|\| resume` | yes |
| `DecodeUopService.pipeFlush` (FetchAlign→decode skid, MicroOpQueue, µcode aborts) | `(doFlush && !feSuppress) \|\| excActive \|\| earlyFire` | yes |
| `RasPlugin.checkpointRestore` | `(doFlush && !feSuppress) \|\| earlyFire \|\| ftqMismatch` | yes |

The three changed rows are, in order: a fetch pointer, a pre-rename instruction
buffer, and a branch-prediction structure. None of them holds architectural state,
an allocated resource, or a pending memory effect. `earlyFire` appears nowhere else.

A secondary consequence worth stating: because the mispredicting branch is **never
discarded early**, it still reaches retire and still trains the BTB and gshare
through the existing retire-gated ports. The reverted attempt had to add
Tier-1 predictor training to escape a livelock it created by throwing the branch
away; this design needs none, and adds none.

---

## 4. The instrument, and why the 2026-09-03 one could not have seen this

The prior A/B judged the mechanism with a histogram of **ROB entries ahead of the
mispredicting branch**, and read `branchy`'s 2.05 as a small ceiling. Backlog depth
is the wrong quantity for a mechanism whose product is refetch latency.

The right quantity is the number of cycles between a branch **resolving in the EU**
and its retire-gated redirect firing at the ROB head — that interval is exactly what
an early redirect can convert into useful frontend work. It is measurable off
`branchCompletion`, `branchRedirect` and `head`, all of which are `simPublic` on a
baseline netlist, so the same harness code runs unchanged on both arms. Landed in
`IpcBenchSpec.scala` as `[resolve2retire]`, alongside `[flush2commit]` (cycles from
the flush pulse to the next macro commit — the recovery latency the mechanism is
supposed to shorten).

Measured resolve→retire delay, baseline netlist:

| kernel | n | mean | histogram |
|---|---|---|---|
| `branchy` | 21 | **1.10** | `1:19, 2:2` |
| `hot-loop` | 2 | 1.00 | `1:2` |
| `shift-mixed` | 2 | 5.00 | `5:2` |
| `shift-stream` | 2 | 6.50 | `6:1, 7:1` |
| `call-return` | 3 | 10.33 | `1:2, 29:1` |
| `store-stream` | 2 | 18.00 | `7:1, 29:1` |
| `same-line-copyback` | 2 | 22.50 | `8:1, 37:1` |
| `load-stream` | 2 | 28.50 | `10:1, 47:1` |
| **`deep-backlog`** | 12 | **12.83** | `1:1, 12:8, 19:3` |

**`branchy`'s own delay is ~1 cycle.** Its mispredicting branch is essentially
always at the ROB head by the time it resolves. `branchy` alone therefore cannot
resolve *any* early-redirect mechanism, whatever its merit — a null on `branchy`
would have been uninformative in both directions. That is a real limitation of the
standing harness and it is the reason `deep-backlog` was reconstructed and landed
(from the description in `2026-09-03-early-flush-ipc-ab-measurement.md` §2.3): five
dependent slow-path shifts pin the head, twelve independent `add.l %d1,%aN` sit in
the ROB completed-but-unretired, and the branch's flag producer depends on none of
it. Read it as an upper bound, not as representative code.

---

## 5. Verification

Every result below is from the wired configuration (§7). All simulation was
serialised to at most one heavy JVM at a time, with no Vivado build running.

| check | result |
|---|---|
| `BsrFlushSkipSpec` (139 directed lock-step programs, 16 flush shapes) | **17/17 PASS, 0 hangs** |
| `flush_younger_{bsr,bsr_exc,bsr_tree,rts_bsr}_reexec` | **4/4 PASS** |
| `mispredict`, `deep_mispredict`, `adv_store_squash_mispredict`, `adv_a7_spec_flush`, `adv_flush_restart_store`, `unstable_branch` | **6/6 PASS** |
| `make test-fast` (`fastTest`) | **337/337 PASS**, 0 failed (the known `RobPluginSpec` `debugPcApply` flake did not fire) |
| 200-seed fuzz lock-step sweep | see §5.1 |

`deep_mispredict.s` asserts full post-flush architectural register state and is the
strongest single check in the set; `adv_flush_restart_store` is the specific test
the reverted attempt could never make pass.

### 5.1 Fuzz

Full 200-seed sweep (`tools/fuzz/sweep.sh 0 200 25 20`, `FUZZ_MINIMIZE=0`, 8 batches,
one JVM at a time):

**3 divergences — seeds 80, 109, 127. Byte-for-byte the pre-existing set. It did
not rise.**

```
seed=80  DIVERGED[STEP] idx=79 reg D5: dut=0x00000000 oracle=0x0000007e
seed=109 DIVERGED[STEP] idx=64 pc:     dut=0x7bca4112 oracle=0x4080013e
seed=127 DIVERGED[STEP] idx=70 pc:     dut=0x3d973c90 oracle=0x4080015e
```

197/200 PASS. No new seed diverged, and the three that do are the same three, with
the same failing index and the same values.

### 5.2 `ExecuteLockStepSpec` (excluded from `fastTest`, so run separately)

**460 succeeded / 2 failed — and the identical 460/2 with the identical two test
names on the baseline `87d4089` netlist.** Verified by reverting the six changed
files to `87d4089` and re-running. Both are pre-existing, neither is a regression:

* `lock-step: mispredicted branch with a wrong-path UNMATCHED bsr does not leak a
  phantom RAS entry — rollback-on-flush fix`
* `lock-step: CMP2.W (d8,An,Xn) indexed bounds pointer`

The first one is worth naming explicitly because it is RAS-and-flush-adjacent and
this change moves the RAS restore to Tier 1 — it would have been the natural place
for this design to break, and it fails identically without it.

---

## 6. IPC A/B

`IPC_SEED=860374149`, zero-latency memory model, 13 kernels. Both arms use the
identical harness; the WITHOUT arm is `87d4089`'s three RTL files restored into this
worktree, with the three Tier-1 telemetry taps stubbed to `false` (they do not exist
on that netlist). Run-to-run variance with `IPC_SEED` pinned is zero, as it has been
for every prior use of this harness.

| kernel | IPC without | IPC with | ΔIPC | cycles wo → wi |
|---|---|---|---|---|
| dependent-ALU | 1.002 | 1.002 | +0.00 % | 401 → 401 |
| independent-ALU | 2.000 | 2.000 | +0.00 % | 214 → 214 |
| load/store | 0.246 | 0.246 | +0.00 % | 1182 → 1182 |
| load-stream | 0.697 | 0.692 | −0.72 % | 690 → 695 |
| store-stream | 1.027 | 1.021 | −0.58 % | 475 → 478 |
| same-line-copyback | 0.853 | 0.822 | **−3.63 %** | 286 → 297 |
| shift-stream | 0.974 | 0.986 | +1.23 % | 500 → 494 |
| shift-mixed | 1.730 | 1.749 | +1.10 % | 467 → 462 |
| **branchy** | **0.508** | **0.518** | **+1.97 %** | 443 → 434 |
| **deep-backlog** (synthetic) | **0.440** | **0.485** | **+10.23 %** | 1038 → 943 |
| hot-loop | 1.283 | 1.239 | **−3.43 %** | 315 → 326 |
| mixed | 0.526 | 0.526 | +0.00 % | 620 → 620 |
| call-return | 0.330 | 0.332 | +0.61 % | 2443 → 2427 |
| **AGGREGATE** | **0.644** | **0.652** | **+1.24 %** | 9074 → 8973 |

### 6.1 The mechanism does what it says

Recovery latency — cycles from the retire-gated flush pulse to the next macro commit:

| kernel | without | with |
|---|---|---|
| `branchy` | 14.00 (`14:20`) | **12.90** (`12:2, 13:19`) |
| `deep-backlog` | 14.45 (`14:10, 19:1`) | **7.50** (`7:9, 12:1`) |
| `call-return` | 18.67 (`14:2, 28:1`) | 15.00 (`7:1, 13:1, 25:1`) |

`deep-backlog`'s 14.45 → 7.50 is the finding in one line: with a ~13-cycle freeze
window the frontend gets roughly half of the post-flush refill done *before* the
flush, and the recovery halves. `branchy`'s window is ~1 cycle and it recovers
~1 cycle. The mechanism's payoff is a direct function of the resolve→retire delay,
and it behaves accordingly across the whole kernel set.

### 6.2 The reverted attempt's failure signature is absent

| | reverted attempt | this design |
|---|---|---|
| `branchy` flush pulses wo → wi | 21 → **46** | 21 → **22** |
| `branchy` mispredicts wo → wi | 22 → **46** | 21 → **22** |
| `branchy` ΔIPC | **−44.88 %** | **+1.97 %** |
| `BsrFlushSkipSpec` | 6/17, **69 hangs** | **17/17, 0 hangs** |

There is no new population of mispredicts at `ahead = 0/1`, because the branch is
never re-fetched or re-executed. Tier-1 events and Tier-2 flushes are 1:1 and every
one of them is suppressed at Tier 2 (`branchy`: `earlyFire=22, suppressed=22`), i.e.
the robId/PC match declines zero times in these workloads.

### 6.3 The two regressions, honestly

`hot-loop` −3.43 % and `same-line-copyback` −3.63 % are real, reproducible, and not
noise. Their cause is visible in the telemetry: both gain **exactly one extra
mispredict** over the run (2 → 3), and one mispredict on `hot-loop` costs ~13 cycles,
which is the entire 315 → 326 delta. `load-stream` and `store-stream` gain one too;
`deep-backlog` *loses* one.

The mechanism is that refetch now begins ~1-13 cycles **earlier than the BTB/gshare
training for that same branch**, which is still retire-gated. The frontend therefore
predicts the first block of the corrected path against a not-yet-updated predictor.
Sometimes that is worse, sometimes better; on these short kernels a single flipped
prediction is several percent.

The identified follow-up is NaxRiscv's other early-port consumer — training/history
restore on the early port (`DecoderPredictionPlugin.scala:118`,
`HistoryPlugin.scala:135`). It is **deliberately not done here.** Training off
`branchCompletion` is sound for a branch that will retire, but a branch that is
itself later squashed by an *older* mispredict or exception would pollute the
predictor — a real risk that deserves its own measurement rather than being bundled
into this change to improve a number. Recorded, not implemented.

---

## 7. The methodological finding: four copies of the backend wiring

This is the most transferable thing in this document.

`decodeUop.pipeFlush` / `faRedir` / `rasCheckpointRestore` / `allocHalt` are wired in
**four** places:

* `src/main/scala/m68k040/top/FullCoreSynth.scala` — `BackendWiringPlugin` (synthesis)
* `src/test/scala/m68k040/fuzz/FuzzDut.scala` — ported corpus, `BsrFlushSkipSpec`, fuzz
* `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`
* `src/test/scala/m68k040/bench/IpcBenchSpec.scala`

The first implementation landed in the synthesis copy only. The result was **not** an
error, a failing test, or an obviously null measurement:

* `RobPlugin`'s `earlyFire` / `earlyPend` / `earlySuppressFe` are ROB-internal, so
  the telemetry showed the mechanism "engaging" — `branchy earlyFire=21,
  suppressed=21`, a 100 % robId/PC match rate. Entirely convincing, entirely inert.
* All ten directed mispredict/flush tests passed. `BsrFlushSkipSpec` passed 17/17
  with zero hangs. Both results were **vacuous** — those DUTs never had the
  mechanism.
* The IPC A/B came out **bit-identical on all 13 kernels**, which read as a clean
  negative result and very nearly *was reported as one*.

What broke the illusion was a physical cross-check that the two arms could not both
satisfy: `feedFires` (FetchAlign `feed` handshakes over the whole run) was **exactly
equal** between arms on three separate kernels — 389, 480, 671. Two frontends that
genuinely fetch different instruction streams for 12 cycles after every mispredict do
not deliver an identical packet count three times over. That forced the search that
found the extra wiring copies.

Two rules follow, and they are worth more than the mechanism:

1. **Before believing any A/B, verify the DUT under test actually contains the
   change.** Telemetry taps on the *producer* prove nothing about whether the
   *consumer* is wired. The cheap version of this check is to confirm at least one
   physical quantity that must differ, actually differs.
2. **`allowOverride` default-False service wires make a missing drive silent.** That
   is the same property that makes them useful (a plugin can elaborate standalone)
   and dangerous (a forgotten wiring copy degrades to "old behaviour", never to an
   elaboration error). Every new one added to this codebase inherits that trap.

---

## 8. Timing

**Not measured. No number is claimed, and none should be inferred.**

Per this session's brief the OOC gate is explicitly *not* usable here — it was
measured to have a **0.919 ns noise floor**, three times the deltas being read off
it, and two OOC "regressions" (−13.1 MHz, −8.58 MHz) failed to materialise
postroute. So the only meaningful answer is a `full_impl` postroute WNS, and the
machine was not available for one: `/var/tmp/m68k-ooo-vivado.lock` is held
(created 2026-09-03 23:43), which is one of the two standing blockers for starting
a build, alongside three other agents active on this host (load average ~14) and a
live JTAG lease. Starting a multi-hour place-and-route under those conditions was
judged the wrong trade against a brief that puts functional correctness first.

`generated/M68kFullCoreSynth.v` regenerates cleanly from this branch
(`sbt "runMain m68k040.top.GenFullCoreSynthVerilog"`), so the run is ready to go the
moment the lock frees:

```bash
sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -source synth/impl_FullCore.tcl     # read POSTROUTE WNS, not synth
```

**What that run should be looking at, in priority order** — stated so it is not a
blind re-measure:

1. **`RenameStage`'s `!allocHalt` term on `uopsPort.valid` / `du.uops.ready`.** This
   is the one change on a path with recorded history: the "LS ready-chain skid" is
   one of the four co-critical arc families in
   `lut-fmax-tradeoff-investigation-2026-08-15`. `allocHalt` is a bare register
   output (`earlyPend`) ANDed into an existing gate, which is the cheapest possible
   shape, but it is still one more level on that chain.
2. **`earlyHit`'s 32-bit `nextPcRd0 === earlyPcReg` compare on the retire cone.**
   `nextPcRd0` is a `readAsync` output already feeding `flushPcReg`; this adds a
   comparator branching off it into a new register D-input. It is a pure fail-safe
   (§2.1) — if it costs, it can be dropped for the `h0 === earlyRobId` match alone
   with only a loss of defence in depth.
3. Everything else is register-to-register or a two-input gate on an already-existing
   frontend flush term and should be free.

For calibration: the *reverted* early-flush cost 11.05 MHz (175.25 → 164.20), and
that variant put a combinational execute→flush path into the design. This one does
not — `earlyFire` and `earlyPend` are both registers, and no new signal reaches
`flushing`. The expectation is therefore materially smaller, but it is an
expectation, not a measurement, and it is recorded as such.

Note that constraint fixes landed as `1cc57891`, so any pre-existing FMax figure in
older docs predates them and is not a valid comparison point either.

---

## 9. Verdict and sequencing

**Keep.** The two-tier split is correct, is measurably not the reverted mechanism,
and delivers a real if modest aggregate gain that scales cleanly with the one
quantity it targets.

But the honest framing of the size matters more than the sign:

* On the standing 12-kernel harness the gain is **+1.24 % aggregate**, and the
  headline `branchy` number is **+1.97 %**, not the double-digit figure the framing
  around "refetch latency is the dominant idle term" might suggest. The dominant
  term is real (14 cycles of the ~21-cycle average mispredict cost on `branchy`),
  but only the *resolve-to-retire* slice of it is reachable by an early redirect,
  and on `branchy` that slice is ~1 cycle out of 14.
* The +10.23 % on `deep-backlog` is the mechanism's true shape, on a workload built
  to expose it. Whether real 68k code looks more like `branchy` or more like
  `deep-backlog` is not answered here and should not be assumed. The boot ROM's
  VIA1 shift-register driver — the workload that started this whole campaign — is
  the obvious candidate to measure next, and it is measurable now, because the
  resolve→retire histogram is a landed, arm-agnostic instrument.

**Re-sequencing `2026-09-03-branch-checkpoint-rat-freelist-design.md`:** its
remaining upside is the ~2 retire cycles a narrow squash saves, on top of this. The
`branchy` data sharpens that: with the branch already at the head 19 times out of 21
when it resolves, there is very little for a narrow squash to preserve there either.
The checkpoint design's own S5 gate ("`branchy`/`hot-loop` IPC must improve") should
be measured against **this** design as the baseline, on `deep-backlog` as well as
`branchy`, and it now has a harder bar to clear.

**The cheapest remaining win in this area is not the checkpoint at all** — it is
§6.3's early predictor update, which would recover the two ~3.5 % regressions and
plausibly more. It is one signal and one `when`, versus a multi-day checkpoint
project, and it should be measured first.
