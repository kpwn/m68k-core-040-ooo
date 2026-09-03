# EU-resolution-time early branch-mispredict flush — IPC A/B measurement

**Date:** 2026-09-03
**Repo / branch:** `m68k-core-040-ooo`, `fmax-closure-fanout`
**Status:** MEASUREMENT ONLY. No RTL was changed, reverted or re-landed by this
document's session. It reports numbers and a recommendation; the keep/revert
decision is the project owner's.

**Arms measured (separate `git worktree`s, shared tree untouched):**

| arm | tree | contents |
|---|---|---|
| **WITH** | `fmax-closure-fanout` HEAD `a5b0d22` (identical snapshot on `archive/early-flush-eu-resolution`) | `84b15a9` early-flush + `34330db` tests + `a5b0d22` SQ/LsEu fix |
| **WITHOUT** | `c807e23` | pre-early-flush baseline |

`git diff c807e23 55b003b` is **empty** — the branch's own revert chain
(`2979ea9`/`3f5b774`/`55b003b`) reproduces `c807e23` byte-identically, so
`c807e23` is a verified-clean baseline for this A/B.

---

## 0. TL;DR

* **The mechanism genuinely engages.** This is *not* a null/insensitive result.
  `EarlyBranchFlushSpec` passes on the WITH arm, and the new per-kernel
  telemetry shows `earlyBranchMispredict` firing on 8 of 13 kernels (29 pulses
  on `branchy`, 28 on `deep-backlog`).
* **It is a large IPC REGRESSION, not a win.** `branchy` **−44.9 %**
  (IPC 0.508 → 0.280), the purpose-built deep-backlog kernel **−25.9 %**
  (0.440 → 0.326), 13-kernel aggregate **−7.1 %** (0.646 → 0.600).
* Results are **bit-identical run-to-run on both arms**, so these deltas carry
  no run-to-run noise.
* Against the already-measured ~11 MHz FMax cost (175.25 → 164.20 MHz, −6.31 %),
  **delivered performance (IPC × frequency) is −13.0 % aggregate and −48.4 % on
  the branch-heavy kernel.** Both terms are negative; there is no trade to weigh.
* **New, independent correctness finding.** The `investigate/bsr-flush-skip`
  directed campaign (139 lock-step programs, 17 ScalaTest cases) is **17/17 PASS
  on the WITHOUT arm** and **6/17 with 69 `DIVERGED[HANG]` events on the WITH
  arm**. Zero architectural mismatches — the failures are *hangs*, matching the
  "KNOWN OPEN GAP … chained-early-flush StoreQueue wedge" that `RobPlugin.scala`'s
  own comment already documents as unfixed.
* **Recommendation: REVERT.**

---

## 1. Methodology, and why the sensitivity question had to be settled first

The standing warning against this harness is real: an earlier session A/B'd a
different change with `IpcBenchSpec` and got byte-identical results across all
12 kernels in all 4 runs, because the microkernels were front-end-throughput-
bound and simply insensitive to that change; and today's T1 telemetry work
(cpu040 `3a35bcfd`) found these kernels are dominated by cold-start compulsory
fills rather than steady-state control-flow behaviour. A null here would
therefore have been *inconclusive*, not negative.

Early-flush's benefit scales with **how much ROB backlog sits ahead of the
mispredicting branch at EU-resolution time**. That quantity had never been
measured, so it was measured directly.

### 1.1 Instrumentation added (test-harness only, identical on both arms)

`IpcBenchSpec.scala` was extended in **both** worktrees with counters read off
signals that are already `simPublic()` **on both arms** (`doFlushReg`,
`branchRedirect`, `branchCompletion`, `head`, `count`). No RTL was touched, so
each arm still simulates exactly its committed netlist. The two patched
harnesses differ by exactly **one line** — the `earlyBranchMispredict` tap,
which only exists on the WITH arm:

```scala
if (dut.rob.logic.earlyBranchMispredict.toBoolean) earlyFlushes += 1   // WITH only
```

Per kernel it now reports: flush pulses, retire-gated redirects, early-flush
pulses, branch completions, mispredicts, mean/max ROB occupancy, and — the key
metric — a **histogram of `(branchCompletion.robId - head) & 63` sampled on
every cycle a branch resolves mispredicted**, i.e. the number of strictly older,
not-yet-retired ROB entries ahead of that branch.

### 1.2 Run protocol

`IPC_SEED=860374149` (Part 61's seed) pinned on every run; zero-latency memory
model (Part 61's config) plus a second sweep at `IPC_MEM=l2:5:70`; every run
serialised to one JVM, started only after the concurrent Vivado `full_impl`
build had exited.

---

## 2. Sensitivity result — all three checks were completed

### 2.1 Check 1: the stock kernels' real backlog (WITHOUT arm)

| kernel | mispredicts | mean entries ahead | max | histogram |
|---|---|---|---|---|
| `branchy` | 22 | **2.05** | 3 | `2:21, 3:1` |
| `hot-loop` | 2 | 1.00 | 1 | `1:2` |
| `call-return` | 6 | 3.17 | 8 | `0:1, 1:1, 2:2, 6:1, 8:1` |
| `shift-mixed` | 2 | 9.50 | 10 | `9:1, 10:1` |
| `load-stream` | 2 | 13.00 | 13 | `13:2` |

**The concern was justified.** `branchy` — the kernel Part 61 nominated as the
one that "should be sensitive if the mechanism works at all" — carries only
**2.05** older entries ahead of its mispredicting branch. A *narrow* early flush
could save at most ~2 retire cycles per mispredict there. The kernels with a
genuinely deep backlog (`load-stream`, `shift-mixed`) mispredict only twice each
in the whole run, so they cannot resolve the effect either.

### 2.2 Check 2: does a deeper `IPC_MEM` deepen the backlog?

**No.** `branchy`, `hot-loop` and `deep-backlog` perform **zero data-memory
accesses** — they are I-cache-resident tight loops — so `IPC_MEM=l2:5:70`
reproduces their numbers *exactly* (identical cycles, identical IPC, identical
backlog histograms). `IPC_MEM` does move the memory-bound kernels (`mixed`
0.526 → 0.405, `call-return` 0.330 → 0.272), confirming the setting is live; it
simply cannot sensitise the branch-recovery path. This is why check 3 was needed.

### 2.3 Check 3: a purpose-built deep-backlog kernel — **SYNTHETIC, not representative**

`deep-backlog` was added to both harnesses identically. Per iteration:

* **5 dependent slow-path shifts** on `d2` (~8-9 cycles dependent-use latency
  each) — these **pin the ROB head** for ~40+ cycles while in-order retire waits.
* **12 independent `add.l %d1,%aN`** — ADDA does **not** write NZVC, so they
  neither depend on nor disturb the branch's flag producer; each completes in a
  cycle and then simply *sits* in the ROB, completed-but-unretired.
* the branch's own flag producer (`add`/`and` on `d6`/`d4`, alternating 1,0,1,0
  exactly like `branchy`), dependent on nothing in the chain — so the `Bcc`'s
  operands are ready almost immediately.

Measured on the WITHOUT arm: **mean 15.70 entries ahead, max 18**
(`15:5, 16:4, 18:1`) over 10 mispredicts — a 7.7× deeper backlog than `branchy`.
The harness *can* now resolve the effect.

> This kernel is a deliberately constructed best case for the *narrow* per-branch
> flush Part 61 described. For the *degenerate* variant that actually landed
> (§4) a deep backlog is the **worst** case, because the backlog is exactly what
> gets discarded and re-executed. Both readings are reported below.

---

## 3. The A/B result

`IPC_SEED=860374149`, zero-latency memory model, all 13 kernels.

| kernel | IPC without | IPC with | ΔIPC | cycles wo → wi | Δcyc | active% wo → wi |
|---|---|---|---|---|---|---|
| dependent-ALU | 1.002 | 1.002 | +0.00 % | 401 → 401 | +0.00 % | 100.0 → 100.0 |
| independent-ALU | 2.000 | 2.000 | +0.00 % | 214 → 214 | +0.00 % | 100.0 → 100.0 |
| load/store | 0.246 | 0.246 | +0.00 % | 1182 → 1182 | +0.00 % | 24.5 → 24.5 |
| load-stream | 0.697 | 0.719 | +3.16 % | 690 → 669 | −3.04 % | 62.2 → 62.9 |
| store-stream | 1.027 | 0.992 | −3.41 % | 475 → 492 | +3.58 % | 77.1 → 74.6 |
| same-line-copyback | 0.853 | 0.924 | +8.32 % | 286 → 264 | −7.69 % | 64.0 → 69.3 |
| shift-stream | 0.974 | 0.940 | −3.49 % | 500 → 518 | +3.60 % | 60.8 → 58.7 |
| shift-mixed | 1.730 | 1.606 | −7.17 % | 467 → 503 | +7.71 % | 95.1 → 88.5 |
| **branchy** | **0.508** | **0.280** | **−44.88 %** | 443 → 804 | **+81.49 %** | 41.3 → 24.1 |
| hot-loop | 1.283 | 1.232 | −3.98 % | 315 → 328 | +4.13 % | 95.9 → 92.1 |
| mixed | 0.526 | 0.526 | +0.00 % | 620 → 620 | +0.00 % | 39.2 → 39.2 |
| call-return | 0.330 | 0.337 | +2.12 % | 2443 → 2394 | −2.01 % | 25.7 → 26.1 |
| **deep-backlog** (synthetic) | **0.440** | **0.326** | **−25.91 %** | 978 → 1319 | **+34.87 %** | 29.1 → 21.6 |
| **AGGREGATE** | **0.646** | **0.600** | **−7.12 %** | 9014 → 9708 | +7.70 % | |

Under `IPC_MEM=l2:5:70` (7-kernel subset) the aggregate delta is **−10.22 %**
(0.489 → 0.439); `branchy`, `hot-loop` and `deep-backlog` are numerically
identical to the zero-latency run, per §2.2.

**Run-to-run variance: zero.** Each arm was run twice at the same pinned seed and
the per-kernel result rows are **byte-identical** between runs. That is this
harness's normal behaviour with `IPC_SEED` pinned, and it means even the small
single-digit deltas above are real rather than noise.

The scattered small positives (`same-line-copyback` +8.3 %, `load-stream`
+3.2 %) are second-order: those kernels mispredict only twice in the whole run,
so the early flush merely shifts *when* one flush lands relative to a store
drain. They are not a branch-recovery win.

---

## 4. Why it regresses — the telemetry names the mechanism

The variant that landed is **not** the narrow per-branch squash. It is the
**degenerate variant Part 61 itself identified and set aside**:

```scala
when(earlyBranchMispredict) { flushPcReg := committedResumePc }
```

The redirect target is the **current committed PC**, never the branch's own
resolved target — necessarily so, because `flushing` drives `RatTable`/`Freelist`'s
single *global* "restore to committed shadow" rollback with no per-`robId`
selectivity. So the flush discards **everything in flight, including every
instruction OLDER than the branch**, and re-fetches from the committed point —
re-executing the whole backlog *and the mispredicting branch itself*.

Part 61 predicted the consequence in words ("discarding strictly MORE legitimate
in-flight work than today's design and delivering no real latency win — the
redone work is now larger, not smaller"). The telemetry now quantifies it:

| kernel | flush pulses wo → wi | retire-gated wo → wi | early (wi) | mispredicts wo → wi |
|---|---|---|---|---|
| `branchy` | 21 → **46** | 21 → 17 | 29 | 22 → **46** |
| `deep-backlog` | 10 → **35** | 10 → 7 | 28 | 10 → **35** |

Mispredict counts **more than double** (`branchy` 2.1×) and **triple** on
`deep-backlog` (3.5×). The backlog histogram shows exactly where the extra ones
come from:

```
branchy       WITHOUT  n=22  avg=2.05  2:21, 3:1
branchy       WITH     n=46  avg=0.85  0:16, 1:22, 2:7, 3:1
deep-backlog  WITHOUT  n=10  avg=15.70 15:5, 16:4, 18:1
deep-backlog  WITH     n=35  avg=9.43  0:5, 1:5, 2:2, 9:6, 15:7, 16:9, 18:1
```

The baseline's single population at *ahead = 2* (branchy) / *ahead = 15-18*
(deep-backlog) gains a whole new population at **ahead = 0 and 1** — those are
the *re-executions* of the same branch after it was flushed back to the committed
PC and re-fetched, now resolving when it is already at (or one behind) the head.
Each original mispredict has become roughly **two flushes plus a full replay of
the backlog**. `branchy`'s window grows 443 → 804 cycles for the identical 225
retired instructions.

The early BTB/gshare training the mechanism adds does not offset this — the
predictor is trained earlier, but the branch is re-executed anyway.

---

## 5. Independent correctness check — a serious, separate finding

The `investigate/bsr-flush-skip` campaign (commit `45bc9a6`, unmerged; 139
directed Musashi lock-step programs across 16 flush shapes, with a non-vacuity
probe) was written and validated against the **no-early-flush** baseline. Its
test files (test-only: one spec + four `.s` programs) were applied unmodified to
both arms and run.

| arm | result | hangs | architectural mismatches |
|---|---|---|---|
| **WITHOUT** (`c807e23`) | **17/17 PASS** | **0** | **0** |
| **WITH** (`a5b0d22`) | **6/17 pass, 11 FAIL** | **69 × `DIVERGED[HANG]`** | **0** |

Failing shapes span cold `Bcc` → `bsr`, absolute/indirect `jsr`, deep
late-resolving windows, an older mispredicting `rts`, two stacked mispredicts,
cold/far callees, squashed speculative stores, and the cross-seed stability case.
Typical signature: `bcc_bsr_k10 DIVERGED[HANG]: only 12/17 instructions committed
within 4360 cycles`. (2 of the 11 failures are the non-vacuity probe reporting
"NO commit-time `branchRedirect`" — that one is an instrumentation mismatch, not
a bug: the flush now fires through `earlyBranchMispredict` instead. The other 69
events are genuine hangs.)

Zero mismatches means the mechanism does not produce *wrong* architectural
results when it completes — it **wedges**. That is exactly the failure class
`RobPlugin.scala`'s own in-tree comment already flags as **unfixed**:

> "(5) KNOWN OPEN GAP, NOT FIXED THIS SESSION: chained-early-flush StoreQueue
> wedge… once a SECOND early flush lands shortly after a first one… retire STOPS
> PERMANENTLY… This is a PERMANENT state wedge, not a timing race."

This measurement independently confirms that gap is not narrow: it reproduces
across 69 directed programs and 8 distinct flush shapes on a corpus that is 100 %
clean without the mechanism.

**`make test-fast` on the WITH arm: 336 succeeded / 1 failed** — the single
failure is `RobPluginSpec`'s "preciseDrainBusyIn holds a `debugPcApply` off an
in-flight precise drain", the known pre-existing seed-dependent flake documented
in Part 61 §4. So `fastTest` alone does **not** surface the wedge; it takes the
deep flush-shape corpus.

---

## 6. Verdict

Delivered performance is IPC × frequency. Using the already-measured same-flow
A/B synth result (WNS −0.706 ns / 175.25 MHz baseline vs −1.090 ns / 164.20 MHz
with the fix — a 0.93695× frequency factor):

| workload | ΔIPC | Δfrequency | **Δdelivered performance** |
|---|---|---|---|
| `branchy` (branch-heavy) | −44.88 % | −6.31 % | **−48.4 %** |
| `deep-backlog` (synthetic deep ROB backlog) | −25.91 % | −6.31 % | **−30.6 %** |
| aggregate, 13 kernels, zero-latency | −7.12 % | −6.31 % | **−13.0 %** |
| aggregate, 7 kernels, `l2:5:70` | −10.22 % | −6.31 % | **−15.9 %** |

The framing in the dispatch — "an IPC gain below roughly 6.3 % is net-negative,
above it net-positive" — does not apply, because **there is no IPC gain to
weigh**. Both terms are negative and they compound.

**Is the FMax loss recoverable?** Probably (the prior analysis traces both worst
paths to a pre-existing, unrelated DcachePlugin-adjacent congestion cone, and
area cost is tiny at +310 LUTs / −1 CARRY8). **It does not matter.** Even with
the full 11 MHz restored, the IPC term alone is −7.1 % aggregate / −44.9 % on
branch-heavy code, so the change stays net-negative.

**RECOMMENDATION: REVERT** `84b15a9` / `34330db` / `a5b0d22`.

Justification, in order of weight:

1. Its original justification (Part 29's boot livelock) is already disproven
   (Part 112).
2. It cannot justify itself on performance: it is a −7 % to −45 % IPC regression,
   measured, deterministic, and causally explained.
3. It introduces 69 reproducible hangs on a directed corpus that is 100 % clean
   without it — a strictly worse correctness posture, in the exact failure class
   its own source comment flags as unfixed.

The clean-revert path already exists and is proven byte-identical
(`2979ea9`/`3f5b774`/`55b003b` → `c807e23`).

---

## 7. What this does *not* say, and what to do instead

* This says **nothing negative about the properly-scoped fix**. Part 61's
  finding stands: the real mechanism needs per-branch (or bounded-N)
  RAT/Freelist checkpoint-and-restore, already scoped in
  `docs/superpowers/specs/2026-09-03-branch-checkpoint-rat-freelist-design.md`.
  Only the degenerate committed-PC shortcut is being rejected here — and it is
  being rejected on exactly the grounds Part 61 predicted for it.
* **A calibration for that design's payoff, now measured rather than assumed.**
  That document's §5.4 justifies the work from `branchy`'s 58.7 % backend idle.
  That number is right, but the *reachable* part of it is smaller than it looks:
  `branchy`'s real backlog ahead of the mispredicting branch is only **2.05
  entries**, so a perfect narrow squash can recover at most ~2 retire cycles per
  mispredict there. Most of `branchy`'s idle time is refetch/refill latency,
  which no checkpoint scheme removes. The checkpoint design's S5 gate ("compare
  `branchy`/`hot-loop` IPC … must improve, not merely hold even") should
  therefore be measured on a **deep-backlog** kernel as well, or it risks
  reading a genuine improvement as a null.
* **The measurement apparatus is reusable and is the concrete deliverable to
  carry forward**: the mispredict-ahead histogram plus the `deep-backlog` kernel
  are what turned an ambiguous null into an attributable result here, and are
  exactly what S5 needs. They live as a 91-line, RTL-free patch to
  `IpcBenchSpec.scala` (reproduced in this session's scratch as
  `patch_ipc.py`); landing them into the shared harness is recommended as a
  small follow-up, deliberately not done here to avoid colliding with concurrent
  work on the shared tree.
* The `investigate/bsr-flush-skip` campaign proved its own point (a flushed
  `bsr`/`jsr` on the retire-gated design is **always** re-executed) and has now
  also proved itself a high-value *regression* gate for any future change to the
  flush path. It is worth merging on that basis alone.

---

## 8. Reproduction

```bash
git worktree add --detach <dir>/wt-with    a5b0d22
git worktree add --detach <dir>/wt-without c807e23
# apply the identical IpcBenchSpec instrumentation + deep-backlog kernel to both
# (the only inter-arm difference is the earlyBranchMispredict tap, WITH only)
export SBT_OPTS="-Xmx8g -Xss16m -XX:MaxMetaspaceSize=1g"   # stock heap OOMs on a cold worktree
IPC_SEED=860374149 sbt "testOnly m68k040.bench.IpcBenchSpec"                     # zero-latency
IPC_SEED=860374149 IPC_MEM=l2:5:70 sbt "testOnly m68k040.bench.IpcBenchSpec"     # L2-faithful
sbt "testOnly m68k040.fuzz.BsrFlushSkipSpec"   # after applying 45bc9a6's test files
```

Machine discipline observed: every JVM run serialised (one at a time), all of it
started only after the concurrent Vivado `full_impl` build exited; no other
process was killed or disturbed.
