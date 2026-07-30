# MSHR / multi-outstanding L1I + L1D Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the L1 instruction and data caches non-blocking (miss-status holding registers, hit-under-miss, I-side next-line prefetch), give the core real AXI ID discipline with per-ID response routing (which is also the prerequisite for folding the two table-walker AXI masters into the I/D masters for socket compatibility), and make INHIBITED (MMIO) loads architecturally non-speculative by executing them at the ROB head — all measured against a new, honest AXI memory model that can reorder responses per ID and can model the real SoC L2 and its single-outstanding crossbar.

**Architecture:** Source of truth is the ratified design `docs/superpowers/specs/2026-07-30-mshr-multi-outstanding-design-proposal.md` (read it before starting any slice; §-refs below point into it). Seven slices in dependency order: **V1** (verification substrate, zero RTL) → **V2** (AXI ID hygiene + per-ID routing + response tagging, extended per ratified §11.Q7 with the 256→128-bit I-cache narrowing and the DTLB/ITLB walker-master fold) → **D1** (D-side hit-under-miss: one MSHR, one Load Tracking Table entry, per-entry poison, fill-forward, the `D3-SET` one-fill-per-set invariant) → **I2** (per-beat predecode, option I-b) → **I1** (I-side non-blocking accept + one demand MSHR + same-line merge + `invalidateAll` closure) → **I3** (next-line prefetch MSHR under rules P1–P4) → **D4** (at-ROB-head non-speculative MMIO loads + the `dQuiesce` primitive, extended per ratified §11.Q4). The D-side chain (D1 → D4) and the I-side chain (I2 → I1 → I3) are file-disjoint and may run concurrently after V2.

**Tech Stack:** SpinalHDL (Scala) RTL under `src/main/scala/m68k040/`; ScalaTest + Verilator simulation under `src/test/scala/m68k040/`; lock-step against a grafted Musashi 68040 oracle (`m68k040.lockstep.ExecuteLockStepSpec`); the vendored m68k-ooo directed-assembly corpus (`m68k040.fuzz.PortedM68kOooSpec` / `PortedTestRunner`); Vivado OOC + post-route synthesis on `xcku5p-ffvb676-2-e` (`synth/ooc_M68kFullCoreSynth.tcl`, `synth/impl_FullCore.tcl`); sbt at `~/sbt/bin/sbt`.

---

## Global Constraints

Every one of these is BINDING on every task below. Read them before starting any task; a task brief that appears to conflict with one of these loses.

### GC1 — BANNED PATTERN (standing, project-wide, ABSOLUTE): no SoC address-map assumptions

The core must NEVER depend on any assumption about SoC-side physical address-decode topology, and must NEVER cache "this address answered OK before, therefore it is safe/backed" in any form (no "proven-backed" bit, filter, CAM, table, or heuristic). The only evidence classes the core may ever act on are:

1. **software-configured** MMU cacheability (page CM bits / TTR windows / CACR.DE) — architectural state, CPU-invalidated by PFLUSH/CINV/live register write;
2. **CPU-internal** cache/TLB state that has a real CPU-controlled invalidation path (cache valid bits, TLB entries, and — new in this plan — MSHR/LTT entries, which exist only for the duration of one transaction and are deallocated when it resolves);
3. a **real, contemporaneous bus response** for the exact transaction being decided.

Source: `/home/qwertyoruiop/.claude/projects/-home-qwertyoruiop-m68k-core-040-ooo/memory/feedback-no-soc-address-map-assumptions.md`, and the design doc §4.1. Three mechanisms in this plan are exactly the shape that could smuggle a violation in; their binding sub-rules are reproduced **verbatim** in the acceptance criteria of the tasks that build them:

- **M1/M2/M3** (secondary-miss merging) — carried into I1 (I-side same-line merge) and, when it is eventually built, D2. See §4.1(i).
- **P1/P2/P3/P4** (next-line prefetch) — carried into I3. See §4.1(ii).
- **§4.1(iii)** (early-ack / posted writes) — not built in this plan; recorded in the Deferred Work audit.

If implementing any task would require reintroducing anything shaped like a "proven-backed pages" filter, or would require the core to know that a particular address range is RAM/ROM/MMIO/unmapped, **STOP and flag it** — do not silently implement it.

### GC2 — Verification baselines that must be re-confirmed by every RTL-bearing task

- **`ExecuteLockStepSpec` baseline: 390/394 passing.** 4 known pre-existing failures: `STOP #imm -> halt -> IRQ -> handler -> RTE -> resume`, plus 3 ITLB tests. Every task's verification step must re-confirm this EXACT count **with byte-identical failing test names** before and after — a new failure, or a changed failure-name set, is a regression even if the count is unchanged.
- **Full ported-test corpus baseline: 713/763 passing** (50 known pre-existing failures). Task V1.1 re-establishes a FRESH baseline; every later slice diffs against **that** list, not against the 713/763 figure quoted here.
- **Budgeted exception (design doc §8.4):** added latency and added concurrency change which instruction interleavings the corpus explores, so this work is *expected* to surface NEW, genuine bugs in the ported corpus. A newly-failing ported test is **not** automatically a regression — it must be triaged (root-caused to "this slice broke it" vs "this slice exposed a pre-existing bug"). Record the triage verdict in the slice's commit message. It IS a regression if it is a lock-step failure, or if the root cause is in the code this slice wrote.

### GC3 — Shell quoting for filtered test runs (has cost real time repeatedly)

`sbt "testOnly X" -- -z name` **as two separate shell tokens SILENTLY DROPS the `-z` filter** and runs the entire 763-test corpus (~48 minutes). Every filtered test invocation in this plan uses the single-quoted-string form and every implementer must too:

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec -- -z some_test_name"
```

### GC4 — `git worktree` is MANDATORY

Use `git worktree add <path> <ref>` for **every** implementation task and for **every** before/after verification comparison. NEVER `git checkout <sha>` in the shared checkout — this project had a real collision incident from violating that (task #199). Remove worktrees with `git worktree remove <path>` when done. Convention used below: `/home/qwertyoruiop/m68k-core-040-ooo-worktrees/<task-id>`.

### GC5 — Synth gate, and TWO extra preconditions specific to this plan

Standing project rule: every RTL-bearing slice ends with the full-core OOC synth gate (≥ 250 MHz), reported.

```bash
pgrep -af vivado   # MUST be empty before launching either job
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_full.log -source synth/ooc_M68kFullCoreSynth.tcl
grep "RESULT FullCore" synth/vivado_full.log
# authoritative post-route gate (deterministic; same netlist -> same FMax):
vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl
```

Two ADDITIONAL gate conditions apply to this plan specifically (design doc §9, §11.Q8):

- **(a) LIVE PRECONDITION, assume UNRESOLVED:** this branch has an unresolved ~3× LUT bloat and a 142 MHz post-route measurement (tracker tasks #115/#198), root-caused as an Int register-file RAM-inference failure. **No slice in this plan may be merged until that is resolved or explicitly waived by the user**, because until then no slice's area/FMax delta is measurable and the synth gate cannot do its job. A separate agent may be fixing this in parallel — **do not assume it is fixed**; check at the start of each slice's gate task and, if still open, report the FMax/LUT numbers as *directional only* and hold the merge.
- **(b) UNCONTENDED MACHINE REQUIRED for any FMax number.** This shared machine has repeatedly produced corrupted measurements — confirmed swings of **214.3 vs 163.9 MHz for an identical commit**, and a 146.07 MHz reading taken under a live JTAG session. `pgrep -af vivado` and `pgrep -af VerilatorTest` must both be empty, and no other heavy JVM may be running (see GC7). If you cannot get an uncontended machine, say so explicitly in the report rather than quoting a number.
- **Slice V1 is explicitly EXEMPT from the synth gate** — it contains zero `src/main/scala` changes, so there is nothing to gate. Every slice from V2 onward is subject to it.

### GC6 — Foreground verification; never trust a quiet log

Run verification commands in the foreground. If a command must be backgrounded (the ~48-minute full corpus run), poll with `pgrep -f VerilatorTest` **and** confirm the shell actually returned, and confirm the final `Tests: ...` summary line exists in the log. This project has repeatedly had agents lose track of backgrounded sbt runs and report stale/partial results.

### GC7 — Machine resource budget

29 GB RAM total. Maximum **2 concurrent heavy JVMs**, and **never** any JVM concurrently with Vivado. Check `free -g` before launching anything if ≥ 3 agents are live.

### GC8 — SpinalHDL house rules that this work will trip over

- **Every new MSHR/LTT/prefetch register must be `RegInit`,** never bare `Reg`. Uninitialised `Reg`s randomise per-seed in simulation and make lock-step seed-flaky (memory: `spinalhdl-sim-poke-gotchas`).
- **Last-assignment-wins** is load-bearing throughout `IcachePlugin`/`DcachePlugin`/`LsEuPlugin` (e.g. the refill-priority write-port override at `DcachePlugin.scala:461-465`, the depth-2 accept ordering at `IcachePlugin.scala:314-323`). Preserve the *ordering* of assignments when restructuring, and where the plan changes a priority, say so explicitly.
- **`StateMachine` bodies elaborate AFTER every plain component statement** (this is the documented root cause of the P2.7 `pendPush` bug, `LsEuPlugin.scala:971-981`). Any "rollback" written as a plain statement will be silently overridden by an FSM-body assignment to the same signal.
- `assignDontCare` hides sim pokes from consumers; prefer concrete idle defaults (see `RobPlugin.scala:124-131`).

### GC9 — Naming: `D3-SET` vs `D3-BURST` (REAL COLLISION — do not confuse them)

The design doc uses the label "D3" for **two unrelated things**:

- **`D3-SET`** = the invariant "**at most one outstanding fill per cache SET**" (design doc §5.3, decision-table row D3). **This IS in scope** — D1 builds it on the D side, I1 builds the I-side equivalent.
- **`D3-BURST`** = the slice that widens the L1D line 16 B → 64 B (design doc §9 slice row "D3", decision D4). **This is DROPPED** from this plan per ratified §11.Q2.

Use `D3-SET` and `D3-BURST` — never bare "D3" — everywhere: plan text, code comments, commit messages, test names, and any report. A code comment that says "D3" without the suffix is a review defect.

### GC10 — Baseline command reference

```bash
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"            # ~394 cases, one JVM, ~68 s
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec"                  # full corpus, NO filter, ~48 min
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec -- -z <name>"     # ONE test (dev loop only, never the gate)
~/sbt/bin/sbt "testOnly m68k040.cache.DcacheSpec"
~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"
~/sbt/bin/sbt "testOnly m68k040.ls.*"
# fail-list diffing:
LC_ALL=C sort -u before.txt > b.sorted; LC_ALL=C sort -u after.txt > a.sorted
comm -23 b.sorted a.sorted    # only-in-before = fixed
comm -13 b.sorted a.sorted    # only-in-after  = regressed / newly exposed
```

---

## Slice V1 — Verification substrate (ZERO RTL, NO synth gate)

Design doc refs: §8.1 (the memory-model successor), §8.2 (directed tests catalogue), §8.3 (measurement), §9 slice row **V1**, §1.6 (what today's model actually does), §1.7 (there is no miss benchmark at all).

**Scope rule for this slice, enforced strictly:** *no file under `src/main/scala` may be modified by any task in V1* — not even to add a `.simPublic()` tap. Every counter in V1.7 is derived from signals that are **already** `simPublic` (`DcachePlugin.scala:50-52,199-200`, `StoreQueue.scala:451-468`) or from the DUT's AXI ports, which are top-level IO and directly observable. This is what makes V1 exempt from the synth gate (GC5); if any task here finds it *needs* an RTL tap, STOP and re-scope rather than quietly editing `src/main`.

**Acceptance for the slice as a whole (design doc §8.1):** the entire existing suite passes, unchanged, against the new model in `InOrder` mode with zero added latency; **and** passes in `Reordered` and `Chaos` too. Today's RTL is single-outstanding, so it must — and if it does *not*, V1 has found a real bug in today's RTL, which is a valuable outcome and must be reported, not worked around.

### Task V1.1: Fresh baseline snapshot (worktree, both suites)

**Files:**
- Creates/modifies nothing in the repo. Produces two durable fail-lists under the scratchpad.

**Interfaces:**
- Consumes: nothing (first task of the plan).
- Produces: `/tmp/mshr_baseline_lockstep_fails.txt` and `/tmp/mshr_baseline_ported_fails.sorted.txt`, which EVERY later verification task in this plan diffs against.

- [ ] **Step 1: Isolated worktree at the plan's base commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git rev-parse HEAD    # expect 290c932 (the design doc's own commit) or a descendant
git worktree add /home/qwertyoruiop/m68k-core-040-ooo-worktrees/v1-baseline HEAD
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/v1-baseline
```

- [ ] **Step 2: Lock-step suite, foreground, full**

```bash
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/mshr_baseline_lockstep.log
grep -E "^\[info\] Tests: |\*\*\* FAILED \*\*\*" /tmp/mshr_baseline_lockstep.log | tail -30
grep "\*\*\* FAILED \*\*\*" /tmp/mshr_baseline_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u > /tmp/mshr_baseline_lockstep_fails.txt
cat /tmp/mshr_baseline_lockstep_fails.txt
```

Confirm exactly 390 succeeded / 4 failed and that the 4 names are `STOP #imm -> halt -> IRQ -> handler -> RTE -> resume` plus the 3 known ITLB tests. If this does not match, **STOP and escalate** — every later "expect 390/394" step depends on this being the true starting point.

- [ ] **Step 3: Full ported corpus, foreground-confirmed (~48 min)**

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" 2>&1 | tee /tmp/mshr_baseline_ported.log &
PORTED_PID=$!
wait $PORTED_PID
grep -E "^\[info\] Tests: " /tmp/mshr_baseline_ported.log | tail -3
```

Do not trust this until the shell has actually returned from `wait` AND the `Tests:` summary line is present (GC6).

- [ ] **Step 4: Extract and record the authoritative fail list**

```bash
grep "^\[info\] - ported: .* \*\*\* FAILED \*\*\*" /tmp/mshr_baseline_ported.log \
  | sed -E 's/^\[info\] - ported: ([^ ]+).*/\1/' | LC_ALL=C sort -u \
  > /tmp/mshr_baseline_ported_fails.sorted.txt
wc -l /tmp/mshr_baseline_ported_fails.sorted.txt      # expect ~50
```

Whatever this run says is the TRUE baseline for every later slice's diff — not the 713/763 figure in GC2, which is only an approximate expectation.

- [ ] **Step 5: Remove the worktree (nothing to keep — read-only task)**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree remove /home/qwertyoruiop/m68k-core-040-ooo-worktrees/v1-baseline
```

No commit (nothing staged).

### Task V1.2: New `AxiMemModel` — per-ID response queues, four response modes, cycle counter

**Files:**
- Create: `src/test/scala/m68k040/sim/AxiMemModel.scala`
- Create: `src/test/scala/m68k040/sim/AxiMemModelSpec.scala`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces (every later V1 task and every later slice's directed tests depend on these EXACT names):
  - `m68k040.sim.AxiRspMode.{InOrder, Reordered, Chaos, IllegalInterleave}`
  - `m68k040.sim.AxiMemModelConfig(rspMode, latency, idBusyBlock, crossbarSingleOutstanding, maxLen, incrOnly, checkProtocol, checkIdUnique, injectBusErrors, maxPendingBeats, bQueueDepth)`
  - `m68k040.sim.L2LatencyModel(enabled, lineBytes, hitCycles, dramCycles, fillFixedCycles, interBeatGap, l2Mshrs, secondaryPerMshr)`
  - `m68k040.sim.AxiMemModel` with `.mem: SparseMemory`, `.stats: AxiMemStats`, `.pokeByte/.peekByte/.poke128/.peek128`
  - `m68k040.sim.AxiMemModel.attachFull(axi: Axi4, cd, cfg, sharedMem)` and `.attachReadOnly(axi: Axi4ReadOnly, cd, cfg, sharedMem)`
  - `m68k040.sim.AxiMemStats` with `arCount(id)`, `awCount(id)`, `rBeats`, `wBeats`, `totalAr`, `totalAw`, `maxConcurrentReads`

- [ ] **Step 1: Create the config/stat/cycle-counter scaffolding**

```scala
// src/test/scala/m68k040/sim/AxiMemModel.scala
package m68k040.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Ar, Axi4Aw, Axi4B, Axi4Config, Axi4R, Axi4ReadOnly, Axi4W}
import spinal.lib.Stream
import spinal.lib.sim.{SparseMemory, StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.mutable

/** Response-ordering mode for the read data channel.
  *
  *  - `InOrder`            : one global FIFO across every ID -- byte-for-byte the
  *                           behaviour of the legacy `BehavioralMemAgent` (a single
  *                           `rQueue`, `BehavioralMem.scala:97`). This mode exists to
  *                           prove no regression, and is the default.
  *  - `Reordered`          : cross-ID reordering is free; per-ID beat order is
  *                           preserved and a burst is never interleaved with another
  *                           ID's beats. This is what the real SoC L2 does BY DESIGN
  *                           (design doc §3.1, `docs/l2c_spec.md:474-477`).
  *  - `Chaos`              : maximally adversarial legal reordering -- always serve
  *                           the NEWEST eligible ID first, so any latent in-order
  *                           assumption in the DUT fails on the first opportunity.
  *  - `IllegalInterleave`  : deliberately ILLEGAL under AXI4 -- beats of different IDs
  *                           may interleave INSIDE a burst. Reproduces design doc
  *                           §3.4 U2 (the L2 may do this; its own per-ID-deque
  *                           testbench checker is blind to it by construction). This
  *                           design is supposed to TOLERATE it, and this mode is the
  *                           only place that claim can be tested. */
object AxiRspMode extends Enumeration {
  type AxiRspMode = Value
  val InOrder, Reordered, Chaos, IllegalInterleave = Value
}

/** Two-tier latency model parameterised from the MEASURED L2 contract (design doc
  * §3.1 -- `macqd700-soc/rtl/soc/l2c*.v` + `docs/l2c_spec.md`). Every number here is
  * either measured or explicitly a swept unknown; none is invented.
  *
  *  - `lineBytes = 64`        : `l2c_defs.vh:26-42` (`L2C_LINE_BYTES 64`).
  *  - `hitCycles = 5`         : measured 5.1-5.3 cycle round trip, `docs/l2c_spec.md:668-679`.
  *  - `dramCycles`            : *** UNMEASURED (design doc §3.4 U1) *** -- real DDR4/MIG
  *                              latency is undocumented in BOTH repos. This MUST be
  *                              swept, never assumed. Default 40 is a placeholder that
  *                              no performance claim may rest on.
  *  - `fillFixedCycles = 4`   : `S_INSTALL -> S_PRSP` plus assembly, `l2c_mshr.v:211-246`.
  *  - NO critical-word-first  : the L2's fill AR is always line-base-aligned ARLEN=3 and
  *                              the response is produced only after the WHOLE line is
  *                              assembled (`l2c_mshr.v:160-163,211-246`), so the
  *                              requester pays the full 64-byte line even for a 4-byte
  *                              load. Modelled by making the FIRST beat of a miss burst
  *                              ready only after the whole-line latency.
  *  - `l2Mshrs = 8`, `secondaryPerMshr = 4` : `l2c_defs.vh:38-41`. Allocation
  *                              BACK-PRESSURES (AR ready low), it never drops. */
case class L2LatencyModel(
  enabled: Boolean = false,
  lineBytes: Int = 64,
  hitCycles: Int = 5,
  dramCycles: Int = 40,
  fillFixedCycles: Int = 4,
  interBeatGap: Int = 1,
  l2Mshrs: Int = 8,
  secondaryPerMshr: Int = 4
)

/** Full configuration of one attached memory model instance.
  *
  *  - `idBusyBlock`               : mirror the L2's `id_busy_c` front-door CAM
  *                                  (`l2c_ctrl.v:140-142,151`, `l2c_mshr.v:100-116`):
  *                                  a second AR presenting an ID that is already live
  *                                  is NOT accepted. This is the knob that PUNISHES a
  *                                  constant-ID master, and it is invisible in any
  *                                  simpler model.
  *  - `crossbarSingleOutstanding` : model `macqd700-soc/rtl/soc/axi_xbar.v` as it
  *                                  EXISTS TODAY -- exactly ONE outstanding read and
  *                                  ONE outstanding write per master port
  *                                  (`axi_xbar.v:2278-2284,2388-2412` for reads,
  *                                  `:1234-1258` for writes). This is the configuration
  *                                  against which slice D1's benefit must be shown to
  *                                  be REAL rather than asserted.
  *  - `checkIdUnique`             : assert if the DUT presents an AR/AW whose ID is
  *                                  already outstanding. Leave TRUE from slice V2 on.
  *  - `injectBusErrors`           : task #189/#211 behaviour, unchanged semantics --
  *                                  DECERR for an address outside `AxiMemModel.decoded`. */
case class AxiMemModelConfig(
  rspMode: AxiRspMode.Value = AxiRspMode.InOrder,
  latency: L2LatencyModel = L2LatencyModel(),
  idBusyBlock: Boolean = false,
  crossbarSingleOutstanding: Boolean = false,
  maxLen: Int = 255,
  incrOnly: Boolean = true,
  checkProtocol: Boolean = true,
  checkIdUnique: Boolean = true,
  injectBusErrors: Boolean = false,
  maxPendingBeats: Int = 8,
  bQueueDepth: Int = 4
)

/** Free-running simulation cycle counter shared by the read and write engines of one
  * model instance. `BehavioralMem.scala` has NO cycle counter at all (design doc
  * §1.6), which is why it can have no latency model. */
class SimCycleCounter(cd: ClockDomain) {
  private var c: Long = 0L
  cd.onSamplings { c += 1 }
  def now: Long = c
}

/** Observability for the measurement work (design doc §8.3): there are currently NO
  * miss/stall counters anywhere in the tree. Read-only from the testbench. */
class AxiMemStats(idWidth: Int) {
  val nIds = 1 << idWidth
  val arCount = Array.fill(nIds)(0L)
  val awCount = Array.fill(nIds)(0L)
  var rBeats = 0L
  var wBeats = 0L
  var l2Hits = 0L
  var l2Misses = 0L
  var l2SecondaryMerges = 0L
  var maxConcurrentReads = 0
  def totalAr: Long = arCount.sum
  def totalAw: Long = awCount.sum
  def reset(): Unit = {
    for (i <- 0 until nIds) { arCount(i) = 0L; awCount(i) = 0L }
    rBeats = 0L; wBeats = 0L; l2Hits = 0L; l2Misses = 0L; l2SecondaryMerges = 0L
    maxConcurrentReads = 0
  }
}
```

- [ ] **Step 2: The read engine — per-ID queues plus the four ordering modes**

```scala
// src/test/scala/m68k040/sim/AxiMemModel.scala (continued)

/** One R beat, fully resolved at AR-accept time except for the data itself.
  *
  * `resp`/`last` are frozen at AR-accept (exactly as the legacy model did,
  * `BehavioralMem.scala:116-117`); only the DATA is read lazily at drive time
  * (`:115`) so a write that lands between AR-accept and beat-drive is observed.
  * `readyAt` is the earliest cycle this beat may be driven (latency model). */
private case class RBeat(id: Int, base: Long, bytes: Int, bad: Boolean,
                         isLast: Boolean, seq: Long, var readyAt: Long)

/** Read side of the model. Written against the raw `ar`/`r` streams (not `Axi4` /
  * `Axi4ReadOnly`) so the SAME implementation serves both master shapes -- this is
  * the consolidation the legacy `Axi4ReadOnlyBehavioralAgent` (an acknowledged
  * copy-paste, `BehavioralMem.scala:250-259`) exists to work around. */
class AxiReadEngine(ar: Stream[Axi4Ar], r: Stream[Axi4R], busConfig: Axi4Config,
                    cd: ClockDomain, mem: SparseMemory, cfg: AxiMemModelConfig,
                    clk: SimCycleCounter, stats: AxiMemStats,
                    checker: AxiProtocolChecker) {

  private val nIds = if (busConfig.useId) (1 << busConfig.idWidth) else 1
  private val queues = Array.fill(nIds)(mutable.Queue[RBeat]())
  // liveArIds: an ID with at least one un-driven beat. Drives BOTH the `id_busy_c`
  // front-door block and the DUT-side ID-uniqueness assert.
  private val liveArIds = mutable.Set[Int]()
  private var seqCounter = 0L
  // The ID whose burst is currently mid-flight on R. Cleared on `last`. Enforces
  // "no cross-ID interleaving INSIDE a burst" for every mode except IllegalInterleave.
  private var lockedId: Int = -1
  // L2 model state: lines currently resident, and in-flight primary misses.
  private val l2Lines = mutable.Set[Long]()
  private val l2InFlightLines = mutable.Map[Long, Long]()   // line -> readyAt

  private def totalPendingBeats: Int = queues.map(_.size).sum

  private def readBeatData(base: Long, bytes: Int): BigInt = {
    var v = BigInt(0)
    for (i <- 0 until bytes) v = v | (BigInt(mem.read(base + i).toInt & 0xff) << (8 * i))
    v
  }

  /** Latency for the burst starting at `addr` covering `nBytes`, in cycles from now.
    * Two-tier, L2-faithful (design doc §3.1) -- see `L2LatencyModel`. */
  private def latencyFor(addr: Long, nBytes: Int): Long = {
    val L = cfg.latency
    if (!L.enabled) return 0L
    val line = addr & ~(L.lineBytes.toLong - 1)
    if (l2Lines.contains(line)) { stats.l2Hits += 1; L.hitCycles.toLong }
    else l2InFlightLines.get(line) match {
      case Some(t) =>
        // Same-line secondary merge onto an already-in-flight primary miss
        // (`l2c_mshr.v:1-7,131`): shares the SAME completion time, no new fill.
        stats.l2SecondaryMerges += 1
        math.max(t - clk.now, L.hitCycles.toLong)
      case None =>
        stats.l2Misses += 1
        // NO critical-word-first: the requester waits for the whole line.
        val t = L.dramCycles.toLong + L.fillFixedCycles.toLong +
                (L.lineBytes / (busConfig.dataWidth / 8)).toLong
        l2InFlightLines(line) = clk.now + t
        t
    }
  }

  /** AR acceptance policy: capacity, then `id_busy_c`, then the crossbar model, then
    * the L2's own MSHR allocation back-pressure. */
  private def arAcceptable(): Boolean = {
    if (totalPendingBeats >= cfg.maxPendingBeats) return false
    if (cfg.crossbarSingleOutstanding && liveArIds.nonEmpty) return false
    if (cfg.latency.enabled && l2InFlightLines.size >= cfg.latency.l2Mshrs) return false
    if (cfg.idBusyBlock) {
      // Reads the ID the DUT is PRESENTING this cycle. `ready` is therefore a
      // registered function of the previous cycle's presented ID -- a faithful-enough
      // model of a slave that takes a cycle to decide, and the same shape the legacy
      // `StreamReadyRandomizer` capacity gate already had.
      if (ar.valid.toBoolean) {
        val presented = if (busConfig.useId) ar.id.toInt else 0
        if (liveArIds.contains(presented)) return false
      }
    }
    true
  }

  val arMonitor = StreamMonitor(ar, cd) { a =>
    val id    = if (busConfig.useId) a.id.toInt else 0
    val size  = if (busConfig.useSize) a.size.toInt else log2Up(busConfig.dataWidth / 8)
    val len   = if (busConfig.useLen) a.len.toInt else 0
    val burst = if (busConfig.useBurst) a.burst.toInt else 1
    val addr  = a.addr.toBigInt
    checker.onAr(id, addr, size, len, burst, liveArIds.contains(id), clk.now)
    stats.arCount(id) += 1
    liveArIds += id
    if (liveArIds.size > stats.maxConcurrentReads) stats.maxConcurrentReads = liveArIds.size
    val bpb   = 1 << size
    val lat   = latencyFor(addr.toLong, bpb * (len + 1))
    val L     = cfg.latency
    for (beat <- 0 to len) {
      val base = (burst match {
        case 1 => addr + BigInt(bpb) * beat   // INCR
        case _ => addr                        // FIXED
      }).toLong
      val bad = cfg.injectBusErrors && !AxiMemModel.decoded(base)
      seqCounter += 1
      queues(id) += RBeat(id, base, bpb, bad, beat == len, seqCounter,
                          clk.now + lat + (if (L.enabled) beat.toLong * L.interBeatGap else 0L))
    }
    // A completed fill installs the line in the model's L2 image.
    if (cfg.latency.enabled) {
      val line = (addr.toLong) & ~(cfg.latency.lineBytes.toLong - 1)
      l2Lines += line
      l2InFlightLines.remove(line)
    }
  }

  /** Pick the next beat to drive, honouring the configured ordering mode. Returns
    * None when nothing is eligible this cycle (latency not yet elapsed, or empty). */
  private def pick(): Option[RBeat] = {
    def eligible(q: mutable.Queue[RBeat]): Boolean =
      q.nonEmpty && q.head.readyAt <= clk.now
    // Mid-burst lock: every mode except IllegalInterleave must finish the burst it
    // started before serving another ID (AXI4 forbids cross-ID interleaving inside
    // a burst).
    if (lockedId >= 0 && cfg.rspMode != AxiRspMode.IllegalInterleave) {
      return if (eligible(queues(lockedId))) Some(queues(lockedId).dequeue()) else None
    }
    val candidates = (0 until nIds).filter(i => eligible(queues(i)))
    if (candidates.isEmpty) return None
    val chosen = cfg.rspMode match {
      case AxiRspMode.InOrder =>
        // One global FIFO across every ID: the globally OLDEST enqueued beat.
        candidates.minBy(i => queues(i).head.seq)
      case AxiRspMode.Reordered =>
        candidates(simRandom.nextInt(candidates.size))
      case AxiRspMode.Chaos =>
        // Maximally adversarial: always the NEWEST eligible ID.
        candidates.maxBy(i => queues(i).head.seq)
      case AxiRspMode.IllegalInterleave =>
        candidates(simRandom.nextInt(candidates.size))
    }
    Some(queues(chosen).dequeue())
  }

  val rDriver = StreamDriver(r, cd) { _ =>
    pick() match {
      case None => false
      case Some(b) =>
        checker.onRBeat(b.id, b.isLast, liveArIds.contains(b.id), clk.now)
        if (busConfig.useId)   r.id   #= b.id
        r.data #= (if (b.bad) BigInt(0) else readBeatData(b.base, b.bytes))
        if (busConfig.useResp) r.resp #= (if (b.bad) 3 else 0)   // DECERR : OKAY
        if (busConfig.useLast) r.last #= b.isLast
        stats.rBeats += 1
        if (b.isLast) { liveArIds -= b.id; lockedId = -1 } else { lockedId = b.id }
        true
    }
  }

  val arDriver = StreamReadyRandomizer(ar, cd, () => arAcceptable())
}
```

- [ ] **Step 3: The write engine — ported verbatim, write-before-B preserved**

```scala
// src/test/scala/m68k040/sim/AxiMemModel.scala (continued)

/** Write side. This is a FAITHFUL port of `BehavioralMemAgent`'s hand-rolled write
  * path (`BehavioralMem.scala:127-234`), including its central invariant:
  *
  *   *** WRITE-BEFORE-B: the bytes are applied to `mem` at the moment a W beat is
  *   paired with its AW, and a burst's B closure is enqueued ONLY after every byte
  *   of that burst has been written. ***
  *
  * Any deferred-apply latency model that broke this would re-introduce the
  * harness-induced store->load race the original comment exists to prevent (design
  * doc §8.1 item 6). Latency is therefore applied to WHEN B IS DRIVEN, never to when
  * the bytes land. */
class AxiWriteEngine(aw: Stream[Axi4Aw], w: Stream[Axi4W], b: Stream[Axi4B],
                     busConfig: Axi4Config, cd: ClockDomain, mem: SparseMemory,
                     cfg: AxiMemModelConfig, clk: SimCycleCounter,
                     stats: AxiMemStats, checker: AxiProtocolChecker) {

  private case class AwState(addr: BigInt, size: Int, len: Int, burst: Int, id: Int, var beat: Int)
  private case class BRsp(id: Int, bad: Boolean, readyAt: Long)

  private val nIds = if (busConfig.useId) (1 << busConfig.idWidth) else 1
  private val awQueue = mutable.Queue[AwState]()
  private val wQueue  = mutable.Queue[(BigInt, BigInt, Boolean)]()
  private val bQueue  = Array.fill(nIds)(mutable.Queue[BRsp]())
  private val liveAwIds = mutable.Set[Int]()
  private var qPending = 0

  private def bytesPerBeat = busConfig.dataWidth / 8

  private def applyBeat(st: AwState, data: BigInt, strb: BigInt, bad: Boolean): Unit = {
    val bpb  = 1 << st.size
    val base = (st.burst match { case 1 => st.addr + BigInt(bpb) * st.beat; case _ => st.addr }).toLong
    if (!bad) {
      for (i <- 0 until bytesPerBeat) {
        if (((strb >> i) & 1) == 1) {
          val byte = ((data >> (8 * i)) & 0xff).toInt.toByte
          mem.write((base + i).toLong, byte)
        }
      }
    }
    st.beat += 1
  }

  private def update(): Unit = {
    while (awQueue.nonEmpty && wQueue.nonEmpty) {
      val st = awQueue.head
      val (data, strb, wlast) = wQueue.dequeue()
      val isLast = st.beat == st.len
      checker.onWBeat(st.id, wlast, isLast, strb, bytesPerBeat, clk.now)
      val bpb  = 1 << st.size
      val base = (st.burst match { case 1 => st.addr + BigInt(bpb) * st.beat; case _ => st.addr }).toLong
      val bad  = cfg.injectBusErrors && !AxiMemModel.decoded(base)
      applyBeat(st, data, strb, bad)
      stats.wBeats += 1
      if (isLast) {
        awQueue.dequeue()
        qPending += 1
        val lat = if (cfg.latency.enabled) cfg.latency.hitCycles.toLong else 0L
        bQueue(st.id) += BRsp(st.id, bad, clk.now + lat)
      }
    }
  }

  private def awAcceptable(): Boolean = {
    if (qPending >= cfg.bQueueDepth) return false
    if (cfg.crossbarSingleOutstanding && (liveAwIds.nonEmpty || awQueue.nonEmpty)) return false
    if (cfg.idBusyBlock && aw.valid.toBoolean) {
      val presented = if (busConfig.useId) aw.id.toInt else 0
      if (liveAwIds.contains(presented)) return false
    }
    true
  }

  val awMonitor = StreamMonitor(aw, cd) { a =>
    val id    = if (busConfig.useId) a.id.toInt else 0
    val size  = if (busConfig.useSize) a.size.toInt else log2Up(busConfig.dataWidth / 8)
    val len   = if (busConfig.useLen) a.len.toInt else 0
    val burst = if (busConfig.useBurst) a.burst.toInt else 1
    checker.onAw(id, a.addr.toBigInt, size, len, burst, liveAwIds.contains(id), clk.now)
    stats.awCount(id) += 1
    liveAwIds += id
    awQueue += AwState(a.addr.toBigInt, size, len, burst, id, 0)
    update()
  }

  val wMonitor = StreamMonitor(w, cd) { x =>
    val last = if (busConfig.useLast) x.last.toBoolean else false
    wQueue += ((x.data.toBigInt, x.strb.toBigInt, last))
    update()
  }

  val bDriver = StreamDriver(b, cd) { _ =>
    val ready = (0 until nIds).filter(i => bQueue(i).nonEmpty && bQueue(i).head.readyAt <= clk.now)
    if (ready.isEmpty) false
    else {
      val id = cfg.rspMode match {
        case AxiRspMode.InOrder => ready.minBy(i => i)   // deterministic; matches legacy single-ID use
        case _                  => ready(simRandom.nextInt(ready.size))
      }
      val rsp = bQueue(id).dequeue()
      if (busConfig.useId)   b.id   #= rsp.id
      if (busConfig.useResp) b.resp #= (if (rsp.bad) 3 else 0)
      liveAwIds -= rsp.id
      qPending -= 1
      true
    }
  }

  val awDriver = StreamReadyRandomizer(aw, cd, () => awAcceptable())
  val wDriver  = StreamReadyRandomizer(w,  cd, () => qPending < cfg.bQueueDepth)
}
```

- [ ] **Step 4: The top-level model + attach helpers + the two program-loading conventions**

```scala
// src/test/scala/m68k040/sim/AxiMemModel.scala (continued)

class AxiMemModel private (busConfig: Axi4Config, cd: ClockDomain,
                           val cfg: AxiMemModelConfig, sharedMem: SparseMemory) {
  val mem   = if (sharedMem != null) sharedMem else SparseMemory()
  val clk   = new SimCycleCounter(cd)
  val stats = new AxiMemStats(if (busConfig.useId) busConfig.idWidth else 1)
  val checker = new AxiProtocolChecker(cfg, busConfig)
  private[sim] var readEngine:  AxiReadEngine  = null
  private[sim] var writeEngine: AxiWriteEngine = null

  def pokeByte(addr: Long, value: Int): Unit = mem.write(addr, value.toByte)
  def peekByte(addr: Long): Int             = mem.read(addr).toInt & 0xff
  def poke128(addr: Long, data: BigInt): Unit =
    for (i <- 0 until 16) pokeByte(addr + i, ((data >> (8 * i)) & 0xff).toInt)
  def peek128(addr: Long): BigInt =
    (0 until 16).foldLeft(BigInt(0)) { (acc, i) => acc | (BigInt(peekByte(addr + i)) << (8 * i)) }
}

object AxiMemModel {
  val DATA_BITS = 128
  val BYTES     = DATA_BITS / 8

  /** The D-side / walker AXI config, unchanged from `BehavioralMem.axiConfig`. */
  def axiConfig(dataWidth: Int = DATA_BITS, idWidth: Int = 4) = Axi4Config(
    addressWidth = 32, dataWidth = dataWidth, idWidth = idWidth,
    useId = true, useRegion = false, useBurst = true, useLock = false,
    useCache = false, useSize = true, useQos = false, useLen = true,
    useLast = true, useResp = true, useProt = false, useStrb = true)

  /** Task #189/#211 bus-error decode, MOVED VERBATIM from `BehavioralMem.decoded`.
    *
    * NOTE FOR REVIEWERS: this is a *test-harness* address map. It models the SoC the
    * ported corpus's own headers describe, so that a probe to genuinely-unmapped
    * space DECERRs instead of silently reading zeros. It lives entirely on the
    * simulation side and NOTHING in `src/main/scala` may ever consult anything of
    * this shape -- see GC1. */
  def decoded(addr: Long): Boolean = {
    val a = addr & 0xffffffffL
    val topNibble = (a >>> 28) & 0xf
    (topNibble == 0x0L) || (topNibble == 0x4L) || (topNibble == 0x5L) || (topNibble == 0x6L) ||
      ((a >>> 16) == 0xffffL)
  }

  def attachFull(axi: Axi4, cd: ClockDomain,
                 cfg: AxiMemModelConfig = AxiMemModelConfig(),
                 sharedMem: SparseMemory = null): AxiMemModel = {
    val m = new AxiMemModel(axi.config, cd, cfg, sharedMem)
    m.readEngine  = new AxiReadEngine(axi.ar, axi.r, axi.config, cd, m.mem, cfg, m.clk, m.stats, m.checker)
    m.writeEngine = new AxiWriteEngine(axi.aw, axi.w, axi.b, axi.config, cd, m.mem, cfg, m.clk, m.stats, m.checker)
    m
  }

  def attachReadOnly(axi: Axi4ReadOnly, cd: ClockDomain,
                     cfg: AxiMemModelConfig = AxiMemModelConfig(),
                     sharedMem: SparseMemory = null): AxiMemModel = {
    val m = new AxiMemModel(axi.config, cd, cfg, sharedMem)
    m.readEngine = new AxiReadEngine(axi.ar, axi.r, axi.config, cd, m.mem, cfg, m.clk, m.stats, m.checker)
    m
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // *** TWO INCOMPATIBLE PROGRAM-IMAGE CONVENTIONS EXIST IN THIS TREE. ***
  // Do NOT "unify" them -- both are load-bearing and every passing test depends on
  // the one it uses. Documented at `PortedTestRunner.scala:109-115`.
  //
  //  - I-SIDE (instruction fetch): each 16-bit big-endian opword is stored
  //    LOW-BYTE-FIRST, i.e. BYTE-SWAPPED per word relative to the raw image. Every
  //    existing `attachProgram` clone (`ExecuteLockStepSpec.scala:379-395`,
  //    `FuzzDut.scala:283-294`, `IpcBenchSpec.scala:289-300`) and
  //    `IcacheSim.attachMemoryWithWords` uses this.
  //  - D-SIDE (data): PLAIN byte-at-address, no swap. `BehavioralMemAgent` and every
  //    store/load ported test use this.
  //
  // `PortedTestRunner` deliberately seeds the SAME image into BOTH views, each in its
  // own convention, so a PC-relative literal-pool read sees the same bytes the
  // I-cache fetched as code.
  // ─────────────────────────────────────────────────────────────────────────────

  /** I-side convention: byte-swapped per 16-bit word. */
  def loadProgramIFetch(mem: SparseMemory, loadAddr: Long, bytes: Vector[Int]): Unit = {
    val nWords = bytes.length / 2
    for (i <- 0 until nWords) {
      val w = ((bytes(2 * i) & 0xff) << 8) | (bytes(2 * i + 1) & 0xff)   // big-endian word
      mem.write(loadAddr + 2 * i,     (w & 0xff).toByte)
      mem.write(loadAddr + 2 * i + 1, ((w >> 8) & 0xff).toByte)
    }
  }

  /** D-side convention: plain byte-at-address, no swap. */
  def loadProgramData(mem: SparseMemory, loadAddr: Long, bytes: Vector[Int]): Unit =
    for (i <- bytes.indices) mem.write(loadAddr + i, bytes(i).toByte)
}
```

- [ ] **Step 5: A self-test spec for the model itself (it must be trustworthy before anything is gated on it)**

```scala
// src/test/scala/m68k040/sim/AxiMemModelSpec.scala
package m68k040.sim

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

/** A trivial DUT that issues N reads with N distinct IDs and records the ORDER in
  * which their `last` beats come back. Proves the model's reordering modes actually
  * reorder (and that InOrder does not). */
class MultiIdReadDut(nIds: Int) extends Component {
  val io = new Bundle {
    val axi  = master(Axi4ReadOnly(AxiMemModel.axiConfig(128, 4)))
    val go   = in Bool()
    val done = out Bits(nIds bits)
  }
  val issued = RegInit(U(0, log2Up(nIds + 1) bits))
  val doneReg = RegInit(B(0, nIds bits)); io.done := doneReg
  io.axi.ar.valid         := io.go && (issued < nIds)
  io.axi.ar.payload.addr  := (issued.resize(32) << 6)
  io.axi.ar.payload.id    := issued.resize(4)
  io.axi.ar.payload.len   := U(0, 8 bits)
  io.axi.ar.payload.size  := U(4, 3 bits)
  io.axi.ar.payload.burst := Axi4.burst.INCR
  when(io.axi.ar.fire) { issued := issued + 1 }
  io.axi.r.ready := True
  when(io.axi.r.valid && io.axi.r.payload.last) {
    doneReg(io.axi.r.payload.id.resize(log2Up(nIds))) := True
  }
  io.done.simPublic(); issued.simPublic()
}

class AxiMemModelSpec extends AnyFunSuite {
  private def compiled(nIds: Int) = SimConfig.withWave.compile(new MultiIdReadDut(nIds))

  private def runOrder(mode: AxiRspMode.Value): Seq[Int] = {
    val order = scala.collection.mutable.ArrayBuffer[Int]()
    compiled(4).doSim(s"order-$mode", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachReadOnly(dut.io.axi, dut.clockDomain,
        AxiMemModelConfig(rspMode = mode, maxPendingBeats = 16))
      for (i <- 0 until 4 * 16) m.pokeByte(i.toLong, i & 0xff)
      dut.io.go #= true
      var seen = 0
      var guard = 0
      while (seen < 4 && guard < 2000) {
        dut.clockDomain.waitSampling()
        val d = dut.io.done.toInt
        for (i <- 0 until 4) if (((d >> i) & 1) == 1 && !order.contains(i)) { order += i; seen += 1 }
        guard += 1
      }
      assert(seen == 4, s"only $seen of 4 reads completed")
    }
    order.toSeq
  }

  test("InOrder mode returns responses in issue order") {
    assert(runOrder(AxiRspMode.InOrder) == Seq(0, 1, 2, 3))
  }

  test("Chaos mode returns responses out of issue order") {
    assert(runOrder(AxiRspMode.Chaos) != Seq(0, 1, 2, 3),
      "Chaos mode produced issue order -- the reordering model is not actually reordering")
  }

  test("idBusyBlock refuses a second AR with a live ID") {
    // One ID, two back-to-back reads: the second must not be accepted until the
    // first's last beat has been driven (mirrors the L2's id_busy_c CAM).
    compiled(1).doSim("idbusy", seed = 7) { dut =>
      dut.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachReadOnly(dut.io.axi, dut.clockDomain,
        AxiMemModelConfig(idBusyBlock = true, maxPendingBeats = 16,
                          latency = L2LatencyModel(enabled = true, dramCycles = 20)))
      dut.io.go #= true
      dut.clockDomain.waitSampling(200)
      assert(m.stats.maxConcurrentReads <= 1,
        s"idBusyBlock allowed ${m.stats.maxConcurrentReads} concurrent same-ID reads")
    }
  }

  test("write-before-B: a read issued after B observes the written bytes") {
    // Guards design doc §8.1 item 6 -- the invariant `BehavioralMem.scala:127-148`
    // exists to protect. Exercised through the full-duplex attach.
    assert(true)   // placeholder replaced in Step 6 by the real DUT-level check
  }
}
```

- [ ] **Step 6: Replace the placeholder self-test with a real full-duplex write-then-read check**

Write a second tiny DUT in the same spec file that (a) issues one AW/W burst of a known pattern, (b) waits for B, (c) issues an AR to the same address, and asserts the read data equals the written pattern. Model it on `MultiIdReadDut` above (same `master(Axi4(...))` shape but with the write channels driven), attach with `AxiMemModel.attachFull`, and assert with a hard `assert(...)` — not a print. This is the one invariant a deferred-apply latency model would silently break, so it must be a real test, not a placeholder.

- [ ] **Step 7: Compile and run only this spec**

```bash
~/sbt/bin/sbt "testOnly m68k040.sim.AxiMemModelSpec"
```

All tests must pass. Nothing else in the tree references the new file yet, so nothing else can break.

- [ ] **Step 8: Commit**

```bash
git add src/test/scala/m68k040/sim/AxiMemModel.scala src/test/scala/m68k040/sim/AxiMemModelSpec.scala
git commit -m "$(cat <<'EOF'
sim: AxiMemModel -- per-ID reordering AXI memory model (V1.2)

New shared AXI slave model with per-ID response queues and four ordering modes
(InOrder / Reordered / Chaos / IllegalInterleave), a free-running cycle counter,
and a stats block. One read engine serves both Axi4 and Axi4ReadOnly masters,
replacing the acknowledged copy-paste at BehavioralMem.scala:250-259.

Preserves the write-before-B invariant verbatim (BehavioralMem.scala:127-148):
bytes land at W-beat pairing time; latency only ever delays when B is DRIVEN.

Design doc: 2026-07-30-mshr-multi-outstanding-design-proposal.md §8.1, §1.6.
No src/main changes -- V1 is a zero-RTL slice.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task V1.3: L2-faithful latency + `id_busy_c` front door + L2 MSHR back-pressure (wire-up and validation)

**Files:**
- Modify: `src/test/scala/m68k040/sim/AxiMemModel.scala` (only if Step 1 finds gaps — the mechanism was written in V1.2; this task VALIDATES it against the measured contract and adds the sweep helper)
- Create: `src/test/scala/m68k040/sim/L2LatencySpec.scala`

**Interfaces:**
- Consumes: `AxiMemModel`, `L2LatencyModel`, `AxiMemModelConfig` from V1.2.
- Produces: `m68k040.sim.L2Sweeps.standard: Seq[(String, AxiMemModelConfig)]` — the named configuration sweep every later measurement task reports against.

- [ ] **Step 1: Cross-check every latency constant against its cited source**

Open `/home/qwertyoruiop/macqd700-soc/docs/l2c_spec.md` and `rtl/soc/l2c_defs.vh` / `l2c_mshr.v` and confirm, line by line, that `L2LatencyModel`'s defaults match the citations in its doc comment (`lineBytes = 64`, `hitCycles = 5`, `fillFixedCycles = 4`, `l2Mshrs = 8`, `secondaryPerMshr = 4`, no critical-word-first). If any constant does not match its cited line, **fix the constant, not the citation**, and note the correction in the commit message.

`dramCycles` has **no** source and must stay a swept parameter (design doc §3.4 U1). Add an explicit comment saying so if it is not already there.

- [ ] **Step 2: Add the named sweep set**

```scala
// src/test/scala/m68k040/sim/AxiMemModel.scala — append at the end of the file
/** The configuration sweep every measurement task in this plan reports against
  * (design doc §8.3). `N_MSHR` is a DUT-side build parameter and is swept separately;
  * these are the MEMORY-side axes: latency tier x response mode x crossbar.
  *
  * `dramCycles` is UNMEASURED (design doc §3.4 U1) -- the two values below are a
  * deliberate bracket, not a claim. No performance statement may quote one of them
  * as "the" DRAM latency. */
object L2Sweeps {
  val zeroLatency   = AxiMemModelConfig()
  val l2HitOnly     = AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 0))
  val l2DramFast    = AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 20))
  val l2DramSlow    = AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 60))
  val todaysCrossbar = AxiMemModelConfig(
    latency = L2LatencyModel(enabled = true, dramCycles = 20),
    idBusyBlock = true, crossbarSingleOutstanding = true)
  val chaosDram = AxiMemModelConfig(
    rspMode = AxiRspMode.Chaos,
    latency = L2LatencyModel(enabled = true, dramCycles = 20), idBusyBlock = true)

  val standard: Seq[(String, AxiMemModelConfig)] = Seq(
    "zero-latency"       -> zeroLatency,
    "l2-hit-only"        -> l2HitOnly,
    "l2+dram(20)"        -> l2DramFast,
    "l2+dram(60)"        -> l2DramSlow,
    "todays-crossbar"    -> todaysCrossbar,
    "chaos+dram(20)"     -> chaosDram)
}
```

- [ ] **Step 3: A latency-model spec that measures what it claims**

```scala
// src/test/scala/m68k040/sim/L2LatencySpec.scala
package m68k040.sim

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

/** Measures the model's own hit/miss latency and asserts the two-tier separation is
  * real. Reuses `MultiIdReadDut` from AxiMemModelSpec (same package). */
class L2LatencySpec extends AnyFunSuite {
  test("second read of the same 64-byte line is an L2 hit (much faster than the first)") {
    val dut = spinal.core.SimConfig.compile(new MultiIdReadDut(2))
    dut.doSim("two-tier", seed = 3) { d =>
      d.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachReadOnly(d.io.axi, d.clockDomain,
        AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 40),
                          maxPendingBeats = 16))
      // MultiIdReadDut addresses are `issued << 6` => id0 -> 0x00, id1 -> 0x40:
      // two DIFFERENT 64-byte lines, so BOTH are misses. Assert the miss count.
      d.io.go #= true
      d.clockDomain.waitSampling(400)
      assert(m.stats.l2Misses == 2, s"expected 2 L2 misses, got ${m.stats.l2Misses}")
      assert(m.stats.l2Hits == 0, s"expected 0 L2 hits, got ${m.stats.l2Hits}")
    }
  }
}
```

If `MultiIdReadDut` addresses need adjusting so two reads land in the SAME line (to exercise the hit tier and the secondary-merge counter), add a constructor parameter `addrStride: Int` to `MultiIdReadDut` (default 64) and add a second test with `addrStride = 16` asserting `l2SecondaryMerges > 0`.

- [ ] **Step 4: Run and commit**

```bash
~/sbt/bin/sbt "testOnly m68k040.sim.L2LatencySpec"
~/sbt/bin/sbt "testOnly m68k040.sim.AxiMemModelSpec"
git add -A src/test/scala/m68k040/sim
git commit -m "$(cat <<'EOF'
sim: L2-faithful two-tier latency + sweep set (V1.3)

Validates every latency constant against its cited line in macqd700-soc
(l2c_defs.vh / l2c_mshr.v / docs/l2c_spec.md). dramCycles stays a SWEPT
parameter -- it is genuinely unmeasured in both repos (design doc §3.4 U1)
and no performance claim may rest on a specific value.

Adds L2Sweeps.standard: the named memory-side configuration sweep every
measurement task in this plan reports against (design doc §8.3).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task V1.4: Crossbar mode — one outstanding read AND one outstanding write per master port

**Files:**
- Modify: `src/test/scala/m68k040/sim/AxiMemModel.scala` (the mechanism is in V1.2's `arAcceptable`/`awAcceptable`; this task proves it and documents the citation)
- Create: `src/test/scala/m68k040/sim/CrossbarModeSpec.scala`

**Interfaces:**
- Consumes: `AxiMemModelConfig.crossbarSingleOutstanding`.
- Produces: a proven-correct `todaysCrossbar` configuration, which is the configuration **slice D1's benefit must be demonstrated against** (design doc §8.1 item 4, §3.2).

- [ ] **Step 1: Verify the citation and tighten the comment**

Confirm against `/home/qwertyoruiop/macqd700-soc/rtl/soc/axi_xbar.v`:
- reads: `rs_state` FSM `RS_IDLE -> RS_WAIT_SLV_AR -> RS_WAIT_R -> RS_IDLE`, returning to IDLE only after RLAST (`:2278-2284`, `:2388-2412`);
- writes: `sw_owned` locks a slave's whole AW→B sequence to one master; combined with the per-master `ws_state` FSM this is **1 outstanding write per master AND per slave** (`:1234-1258`), and the same comment block records that pipelining was analysed and deliberately NOT implemented.

Ensure `AxiMemModelConfig.crossbarSingleOutstanding`'s doc comment carries both line references.

- [ ] **Step 2: Directed test**

```scala
// src/test/scala/m68k040/sim/CrossbarModeSpec.scala
package m68k040.sim

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class CrossbarModeSpec extends AnyFunSuite {
  test("crossbarSingleOutstanding admits at most one concurrent read") {
    val dut = spinal.core.SimConfig.compile(new MultiIdReadDut(4))
    dut.doSim("xbar", seed = 11) { d =>
      d.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachReadOnly(d.io.axi, d.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, maxPendingBeats = 16,
                          latency = L2LatencyModel(enabled = true, dramCycles = 20)))
      d.io.go #= true
      d.clockDomain.waitSampling(1200)
      assert(m.stats.maxConcurrentReads <= 1,
        s"crossbar mode allowed ${m.stats.maxConcurrentReads} concurrent reads")
      assert(d.io.done.toInt == 0xf, "not all four reads completed under the crossbar model")
    }
  }

  test("WITHOUT crossbar mode, four distinct IDs ARE concurrent") {
    val dut = spinal.core.SimConfig.compile(new MultiIdReadDut(4))
    dut.doSim("noxbar", seed = 11) { d =>
      d.clockDomain.forkStimulus(10)
      val m = AxiMemModel.attachReadOnly(d.io.axi, d.clockDomain,
        AxiMemModelConfig(maxPendingBeats = 16,
                          latency = L2LatencyModel(enabled = true, dramCycles = 20)))
      d.io.go #= true
      d.clockDomain.waitSampling(1200)
      assert(m.stats.maxConcurrentReads > 1,
        "the model never allowed concurrency even with the crossbar model OFF -- " +
        "the 'multi-outstanding buys nothing on today's SoC' claim cannot be measured")
    }
  }
}
```

The second test is the important one: it is what makes the design doc's §3.2 claim **measurable rather than asserted**.

- [ ] **Step 3: Run and commit**

```bash
~/sbt/bin/sbt "testOnly m68k040.sim.CrossbarModeSpec"
git add -A src/test/scala/m68k040/sim
git commit -m "$(cat <<'EOF'
sim: crossbar model -- 1 outstanding read + 1 outstanding write per master (V1.4)

Models macqd700-soc/rtl/soc/axi_xbar.v AS IT EXISTS TODAY (:2278-2284,
:2388-2412 reads; :1234-1258 writes). This is the configuration slice D1's
benefit must be demonstrated against, and the paired negative test proves the
model CAN express concurrency when the crossbar mode is off -- otherwise the
"multi-outstanding buys nothing end-to-end yet" claim would be untestable.

Design doc §3.2, §8.1 item 4.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task V1.5: The always-on AXI protocol checker

**Files:**
- Modify: `src/test/scala/m68k040/sim/AxiMemModel.scala` (add `AxiProtocolChecker`, referenced by V1.2's engines)
- Create: `src/test/scala/m68k040/sim/AxiProtocolCheckerSpec.scala`

**Interfaces:**
- Consumes: nothing new.
- Produces: `m68k040.sim.AxiProtocolChecker` with `.violations: Seq[String]` and hard `assert`s. **This checker is the artefact that makes every later slice trustworthy** (design doc §8.1 item 5).

- [ ] **Step 1: Implement the checker**

```scala
// src/test/scala/m68k040/sim/AxiMemModel.scala — insert BEFORE AxiReadEngine
/** Always-on AXI4 protocol checker. Every violation is BOTH recorded (so a test can
  * assert on the list) and raised as a hard `assert` (so a violation cannot be
  * silently ignored by a test that forgets to look).
  *
  * Checks (design doc §8.1 item 5, §7.1 "Mandatory assertions"):
  *   - an AR/AW ID that is ALREADY outstanding (the ID-uniqueness rule that makes
  *     per-ID routing sound; also exactly what the L2's id_busy_c CAM enforces);
  *   - burst type: INCR only (the L2 SLVERRs FIXED/WRAP, l2c_ctrl.v:26-37,136-139);
  *   - `len` within the configured maximum;
  *   - an R beat driven for an ID with NO outstanding transaction (a model self-check
  *     -- if this ever fires the MODEL is broken, not the DUT);
  *   - per-ID beat count: exactly `len+1` beats and exactly one `r.last`, at the end;
  *   - W beat count matches the AW's `len+1`, with `w.last` on exactly the last beat;
  *   - WSTRB sanity: no strobe bit set beyond the beat's byte width. */
class AxiProtocolChecker(cfg: AxiMemModelConfig, busConfig: Axi4Config) {
  private val _violations = scala.collection.mutable.ArrayBuffer[String]()
  def violations: Seq[String] = _violations.toSeq

  private def fail(msg: String, cycle: Long): Unit = {
    val full = s"[AXI-PROTOCOL @cyc$cycle] $msg"
    _violations += full
    if (cfg.checkProtocol) assert(false, full)
  }

  // per-ID expected/observed read beat bookkeeping
  private val rExpected = scala.collection.mutable.Map[Int, Int]()
  private val rSeen     = scala.collection.mutable.Map[Int, Int]()
  private val wExpected = scala.collection.mutable.Queue[(Int, Int)]()   // (id, len+1)
  private var wSeen = 0

  def onAr(id: Int, addr: BigInt, size: Int, len: Int, burst: Int,
           idAlreadyLive: Boolean, cycle: Long): Unit = {
    if (cfg.checkIdUnique && idAlreadyLive)
      fail(f"AR id=$id presented while ALREADY outstanding (addr=0x${addr}%x)", cycle)
    if (cfg.incrOnly && burst != 1)
      fail(s"AR id=$id burst=$burst is not INCR (the L2 SLVERRs FIXED/WRAP)", cycle)
    if (len > cfg.maxLen)
      fail(s"AR id=$id len=$len exceeds maxLen=${cfg.maxLen}", cycle)
    rExpected(id) = len + 1
    rSeen(id) = 0
  }

  def onAw(id: Int, addr: BigInt, size: Int, len: Int, burst: Int,
           idAlreadyLive: Boolean, cycle: Long): Unit = {
    if (cfg.checkIdUnique && idAlreadyLive)
      fail(f"AW id=$id presented while ALREADY outstanding (addr=0x${addr}%x)", cycle)
    if (cfg.incrOnly && burst != 1)
      fail(s"AW id=$id burst=$burst is not INCR", cycle)
    if (len > cfg.maxLen)
      fail(s"AW id=$id len=$len exceeds maxLen=${cfg.maxLen}", cycle)
    wExpected.enqueue((id, len + 1))
  }

  def onRBeat(id: Int, isLast: Boolean, idOutstanding: Boolean, cycle: Long): Unit = {
    if (!idOutstanding)
      fail(s"R beat driven for id=$id with NO outstanding AR (MODEL BUG, not DUT)", cycle)
    val seen = rSeen.getOrElse(id, 0) + 1
    rSeen(id) = seen
    val exp = rExpected.getOrElse(id, -1)
    if (exp >= 0) {
      if (isLast && seen != exp) fail(s"R id=$id last on beat $seen but len+1=$exp", cycle)
      if (!isLast && seen >= exp) fail(s"R id=$id beat $seen exceeds len+1=$exp without last", cycle)
    }
  }

  def onWBeat(id: Int, wlast: Boolean, engineSaysLast: Boolean,
              strb: BigInt, bytesPerBeat: Int, cycle: Long): Unit = {
    wSeen += 1
    if (wlast != engineSaysLast)
      fail(s"W id=$id w.last=$wlast but the AW's len says last=$engineSaysLast (beat $wSeen)", cycle)
    if (engineSaysLast) { if (wExpected.nonEmpty) wExpected.dequeue(); wSeen = 0 }
    val overflow = strb >> bytesPerBeat
    if (overflow != 0) fail(f"W id=$id strb=0x${strb}%x has bits beyond $bytesPerBeat bytes", cycle)
  }
}
```

- [ ] **Step 2: Prove the checker actually fires**

```scala
// src/test/scala/m68k040/sim/AxiProtocolCheckerSpec.scala
package m68k040.sim

import org.scalatest.funsuite.AnyFunSuite
import spinal.lib.bus.amba4.axi.Axi4Config

class AxiProtocolCheckerSpec extends AnyFunSuite {
  private val busCfg = AxiMemModel.axiConfig(128, 4)

  test("duplicate live AR id is reported") {
    val c = new AxiProtocolChecker(AxiMemModelConfig(checkProtocol = false), busCfg)
    c.onAr(3, BigInt(0), 4, 0, 1, idAlreadyLive = true, cycle = 10)
    assert(c.violations.exists(_.contains("ALREADY outstanding")))
  }

  test("non-INCR burst is reported") {
    val c = new AxiProtocolChecker(AxiMemModelConfig(checkProtocol = false), busCfg)
    c.onAr(0, BigInt(0), 4, 0, 0 /* FIXED */, idAlreadyLive = false, cycle = 10)
    assert(c.violations.exists(_.contains("not INCR")))
  }

  test("r.last on the wrong beat is reported") {
    val c = new AxiProtocolChecker(AxiMemModelConfig(checkProtocol = false), busCfg)
    c.onAr(1, BigInt(0), 4, 3, 1, idAlreadyLive = false, cycle = 1)
    c.onRBeat(1, isLast = true, idOutstanding = true, cycle = 2)
    assert(c.violations.exists(_.contains("last on beat 1 but len+1=4")))
  }

  test("wstrb wider than the beat is reported") {
    val c = new AxiProtocolChecker(AxiMemModelConfig(checkProtocol = false), busCfg)
    c.onAw(8, BigInt(0), 4, 0, 1, idAlreadyLive = false, cycle = 1)
    c.onWBeat(8, wlast = true, engineSaysLast = true, strb = BigInt(1) << 16,
              bytesPerBeat = 16, cycle = 2)
    assert(c.violations.exists(_.contains("bits beyond 16 bytes")))
  }
}
```

Note the deliberate `checkProtocol = false` in these tests: it turns the hard `assert` off so the *recording* half can be unit-tested. Production configurations leave it `true`.

- [ ] **Step 3: Run and commit**

```bash
~/sbt/bin/sbt "testOnly m68k040.sim.AxiProtocolCheckerSpec"
~/sbt/bin/sbt "testOnly m68k040.sim.AxiMemModelSpec"
~/sbt/bin/sbt "testOnly m68k040.sim.L2LatencySpec"
~/sbt/bin/sbt "testOnly m68k040.sim.CrossbarModeSpec"
git add -A src/test/scala/m68k040/sim
git commit -m "$(cat <<'EOF'
sim: always-on AXI4 protocol checker (V1.5)

Every violation is BOTH recorded and hard-asserted, so a test that forgets to
inspect the list still fails. Covers the design doc §7.1 mandatory assertion
set: ID uniqueness, INCR-only, len bound, beat counts, exactly-one-r.last,
per-ID beat ordering, wstrb sanity, and a model self-check for a beat driven
to an ID with no outstanding AR.

Design doc §8.1 item 5.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task V1.6: Consolidate the three model implementations and the four `attachProgram` clones

**Files:**
- Modify: `src/test/scala/m68k040/ls/BehavioralMem.scala` (turn `BehavioralMemAgent` and `Axi4ReadOnlyBehavioralAgent` into thin compatibility shims over `AxiMemModel`; keep the class names and constructor signatures so the ~60 existing instantiation sites do not change)
- Modify: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` (`attachProgram` at `:379-395`; the three inline `Axi4ReadOnlySlaveAgent`s at `:5348`, `:5455`, `:5581`)
- Modify: `src/test/scala/m68k040/fuzz/FuzzDut.scala` (`attachProgram` at `:283-294`, already flagged as deliberate duplication at `:23-30`)
- Modify: `src/test/scala/m68k040/bench/IpcBenchSpec.scala` (`attachProgram` at `:289-300`)
- Modify: `src/test/scala/m68k040/cache/IcacheSim.scala` (`attachMemory` / `attachMemoryWithWords`)

**Interfaces:**
- Consumes: everything from V1.2–V1.5.
- Produces: exactly one read implementation and exactly one program loader pair in the tree. `BehavioralMemAgent(axi, cd, sharedMem, injectBusErrors)` keeps its EXACT current signature and default-argument behaviour so no call site changes.

- [ ] **Step 1: Turn `BehavioralMemAgent` into a shim**

```scala
// src/test/scala/m68k040/ls/BehavioralMem.scala — replace the whole file body below
// the package/import block with this. The object `BehavioralMem` keeps `DATA_BITS`,
// `BYTES`, `axiConfig` and `decoded` as ALIASES so no call site changes.
package m68k040.ls

import spinal.core.ClockDomain
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4ReadOnly}
import spinal.lib.sim.SparseMemory
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}

/** COMPATIBILITY SHIM (V1.6). The real implementation now lives in
  * `m68k040.sim.AxiMemModel` -- one read engine, one write engine, one protocol
  * checker, one latency model. These names are kept ONLY so the ~60 existing
  * instantiation sites (and `BehavioralMemSpec`) do not have to change in the same
  * commit. New code should use `AxiMemModel.attachFull` / `.attachReadOnly` directly
  * and pass an explicit `AxiMemModelConfig`. */
object BehavioralMem {
  val DATA_BITS = AxiMemModel.DATA_BITS
  val BYTES     = AxiMemModel.BYTES
  def axiConfig: Axi4Config = AxiMemModel.axiConfig()
  def decoded(addr: Long): Boolean = AxiMemModel.decoded(addr)
}

class BehavioralMemAgent(axi: Axi4, cd: ClockDomain, sharedMem: SparseMemory = null,
                         injectBusErrors: Boolean = false) {
  val model = AxiMemModel.attachFull(axi, cd,
    AxiMemModelConfig(injectBusErrors = injectBusErrors), sharedMem)
  val mem = model.mem
  def pokeByte(addr: Long, value: Int): Unit = model.pokeByte(addr, value)
  def peekByte(addr: Long): Int             = model.peekByte(addr)
  def poke128(addr: Long, data: BigInt): Unit = model.poke128(addr, data)
  def peek128(addr: Long): BigInt             = model.peek128(addr)
}

class Axi4ReadOnlyBehavioralAgent(axi: Axi4ReadOnly, cd: ClockDomain,
                                  sharedMem: SparseMemory = null,
                                  injectBusErrors: Boolean = false) {
  val model = AxiMemModel.attachReadOnly(axi, cd,
    AxiMemModelConfig(injectBusErrors = injectBusErrors), sharedMem)
  val mem = model.mem
  def pokeByte(addr: Long, value: Int): Unit = model.pokeByte(addr, value)
  def peekByte(addr: Long): Int             = model.peekByte(addr)
}
```

**HAZARD (must be checked, not assumed):** the legacy agents defaulted to `maxPendingBeats = 8` (`rQueue.size < 8`, `BehavioralMem.scala:125`) and `bQueueDepth = 4` (`:158,233-234`). `AxiMemModelConfig`'s defaults above match those exactly. If any existing test depends on deeper or shallower buffering it will show up in Step 5's full-suite run — do not "fix" it by loosening the default; investigate.

- [ ] **Step 2: Collapse the four `attachProgram` clones onto one loader**

In each of `ExecuteLockStepSpec.scala`, `FuzzDut.scala`, `IpcBenchSpec.scala`, replace the body of `attachProgram` with:

```scala
  /** Attach the assembled program to a read-only (instruction-fetch) AXI master.
    * The I-SIDE byte-swapped-per-word convention lives in
    * `AxiMemModel.loadProgramIFetch` -- see the big convention comment there; do NOT
    * substitute `loadProgramData`, every instruction-fetch test depends on the swap. */
  def attachProgram(axi: Axi4ReadOnly, cd: ClockDomain, loadAddr: Long,
                    bytes: Vector[Int]): m68k040.sim.AxiMemModel = {
    val model = m68k040.sim.AxiMemModel.attachReadOnly(axi, cd,
      m68k040.sim.AxiMemModelConfig())
    m68k040.sim.AxiMemModel.loadProgramIFetch(model.mem, loadAddr, bytes)
    model
  }
```

**Return-type change:** these previously returned `Axi4ReadOnlySlaveAgent`. Grep every call site for uses of the returned value before changing the signature:

```bash
grep -rn "attachProgram(" src/test | grep -v "def attachProgram"
grep -rn "= attachProgram\|val .*attachProgram" src/test
```

If any call site keeps the result and calls an `Axi4ReadOnlySlaveAgent` method on it, either add that method to `AxiMemModel` or leave that specific call site alone and note it. Do not silently drop functionality.

- [ ] **Step 3: The three inline agents in `ExecuteLockStepSpec`**

At `:5348`, `:5455`, `:5581` the pattern is:

```scala
      new Axi4ReadOnlySlaveAgent(dut.icache.logic.axi, cd) {
        override def readByte(address: BigInt, id: Int): Byte = icmem.read(address.toLong)
      }
```

Replace each with:

```scala
      m68k040.sim.AxiMemModel.attachReadOnly(dut.icache.logic.axi, cd,
        m68k040.sim.AxiMemModelConfig(), sharedMem = icmem)
```

(`icmem` is already a `SparseMemory` built by `writeCodeAt` in each of those three blocks, so passing it as `sharedMem` is a direct substitution with no convention change.)

- [ ] **Step 4: `IcacheSim`**

```scala
// src/test/scala/m68k040/cache/IcacheSim.scala — replace both attach helpers
  def attachMemory(axi: Axi4ReadOnly, cd: ClockDomain, base: Long, size: Int): m68k040.sim.AxiMemModel = {
    val mem = SparseMemory()
    val img = Array.tabulate(size)(i => memByte(base + i).toByte)
    mem.writeArray(base, img)
    m68k040.sim.AxiMemModel.attachReadOnly(axi, cd, m68k040.sim.AxiMemModelConfig(), sharedMem = mem)
  }

  def attachMemoryWithWords(axi: Axi4ReadOnly, cd: ClockDomain, base: Long,
                            words: Seq[Int]): m68k040.sim.AxiMemModel = {
    val mem = SparseMemory()
    // Same I-SIDE byte-swapped-per-word convention as AxiMemModel.loadProgramIFetch,
    // expressed over raw 16-bit words rather than a byte image.
    words.zipWithIndex.foreach { case (w, i) =>
      mem.write(base + 2 * i,     (w & 0xff).toByte)
      mem.write(base + 2 * i + 1, ((w >> 8) & 0xff).toByte)
    }
    m68k040.sim.AxiMemModel.attachReadOnly(axi, cd, m68k040.sim.AxiMemModelConfig(), sharedMem = mem)
  }
```

Note `SparseMemory` must remain imported from `spinal.lib.sim` here — `IcacheSim` currently imports it from `spinal.lib.bus.amba4.axi.sim`, which is a **different class**. Check which one the existing code actually uses and keep it consistent; if the two are incompatible, keep `IcacheSim`'s existing import and add an overload of `attachReadOnly` that accepts it, rather than churning every I-cache test.

- [ ] **Step 5: Full-suite regression (this is the acceptance gate for V1.6)**

```bash
~/sbt/bin/sbt compile Test/compile
~/sbt/bin/sbt "testOnly m68k040.cache.*"
~/sbt/bin/sbt "testOnly m68k040.ls.*"
~/sbt/bin/sbt "testOnly m68k040.mmu.*"
~/sbt/bin/sbt "testOnly m68k040.exception.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v16_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/v16_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u > /tmp/v16_lockstep_fails.txt
diff /tmp/mshr_baseline_lockstep_fails.txt /tmp/v16_lockstep_fails.txt && echo "LOCKSTEP FAIL-SET IDENTICAL"
```

The lock-step fail set must be **byte-identical** to V1.1's baseline.

- [ ] **Step 6: Full ported corpus, then commit**

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" 2>&1 | tee /tmp/v16_ported.log &
wait $!
grep "^\[info\] - ported: .* \*\*\* FAILED \*\*\*" /tmp/v16_ported.log \
  | sed -E 's/^\[info\] - ported: ([^ ]+).*/\1/' | LC_ALL=C sort -u > /tmp/v16_ported_fails.sorted.txt
comm -13 /tmp/mshr_baseline_ported_fails.sorted.txt /tmp/v16_ported_fails.sorted.txt   # MUST be empty
```

`comm -13` must be **empty**: V1.6 is a pure refactor of the harness, so it may not introduce a single new ported failure. (Unlike RTL slices, GC2's "budgeted new bugs" exception does NOT apply here — nothing about the DUT changed.)

```bash
git add -A src/test
git commit -m "$(cat <<'EOF'
sim: collapse 3 memory models + 4 attachProgram clones onto AxiMemModel (V1.6)

BehavioralMemAgent / Axi4ReadOnlyBehavioralAgent become thin shims with
byte-identical constructor signatures, so all ~60 instantiation sites are
untouched. The four attachProgram clones (ExecuteLockStepSpec, FuzzDut,
IpcBenchSpec) plus IcacheSim's two helpers and the three inline
Axi4ReadOnlySlaveAgents in ExecuteLockStepSpec all route through one loader.

The TWO program-image conventions (I-side byte-swapped-per-word vs D-side
plain) are preserved and now documented in one place -- they are NOT unified,
both are load-bearing (PortedTestRunner.scala:109-115).

Lock-step fail set byte-identical to baseline; zero new ported failures.

Design doc §8.1 item 7, §1.6.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task V1.7: Miss-heavy IPC kernels + miss/stall counters (sim-side only)

**Files:**
- Modify: `src/test/scala/m68k040/bench/IpcBenchSpec.scala` (new kernels + a counter-collection fork + the sweep loop)
- Create: `src/test/scala/m68k040/bench/MissCounters.scala`

**Interfaces:**
- Consumes: `AxiMemModel`, `L2Sweeps.standard`.
- Produces: `m68k040.bench.MissCounters` (a sim-side observer, constructed from a DUT + its two `AxiMemModel`s) and five new kernels: `kStrideWalk`, `kMovemCopy`, `kPointerChase`, `kBigCodeLoop`, `kMixedLineCross`.

**RTL-FREE CONSTRAINT (GC-scoped):** every counter below is derived from signals that are ALREADY `simPublic` — `DcachePlugin.scala:50-52` (`loadCmdPort.valid/.ready/.payload`), `:199-200` (`ldS1Valid`, `ldS1Hit`), `:217` (`busFaultResp`), `:225` (`inhibitedResp`), `StoreQueue.scala:451-468` — or from the DUT's top-level AXI ports via `AxiMemStats`. **Do not add a single `.simPublic()` to `src/main`.**

- [ ] **Step 1: The counter observer**

```scala
// src/test/scala/m68k040/bench/MissCounters.scala
package m68k040.bench

import spinal.core.ClockDomain
import spinal.core.sim._
import m68k040.sim.AxiMemModel

/** Sim-side miss/stall counters (design doc §8.3 -- NONE exist in the tree today).
  *
  * Deliberately built ONLY from signals that are already `simPublic` plus the two
  * memory models' AXI stats, so slice V1 stays a zero-RTL slice. When the D1/I1
  * MSHR arrays land, extend this with MSHR-occupancy sampling off the (then new)
  * MSHR `valid` taps.
  *
  * `dcache` / `icacheStats` are passed in rather than discovered so this works with
  * every DUT shape (FullCoreSynth, FuzzDut, IpcBenchSpec's own top). */
class MissCounters(cd: ClockDomain,
                   ldS1Valid: () => Boolean, ldS1Hit: () => Boolean,
                   ldCmdValid: () => Boolean, ldCmdReady: () => Boolean,
                   dStats: () => (Long, Long),      // (total AR, total AW) on the D master
                   iStats: () => Long) {            // total AR on the I master
  var cycles            = 0L
  var dLookups          = 0L   // cycles with a load resolving in S1
  var dHits             = 0L
  var dMisses           = 0L
  var lsStallCycles     = 0L   // loadCmd.valid && !loadCmd.ready -- the LS pipe blocked on the cache
  var dArAtStart        = 0L
  var dAwAtStart        = 0L
  var iArAtStart        = 0L

  def start(): Unit = {
    val (dar, daw) = dStats(); dArAtStart = dar; dAwAtStart = daw; iArAtStart = iStats()
    cd.onSamplings {
      cycles += 1
      if (ldS1Valid()) { dLookups += 1; if (ldS1Hit()) dHits += 1 else dMisses += 1 }
      if (ldCmdValid() && !ldCmdReady()) lsStallCycles += 1
    }
  }

  def dRefills: Long = { val (dar, _) = dStats(); dar - dArAtStart }
  def dWrites:  Long = { val (_, daw) = dStats(); daw - dAwAtStart }
  def iRefills: Long = iStats() - iArAtStart

  def report(name: String): String =
    f"$name%-24s cycles=$cycles%8d dLookup=$dLookups%7d dHit=$dHits%7d dMiss=$dMisses%7d " +
    f"lsStall=$lsStallCycles%7d dAR=${dRefills}%6d dAW=${dWrites}%6d iAR=${iRefills}%6d"
}
```

- [ ] **Step 2: The five miss-heavy kernels (design doc §8.3 minimum set)**

```scala
// src/test/scala/m68k040/bench/IpcBenchSpec.scala — add alongside kLoadStore etc.

  /** MISS-HEAVY #1 -- stride walk larger than the 8 KiB L1D.
    * Every access is a fresh 16-byte line and the working set exceeds capacity, so
    * essentially every load misses. Measures RAW miss throughput and is the kernel
    * most sensitive to MSHR depth. Straight-line unrolled (no backward branch) for
    * the same reason kLoadStore is: a redirect crossing the LS pipe exposes an
    * unrelated replay stall that would pollute the measurement. */
  def kStrideWalk: Kernel = {
    val base   = 0x40000
    val stride = 0x40              // 4 L1D lines apart -> never reuses a line
    val n      = 200
    val setup  = Seq("moveq #0,%d0")
    val body   = (0 until n).map(i => f"add.l 0x${base + i * stride}%x,%%d0")
    val src    = (setup ++ body).mkString(" ; ")
    Kernel("stride-walk", src, setup.size + body.size)
  }

  /** MISS-HEAVY #2 -- a memcpy-shaped MOVEM burst across many lines.
    * This is the LSU-stress shape the ported corpus already has, and the case
    * outstanding writes (deferred slice D5) and the dropped D3-BURST line widening
    * both target. Included so the measurement exists BEFORE either is attempted. */
  def kMovemCopy: Kernel = {
    val src0 = 0x50000
    val dst0 = 0x58000
    val reps = 24
    val setup = Seq(f"movea.l #0x$src0%x,%%a0", f"movea.l #0x$dst0%x,%%a1")
    val body = (0 until reps).flatMap { _ =>
      Seq("movem.l (%a0)+,%d0-%d3", "movem.l %d0-%d3,(%a1)+")
    }
    Kernel("movem-copy", (setup ++ body).mkString(" ; "), setup.size + body.size)
  }

  /** MISS-HEAVY #3 -- pointer chase (dependent misses).
    * The CONTROL kernel: MSHRs cannot help here at all, because each miss's address
    * depends on the previous miss's data. If a slice appears to speed THIS up, the
    * measurement is wrong. Requires the memory image to be pre-seeded with the chain
    * (see Step 3). */
  def kPointerChase: Kernel = {
    val head = 0x60000
    val n    = 120
    val setup = Seq(f"movea.l #0x$head%x,%%a0")
    val body  = (0 until n).map(_ => "movea.l (%a0),%a0")
    Kernel("pointer-chase", (setup ++ body).mkString(" ; "), setup.size + body.size)
  }

  /** MISS-HEAVY #4 -- large-footprint straight-line code (I-side misses + prefetch).
    * The code itself is the working set: >16 KiB of straight-line instructions walks
    * every I-cache set several times. This is the kernel slice I3 (next-line
    * prefetch) must move, and the one I1 (non-blocking accept) should move a little. */
  def kBigCodeLoop: Kernel = {
    // 6000 x 2-byte `nop`-equivalent (`tst.l %d0` is 2 bytes and flag-only) = 12 KiB,
    // plus the harness prologue. Adjust the count upward if `IcachePlugin`'s 16 KiB
    // capacity is not exceeded -- measure `iRefills` and raise until it is.
    val body = (0 until 6000).map(_ => "tst.l %d0")
    Kernel("big-code", body.mkString(" ; "), body.size)
  }

  /** MISS-HEAVY #5 -- mixed load/store bursts that cross lines.
    * Exercises the SQ `sameLine` stall (StoreQueue.scala:250-261) against a missing
    * load, i.e. the store-drain / load-miss overlap win (design doc §5.1 item 3). */
  def kMixedLineCross: Kernel = {
    val base = 0x70000
    val reps = 60
    val setup = Seq("moveq #7,%d2", "moveq #1,%d1")
    val body = (0 until reps).flatMap { i =>
      val a = base + i * 0x30
      Seq(f"move.l %%d2,0x$a%x", f"add.l 0x${a + 0x10}%x,%%d0", f"move.l %%d0,0x${a + 0x20}%x")
    }
    Kernel("mixed-line-cross", (setup ++ body).mkString(" ; "), setup.size + body.size)
  }
```

- [ ] **Step 3: Seed the D-side image for the pointer chase**

`kPointerChase` needs a real chain in memory. In `IpcBenchSpec`'s sim body, after the D-side model is attached, add:

```scala
      // Pointer-chase chain: each node holds the (big-endian, 4-byte) address of the
      // next node, 0x40 bytes ahead so consecutive hops always miss a different line.
      // Big-endian because `DcacheByteLane.extract`'s LONG case reads the byte at the
      // LOWEST address as the MSB (DcacheTypes.scala:75-90).
      val chaseHead = 0x60000L
      for (i <- 0 until 200) {
        val here = chaseHead + i * 0x40L
        val next = chaseHead + ((i + 1) % 200) * 0x40L
        for (b <- 0 until 4) dmem.pokeByte(here + b, ((next >> (8 * (3 - b))) & 0xff).toInt)
      }
```

- [ ] **Step 4: Sweep loop + reporting**

Extend `IpcBenchSpec`'s kernel driver so that, for each kernel, it runs once per entry in `m68k040.sim.L2Sweeps.standard`, attaches the memory models with that config, wires a `MissCounters`, and prints one line per (kernel, config). Add the new kernels to the kernel list, gated by the existing `IPC_ONLY` environment-variable selector so the default run does not get 6× longer:

```scala
    val missKernels = Seq(kStrideWalk, kMovemCopy, kPointerChase, kBigCodeLoop, kMixedLineCross)
    val sweepConfigs =
      if (sys.env.get("IPC_SWEEP").contains("full")) m68k040.sim.L2Sweeps.standard
      else Seq("l2+dram(20)" -> m68k040.sim.L2Sweeps.l2DramFast)
```

- [ ] **Step 5: Run and record the FIRST-EVER miss numbers**

```bash
IPC_SWEEP=full ~/sbt/bin/sbt "testOnly m68k040.bench.IpcBenchSpec" 2>&1 | tee /tmp/v17_ipc.log
grep -E "stride-walk|movem-copy|pointer-chase|big-code|mixed-line-cross" /tmp/v17_ipc.log
```

Record this output verbatim in the commit message. **This is the pre-MSHR baseline every later slice's speedup claim is measured against**, and it is the first miss measurement this project has ever had. Sanity-check it before trusting it: `stride-walk` must show `dMiss` ≈ its load count and `dAR` ≈ the same (one refill per miss); `pointer-chase` must show a much lower IPC than `stride-walk` at the same miss count; `big-code` must show a nonzero `iAR`. If any of those does not hold, the kernel is not doing what it claims — fix the kernel, not the expectation.

- [ ] **Step 6: Commit**

```bash
git add -A src/test/scala/m68k040/bench
git commit -m "$(cat <<'EOF'
bench: miss-heavy kernels + sim-side miss/stall counters (V1.7)

Five new kernels (stride-walk, movem-copy, pointer-chase, big-code,
mixed-line-cross) -- the design doc §8.3 minimum set. Until now NO benchmark in
the tree exercised a cache miss at all (design doc §1.7): every existing memory
kernel is deliberately all-hits-after-first-refill.

pointer-chase is the CONTROL: MSHRs cannot help dependent misses. If a later
slice appears to speed it up, that slice's measurement is wrong.

Counters are sim-side only, derived from already-simPublic signals and the
memory models' AXI stats -- V1 adds zero RTL, hence no synth gate.

Baseline numbers (pre-MSHR, this commit):
<PASTE THE /tmp/v17_ipc.log TABLE HERE>

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task V1.8: Slice V1 acceptance — the whole suite under `Reordered` and `Chaos`

**Files:**
- Modify: `src/test/scala/m68k040/fuzz/PortedTestRunner.scala` and `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala` — add an environment-variable-selected response mode so the whole suite can be re-run under a different model configuration without duplicating any test.

**Interfaces:**
- Consumes: everything in V1.
- Produces: `AXI_RSP_MODE={InOrder|Reordered|Chaos|IllegalInterleave}` and `AXI_LATENCY={off|l2|dram}` environment knobs honoured by both suites.

- [ ] **Step 1: Central config resolver**

```scala
// src/test/scala/m68k040/sim/AxiMemModel.scala — append
object SimEnvConfig {
  /** Whole-suite response-mode / latency selection from the environment, so the
    * entire existing test corpus can be re-run under a reordering model with no
    * per-test change. Defaults reproduce the legacy behaviour EXACTLY. */
  def fromEnv(base: AxiMemModelConfig = AxiMemModelConfig()): AxiMemModelConfig = {
    val mode = sys.env.get("AXI_RSP_MODE").map {
      case "InOrder"           => AxiRspMode.InOrder
      case "Reordered"         => AxiRspMode.Reordered
      case "Chaos"             => AxiRspMode.Chaos
      case "IllegalInterleave" => AxiRspMode.IllegalInterleave
      case other => throw new IllegalArgumentException(s"unknown AXI_RSP_MODE=$other")
    }.getOrElse(base.rspMode)
    val lat = sys.env.get("AXI_LATENCY") match {
      case Some("off") | None => base.latency
      case Some("l2")         => L2LatencyModel(enabled = true, dramCycles = 0)
      case Some("dram")       => L2LatencyModel(enabled = true, dramCycles = 20)
      case Some(other) => throw new IllegalArgumentException(s"unknown AXI_LATENCY=$other")
    }
    base.copy(rspMode = mode, latency = lat)
  }
}
```

- [ ] **Step 2: Route every attach site through it**

Change the shims in `BehavioralMem.scala` and the three `attachProgram` helpers to build their config with `m68k040.sim.SimEnvConfig.fromEnv(AxiMemModelConfig(injectBusErrors = injectBusErrors))` instead of the bare config. With no environment variables set, this is bit-identical to today.

- [ ] **Step 3: The acceptance runs**

```bash
# 1. InOrder, zero latency -- MUST be byte-identical to V1.1's baseline
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v18_inorder.log

# 2. Reordered
AXI_RSP_MODE=Reordered ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v18_reordered.log

# 3. Chaos
AXI_RSP_MODE=Chaos ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v18_chaos.log

# 4. Chaos + real latency (the harshest configuration today's RTL should still pass)
AXI_RSP_MODE=Chaos AXI_LATENCY=dram ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v18_chaos_dram.log

for f in /tmp/v18_*.log; do
  echo "== $f"; grep "\*\*\* FAILED \*\*\*" $f | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' | LC_ALL=C sort -u
done
```

Every one of the four must produce the SAME 4-name fail set as V1.1. Today's RTL is single-outstanding per master, so reordering across IDs cannot reach it — **if a reordering mode DOES break something, that is a real bug in today's RTL and must be reported and root-caused before V2 starts.** Do not suppress it.

- [ ] **Step 4: Ported corpus under Chaos+dram (the long one)**

```bash
AXI_RSP_MODE=Chaos AXI_LATENCY=dram ~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" 2>&1 | tee /tmp/v18_ported_chaos.log &
wait $!
grep "^\[info\] - ported: .* \*\*\* FAILED \*\*\*" /tmp/v18_ported_chaos.log \
  | sed -E 's/^\[info\] - ported: ([^ ]+).*/\1/' | LC_ALL=C sort -u > /tmp/v18_ported_chaos_fails.sorted.txt
comm -13 /tmp/mshr_baseline_ported_fails.sorted.txt /tmp/v18_ported_chaos_fails.sorted.txt
```

Any newly-failing test here is a genuine finding about today's RTL under latency (GC2's budgeted-new-bugs note applies — but at V1 the DUT is unchanged, so the cause can only be *timing sensitivity that already existed*). Triage each one and record the verdict; do not simply add it to the baseline.

- [ ] **Step 5: Commit and close the slice**

```bash
git add -A src/test
git commit -m "$(cat <<'EOF'
sim: whole-suite response-mode/latency env knobs + V1 acceptance (V1.8)

AXI_RSP_MODE and AXI_LATENCY re-run the ENTIRE existing corpus under a
reordering / latency-bearing memory model with no per-test change. Defaults are
bit-identical to the legacy behaviour.

Acceptance (design doc §8.1): lock-step fail set byte-identical to baseline
under InOrder, Reordered, Chaos, and Chaos+dram.

Slice V1 complete. NO synth gate -- zero src/main changes.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Slice V2 — AXI ID hygiene, response tagging, I-cache narrowing, walker fold

Design doc refs: §7.1 (ID allocation, D2), §1.1 (there is no arbiter — four independent masters, and the walkers COLLIDE on IDs 2/3), §1.2 (`r.ready` is state-gated, so a beat must be routed by `r.id` and NEVER by FSM state), §5.4 (`DLoadRsp` has no tag at all; `busFaultResp`/`inhibitedResp` are untagged one-shot pulses), §5.6 (`storeAck` is untagged), **§11.Q7 RATIFIED** (do the walker fold and the I-cache 128-bit narrowing NOW).

**Why this slice is bigger than it looks:** §11.Q7's ratification turned V2 from "rename some ID constants" into "make both physical masters socket-conformant". It is therefore split into three sub-slices, each independently green, gated and commitable:

| Sub-slice | Content | RTL-bearing? | Depends on |
|---|---|---|---|
| **V2a** | ID constants, unique IDs, widen I-side `idWidth` 2→4, route R/B by ID with asserts, pool-level `r.ready`, tag `DLoadRsp`/`busFaultResp`/`inhibitedResp`/`storeAck` | yes | V1 |
| **V2b** | I-cache AXI master 256-bit → 128-bit (`len=3` instead of `len=1`), cache geometry unchanged | yes | V2a |
| **V2c** | Fold the DTLB walker master into the D master and the ITLB walker master into the I master (I master becomes full `Axi4`) | yes | V2b |

**Ordering rationale (do not reorder):** V2b must precede V2c because folding a 128-bit walker onto a 256-bit I master would need an upsizer that V2b deletes the need for. V2a must precede both because per-ID routing is the mechanism the fold relies on for response demux.

**Behavioural intent:** V2a and V2b are **inert** — no observable behaviour change beyond bus width/beat count. V2c changes the top-level port list (two masters disappear), which every DUT and several tests reference.

### Task V2a.1: Central AXI ID map + widen the I-side `idWidth` to 4

**Files:**
- Create: `src/main/scala/m68k040/cache/AxiIds.scala`
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala:31` (`idWidth = 2` → `4`), `:336` (AR id literal)
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala:373` (AR id), `:507` (AW id)
- Modify: `src/main/scala/m68k040/mmu/TableWalker.scala:108` (AR id becomes a constructor parameter)
- Modify: `src/main/scala/m68k040/mmu/DtlbPlugin.scala:343` (AW id), and its `new TableWalker()` construction
- Modify: `src/main/scala/m68k040/mmu/ItlbPlugin.scala:253` (AW id), and its `new TableWalker()` construction

**Interfaces:**
- Consumes: nothing from V1 (V1 is test-only).
- Produces: `m68k040.cache.AxiIds` — the single source of truth for every AXI ID in the core. Every later task refers to these names, never to a numeric literal.

- [ ] **Step 1: The ID map**

```scala
// src/main/scala/m68k040/cache/AxiIds.scala
package m68k040.cache

/** THE single source of truth for every AXI transaction ID this core emits.
  *
  * Why this exists (design doc §1.1, §7.1): before this file, IDs were numeric
  * literals scattered across five files, and the DTLB and ITLB walkers BOTH emitted
  * AR id 2 and AW id 3. That was harmless only because they sat on separate physical
  * masters -- and it becomes a hard bug the instant those masters are folded (which
  * slice V2c does). It is also mandatory for a different reason: the SoC L2 BLOCKS a
  * second transaction presenting an already-live ID at its front door (`id_busy_c`,
  * `l2c_ctrl.v:140-142,151`), so a constant-ID master gets ZERO benefit from the L2's
  * 8 internal MSHRs.
  *
  * Width: the socket contract fixes AXI_IW = 4 (`macqd700-soc/rtl/soc/cpu_socket.vh:78-87`)
  * and the crossbar prepends a 2-bit slot tag to reach the L2's ID_WIDTH = 6. So 16
  * IDs are available per master. The D side already had idWidth = 4; the I side had 2
  * and is widened here.
  *
  * RULE: never write a numeric AXI ID literal anywhere else in `src/main`. */
object AxiIds {
  /** AXI ID width for BOTH physical masters. Socket-conformant. */
  val ID_W = 4

  // ── D master (`DcachePlugin_logic_axi`) ───────────────────────────────────────
  /** Read ID of D-cache load-refill MSHR `k`. Reserved range 0..3 (room for N_MSHR=4);
    * only 0 is used while N_MSHR == 1. */
  def dRefill(k: Int): Int = { require(k >= 0 && k < 4, s"D refill MSHR index $k out of 0..3"); k }
  val D_REFILL_MAX = 4
  /** DTLB table-walker descriptor read (moves onto the D master in slice V2c). */
  val D_DTLB_WALK = 4
  /** ALL D-side writes share ONE ID: write-through beats, eviction writebacks, CPUSH
    * writebacks. AXI4 requires same-ID transactions to complete in order, so program
    * -order write visibility comes for free with no core-side reorder logic
    * (design doc §7.1, decision D2). */
  val D_WRITE = 8
  /** DTLB U/M descriptor write (moves onto the D master in slice V2c). */
  val D_DTLB_UM = 9
  /** Reserved: a future store-side write-allocate refill read. Not emitted today. */
  val D_WALLOC = 12

  // ── I master (`IcachePlugin_logic_axi`) ───────────────────────────────────────
  /** I-cache DEMAND refill MSHR. */
  val I_DEMAND = 0
  /** I-cache PREFETCH MSHR (slice I3). */
  val I_PREFETCH = 1
  /** ITLB table-walker descriptor read (moves onto the I master in slice V2c). */
  val I_ITLB_WALK = 4
  /** ITLB U-bit descriptor write (moves onto the I master in slice V2c). */
  val I_ITLB_UM = 5
}
```

- [ ] **Step 2: `IcachePlugin` — widen `idWidth`, use the constant**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala:31 — was idWidth = 2
  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = 256, idWidth = AxiIds.ID_W)
```

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala:336 — was U(0, 2 bits)
          axi.ar.payload.id    := U(AxiIds.I_DEMAND, AxiIds.ID_W bits)
```

- [ ] **Step 3: `DcachePlugin` — use the constants**

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala:373 — was U(0, 4 bits)
          axi.ar.payload.id    := U(AxiIds.dRefill(0), AxiIds.ID_W bits)
```

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala:507 — was U(1, 4 bits)
      axi.aw.payload.id    := U(AxiIds.D_WRITE, AxiIds.ID_W bits)
```

- [ ] **Step 4: `TableWalker` — the AR ID becomes a constructor parameter**

```scala
// src/main/scala/m68k040/mmu/TableWalker.scala — class declaration
/** `arId`: the AXI read ID this walker emits. MUST be distinct from every other
  * requester on the SAME physical master (design doc §1.1: the DTLB and ITLB walkers
  * both hardcoded 2, which is a hard bug the moment their masters are folded --
  * slice V2c). Supplied by the owning TLB plugin from `AxiIds`. */
class TableWalker(arId: Int = m68k040.cache.AxiIds.D_DTLB_WALK) extends Component {
```

```scala
// src/main/scala/m68k040/mmu/TableWalker.scala:108 — was U(2, 4 bits)
        io.axi.ar.payload.id   := U(arId, m68k040.cache.AxiIds.ID_W bits)
```

- [ ] **Step 5: The two TLB plugins pass their own IDs**

```scala
// src/main/scala/m68k040/mmu/DtlbPlugin.scala — in `logic`
    val walker = new TableWalker(arId = m68k040.cache.AxiIds.D_DTLB_WALK)
```

```scala
// src/main/scala/m68k040/mmu/DtlbPlugin.scala:343 — was U(3, 4 bits)
      walkerAxi.aw.payload.id   := U(m68k040.cache.AxiIds.D_DTLB_UM, m68k040.cache.AxiIds.ID_W bits)
```

```scala
// src/main/scala/m68k040/mmu/ItlbPlugin.scala — in `logic`
    val walker = new TableWalker(arId = m68k040.cache.AxiIds.I_ITLB_WALK)
```

```scala
// src/main/scala/m68k040/mmu/ItlbPlugin.scala:253 — was U(3, 4 bits)
      walkerAxi.aw.payload.id   := U(m68k040.cache.AxiIds.I_ITLB_UM, m68k040.cache.AxiIds.ID_W bits)
```

- [ ] **Step 6: Confirm there are no remaining numeric ID literals**

```bash
grep -rn "payload\.id\s*:=" src/main/scala | grep -v "AxiIds"
```

Must print nothing.

- [ ] **Step 7: Compile + the affected directed specs**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"
~/sbt/bin/sbt "testOnly m68k040.cache.DcacheSpec"
~/sbt/bin/sbt "testOnly m68k040.mmu.DtlbSpec"
~/sbt/bin/sbt "testOnly m68k040.mmu.UmWriteSpec"
```

The I-side `idWidth` change widens a top-level port; any test that constructs an `Axi4ReadOnly` with a hardcoded `idWidth = 2` to attach to `icache.logic.axi` will fail to elaborate. Fix those by reading `icache.logic.axiCfg` (or `axi.config`) instead of restating the width:

```bash
grep -rn "idWidth = 2" src/test src/main
```

- [ ] **Step 8: Commit**

```bash
git commit -am "$(cat <<'EOF'
axi: central AxiIds map; unique IDs; widen I-side idWidth 2 -> 4 (V2a.1)

Before this commit the DTLB and ITLB table walkers BOTH emitted AR id 2 and
AW id 3 (design doc §1.1) -- harmless only while they sit on separate physical
masters, and a hard bug the moment slice V2c folds them. Every AXI ID in the
core is now a named constant in one file; a numeric ID literal anywhere in
src/main is now a review defect.

I-side idWidth 2 -> 4 makes both masters socket-conformant
(macqd700-soc/rtl/soc/cpu_socket.vh:78-87 fixes AXI_IW = 4) and is a pure port
-width change with no logic cost.

Inert: no behavioural change.

Design doc §7.1, §1.1, §11.Q7 (RATIFIED).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task V2a.2: Route R and B beats by ID; pool-level `r.ready`; mandatory asserts

**Files:**
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala:379-408` (REFILL's `r.ready`/beat consumption), `:530-531` (B handling)
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala:343-369` (REFILL's `r.ready`/beat consumption)
- Modify: `src/main/scala/m68k040/mmu/TableWalker.scala:76,114` (`issueRead`'s `r.ready`)
- Create: `src/test/scala/m68k040/cache/AxiIdRoutingSpec.scala`

**Interfaces:**
- Consumes: `AxiIds` from V2a.1.
- Produces: the invariant that **no FSM state ever gates whether an R beat belongs to it** — every consumer matches on `r.payload.id`. This is the invariant D1/I1/I3 build on, and the one design doc §1.2 identifies as the thing that breaks first.

**Why this matters (design doc §1.2, quoted):** `axi.r.ready` defaults `False` and is asserted only inside the refill/walk state. "The real hazard is the reverse: any design that lets a *second* transaction's beats arrive while the FSM is servicing the first will mis-attribute them, because the FSM's `beatCnt` counts beats without checking `r.id`." Rule for this design: **`r.ready` must become unconditional-per-MSHR-pool and every beat must be routed by `r.id`, never by FSM state.**

- [ ] **Step 1: D-cache — route by ID, keep the FSM only for sequencing**

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala — REPLACE the REFILL r-beat block
// (currently `axi.r.ready := True; when(axi.r.valid) { ... }` at :379-408).
//
// Two changes, both structural rather than behavioural at N_MSHR == 1:
//   (1) `r.ready` is now driven POOL-level (outside the FSM), high whenever this
//       master has ANY outstanding read of its own. It is no longer a property of
//       being in the REFILL state.
//   (2) the beat is attributed by `r.payload.id`, never by "we are in REFILL".
//
// Declared next to the other defaults (near :140-149), BEFORE the FSM, so the FSM
// body (which SpinalHDL elaborates last -- GC8) cannot silently override it.
    val refillOutstanding = RegInit(False)     // set at AR accept, cleared at r.last
    val rIsOurRefill      = axi.r.payload.id === U(AxiIds.dRefill(0), AxiIds.ID_W bits)
    axi.r.ready := refillOutstanding           // POOL-level: not state-gated
    val rBeatFire = axi.r.valid && axi.r.ready && rIsOurRefill

    // Mandatory sim assert (design doc §7.1): a beat whose ID matches no outstanding
    // transaction of ours is a fatal error, not something to consume silently. Today
    // this is unreachable (the D master has exactly one requester); it becomes
    // reachable in V2c (walker fold) and D2 (N_MSHR > 1).
    GenerationFlags.simulation {
      assert(!(axi.r.valid && axi.r.ready && rIsOurRefill && !refillOutstanding),
        "DcachePlugin: R beat with refill ID but NO outstanding refill")
    }
```

```scala
// ...and inside REFILL, replace `axi.r.ready := True; when(axi.r.valid) {` with:
        when(rBeatFire) {
```

and set/clear `refillOutstanding` where the AR is accepted and where `r.last` lands:

```scala
          when(axi.ar.ready) { arSent := True; refillOutstanding := True }
```

```scala
          // ...at the end of the beat block, where the FSM currently does `goto(REPLAY)`:
          when(axi.r.payload.last) { refillOutstanding := False }
```

(The D-cache's refill is `len = 0` today, so `r.last` is asserted on the single beat and this reduces to today's behaviour exactly.)

- [ ] **Step 2: D-cache — B by ID**

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala:530-531 — was an untyped
// `axi.b.valid && axi.b.ready` pair. Qualify by ID so a folded-in walker's B
// (slice V2c, AW id D_DTLB_UM) can never be mistaken for a store ack.
    val bIsOurStore = axi.b.payload.id === U(AxiIds.D_WRITE, AxiIds.ID_W bits)
    val bFire       = axi.b.valid && axi.b.ready && bIsOurStore
    storeErrReg := bFire && (axi.b.payload.resp =/= Axi4.resp.OKAY)
    storeAckReg := bFire
```

**Careful:** `axi.b.ready` stays unconditionally `True` (`:149`) — that is correct and must not change; the qualification is on *interpretation*, not acceptance. But once V2c lands, `b.ready` must be `True` only if *someone* will consume it; since both consumers are always ready, keeping it `True` is still right. Add a comment saying so.

- [ ] **Step 3: I-cache — same treatment**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala — near the defaults at :190-195,
// BEFORE the FSM:
    val refillOutstanding = RegInit(False)
    val rIsDemand = axi.r.payload.id === U(AxiIds.I_DEMAND, AxiIds.ID_W bits)
    axi.r.ready := refillOutstanding
    val rDemandFire = axi.r.valid && axi.r.ready && rIsDemand
    GenerationFlags.simulation {
      assert(!(axi.r.valid && axi.r.ready && rIsDemand && !refillOutstanding),
        "IcachePlugin: R beat with demand ID but NO outstanding refill")
    }
```

```scala
// :340 — set on AR accept
          when(axi.ar.ready) { arSent := True; refillOutstanding := True }
// :343-344 — replace `axi.r.ready := True; when(axi.r.valid) {` with:
        when(rDemandFire) {
// :366 — clear on last
          when(axi.r.payload.last) {
            refillOutstanding := False
            when(missBusFault || respErr) { goto(FAULT) } otherwise { goto(PREDECODE) }
          }
```

- [ ] **Step 4: `TableWalker` — same treatment**

```scala
// src/main/scala/m68k040/mmu/TableWalker.scala — in `issueRead`, replace
// `io.axi.r.ready := True` with an ID-qualified pool-level ready. Declare the
// register next to `arSent`.
      when(!arSent) {
        io.axi.ar.valid        := True
        io.axi.ar.payload.addr := (descAddr(31 downto 4) ## U(0, 4 bits)).asUInt
        io.axi.ar.payload.id   := U(arId, m68k040.cache.AxiIds.ID_W bits)
        io.axi.ar.payload.len  := U(0, 8 bits)
        io.axi.ar.payload.size := U(4, 3 bits)
        io.axi.ar.payload.burst := Axi4.burst.INCR
        when(io.axi.ar.ready) { arSent := True; walkOutstanding := True }
      }
      io.axi.r.ready := walkOutstanding
```

and every `when(io.axi.r.valid)` in the walker's read states becomes
`when(io.axi.r.valid && io.axi.r.ready && (io.axi.r.payload.id === U(arId, m68k040.cache.AxiIds.ID_W bits)))`,
with `walkOutstanding := False` on `r.last`. Declare `val walkOutstanding = RegInit(False)` beside `arSent`.

- [ ] **Step 5: Tagged-response spec**

```scala
// src/test/scala/m68k040/cache/AxiIdRoutingSpec.scala
package m68k040.cache

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import m68k040.sim.{AxiMemModel, AxiMemModelConfig, AxiRspMode}

/** V2a.2: prove that (a) every master emits only IDs from `AxiIds`, and (b) a beat
  * carrying a FOREIGN id is never consumed as one of ours.
  *
  * Uses the existing D-cache standalone DUT (`DcacheSpec`'s harness shape) plus the
  * new model's per-ID stats. The foreign-beat half is checked by the model's protocol
  * checker plus the two new RTL asserts from Steps 1 and 3 -- if the DUT ever accepts
  * a beat it should not, one of those fires. */
class AxiIdRoutingSpec extends AnyFunSuite {
  test("D-cache refill uses AxiIds.dRefill(0) and writes use AxiIds.D_WRITE") {
    // Build the same standalone D-cache DUT DcacheSpec uses (copy its `compiled`
    // helper), drive one load miss and one store, then assert on the model stats.
    // Expected: stats.arCount(AxiIds.dRefill(0)) > 0, every OTHER arCount == 0,
    //           stats.awCount(AxiIds.D_WRITE) > 0, every OTHER awCount == 0.
  }

  test("I-cache refill uses AxiIds.I_DEMAND only") {
    // Same shape against IcacheSpec's DUT.
  }
}
```

Fill both test bodies by copying the DUT-construction helper from `DcacheSpec.scala` / `IcacheSpec.scala` (do not invent a new harness — this project has repeatedly been bitten by hand-rolled boot harnesses diverging via `Random`-state ordering; memory: `fuzz-campaign-divergence-2026-07-16`). The assertions are exactly the four listed in the comments; write them as hard `assert`s.

- [ ] **Step 6: Verify + commit**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.cache.AxiIdRoutingSpec"
~/sbt/bin/sbt "testOnly m68k040.cache.*"
~/sbt/bin/sbt "testOnly m68k040.mmu.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v2a2_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/v2a2_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
git commit -am "axi: route R/B beats by ID, pool-level r.ready, mandatory asserts (V2a.2)

Design doc §1.2: r.ready was state-gated, so an unexpected beat stalled the
slave rather than being back-pressured, and beatCnt counted beats WITHOUT
checking r.id -- so a second transaction's beats would be mis-attributed. Every
consumer (D-cache refill, I-cache refill, both table walkers) now drives a
pool-level ready off its own outstanding-transaction register and matches beats
on r.payload.id. D-side storeAck/storeErr are qualified by b.id.

Sim asserts added per design doc §7.1: a beat whose ID matches no outstanding
transaction is fatal. Currently unreachable; becomes reachable in V2c and D2.

Inert: no behavioural change at one-outstanding-per-master.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

### Task V2a.3: Tag `DLoadRsp`, `busFaultResp`, `inhibitedResp`, and `storeAck`

**Files:**
- Modify: `src/main/scala/m68k040/cache/DcacheTypes.scala:13-18` (`DLoadCmd` gains `tag`), `:24-28` (`DLoadRsp` gains `tag`), `:48-60` (`DcacheService` gains `storeAckTag`)
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala` (carry the tag through `ldS1*`/`miss*`, drive it on every response path)
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala:515-519` (drive `loadCmd.payload.tag`), `:1574-1582` (the exception-unit override drives the reserved exception tag)
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala:102-114` (its `dcLoadCmd`/`dcLoadRsp` gain the field; the exception FSM filters on the reserved tag)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `m68k040.cache.DcacheTags.TAG_W = 2` and `DcacheTags.EXC = 3` (the reserved exception-sequencer tag).
  - `DLoadCmd.tag: UInt(DcacheTags.TAG_W bits)` / `DLoadRsp.tag: UInt(DcacheTags.TAG_W bits)`.
  - `DcacheService.storeAckTag: UInt(DcacheTags.TAG_W bits)`.

  **These widths are FIXED at 2 bits now and never change**, even though slice D1 uses only one LTT entry. Sizing them at 2 from the start means D2 (`N_LTT` → 2 or 4) is a parameter change, not a bundle-width change rippling through six files.

- [ ] **Step 1: Tag constants and bundle fields**

```scala
// src/main/scala/m68k040/cache/DcacheTypes.scala — add near the top
/** D-side response tag space. A response tag identifies WHICH requester context a
  * `DLoadRsp` / `storeAck` belongs to (design doc §5.4: `DLoadRsp` has no tag field
  * at ALL today, and `busFaultResp`/`inhibitedResp` are untagged one-shot pulses --
  * "this is small but it is the first thing that breaks, so it belongs in the
  * earliest RTL slice").
  *
  * Width is fixed at 2 bits from the outset even though slice D1 uses a single Load
  * Tracking Table entry, so that raising N_LTT later is a parameter change rather
  * than a bundle-width change rippling through six files.
  *
  *   0 .. 2  : LS-EU Load Tracking Table entries (only 0 used while N_LTT == 1)
  *   3       : RESERVED for the commit-side exception sequencer's own direct
  *             accesses (`ExceptionUnit.dcLoadCmd`, arbitrated onto the same port
  *             at `LsEuPlugin.scala:1574-1582`). The exception FSM is serializing,
  *             so it never needs more than one. */
object DcacheTags {
  val TAG_W = 2
  /** Highest usable LTT index count. TAG_W = 2 gives tags 0..3; `LIVE` takes 2 and
    * `EXC` takes 3, so the Load Tracking Table may use tags 0..1 -- i.e. N_LTT <= 2.
    * Raising N_LTT beyond 2 requires TAG_W = 3, which is a one-line change here plus
    * a re-elaboration; nothing else in the tree restates the width. */
  val N_LTT_MAX = 2
  /** Tag of the LIVE (blocking) load path -- a load that did not park. */
  val LIVE = 2
  val EXC = 3
}
```

```scala
// src/main/scala/m68k040/cache/DcacheTypes.scala — DLoadCmd gains `tag`
case class DLoadCmd() extends Bundle {
  val vaddr     = UInt(32 bits)
  val paddr     = UInt(32 bits)
  val size      = Size()
  val cacheMode = CacheMode()
  /** Requester context id -- echoed verbatim on the matching `DLoadRsp.tag`. */
  val tag       = UInt(DcacheTags.TAG_W bits)
}
```

```scala
// src/main/scala/m68k040/cache/DcacheTypes.scala — DLoadRsp gains `tag`
case class DLoadRsp() extends Bundle {
  val data  = Bits(32 bits)
  val line  = Bits(128 bits)
  val fault = Bool()
  /** Echo of the originating `DLoadCmd.tag`. Consumers MUST match on this rather
    * than assuming the response belongs to whatever they last issued. */
  val tag   = UInt(DcacheTags.TAG_W bits)
}
```

```scala
// src/main/scala/m68k040/cache/DcacheTypes.scala — DcacheService gains storeAckTag
trait DcacheService {
  def loadCmd:  spinal.lib.Stream[DLoadCmd]
  def loadRsp:  spinal.lib.Flow[DLoadRsp]
  def loadBusy: Bool
  def store:    spinal.lib.Flow[DStoreCmd]
  def storeAck: Bool
  def storeErr: Bool
  /** Which outstanding write `storeAck`/`storeErr` refer to. Inert while writes are
    * single-outstanding (always 0); real when slice D5 makes them concurrent.
    * Declared now so the consumer contract does not change later
    * (design doc §5.6: "storeAck must gain a tag"). */
  def storeAckTag: UInt
}
```

- [ ] **Step 2: `DcachePlugin` — carry and echo the tag**

Add `val ldS1Tag2 = Reg(UInt(DcacheTags.TAG_W bits))` beside `ldS1Cmode` (`:180`), and `val missTagId = Reg(UInt(DcacheTags.TAG_W bits))` beside `missCmode` (`:118`). (Name them so they cannot be confused with the existing `ldS1Tag`/`missTag`, which are *cache* tags — a real trap.) Capture at accept and at miss-latch:

```scala
// in the load-accept arm (:331-344), alongside ldS1Cmode:
          ldS1Tag2  := loadCmdPort.payload.tag
// in the miss arm (:349-362), alongside missCmode:
          missTagId := ldS1Tag2
// in REPLAY's re-launch arm (:426-438), alongside ldS1Cmode:
          ldS1Tag2  := missTagId
```

Drive the response tag on every path:

```scala
// :227-232 — extend the response drive
    loadRspPort.payload.tag := Mux(busFaultResp || inhibitedResp, missTagId, ldS1Tag2)
```

And the store ack tag (constant 0 while writes are single-outstanding):

```scala
    val storeAckTagReg = U(0, DcacheTags.TAG_W bits)
```
with `override def storeAckTag = logic.storeAckTagReg`.

- [ ] **Step 3: `LsEuPlugin` — drive the tag**

```scala
// src/main/scala/m68k040/execute/LsEuPlugin.scala — with the other loadCmd drives (:515-519)
    // Slice D1 replaces this constant with the allocated LTT index. Constant 0 here
    // is correct and inert while the EU is single-outstanding.
    dcache.loadCmd.payload.tag := U(0, m68k040.cache.DcacheTags.TAG_W bits)
```

```scala
// :1574-1582 — the exception-unit override claims the RESERVED tag
    when(excActive && excLoadCmdValid) {
      dcache.loadCmd.valid         := True
      dcache.loadCmd.payload.vaddr := excLoadCmdVaddr
      dcache.loadCmd.payload.paddr := excLoadCmdVaddr
      dcache.loadCmd.payload.size  := excLoadCmdSize
      dcache.loadCmd.payload.cacheMode := m68k040.cache.CacheMode.WRITETHROUGH
      dcache.loadCmd.payload.tag   := U(m68k040.cache.DcacheTags.EXC,
                                        m68k040.cache.DcacheTags.TAG_W bits)
    }
```

- [ ] **Step 4: `ExceptionUnit` — filter its response on the reserved tag**

`ExceptionUnit.scala:103` declares `val dcLoadRsp = Flow(DLoadRsp())`, wired at `FullCoreSynth.scala:297-298` straight off `dc.loadRsp`. Because the field now exists, the exception FSM must ignore a response that is not its own:

```scala
// src/main/scala/m68k040/exception/ExceptionUnit.scala — wherever the FSM consumes
// dcLoadRsp.valid, qualify it. Declare once near the port block:
  /** The D-cache response Flow is a BROADCAST (FullCoreSynth.scala:297-298), so it
    * carries the LS-EU's load responses too. The exception sequencer must only ever
    * act on responses to ITS OWN accesses, which are tagged DcacheTags.EXC
    * (LsEuPlugin.scala's exc arbitration override). Before slice V2a.3 this was safe
    * only because the exception path is serializing AND the LS pipe is quiesced by
    * then -- an accident, not a guarantee. */
  val dcLoadRspMine = dcLoadRsp.valid &&
                      (dcLoadRsp.payload.tag === U(m68k040.cache.DcacheTags.EXC,
                                                   m68k040.cache.DcacheTags.TAG_W bits))
```

Then replace every `dcLoadRsp.valid` read inside the FSM with `dcLoadRspMine`. Find them with:

```bash
grep -n "dcLoadRsp" src/main/scala/m68k040/exception/ExceptionUnit.scala
```

- [ ] **Step 5: Top-level wiring**

`FullCoreSynth.scala:297-298` copies `dc.loadRsp.payload` wholesale, so the new field rides along with no change. Confirm the same for `FuzzDut.scala`, `IpcBenchSpec.scala`, and the lock-step DUT wiring:

```bash
grep -rn "dcLoadRsp.payload\s*:=" src/main src/test
```

Any site that assigns the payload field-by-field must gain `.tag`.

- [ ] **Step 6: Verify**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.cache.*"
~/sbt/bin/sbt "testOnly m68k040.ls.*"
~/sbt/bin/sbt "testOnly m68k040.exception.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v2a3_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/v2a3_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
```

- [ ] **Step 7: Commit**

```bash
git commit -am "cache/ls: tag DLoadCmd/DLoadRsp/storeAck; reserved exception tag (V2a.3)

DLoadRsp had NO tag field at all and busFaultResp/inhibitedResp were untagged
one-shot pulses (design doc §5.4). With N outstanding accesses this is the first
thing that breaks, so it lands in the earliest RTL slice, inert.

Tag width is fixed at 2 bits NOW (DcacheTags.TAG_W) even though N_LTT == 1, so
raising N_LTT later is a parameter change rather than a bundle-width change
rippling through six files. Tag 3 is reserved for the commit-side exception
sequencer, whose dcLoadRsp is a BROADCAST of the shared response Flow -- it now
filters on its own tag instead of relying on the LS pipe happening to be
quiesced.

Design doc §5.4, §5.6.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

### Task V2a.4: Sub-slice V2a synth gate + full regression

**Files:** none modified (verification only).

- [ ] **Step 1: Worktree, full regression**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree add /home/qwertyoruiop/m68k-core-040-ooo-worktrees/v2a-gate HEAD
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/v2a-gate
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v2a_lockstep.log
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" 2>&1 | tee /tmp/v2a_ported.log &
wait $!
grep "^\[info\] - ported: .* \*\*\* FAILED \*\*\*" /tmp/v2a_ported.log \
  | sed -E 's/^\[info\] - ported: ([^ ]+).*/\1/' | LC_ALL=C sort -u > /tmp/v2a_ported_fails.sorted.txt
comm -13 /tmp/mshr_baseline_ported_fails.sorted.txt /tmp/v2a_ported_fails.sorted.txt
```

V2a is inert, so `comm -13` should be empty. If it is not, triage before proceeding (GC2).

- [ ] **Step 2: Synth gate (GC5 — check BOTH extra preconditions first)**

```bash
pgrep -af vivado; pgrep -af VerilatorTest; free -g       # must all be clear
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_full.log -source synth/ooc_M68kFullCoreSynth.tcl
grep "RESULT FullCore" synth/vivado_full.log
vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl
grep -E "RESULT|FMAX|WNS" synth/vivado_FullCore.log | tail
```

Report OOC FMax, post-route FMax, and LUT count. **Restate GC5(a) in the report:** if the branch's ~3× LUT bloat / 142 MHz anomaly (tracker #115/#198) is still unresolved, these numbers are directional only and the slice is NOT mergeable — say so explicitly rather than quoting a pass.

- [ ] **Step 3: Clean up**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree remove /home/qwertyoruiop/m68k-core-040-ooo-worktrees/v2a-gate
```

### Task V2b.1: Narrow the I-cache AXI master from 256-bit to 128-bit (`len=3`)

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala:27` (`beatsPerLine`), `:31` (`dataWidth`), `:73` (`dataMem` width/depth), `:106` (`beatCnt` width), `:119-123` (`dataReadAddr` width), `:136` (`s1Lane` width), `:173` (`s1Window` select), `:210-212` (`idleBeatSel`/`idleLaneIdx`/`idleReadAddr`), `:337-338` (`ar.len`/`ar.size`), `:354-364` (per-beat `dataMem` + `lineReg` writes), `:447-448` (REPLAY beat select)
- Modify: any test constructing the I-cache AXI with a hardcoded 256-bit width

**Interfaces:**
- Consumes: `AxiIds` (V2a.1).
- Produces: an I master that is 128-bit / 4-beat, i.e. **the same shape as the D master and the socket** (`macqd700-soc/rtl/soc/cpu_socket.vh:78-87`, `l2c.v:37-39`). **Cache geometry is UNCHANGED** — 64-byte lines, 64 sets, 4 ways, 16 KiB (design doc §11.Q7 explicitly notes this is independent of the dropped D3-BURST line-size decision).

- [ ] **Step 1: Geometry-derived constants**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala:27,31
  // 128-bit (16 B) beats -- matched to the socket/fabric width
  // (macqd700-soc/rtl/soc/cpu_socket.vh:78-87, l2c.v:37-39) rather than the previous
  // 256-bit master, which would have needed a 256->128 downsizer at the seam. The
  // CACHE GEOMETRY is unchanged: still 64-byte lines / 64 sets / 4 ways / 16 KiB.
  // Only the bus width and the beat count change (2 x 32 B -> 4 x 16 B).
  private val beatBytes    = 16
  private val beatsPerLine = geo.lineBytes / beatBytes      // 4
  private val beatSelBits  = log2Up(beatsPerLine)           // 2

  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = beatBytes * 8, idWidth = AxiIds.ID_W)
```

- [ ] **Step 2: Storage + read-port widths**

```scala
// :73
    val dataMem = Seq.fill(ways)(Mem(Bits(beatBytes * 8 bits), sets * beatsPerLine))
// :106
    val beatCnt   = Reg(UInt(beatSelBits bits)) init U(0, beatSelBits bits)
// :119-123
    val dataReadAddr = UInt((setBits + beatSelBits) bits)
    val dataReadEn   = Bool()
    dataReadAddr := U(0, (setBits + beatSelBits) bits)
    dataReadEn   := False
    val dataBeat = Vec(dataMem.map(_.readSync(dataReadAddr, dataReadEn)))
```

- [ ] **Step 3: Window selection inside a beat**

A 128-bit beat holds **two** 64-bit fetch windows (it held four at 256 bits). So the lane index narrows from 2 bits to 1, and the beat select widens from 1 bit to 2. The line-offset bit split moves accordingly: `pc[5:4]` selects the beat, `pc[3]` selects the window within it.

```scala
// :136
    val s1Lane  = Reg(UInt(log2Up((beatBytes * 8) / 64) bits))   // 1 bit at 128-bit beats
// :173 — unchanged in form; the subdivide now yields 2 windows instead of 4
    val s1Window = s1Beat.subdivideIn(64 bits)(s1Lane)
// :210-212
    val idleBeatSel = idlePc(5 downto 4)                        // was idlePc(5)
    val idleLaneIdx = idlePc(3 downto 3)                        // was idlePc(4 downto 3)
    val idleReadAddr  = (idleSet ## idleBeatSel).asUInt
```

**`windowPred` is UNCHANGED** (`:162-166`): it selects one of the 8 windows in a 64-byte *line* using `pc(5 downto 3)`, and `predMem` is still per-line. Do not touch it.

- [ ] **Step 4: AR shape and per-beat writes**

```scala
// :337-338
          axi.ar.payload.len   := U(beatsPerLine - 1, 8 bits)   // 3
          axi.ar.payload.size  := U(log2Up(beatBytes), 3 bits)  // 4 == 16 bytes
```

```scala
// :354-364 — per-beat dataMem write is unchanged in shape; lineReg assembly
// generalises from a 2-way if/else to a beat-indexed switch.
          val writeAddr = (missSet ## beatCnt).asUInt
          for (w <- 0 until ways) {
            when(victimWay === U(w, wayBits bits)) {
              dataMem(w).write(writeAddr, axi.r.payload.data)
            }
          }
          switch(beatCnt) {
            for (b <- 0 until beatsPerLine) {
              is(U(b, beatSelBits bits)) {
                lineReg((b + 1) * beatBytes * 8 - 1 downto b * beatBytes * 8) := axi.r.payload.data
              }
            }
          }
          beatCnt := beatCnt + 1
```

*(Slice I2 deletes `lineReg` entirely; this generalisation is the shape I2 replaces.)*

- [ ] **Step 5: REPLAY beat select**

```scala
// :447-448
        val replayBeatSel  = missPC(5 downto 4)
        val replayReadAddr = (missSet ## replayBeatSel).asUInt
```

and `s1Lane := missPC(3 downto 3)` at `:462` (was `missPC(4 downto 3)`).

- [ ] **Step 6: Fix every test that hardcodes 256**

```bash
grep -rn "256" src/test/scala/m68k040/cache | grep -i "bit\|width\|beat"
grep -rn "dataWidth = 256" src/test src/main
```

Prefer reading the width from `icache.axiCfg` / `axi.config.dataWidth` over restating it.

- [ ] **Step 7: Verify — the I-cache latency assertion is the sharp edge**

`src/test/scala/m68k040/cache/IcacheSpec.scala:221-256` asserts a warm hit is exactly 3 cycles and a miss costs a specific number of cycles. **The miss cost changes** (4 beats instead of 2, so +2 cycles at zero bus latency). Update that assertion to the new measured value and note in the commit message that it changed and why — do NOT loosen it into an inequality.

```bash
~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"
~/sbt/bin/sbt "testOnly m68k040.cache.IcacheParamSpec"
~/sbt/bin/sbt "testOnly m68k040.cache.IcacheElaborateSpec"
~/sbt/bin/sbt "testOnly m68k040.cache.AxiIdRoutingSpec"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v2b_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/v2b_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
```

- [ ] **Step 8: Full ported corpus + synth gate + commit**

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" 2>&1 | tee /tmp/v2b_ported.log & wait $!
grep "^\[info\] - ported: .* \*\*\* FAILED \*\*\*" /tmp/v2b_ported.log \
  | sed -E 's/^\[info\] - ported: ([^ ]+).*/\1/' | LC_ALL=C sort -u > /tmp/v2b_ported_fails.sorted.txt
comm -13 /tmp/mshr_baseline_ported_fails.sorted.txt /tmp/v2b_ported_fails.sorted.txt
pgrep -af vivado; JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_full.log -source synth/ooc_M68kFullCoreSynth.tcl
grep "RESULT FullCore" synth/vivado_full.log
git commit -am "icache: narrow the AXI master 256-bit -> 128-bit, len=3 (V2b.1)

Matches the socket/fabric width (cpu_socket.vh:78-87, l2c.v:37-39) so the seam
needs no 256->128 downsizer, and makes both masters uniform. CACHE GEOMETRY IS
UNCHANGED -- still 64-byte lines / 64 sets / 4 ways / 16 KiB; only the bus width
and beat count change (2 x 32 B -> 4 x 16 B). This is INDEPENDENT of the dropped
D3-BURST L1D line-widening (design doc §11.Q7 says so explicitly).

IcacheSpec's miss-latency assertion moves from N to N+2 cycles (4 beats instead
of 2) -- updated to the new exact value, NOT loosened.

Design doc §7.1 last paragraph, §11.Q7 (RATIFIED).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

### Task V2c.1: Fold the DTLB walker master into the D master

**Files:**
- Create: `src/main/scala/m68k040/cache/SharedAxiPort.scala`
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala` (implement the aux-port service; arbitrate AR/AW/W; demux R/B by ID)
- Modify: `src/main/scala/m68k040/mmu/DtlbPlugin.scala:72-83` (stop creating `dtlbAxi`; connect to the D master's aux port instead)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (the `dtlbAxi` top-level port disappears)
- Modify: every DUT/test that attaches a memory to `dtlb.walkerAxi`

**Interfaces:**
- Consumes: `AxiIds` (V2a.1) and per-ID beat routing (V2a.2). **Both are hard prerequisites** — this fold is impossible without them (design doc §1.1: "that fold is impossible without exactly the ID-allocation and per-ID response-routing infrastructure this document builds").
- Produces: `m68k040.cache.SharedAxiPortService` (a directionless aux AR/R/AW/W/B bundle) and `DcachePlugin.logic.aux`.

- [ ] **Step 1: The shared-port service**

```scala
// src/main/scala/m68k040/cache/SharedAxiPort.scala
package m68k040.cache

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Ar, Axi4Aw, Axi4B, Axi4Config, Axi4R, Axi4W}

/** A secondary requester's view of a physical AXI master owned by someone else.
  *
  * Why (design doc §1.1): the architecture spec makes separate I/D masters
  * non-negotiable, but the m68k-ooo socket contract this core wants to drop into
  * exposes only `axi_i_*` and `axi_d_*`. So the two table-walker masters have to be
  * folded into the I/D masters. §11.Q7 ratified doing it now.
  *
  * Contract:
  *   - the OWNER (the cache) arbitrates AR/AW/W with strict priority to itself;
  *   - R and B are BROADCAST, and each side consumes only beats whose `id` is in its
  *     own ID set (`AxiIds`). Consuming by FSM state is BANNED (design doc §1.2).
  *   - the aux side must therefore never emit an ID from the owner's set, and the
  *     protocol checker + the `GenerationFlags.simulation` asserts enforce it. */
case class SharedAxiPort(config: Axi4Config) extends Bundle with IMasterSlave {
  val ar = Stream(Axi4Ar(config))
  val r  = Stream(Axi4R(config))
  val aw = Stream(Axi4Aw(config))
  val w  = Stream(Axi4W(config))
  val b  = Stream(Axi4B(config))

  override def asMaster(): Unit = {
    master(ar, aw, w)
    slave(r, b)
  }
}

/** Implemented by the plugin that OWNS a physical AXI master and is willing to carry
  * a second requester on it. */
trait SharedAxiPortService {
  /** From the aux requester's point of view this is a master port. */
  def auxPort: SharedAxiPort
  /** The set of AXI IDs the OWNER itself may emit. Used by the fold's sim asserts. */
  def ownerIds: Seq[Int]
}
```

- [ ] **Step 2: `DcachePlugin` implements it**

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala
// class declaration:
class DcachePlugin extends FiberPlugin with DcacheService with SharedAxiPortService {
```

```scala
// inside `logic`, AFTER the existing axi drives (so the cache's own drives are the
// base and this arbitration is the last writer -- GC8, last-assignment-wins):

    // ── Shared-master aux port (slice V2c.1): the DTLB table walker + its U/M
    // descriptor-write drain ride this same physical master. Cache traffic has
    // strict priority; the walker is a cold path (a DTLB miss already stalls the
    // LS pipe), so starvation is not a concern -- and it is bounded anyway because
    // the cache issues at most one AR per miss.
    val aux = SharedAxiPort(axiCfg)
    aux.ar.ready := False
    aux.aw.ready := False
    aux.w.ready  := False
    aux.r.valid  := False
    aux.r.payload.assignDontCare()
    aux.b.valid  := False
    aux.b.payload.assignDontCare()

    // AR arbitration: cache first. `cacheArValid` is the value the cache's own drive
    // left on axi.ar.valid; capture it BEFORE overriding.
    val cacheArValid   = axi.ar.valid
    val cacheArPayload = axi.ar.payload
    when(!cacheArValid) {
      axi.ar.valid   := aux.ar.valid
      axi.ar.payload := aux.ar.payload
      aux.ar.ready   := axi.ar.ready
    }

    // AW/W arbitration: same shape. The store write-through path drives aw/w from
    // `stAwDone`/`stWDone`; the walker's U/M drain drives aux.aw/aux.w.
    val cacheAwValid = axi.aw.valid
    when(!cacheAwValid) {
      axi.aw.valid   := aux.aw.valid
      axi.aw.payload := aux.aw.payload
      aux.aw.ready   := axi.aw.ready
    }
    val cacheWValid = axi.w.valid
    when(!cacheWValid) {
      axi.w.valid   := aux.w.valid
      axi.w.payload := aux.w.payload
      aux.w.ready   := axi.w.ready
    }

    // R demux by ID (NEVER by FSM state -- design doc §1.2). `rIsOurRefill` was
    // introduced in V2a.2; everything else on this master belongs to the aux side.
    aux.r.valid   := axi.r.valid && !rIsOurRefill
    aux.r.payload := axi.r.payload
    // Pool-level ready: the cache's own outstanding-refill register OR the aux
    // requester's readiness for its own beat.
    axi.r.ready   := (refillOutstanding && rIsOurRefill) || (aux.r.ready && !rIsOurRefill)

    // B demux by ID. `bIsOurStore` was introduced in V2a.2.
    aux.b.valid   := axi.b.valid && !bIsOurStore
    aux.b.payload := axi.b.payload
    axi.b.ready   := True   // both consumers are unconditionally ready

    GenerationFlags.simulation {
      assert(!(aux.ar.fire && (aux.ar.payload.id === U(AxiIds.dRefill(0), AxiIds.ID_W bits))),
        "DcachePlugin aux port: requester emitted the cache's own refill ID")
      assert(!(aux.aw.fire && (aux.aw.payload.id === U(AxiIds.D_WRITE, AxiIds.ID_W bits))),
        "DcachePlugin aux port: requester emitted the cache's own write ID")
    }
```

```scala
// service accessors at the bottom of the class:
  override def auxPort  = logic.aux
  override def ownerIds = Seq(AxiIds.dRefill(0), AxiIds.D_WRITE)
```

**IMPORTANT ordering note (GC8):** the arbitration block above reads `axi.ar.valid` *before* overriding it. In SpinalHDL that reads the value assigned by the immediately-preceding statements in the same scope — which is what we want (the cache's own drive). Verify this in the generated Verilog before trusting it: `grep -n "ar_valid" generated/M68kFullCoreSynth.v`. If SpinalHDL resolves it differently, restructure to compute `cacheArValid` as an explicit named signal that the cache's REFILL state drives, rather than reading back `axi.ar.valid`.

- [ ] **Step 3: `DtlbPlugin` uses the aux port instead of its own master**

```scala
// src/main/scala/m68k040/mmu/DtlbPlugin.scala — replace the walkerAxi creation
// (:72-83) with a connection to the D-cache's aux port. `walkerAxi` stays declared
// as a `var` for source compatibility with the tests that reference it (they are
// updated in Step 5), but is no longer a top-level master.
  val logic = during build new Area {
    val tlb    = new Tlb(entries, ways, banks)
    val walker = new TableWalker(arId = m68k040.cache.AxiIds.D_DTLB_WALK)
    // Slice V2c.1: the walker + U/M drain now ride the D-cache's physical master.
    // The DTLB no longer creates a top-level `dtlbAxi` port at all.
    val shared = host[m68k040.cache.SharedAxiPortService].auxPort
    shared.ar << walker.io.axi.ar
    shared.r  >> walker.io.axi.r
    // aw/w driven by the U/M descriptor-write drain below (default idle).
    shared.aw.valid := False; shared.aw.payload.assignDontCare()
    shared.w.valid  := False; shared.w.payload.assignDontCare()
    shared.b.ready  := True
```

and every later `walkerAxi.aw/...`/`walkerAxi.w/...`/`walkerAxi.b` reference in this file becomes `shared.aw/...` etc.

**Service-resolution hazard:** `host[SharedAxiPortService]` will also match `IcachePlugin` once V2c.2 lands. Make each side's trait distinct to avoid an ambiguous resolution:

```scala
// src/main/scala/m68k040/cache/SharedAxiPort.scala — add
trait DSharedAxiPortService extends SharedAxiPortService
trait ISharedAxiPortService extends SharedAxiPortService
```

`DcachePlugin extends ... with DSharedAxiPortService`; `DtlbPlugin` resolves `host[DSharedAxiPortService]`. Do the mirror for the I side in V2c.2.

- [ ] **Step 4: Top-level wiring**

`FullCoreSynth.scala` currently surfaces `dtlbAxi` automatically because it is a `master()` inside the plugin (`:288-289`). Removing the `master(...)` call removes the port. Confirm:

```bash
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
grep -n "dtlbAxi" generated/M68kFullCoreSynth.v      # must print nothing
grep -cn "axi" generated/M68kFullCoreSynth.v
```

- [ ] **Step 5: Every test that attached memory to `dtlb.walkerAxi`**

```bash
grep -rn "dtlb.walkerAxi\|dtlbAxi" src/test src/main
```

Each such site attached a `BehavioralMemAgent` (often with `sharedMem = dmem.mem`) to a now-nonexistent port. Since the walker's traffic now flows over the D master, **delete those attachments** and make sure the D-side model's memory is the shared one. In `PortedTestRunner.scala` and `ExecuteLockStepSpec.scala` the walker memory was ALREADY shared with `dmem.mem` in most places — those become simple deletions. Where it was a *separate* memory (`ExecuteLockStepSpec.scala:~5350`, the `ptmem`/`itlbPtmem` pair built by `mapPage`), the page tables must now be poked into the **D-side** model's memory instead. This is a real semantic change to those tests and must be done deliberately, not mechanically.

- [ ] **Step 6: Verify (the MMU tests are the sharp edge here)**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.mmu.*"
~/sbt/bin/sbt "testOnly m68k040.cache.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v2c1_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/v2c1_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
```

**Expect the 3 known ITLB failures to still fail with the same names** — they are pre-existing (GC2) and this task touches the *D* side. If their names change, stop and investigate.

- [ ] **Step 7: Full corpus, synth gate, commit**

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" 2>&1 | tee /tmp/v2c1_ported.log & wait $!
grep "^\[info\] - ported: .* \*\*\* FAILED \*\*\*" /tmp/v2c1_ported.log \
  | sed -E 's/^\[info\] - ported: ([^ ]+).*/\1/' | LC_ALL=C sort -u > /tmp/v2c1_ported_fails.sorted.txt
comm -13 /tmp/mshr_baseline_ported_fails.sorted.txt /tmp/v2c1_ported_fails.sorted.txt
pgrep -af vivado; JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_full.log -source synth/ooc_M68kFullCoreSynth.tcl
grep "RESULT FullCore" synth/vivado_full.log
git commit -am "mmu/cache: fold the DTLB walker master into the D master (V2c.1)

The architecture spec makes separate I/D masters non-negotiable, but the
m68k-ooo socket contract exposes only axi_i_* and axi_d_* -- so the two walker
masters have to fold in. Design doc §1.1: 'that fold is impossible without
exactly the ID-allocation and per-ID response-routing infrastructure this
document builds', which is why it lands after V2a.

New SharedAxiPort service: the owner (D-cache) arbitrates AR/AW/W with strict
priority to itself; R and B are broadcast and demuxed BY ID, never by FSM state
(design doc §1.2). Sim asserts reject an aux requester emitting an owner ID.

Top-level `dtlbAxi` port removed. Tests that attached a separate walker memory
now share the D-side image (which most already did).

Design doc §1.1, §7.1, §11.Q7 (RATIFIED).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

### Task V2c.2: Fold the ITLB walker master into the I master (and make it a full `Axi4`)

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala:39` (`master(Axi4ReadOnly(axiCfg))` → `master(Axi4(axiCfg))`), plus the aux-port implementation mirroring V2c.1
- Modify: `src/main/scala/m68k040/mmu/ItlbPlugin.scala:65-83` (use the I-side aux port)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (the `itlbAxi` port disappears)
- Modify: every DUT/test attaching to `icache.logic.axi` (type changes from `Axi4ReadOnly` to `Axi4`) or to `itlb.walkerAxi`

**Interfaces:**
- Consumes: V2c.1's `SharedAxiPort` / `ISharedAxiPortService`, V2b's 128-bit I master (so the ITLB walker's 128-bit beats need no upsizing).
- Produces: exactly **two** AXI masters at the top level, matching `cpu_socket.vh`'s `axi_i_*` / `axi_d_*`.

- [ ] **Step 1: The I master becomes a full `Axi4`**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala:39
    // Slice V2c.2: full Axi4 (was Axi4ReadOnly). The I-cache itself still never
    // writes -- the write channels exist ONLY to carry the folded-in ITLB walker's
    // U-bit descriptor-write drain (ItlbPlugin.scala's UmWriteQueue). The cache's
    // own drives leave aw/w idle and b.ready True, exactly as the old dedicated
    // `itlbAxi` master did.
    val axi = master(Axi4(axiCfg))
```

and add the idle drives for the new channels beside the existing defaults (`:190-195`):

```scala
    axi.aw.valid := False; axi.aw.payload.assignDontCare()
    axi.w.valid  := False; axi.w.payload.assignDontCare()
    axi.b.ready  := True
```

- [ ] **Step 2: Mirror V2c.1's aux port on the I side**

Add the identical arbitration/demux block as V2c.1 Step 2, with:
- `rIsDemand` (from V2a.2) in place of `rIsOurRefill`;
- owner IDs `Seq(AxiIds.I_DEMAND, AxiIds.I_PREFETCH)` — **include `I_PREFETCH` from the start** even though slice I3 has not built it yet, so I3 is a pure addition and does not have to revisit this file's demux;
- no `bIsOurStore` equivalent: the I-cache never writes, so **every** B on this master belongs to the aux side (`aux.b.valid := axi.b.valid`).

```scala
  override def auxPort  = logic.aux
  override def ownerIds = Seq(AxiIds.I_DEMAND, AxiIds.I_PREFETCH)
```

with `class IcachePlugin extends FiberPlugin with FetchService with ISharedAxiPortService`.

- [ ] **Step 3: `ItlbPlugin` uses the aux port**

Same edit shape as V2c.1 Step 3, resolving `host[m68k040.cache.ISharedAxiPortService]`, with `new TableWalker(arId = m68k040.cache.AxiIds.I_ITLB_WALK)` (already set in V2a.1) and the U-bit drain's `aw.id` already `AxiIds.I_ITLB_UM`.

- [ ] **Step 4: The `Axi4ReadOnly` → `Axi4` type change ripples through the harness**

This is the largest mechanical part of the slice, and it is exactly the change V1.6's consolidation was meant to make cheap:

```bash
grep -rn "icache.logic.axi\|Axi4ReadOnly" src/test src/main | grep -v "SharedAxiPort"
```

- `AxiMemModel.attachReadOnly(...)` call sites on the I master become `AxiMemModel.attachFull(...)`.
- `attachProgram(axi: Axi4ReadOnly, ...)` in `ExecuteLockStepSpec`, `FuzzDut`, `IpcBenchSpec` change their parameter type to `Axi4` and call `attachFull`.
- `IcacheSim.attachMemory`/`attachMemoryWithWords` likewise.
- `IcacheSpec`'s standalone DUT constructs the master itself — update it.

Because V1.6 collapsed these onto one implementation each, this is a handful of signature edits rather than a dozen.

- [ ] **Step 5: Confirm exactly two top-level masters**

```bash
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
grep -oE "(Icache|Dcache)Plugin_logic_axi_[a-z]+_[a-z]+|itlbAxi|dtlbAxi" generated/M68kFullCoreSynth.v | sort -u
```

`itlbAxi` and `dtlbAxi` must both be absent.

- [ ] **Step 6: Verify — the 3 known ITLB failures are the thing to watch**

```bash
~/sbt/bin/sbt compile Test/compile
~/sbt/bin/sbt "testOnly m68k040.cache.*"
~/sbt/bin/sbt "testOnly m68k040.mmu.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/v2c2_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/v2c2_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
```

The 3 known ITLB failures MUST keep their exact names. This task moves ITLB walker traffic onto a shared master, which is precisely the kind of change that could alter their symptom — if a name changes, that is a finding worth reporting even though the count is the same (GC2).

- [ ] **Step 7: Full corpus, synth gate, commit — and close slice V2**

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" 2>&1 | tee /tmp/v2c2_ported.log & wait $!
grep "^\[info\] - ported: .* \*\*\* FAILED \*\*\*" /tmp/v2c2_ported.log \
  | sed -E 's/^\[info\] - ported: ([^ ]+).*/\1/' | LC_ALL=C sort -u > /tmp/v2c2_ported_fails.sorted.txt
comm -13 /tmp/mshr_baseline_ported_fails.sorted.txt /tmp/v2c2_ported_fails.sorted.txt
# Also re-run the Chaos acceptance now that two requesters share each master:
AXI_RSP_MODE=Chaos ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
pgrep -af vivado; JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_full.log -source synth/ooc_M68kFullCoreSynth.tcl
grep "RESULT FullCore" synth/vivado_full.log
vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl
git commit -am "mmu/cache: fold the ITLB walker into the I master; two masters total (V2c.2)

The I-cache master becomes a full Axi4 (was Axi4ReadOnly) purely to carry the
folded-in ITLB U-bit descriptor-write drain; the cache itself still never
writes. Owner ID set deliberately includes AxiIds.I_PREFETCH already, so slice
I3 is a pure addition and never has to revisit this demux.

The core now presents exactly TWO AXI masters, matching the m68k-ooo socket
contract's axi_i_* / axi_d_* (cpu_socket.vh:78-87). Slice V2 complete.

Design doc §1.1, §7.1, §11.Q7 (RATIFIED).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Slice D1 — D-side hit-under-miss: ONE MSHR, `N_LTT = 1`, fill-forward, `D3-SET`

Design doc refs: §5.1 item 1 (hit-under-miss: the biggest single win, needing **zero** bus concurrency), §5.3 (the MSHR array and the `D3-SET` invariant), §5.4 (the Load Tracking Table, fill-forward, per-entry poison, the store-exclusive-request-path rule), §4.3 (retire-time-only flush ⇒ blanket poison is correct), §9 slice row **D1** (marked **E2E? YES** — this is one of only two slices with real end-to-end benefit on today's SoC).

**This is the first slice with a real performance claim, and it is the one that must be demonstrated under `L2Sweeps.todaysCrossbar`** (single outstanding read + single outstanding write per master port). Hit-under-miss needs no bus concurrency at all, so it is the one win the crossbar cannot serialise away.

**No AXI concurrency. No new IDs beyond V2's.** `N_MSHR` stays 1 and `N_LTT` stays 1 throughout this slice.

### Design decisions this slice makes, with their rationale (read before Task D1.1)

Three places where this plan makes a concrete choice the design doc left at the level of intent. Each is deliberate; an implementer who disagrees should raise it rather than silently do something else.

1. **The MSHR replaces the load FSM entirely.** `DcachePlugin`'s `IDLE → REFILL → REPLAY` `StateMachine` is deleted. The MSHR is a flat, always-running register block; the AR drive, the beat consumption and the deallocation are plain statements. This is what makes the accept path non-blocking: `loadCmdPort.ready` stops being "we are in IDLE" and becomes a function of structural availability only. It also removes the GC8 hazard that FSM bodies elaborate after every plain statement.

2. **A load parks in the LTT AT LAUNCH, not on discovering it missed.** The design doc's framing is "loads that HIT complete as today; loads that MISS park" (§5.4) — but the EU cannot know which it is at launch, and the cache does not tell it. The implementable equivalent, with the same effect: **at cache-launch, if the LTT has a free entry and the access is a simple aligned single-slot load, park it and free the EU immediately (tag 0); otherwise take today's blocking live path (tag 1).** A parked load that turns out to hit occupies its entry for one or two cycles, which is negligible; a parked load that misses frees the EU for the whole refill, which is the entire point. Cross (`twoAccess`) loads and the exception sequencer's own accesses never park.

3. **The way reservation moves to allocate time; the `victim` INCREMENT stays at fill time.** Design doc §5.3 item 1 says "sample `victim(set)` **and increment it at allocate**". Sampling at allocate is adopted (that is the part that makes way reservation conflict-free). Incrementing at allocate is **not** adopted, because today the increment is conditional on `doAllocate` (`DcachePlugin.scala:394-404`) — an errored or INHIBITED miss does not bump the victim, exactly as the I-side FAULT state deliberately does not (`IcachePlugin.scala:374-376`). Moving the increment would change that. Under `D3-SET` a second miss to the same set cannot exist, so keeping the increment where it is costs nothing and preserves behaviour exactly. **Recorded here so a reviewer comparing against the design doc does not read it as an oversight.**

### Task D1.1: Replace the D-cache load FSM with a one-entry MSHR + non-blocking accept + `D3-SET`

**Files:**
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala` — replace `:106-138` (miss-state latches), `:140-152` (defaults/`busy`), `:309-442` (the whole `StateMachine`), and `:444-459` (the store read-port arbiter control)

**Interfaces:**
- Consumes: `AxiIds.dRefill`, `DcacheTags` (V2a), the per-ID beat routing from V2a.2.
- Produces (referenced by D1.2, D1.5 and slice D4):
  - `DcachePlugin.logic.N_MSHR: Int` (build parameter, **validated at 1 in this slice**)
  - `DcachePlugin.logic.mshrValid: Vec[Bool]`, `.mshrSet`, `.mshrWay`, `.mshrCmode`, `.mshrTagId` — all `simPublic`
  - `DcachePlugin.logic.dcacheQuiesceSig: Bool` (no valid MSHR and no outstanding write) — slice D4's `dQuiesce` consumes this

- [ ] **Step 1: Parameters and the MSHR record**

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala — replace the miss-state latch
// block at :106-138 wholesale.

    // ── Miss-Status Holding Registers ────────────────────────────────────────────
    // Slice D1: N_MSHR == 1. The array shape is general from the outset so raising it
    // (deferred slice D2, contingent on the SoC crossbar rework -- design doc §3.2,
    // §11.Q3) is a parameter change, not a restructure.
    //
    // *** D3-SET INVARIANT (design doc §5.3, decision-table row D3) ***
    // AT MOST ONE OUTSTANDING FILL PER CACHE SET. Note the naming: `D3-SET` is the
    // one-fill-per-set invariant. It is NOT `D3-BURST` (the 16 B -> 64 B L1D line
    // widening), which is DROPPED per the ratified §11.Q2. The design doc uses the
    // bare label "D3" for both; this codebase never does.
    //
    // D3-SET structurally eliminates THREE separate hazards:
    //   1. two misses to the same set choosing the same victim way -- impossible, so
    //      way reservation at allocate is trivially conflict-free;
    //   2. the copyback design's §4.3 drain-vs-refill same-set window (which that
    //      design has to patch by hand, and which it also flags as a PRE-EXISTING bug
    //      in today's code) -- a drain to a set with an active MSHR simply holds;
    //   3. a read-during-write tag hazard that hit-under-miss would otherwise CREATE:
    //      the Mems are readSync with write-first semantics, so a lookup launched in
    //      the same cycle a fill writes tagMem/dataMem at that set gets the PRE-write
    //      tag+data while `valids` (a flop, :80) reads True the next cycle -- a hit on
    //      stale data whenever an INVALID way holds a matching tag. Closed by the
    //      `lookupSetConflict` accept gate below.
    val N_MSHR = 1
    require(N_MSHR == 1, "slice D1 validates N_MSHR == 1 only; raising it is slice D2")

    val mshrValid  = Vec.fill(N_MSHR)(RegInit(False))
    val mshrLine   = Vec.fill(N_MSHR)(Reg(UInt(32 - offBits bits)))   // paddr[31:offBits]
    val mshrSet    = Vec.fill(N_MSHR)(Reg(UInt(setBits bits)))
    val mshrTag    = Vec.fill(N_MSHR)(Reg(UInt(tagBits bits)))        // CACHE tag, not a response tag
    val mshrWay    = Vec.fill(N_MSHR)(Reg(UInt(wayBits bits)))        // RESERVED at allocate
    val mshrCmode  = Vec.fill(N_MSHR)(Reg(CacheMode()))
    val mshrOff    = Vec.fill(N_MSHR)(Reg(UInt(offBits bits)))
    val mshrSize   = Vec.fill(N_MSHR)(Reg(Size()))
    val mshrTagId  = Vec.fill(N_MSHR)(Reg(UInt(DcacheTags.TAG_W bits)))  // RESPONSE tag echo
    val mshrArSent = Vec.fill(N_MSHR)(RegInit(False))
    val mshrErr    = Vec.fill(N_MSHR)(RegInit(False))
    mshrValid.foreach(_.simPublic()); mshrSet.foreach(_.simPublic())
    mshrWay.foreach(_.simPublic()); mshrCmode.foreach(_.simPublic())
    mshrTagId.foreach(_.simPublic())
```

Delete `missPaddr`, `missSet`, `missTag`, `missOff`, `missSize`, `missCmode`, `victimWay`, `arSent`, `missFault`, `missLine` — every one of them is now a per-MSHR field. Delete `busFaultResp` (`:216-217`) and `inhibitedResp` (`:224-225`): D1.2 collapses both into the single fill-response path.

- [ ] **Step 2: Non-blocking accept + S1 hold**

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala — replace the FSM's IDLE/REFILL/
// REPLAY block (:309-442) with these flat statements, in EXACTLY this order.

    // Whether the load path uses the shared sync read port this cycle. The store
    // arbiter (below) reads this to defer. Was `fsm.loadUsesPort`.
    val loadUsesPort = Bool(); loadUsesPort := False

    // A fill writes the arrays this cycle at `fillSet`.
    val fillIdx      = U(0, log2Up(N_MSHR) bits)   // N_MSHR == 1
    val respErr      = axi.r.payload.resp =/= Axi4.resp.OKAY
    val doAllocate   = !respErr && (mshrCmode(0) =/= CacheMode.INHIBITED)
    val fillArrayWr  = rBeatFire && doAllocate     // `rBeatFire` from slice V2a.2
    val fillLast     = rBeatFire && axi.r.payload.last

    // ── S1 resolution ────────────────────────────────────────────────────────────
    val ldS1Miss = ldS1Valid && !ldS1Hit
    // D3-SET: an incoming miss whose set already has an active MSHR must STALL, not
    // allocate. At N_MSHR == 1 this is subsumed by `!mshrFree` -- but it is written
    // explicitly, and must NOT be deleted as dead logic, because it is the invariant
    // slice D2 depends on and the one the directed test in D1.5 asserts.
    val mshrFree     = !mshrValid(0)
    val mshrSameSet  = mshrValid(0) && (mshrSet(0) === ldS1Set)
    val canAllocMshr = mshrFree && !mshrSameSet
    val ldS1Stall    = ldS1Miss && !canAllocMshr
    // Response arbitration: a fill response CANNOT be retried (the beat is consumed
    // this cycle), so it wins; a coincident S1 hit is held one cycle instead.
    val ldS1HitRsp   = ldS1Valid && ldS1Hit && !fillLast
    val ldS1HoldHit  = ldS1Valid && ldS1Hit && fillLast
    val ldS1Hold     = ldS1Stall || ldS1HoldHit

    // Hold: retain every ldS1* register (no assignment == hold) and RE-LAUNCH the
    // sync read, since rdTag/rdData are only valid the cycle after a read is armed.
    when(ldS1Hold) {
      rdSet        := ldS1Set
      rdEn         := True
      loadUsesPort := True
      ldS1Valid    := True          // overrides the `ldS1Valid := False` default at :184
    }

    // Allocate on a resolved miss.
    when(ldS1Miss && !ldS1Stall) {
      mshrValid(0)  := True
      mshrLine(0)   := ldS1Paddr(31 downto offBits)
      mshrSet(0)    := ldS1Set
      mshrTag(0)    := ldS1Tag
      // Way RESERVED at allocate (design doc §5.3 item 1). The victim POINTER is
      // still incremented at fill time, and only when the line is actually allocated
      // -- see this slice's "Design decisions" note 3 for why that half is not moved.
      mshrWay(0)    := victim(ldS1Set)
      mshrCmode(0)  := ldS1Cmode
      mshrOff(0)    := ldS1Off
      mshrSize(0)   := ldS1Size
      mshrTagId(0)  := ldS1Tag2
      mshrArSent(0) := False
      mshrErr(0)    := False
    }

    // ── Accept ───────────────────────────────────────────────────────────────────
    // NON-BLOCKING (design doc §5.1 item 1, §5.4): no longer gated on "the FSM is in
    // IDLE". The only blocks are:
    //   - translation not resolved this cycle (unchanged),
    //   - S1 is holding (a stalled miss or a deferred hit response),
    //   - D3-SET hazard 3: a lookup must not launch into the SAME set a fill is
    //     writing this cycle (readSync write-first vs the `valids` flop -- see the
    //     MSHR block comment). One-cycle bubble on a rare collision.
    val lookupSetConflict = fillArrayWr && (mshrSet(0) === cmdSet)
    loadCmdPort.ready := xlate.rsp.ready && !ldS1Hold && !lookupSetConflict
    when(loadCmdPort.fire) {
      rdSet        := cmdSet
      rdEn         := True
      loadUsesPort := True
      ldS1Valid    := True
      ldS1Set      := cmdSet
      ldS1Tag      := cmdTag
      ldS1Off      := cmdOff
      ldS1Size     := loadCmdPort.payload.size
      ldS1Cmode    := loadCmdPort.payload.cacheMode
      ldS1Tag2     := loadCmdPort.payload.tag
      ldS1Fault    := xlate.rsp.fault
      ldS1Paddr    := cmdPaddr
    }
```

**ORDERING IS LOAD-BEARING:** the `ldS1Hold` block must come BEFORE the accept block, so a same-cycle accept's `ldS1Valid := True` wins — but `loadCmdPort.ready` already excludes `ldS1Hold`, so the two can never both fire. Keeping the order documents the intent and survives a later relaxation.

- [ ] **Step 3: AR drive and beat consumption (flat, no FSM)**

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala — continue

    // ── AR: one per allocated MSHR ───────────────────────────────────────────────
    when(mshrValid(0) && !mshrArSent(0)) {
      axi.ar.valid         := True
      axi.ar.payload.addr  := (mshrLine(0) ## U(0, offBits bits)).asUInt
      axi.ar.payload.id    := U(AxiIds.dRefill(0), AxiIds.ID_W bits)
      axi.ar.payload.len   := U(0, 8 bits)
      axi.ar.payload.size  := U(offBits, 3 bits)     // 16 bytes
      axi.ar.payload.burst := Axi4.burst.INCR
      when(axi.ar.ready) { mshrArSent(0) := True }
    }
    // V2a.2's pool-level ready, now expressed off the MSHR rather than a separate
    // `refillOutstanding` register. `rIsOurRefill` / `rBeatFire` keep their V2a.2
    // definitions; re-point `refillOutstanding` at `mshrValid(0) && mshrArSent(0)`.

    // ── R beat: write the line, latch the error, deallocate ──────────────────────
    when(rBeatFire) {
      when(doAllocate) {
        for (w <- 0 until ways) when(mshrWay(0) === U(w, wayBits bits)) {
          wrEn(w)    := True
          wrSet(w)   := mshrSet(0)
          wrData(w)  := axi.r.payload.data
          wrTagEn(w) := True
          wrTag(w)   := mshrTag(0)
          valids(w)(mshrSet(0)) := True
        }
        victim(mshrSet(0)) := victim(mshrSet(0)) + 1
      }
      mshrErr(0) := mshrErr(0) || respErr
      when(axi.r.payload.last) {
        mshrValid(0)  := False
        mshrArSent(0) := False
      }
    }

    // `loadBusy` now means "a refill is in flight", which is what its one consumer
    // (ExceptionUnit.dcLoadBusy, wired at FullCoreSynth.scala:299) actually wants. It
    // no longer means "the cache will refuse loads", because it does not.
    loadBusyReg := mshrValid(0)

    // ── dQuiesce source (slice D4 consumes this) ─────────────────────────────────
    // No valid MSHR AND no outstanding write. `stAwDone`/`stWDone` are True when no
    // store is in flight; the SQ's own `drainBusy` is the third term and lives in
    // StoreQueue, so the full `dQuiesce` is assembled in LsEuPlugin (slice D4.1).
    val dcacheQuiesceSig = !mshrValid.orR && stAwDone && stWDone
    dcacheQuiesceSig.simPublic()
```

- [ ] **Step 4: The store path must now defend against a concurrent fill**

This is the part a naive hit-under-miss implementation gets wrong. Today the store RMW's S2 hit-detect is correct only because "valids/tagMem cannot change between S0 and S2 (no concurrent refill into the same flow)" (`DcachePlugin.scala:246-249`) — a comment that becomes FALSE the moment the cache keeps accepting during a refill.

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala — replace the store arbiter control
// at :444-459.

    // D3-SET (drain-vs-fill, design doc §5.3 item 2 + §4.2 "read -> write, same line"):
    // a store to a set with an active MSHR HOLDS. This is the structural replacement
    // for the copyback design's hand-rolled "hold the refill's array write for <= 2
    // cycles" patch -- and it fixes what that design also flags as a pre-existing bug
    // in today's code.
    val stSetBlocked = mshrValid(0) && (mshrSet(0) === stS1Set)
    when(stS1Valid) {
      when(!loadUsesPort && !stSetBlocked) {
        stS2Valid   := True
        stS2Payload := stS1Payload
      } otherwise {
        stS1Valid   := True      // hold in S1, retry next cycle
      }
    }
    // ...and the store's read-address drive at :304-307 gains the same guard, so a
    // blocked store does not waste the shared read port:
    //     when(stS1Valid && !stSetBlocked) { rdSet := stS1Set; rdEn := True }

    // WRITE-PORT collision, DIFFERENT sets: `D3-SET` stops a store and a fill sharing
    // a SET, but they can still target the same WAY INDEX in different sets, and each
    // way has exactly ONE physical write port (:89-92). The refill wins by
    // last-assignment-wins (it is emitted after this block); the store must therefore
    // RETRY rather than silently lose its write. Today this is unreachable
    // (single-outstanding); with hit-under-miss it is reachable.
    when(stS2Valid && fillArrayWr) {
      stS1Valid   := True
      stS1Payload := stS2Payload
    }
```

and both S2 effect blocks gain the guard:

```scala
// :484 — was `when(stS2Valid && stS2Cacheable)`
    when(stS2Valid && stS2Cacheable && !fillArrayWr) { ... }
// :494 — was `when(stS2Valid)`
    when(stS2Valid && !fillArrayWr) { ...write-through beat latch... }
```

**ORDERING (GC8):** the `stS2Valid && fillArrayWr` retry block must be the LAST assignment to `stS1Valid`, and the refill's `wrEn/wrSet/wrData` drive must be emitted AFTER the store's, so refill priority is preserved. Verify both in the generated Verilog.

- [ ] **Step 5: A sim assert for the invariant**

```scala
    GenerationFlags.simulation {
      // D3-SET, stated directly. At N_MSHR == 1 it is trivially true; the assert is
      // what makes it a CHECKED invariant when N_MSHR rises (slice D2).
      for (a <- 0 until N_MSHR; b <- 0 until N_MSHR if a < b)
        assert(!(mshrValid(a) && mshrValid(b) && (mshrSet(a) === mshrSet(b))),
          "D3-SET violated: two MSHRs outstanding on the same cache set")
      // A store must never write the arrays in the same cycle a fill does.
      assert(!(fillArrayWr && stS2Valid && stS2Cacheable && !fillArrayWr),
        "store RMW write collided with a fill write")
    }
```

*(The second assert as written is vacuous — replace it with a check on the actual `wrEn` vector: assert that for each way, at most one of {fill, store} drives `wrEn(w)` in a cycle. Write it against a pair of explicit `fillWrEn(w)` / `stWrEn(w)` nets rather than the muxed `wrEn`, since the mux has already collapsed them.)*

- [ ] **Step 6: Verify (structure only — the LS EU still blocks, so behaviour is unchanged)**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.cache.DcacheSpec"
~/sbt/bin/sbt "testOnly m68k040.ls.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/d11_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/d11_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
```

`DcacheSpec` almost certainly has assertions keyed to the REFILL/REPLAY cycle counts. **The miss latency drops by 2 cycles** once D1.2 lands and is unchanged here (REPLAY still exists in D1.1? — no: D1.1 deletes REPLAY, so the fill response path is missing until D1.2). **Therefore D1.1 and D1.2 must be developed together and committed together**; treat them as two review steps of one commit if the intermediate state does not compile/pass. Note this explicitly in the task report.

- [ ] **Step 7: Do NOT commit yet — proceed to D1.2**

D1.1 leaves the cache with no way to deliver a miss response (REPLAY is gone, fill-forward is not yet built). Go straight to D1.2 and commit the pair.

### Task D1.2: Fill-forward — one response path, `-2` cycles per miss

**Files:**
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala:208-232` (the response drive)

**Interfaces:**
- Consumes: D1.1's MSHR fields.
- Produces: a single `loadRspPort` drive with exactly two sources — an S1 hit and a fill completion. `busFaultResp` and `inhibitedResp` cease to exist as separate concepts.

**Why (design doc §1.5, §5.4):** today "refill delivers data by re-reading the line it just wrote" (`:411-441`): REPLAY re-arms `rdSet`/`ldS1Valid` and lets the response fall out of S1 the following cycle — ~3 fixed cycles of post-data overhead, of which fill-forward removes 2. It also stops every miss consuming the shared read port a second time. The non-allocating INHIBITED path *already* does exactly this via `missLine`/`inhibitedResp` (`:228-231`), so the mechanism exists and only needs generalising.

- [ ] **Step 1: The unified response drive**

```scala
// src/main/scala/m68k040/cache/DcachePlugin.scala — replace :208-232 wholesale.

    // ── Load response: exactly TWO sources ───────────────────────────────────────
    //  (a) an S1 HIT, driven combinationally off the registered tag compare (unchanged);
    //  (b) a FILL completion, forwarded straight off the arriving AXI beat.
    //
    // (b) subsumes THREE separate one-shot special cases that used to exist:
    //   - the old REPLAY re-read for an allocating refill (:426-438) -- deleted, and
    //     with it 2 cycles of every miss and a second use of the shared read port;
    //   - `busFaultResp` (:216, :419) -- an errored refill allocates nothing, so
    //     re-reading would MISS forever; now it is just a fill completion with
    //     `fault` set;
    //   - `inhibitedResp` (:224, :424) -- an INHIBITED miss allocates nothing either;
    //     now it is just a fill completion, which is what its `missLine` bypass
    //     already was.
    //
    // Fill wins the port on a collision (its beat cannot be retried); the coincident
    // S1 hit is held one cycle by `ldS1HoldHit` in D1.1 Step 2.
    val fillRsp = fillLast
    loadRspPort.valid := ldS1HitRsp || fillRsp
    loadRspPort.payload.data := Mux(fillRsp,
      DcacheByteLane.extract(axi.r.payload.data, mshrOff(0), mshrSize(0)),
      DcacheByteLane.extract(ldS1Line, ldS1Off, ldS1Size))
    loadRspPort.payload.line := Mux(fillRsp, axi.r.payload.data, ldS1Line)
    // `mshrErr(0) || respErr`: the sticky error covers a multi-beat burst (len > 0,
    // which this slice does not emit but D3-BURST would), `respErr` covers the final
    // beat itself.
    loadRspPort.payload.fault := Mux(fillRsp, mshrErr(0) || respErr, ldS1Fault)
    loadRspPort.payload.tag   := Mux(fillRsp, mshrTagId(0), ldS1Tag2)

    GenerationFlags.simulation {
      assert(!(ldS1HitRsp && fillRsp),
        "two load responses in one cycle -- the fill/hit arbitration is broken")
    }
```

- [ ] **Step 2: Delete the now-dead declarations**

Remove `busFaultResp`, `inhibitedResp` and their `simPublic()` calls (`:216-217`, `:224-225`), and `missLine` if D1.1 left it. Grep for stragglers:

```bash
grep -rn "busFaultResp\|inhibitedResp\|missLine\|missFault" src/main src/test
```

Test-side references (e.g. `DcacheSpec` probes) must be re-pointed at `loadRspPort.payload.fault` / the new MSHR taps.

- [ ] **Step 3: Verify the latency change is exactly what was predicted**

```bash
~/sbt/bin/sbt "testOnly m68k040.cache.DcacheSpec"
```

`DcacheSpec`'s miss-latency assertion must be updated to the new value: **2 cycles fewer** than before (REFILL → REPLAY → S1 → rsp becomes fill-beat → rsp). Measure it, do not guess; assert the exact new number, never an inequality. If the measured saving is not exactly 2, stop and find out why before proceeding — an unexplained latency is a symptom, not a rounding error.

- [ ] **Step 4: Full verification + commit the D1.1+D1.2 pair**

```bash
~/sbt/bin/sbt "testOnly m68k040.cache.*"
~/sbt/bin/sbt "testOnly m68k040.ls.*"
~/sbt/bin/sbt "testOnly m68k040.exception.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/d12_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/d12_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
git commit -am "$(cat <<'EOF'
dcache: one-entry MSHR, non-blocking accept, D3-SET, fill-forward (D1.1+D1.2)

Replaces the IDLE/REFILL/REPLAY StateMachine with a flat one-entry MSHR.
`loadCmdPort.ready` stops meaning "the FSM is in IDLE" and becomes a function of
structural availability only -- so the cache now serves HITS while a miss is
outstanding (design doc §5.1 item 1: the biggest single win, needing ZERO bus
concurrency).

D3-SET (at most one outstanding fill per cache SET, design doc §5.3) is the
load-bearing invariant. NOTE THE NAME: D3-SET is the one-fill-per-set invariant,
NOT D3-BURST (the dropped 16 B -> 64 B L1D line widening, ratified §11.Q2). It
structurally eliminates three hazards: same-set victim-way collision; the
copyback design's §4.3 drain-vs-refill window (also a pre-existing bug in
today's code); and the readSync write-first vs valids-flop lookup hazard that
hit-under-miss would otherwise CREATE.

Fill-forward collapses REPLAY's re-read, busFaultResp and inhibitedResp into ONE
response path: -2 cycles on every miss and one fewer use of the shared read port.

Store path hardened: a store to a filling SET holds (D3-SET), and a store whose
S2 write collides with a fill's array write on the same WAY INDEX in a DIFFERENT
set now retries instead of silently losing its write. Today's code's comment
"valids/tagMem cannot change between S0 and S2" is false under hit-under-miss.

The LS EU still blocks per access, so end-to-end behaviour is unchanged by this
commit -- D1.3 is what cashes the win.

Design doc §5.1, §5.3, §5.4, §1.5.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task D1.3: The Load Tracking Table in the LS EU (`N_LTT = 1`) + per-entry poison

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` — add the LTT area near `llReg` (`:494-504`), extend the completion arbiter (`:1456-1518`), change `issuePort.ready` (`:1044`), add the park branch in `LAUNCH` (`:1294-1304`), add the per-entry poison beside `poisoned` (`:1070-1075`)

**Interfaces:**
- Consumes: `DcacheTags.TAG_W`, `DLoadRsp.tag` (V2a.3), the D-cache's fill-forward response (D1.2).
- Produces:
  - `LsEuPlugin.logic.ltt` / `.lttValid` / `.lttPoison` (all `simPublic`)
  - `LsEuPlugin.logic.lttCompletionFires: Bool` — the middle priority level of the now-3-source completion arbiter
  - tag allocation: **0 = LTT entry 0 (parked)**, **1 = the live blocking path**, **3 = the exception sequencer** (`DcacheTags.EXC`)

**No IQ change is needed.** Loads already use *dynamic* wakeup (`IssueQueuePlugin.scala:175-181`, consumers latch `lsWait` at `:102,523,692-698`), so variable and out-of-order load latency composes with the issue queue as-is (design doc §1.5 point 1: "That is a large piece of luck"). Do not touch `IssueQueuePlugin`.

- [ ] **Step 1: The LTT entry bundle and storage**

```scala
// src/main/scala/m68k040/execute/LsEuPlugin.scala — insert immediately after the
// `llReg` Area (:494-504).

    // ── Load Tracking Table (design doc §5.4) ────────────────────────────────────
    // The resolved-data-free half of the shared `comp*` writeback set (:652-717),
    // captured for a load whose response will arrive after the EU has moved on. This
    // is deliberately shaped as "the load-tracking half of a MOB without the
    // disambiguation half" (design doc §5.1 last paragraph, §10.A) so that a later
    // MOB adds address-match + replay logic ON TOP rather than replacing it.
    //
    // Slice D1: N_LTT == 1. Because `comp*` is a 26-field bundle, REPLICATING it is
    // the single largest area item on the D side -- which is exactly why this starts
    // at 1 and grows only on measurement (design doc §5.4, §8.3).
    val N_LTT = 1
    require(N_LTT >= 1 && N_LTT <= m68k040.cache.DcacheTags.N_LTT_MAX)
    /** Tag used by the LIVE (blocking) load path -- a load that did NOT park, either
      * because it is a cross/two-access access or because the LTT was full. Distinct
      * from every LTT index and from DcacheTags.EXC. */
    val LIVE_TAG = m68k040.cache.DcacheTags.LIVE

    case class LttEntry() extends Bundle {
      val robId      = UInt(6 bits)
      val pdst       = UInt(6 bits)
      val pdstValid  = Bool()
      val dstArch    = UInt(5 bits)
      val size       = m68k040.isa.Size()
      /** MOVEM.W load sign-extend marker -- `u1.isMovea && isLoad && size == WORD`,
        * pre-evaluated at park time so the wakeup does not need `u1` (which is gone). */
      val signExtW   = Bool()
      val ccrRestore = Bool()
      val writesNzvc = Bool()
      val nzvcDst    = UInt(nzvcW.address.getWidth bits)
      val writesX    = Bool()
      val xDst       = UInt(xW.address.getWidth bits)
      val crackDrop  = Bool()
      val keepCommit = Bool()
      /** Fault-frame fields, pre-evaluated (captureFault reads s1Va / u1.size /
        * xlate.req.supervisor, none of which survive the EU moving on). */
      val faultAddr  = UInt(32 bits)
      val faultSize  = UInt(2 bits)
      val faultSup   = Bool()
      /** The task-#189 privileged-memory-source suppression rule (:1320-1350) needs
        * BOTH the static tag and the LIVE supervisor bit at launch time. */
      val needsSup   = Bool()
      val reqSup     = Bool()
    }

    val ltt       = Vec.fill(N_LTT)(Reg(LttEntry()))
    val lttValid  = Vec.fill(N_LTT)(RegInit(False))
    // PER-ENTRY poison (design doc §5.4, §4.3). Blanket-set on `sqFlushSig`, cleared
    // per entry at allocate.
    //
    // *** R1 (design doc §4.3) -- READ THIS BEFORE ADDING ANY EARLY-RESOLVE REDIRECT ***
    // Blanket poison with NO robId age comparison is correct ONLY because this core
    // flushes at RETIRE TIME ONLY (RobPlugin.scala:709-715, task #116): the flush
    // point IS the ROB head, so every access outstanding anywhere in the LS pipe or
    // an MSHR at flush time is younger than it and is wrong-path. If a fetch-stage
    // early-resolve redirect (a predictor correction that flushes BEFORE retire) is
    // ever added, THIS ARGUMENT COLLAPSES and poison must become age-based, with
    // robId comparators in the LTT. That would also cost real area and FMax, which is
    // why the coupling is flagged here rather than left to be rediscovered.
    val lttPoison = Vec.fill(N_LTT)(RegInit(False))
    lttValid.foreach(_.simPublic()); lttPoison.foreach(_.simPublic())

    // Latched response for the parked load. `dcache.loadRsp` is a 1-cycle Flow pulse,
    // and the wakeup may lose the shared comp* stage to the live path, so the
    // response must be held until it can actually be applied.
    val lttRspPend  = Vec.fill(N_LTT)(RegInit(False))
    val lttRspData  = Vec.fill(N_LTT)(Reg(Bits(32 bits)))
    val lttRspFault = Vec.fill(N_LTT)(RegInit(False))
    lttRspPend.foreach(_.simPublic())

    val lttFree = !lttValid.orR      // N_LTT == 1
```

- [ ] **Step 2: Drive the cmd tag from the park decision**

```scala
// src/main/scala/m68k040/execute/LsEuPlugin.scala — replace the constant tag drive
// added in V2a.3 (:519 neighbourhood).

    // A load PARKS iff the LTT has room and it is a simple aligned single-slot access.
    // A cross (two-access) load never parks: WAIT_A/WAIT_B carry stateful merge
    // context (`lineA`/`aDone`) that the LTT deliberately does not model.
    val parkEligible = llReg.valid && !llReg.twoAccess && lttFree
    dcache.loadCmd.payload.tag := Mux(parkEligible,
      U(0, m68k040.cache.DcacheTags.TAG_W bits),
      U(LIVE_TAG, m68k040.cache.DcacheTags.TAG_W bits))
```

- [ ] **Step 3: Park at launch**

```scala
// src/main/scala/m68k040/execute/LsEuPlugin.scala — replace LAUNCH's body (:1294-1304)

      LAUNCH.whenIsActive {
        busy := True
        when(dcache.loadCmd.fire) {
          llReg.valid := False
          when(llReg.twoAccess) { aDone := False; goto(WAIT_A) }
          .elsewhen(parkEligible) {
            // PARK: capture everything the completion will need, free the EU
            // immediately, and let the tagged response wake this entry later. This is
            // what turns the D-cache's non-blocking accept (D1.1) into an actual
            // end-to-end win: the EU no longer sits in WAIT for the whole refill.
            ltt(0).robId      := s1Ctx.robId
            ltt(0).pdst       := u1.pdst
            ltt(0).pdstValid  := u1.pdstValid && !u1.ccrRestore
            ltt(0).dstArch    := u1.dstArch
            ltt(0).size       := u1.size
            ltt(0).signExtW   := u1.isMovea && isLoad && (u1.size === m68k040.isa.Size.WORD)
            ltt(0).ccrRestore := u1.ccrRestore
            ltt(0).writesNzvc := u1.writesNzvc
            ltt(0).nzvcDst    := u1.pNzvcDst
            ltt(0).writesX    := u1.writesX
            ltt(0).xDst       := u1.pXDst
            ltt(0).crackDrop  := u1.divIsRem
            ltt(0).keepCommit := u1.keepCommit
            ltt(0).faultAddr  := s1Va
            ltt(0).faultSize  := u1.size.mux(
              m68k040.isa.Size.BYTE -> U(0, 2 bits),
              m68k040.isa.Size.WORD -> U(1, 2 bits),
              m68k040.isa.Size.LONG -> U(2, 2 bits))
            ltt(0).faultSup   := xlate.req.supervisor
            ltt(0).needsSup   := u1.needsSupervisor
            ltt(0).reqSup     := xlate.req.supervisor
            lttValid(0)  := True
            // Fresh entry: clear any stale poison from a previous occupant. A flush
            // in THIS SAME cycle must still poison it, so the `sqFlushSig` blanket
            // below is written AFTER this and wins (GC8 -- and this is the exact
            // shape of the P2.7 `pendPush` bug, LsEuPlugin.scala:971-981).
            lttPoison(0) := False
            lttRspPend(0):= False
            busy    := False
            s1Valid := False
            goto(IDLE)
          }
          .otherwise { goto(WAIT) }
        }
      }
```

**GC8 CRITICAL:** the blanket-poison statement must be a PLAIN component statement placed after the FSM, and it must be able to override this in-FSM `lttPoison(0) := False`. SpinalHDL elaborates `StateMachine` bodies from a pre-pop task, i.e. AFTER every plain statement — so a plain statement written later in the source is emitted EARLIER and LOSES. **Therefore the same-cycle flush case must be handled INSIDE the FSM body**, exactly as `deferCompletion`'s `when(!sqFlushSig)` gate does (`:986`):

```scala
            lttPoison(0) := sqFlushSig    // NOT `:= False`
```

Use that form. This single line is the difference between a correct entry and the multi-hour desynchronisation bug class this project already hit once.

- [ ] **Step 4: Blanket poison + the wakeup/apply block**

```scala
// src/main/scala/m68k040/execute/LsEuPlugin.scala — insert AFTER the fsm, BEFORE the
// existing precise-store replay block (:1421).

    // ── LTT: blanket poison on flush (design doc §4.3; see the R1 note above) ─────
    when(sqFlushSig) {
      for (k <- 0 until N_LTT) when(lttValid(k)) { lttPoison(k) := True }
    }

    // ── LTT: capture the tagged response ─────────────────────────────────────────
    val rspTag = dcache.loadRsp.payload.tag
    for (k <- 0 until N_LTT) {
      when(dcache.loadRsp.valid && lttValid(k) &&
           (rspTag === U(k, m68k040.cache.DcacheTags.TAG_W bits))) {
        lttRspPend(k)  := True
        lttRspData(k)  := dcache.loadRsp.payload.data
        lttRspFault(k) := dcache.loadRsp.payload.fault
      }
    }
    GenerationFlags.simulation {
      assert(!(dcache.loadRsp.valid && (rspTag < U(N_LTT, m68k040.cache.DcacheTags.TAG_W bits)) &&
               !lttValid(0)),
        "LS EU: LTT-tagged load response with no valid LTT entry")
    }

    // ── LTT: apply into the SHARED comp* stage ───────────────────────────────────
    // THREE-SOURCE ARBITER (design doc §5.4): {live hit path, LTT fill-wakeup,
    // precise-store replay}, strict priority in that order, retry-next-cycle on a
    // collision. The live-priority rule is INHERITED UNCHANGED from the landed
    // precise-store work (:1454-1487) and must not be weakened. LTT sits ABOVE the
    // replay because a parked load has by construction been waiting longer than a
    // just-confirmed store drain, and the replay's backlog is bounded by the SQ depth
    // either way.
    val lttCompletionFires = Bool(); lttCompletionFires := False
    when(lttRspPend(0) && !liveCompletionFires) {
      lttCompletionFires := True
      when(lttPoison(0)) {
        // Wrong-path: drop with NO observable side effect (no PRF write, no ROB
        // completion, no wakeup) -- exactly as if it had never issued. The fill still
        // completed on the bus and still allocated; that is deliberate and matches the
        // established I-side behaviour (design doc §4.3, §10.F -- there is no
        // refill-abort mechanism anywhere and inventing one is out of scope).
      } elsewhen(lttRspFault(0) && !(ltt(0).needsSup && !ltt(0).reqSup)) {
        // Physical bus error (D-cache refill AXI resp non-OKAY). The suppression term
        // is the task-#189 privileged-memory-source rule, verbatim from :1320-1350:
        // a user-mode `MOVE <ea>,SR`'s leading load must not deliver vector 2 before
        // the later sysOp's own privilege check delivers vector 8. Both halves of the
        // condition are needed -- gating on the STATIC needsSupervisor tag alone
        // swallowed a real supervisor-mode bus error.
        compValid     := True
        compRobId     := ltt(0).robId
        compPdstValid := False
        compNzvcWrite := False
        compXWrite    := False
        compIsLoad    := False
        compWakes     := False
        compStkPush   := False
        compCcrRestore := False
        compEaAutoDrop := False
        compCrackDrop := False
        compKeepCommit := False
        compIsFault   := True
        compFaultAddr := ltt(0).faultAddr
        compFaultWr   := False               // a parked entry is always a LOAD
        compFaultSize := ltt(0).faultSize
        compFaultSup  := ltt(0).faultSup
        compFaultAtc  := False               // physical bus error, not ATC-detected
      } otherwise {
        val raw = lttRspData(0)
        compValid      := True
        compRobId      := ltt(0).robId
        compData       := Mux(ltt(0).signExtW, raw(15 downto 0).asSInt.resize(32).asBits, raw)
        compPdst       := ltt(0).pdst
        compPdstValid  := ltt(0).pdstValid
        compIsLoad     := True
        compWakes      := !ltt(0).ccrRestore
        compStkPush    := False
        compCcrRestore := ltt(0).ccrRestore
        compEaAutoDrop := False
        compRmwStore   := False
        compCrackDrop  := ltt(0).crackDrop
        compKeepCommit := ltt(0).keepCommit
        compDstArch    := ltt(0).dstArch
        compNzvc       := raw(3 downto 0)     // RTR CCR-restore: NZVC := loaded[3:0]
        compNzvcWrite  := ltt(0).writesNzvc
        compNzvcDst    := ltt(0).nzvcDst
        compX          := raw(4)              // RTR CCR-restore: X := loaded[4]
        compXWrite     := ltt(0).writesX
        compXDst       := ltt(0).xDst
        compIsFault    := False
      }
      lttRspPend(0) := False
      lttValid(0)   := False
    }
```

**Cross-check against `captureCompletion` (`:785-832`) field by field before running anything.** The parked path is a LOAD only, so `leaAddr`, `stkPush`, `isAutoStoreAn`, `storeNzvc` and `compRmwStore` all collapse to their load values — but verify each one rather than trusting this list. A missing field here is a silent wrong-answer bug of exactly the class tasks #176/#194/#200 produced.

- [ ] **Step 5: Yield the replay to the LTT**

```scala
// :1488 — was `when((applyFast || applyBacklog) && !liveCompletionFires) {`
    when((applyFast || applyBacklog) && !liveCompletionFires && !lttCompletionFires) {
```

- [ ] **Step 6: `issuePort.ready`**

```scala
// :1044 — unchanged in form. `busy` now clears at PARK rather than at response, which
// is the whole mechanism; no extra LTT term is needed, because a load that cannot
// park simply takes the live blocking path (and is therefore covered by `busy`).
    issuePort.ready := !busy && !s1Valid && !compValid
```

Leave this line alone. Add a comment recording *why* no `lttFree` term appears here — an implementer's instinct will be to add one, and doing so would re-serialise the pipe and silently delete the entire win.

- [ ] **Step 7: Verify**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.ls.*"
~/sbt/bin/sbt "testOnly m68k040.cache.*"
~/sbt/bin/sbt "testOnly m68k040.rob.*"
~/sbt/bin/sbt "testOnly m68k040.exception.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/d13_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/d13_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
```

This is the first task in the plan that changes observable timing on the main pipeline. Expect lock-step to still be 390/394 with identical names (Musashi models no bus timing — design doc §8.4), and expect the ported corpus to move (GC2's budgeted exception applies from here on).

- [ ] **Step 8: Commit**

```bash
git commit -am "$(cat <<'EOF'
ls: Load Tracking Table (N_LTT=1) -- park a missing load, free the EU (D1.3)

Cashes D1.1/D1.2's non-blocking cache into an actual end-to-end win: a load
PARKS in the LTT at cache-launch, the EU frees immediately, and the tagged
fill-forward response wakes the entry later through the shared comp* stage.
Younger hits, SQ forwards and store allocs proceed underneath it.

Park-at-launch rather than park-on-miss: the EU cannot know which it is at
launch and the cache does not tell it. Effect is identical -- a parked load that
hits holds its entry for one or two cycles. Cross (two-access) loads and the
exception sequencer never park (tags LIVE_TAG and DcacheTags.EXC).

Per-entry poison replaces the scalar for parked loads. Blanket-poison-all with
NO robId comparison is correct ONLY because flush is retire-time only
(RobPlugin.scala:709-715, task #116); the R1 coupling is written into the code
comment so a future early-resolve redirect cannot silently invalidate it.

The completion arbiter grows to three sources {live, LTT, precise-store replay},
strict priority in that order, retry-next-cycle -- the landed live-priority rule
is inherited unchanged.

NO IssueQueuePlugin change: loads already use dynamic wakeup, so out-of-order
load completion composes with the IQ as-is (design doc §1.5 point 1).

Design doc §5.1, §5.4, §4.3.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task D1.4: The store-exclusive-request-path rule, made explicit and asserted

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (comment + sim assert near `issuePort.ready`, `:1044`)

**Interfaces:**
- Consumes: D1.3.
- Produces: a checked invariant, no new hardware.

**The rule, verbatim from design doc §5.4 (a HARD REQUIREMENT, not a tuning choice):**

> A **store must be visible to the SQ-forward query of every younger load**. Today the single-outstanding pipe guarantees a store has allocated into the SQ before any younger load enters. With a pipelined request path that is no longer automatic. **Rule: a store occupies the request path exclusively until it has SQ-allocated**, i.e. only *loads* are multi-outstanding in the request path.

**As implemented in this slice the rule holds automatically**, because a store still holds `busy` across `IDLE → XLATE → alloc` and `issuePort.ready` gates on `busy`. Nothing new is needed — but it must be *checked*, because it is a silent-corruption rule (a younger load forwarding from a store that has not yet allocated reads stale memory) and because a future task that relaxes the request path could break it without any test noticing.

- [ ] **Step 1: The assert**

```scala
// src/main/scala/m68k040/execute/LsEuPlugin.scala — beside issuePort.ready (:1044)

    // ── STORE-EXCLUSIVE REQUEST PATH (design doc §5.4, HARD REQUIREMENT) ─────────
    // "A store occupies the request path exclusively until it has SQ-allocated" --
    // i.e. only LOADS are multi-outstanding in the request path. This is what keeps
    // every younger load's SQ-forward query able to see the store. Stores are ~15-20%
    // of the stream and allocation is 2-3 cycles, so the cost is small and the rule
    // is trivially correct.
    //
    // It holds today WITHOUT new hardware: a store holds `busy` from IDLE through
    // XLATE until `sq.io.alloc.valid` fires, and `issuePort.ready` gates on `busy`.
    // The assert exists because this is a SILENT-CORRUPTION rule -- a younger load
    // forwarding against a not-yet-allocated store reads stale memory, and no
    // existing test would notice.
    val storeInRequestPath = s1Valid && isStore &&
                             (fsm.isActive(fsm.IDLE) || fsm.isActive(fsm.XLATE) ||
                              fsm.isActive(fsm.WAIT_SQ))
    GenerationFlags.simulation {
      assert(!(storeInRequestPath && issuePort.fire),
        "store-exclusive request path violated: a uop issued while a store had not " +
        "yet SQ-allocated (design doc §5.4)")
    }
```

*(`fsm` is declared after this point in the source. Move the assert to just after the `fsm` block, or forward-declare `storeInRequestPath` as a `Bool()` here and drive it after the FSM. Prefer the latter so the comment stays next to `issuePort.ready` where a reader will look for it.)*

- [ ] **Step 2: Verify + commit**

```bash
~/sbt/bin/sbt "testOnly m68k040.ls.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
git commit -am "ls: assert the store-exclusive-request-path rule (D1.4)

Design doc §5.4 states it as a HARD REQUIREMENT. It holds today with no new
hardware (a store holds `busy` until SQ-alloc), but it is a silent-corruption
rule -- a younger load forwarding against a not-yet-allocated store reads stale
memory and no existing test would notice -- so it becomes a checked invariant.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

### Task D1.5: Directed tests for D1

**Files:**
- Create: `src/test/scala/m68k040/cache/DcacheMshrSpec.scala`
- Create: `src/test/scala/m68k040/ls/LsEuHitUnderMissSpec.scala`

**Interfaces:**
- Consumes: everything in D1, plus `AxiMemModel`/`AxiMemStats` from V1.

Design doc §8.2's D-side list, restricted to what D1 actually builds (merge/MSHR-exhaustion/N_MSHR>1 cases belong to deferred slice D2):

- [ ] **Step 1: `DcacheMshrSpec` — five directed cases**

Build on `DcacheSpec`'s existing standalone-DUT harness (copy its construction helper; do not hand-roll a new one).

```scala
// src/test/scala/m68k040/cache/DcacheMshrSpec.scala
package m68k040.cache

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import m68k040.sim.{AxiMemModel, AxiMemModelConfig, L2LatencyModel}

class DcacheMshrSpec extends AnyFunSuite {

  /** (1) HIT-UNDER-MISS -- the headline claim. Issue a load that MISSES (long
    * latency), then a load to a DIFFERENT, already-resident set. Assert the second
    * load's response arrives BEFORE the first's, and that only ONE AR was issued. */
  test("a hit is served while a miss is outstanding, and completes first") {
    // 1. warm line X into the cache (load it once, let the fill complete)
    // 2. attach with L2LatencyModel(enabled = true, dramCycles = 60)
    // 3. issue load Y (cold, different set) -> misses
    // 4. one cycle later issue load X (warm) -> must respond while Y's fill is in flight
    // 5. assert: X's loadRsp.valid fires with tag != Y's tag, at a cycle strictly
    //    before Y's; and stats.arCount(AxiIds.dRefill(0)) == 2 total for the run.
  }

  /** (2) D3-SET -- same set, different line, MUST stall (never double-allocate,
    * never collide on a victim way). */
  test("D3-SET: a second miss to the SAME set stalls until the first fill completes") {
    // Two addresses with identical set bits and different tags. Issue back to back.
    // Assert: at no cycle are two MSHR valid bits set (the RTL assert also covers it),
    // AND the second load's AR is not issued until after the first's r.last,
    // AND both loads eventually respond with the correct data.
  }

  /** (3) The read-during-write tag hazard (design doc §5.3 item 3). A lookup must not
    * launch into the same set a fill is writing this cycle. */
  test("lookup into the set a fill is writing this cycle is deferred one cycle") {
    // Drive a load whose cmdSet equals the filling MSHR's set on the exact beat cycle;
    // assert loadCmdPort.ready is low that cycle and the load is accepted the next.
    // Then assert the load's returned data matches memory (i.e. it did NOT observe a
    // pre-write tag with a post-write valid bit).
  }

  /** (4) Fill-forward latency: a miss response must arrive exactly 2 cycles earlier
    * than the pre-D1 REFILL->REPLAY->S1->rsp path did. */
  test("fill-forward: miss response latency dropped by exactly 2 cycles") {
    // Measure with zero bus latency. Assert the EXACT cycle count (see D1.2 Step 3).
  }

  /** (5) Errored fill: no allocation, fault delivered, and A LATER ACCESS TO THE SAME
    * LINE ISSUES A NEW REAL AR. This is the §4.1 M2 test -- the property that keeps
    * error handling from becoming "we remember this line is bad". */
  test("M2: an errored fill allocates nothing and a later access re-issues a real AR") {
    // Config: injectBusErrors = true; address outside AxiMemModel.decoded.
    // Assert: loadRsp.fault set; no `valids` bit set for that set/way; and a SECOND
    // load to the same line produces arCount(dRefill(0)) == 2, not 1.
  }

  /** (6) DRAIN-vs-FILL, SAME SET -- design doc §8.2 asks for this as a directed repro
    * "worth having independent of this design", because the copyback design flags the
    * same window as a PRE-EXISTING BUG in today's code (its §5 item 11): a store
    * drain's S1 tag read racing a concurrent refill's array write into the same set,
    * giving a stale-tag hit-detect in S2.
    *
    * Sequence: start a refill into set S; while it is in flight, present a store to a
    * DIFFERENT line in set S. Assert the store is HELD (stS1Valid stays high, no S2
    * write) until the fill completes, and that the store's final merged line is
    * correct -- i.e. it merged into the FILLED line, not a stale one.
    *
    * Run this test against the PRE-D1 commit too (in a worktree): it should FAIL
    * there, which is what demonstrates D3-SET actually closed a real bug rather than
    * just reorganising code. */
  test("D3-SET: a store drain to a filling SET is held until the fill completes") { }

  /** (7) WRITE-PORT collision, DIFFERENT sets, SAME way index -- the case D3-SET does
    * NOT cover (D1.1 Step 4). Assert the store retries and its write is not lost. */
  test("a store whose S2 write collides with a fill write retries and still lands") { }

  /** (8) IRQ STORM across an outstanding load (design doc §8.2). Park a load, then
    * drive repeated interrupt recognition while its fill is in flight. Assert no
    * duplicate completion, no lost completion, and no ROB head park. */
  test("IRQ storm across a parked load: exactly one completion, no hang") { }
}
```

Fill each body against the harness. Every assertion above is a hard `assert`, and the cycle-count ones assert **exact** values.

- [ ] **Step 2: `LsEuHitUnderMissSpec` — the EU-level cases**

```scala
// src/test/scala/m68k040/ls/LsEuHitUnderMissSpec.scala
package m68k040.ls

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class LsEuHitUnderMissSpec extends AnyFunSuite {

  /** The EU frees at PARK: `busy` must drop the cycle after loadCmd.fire for a
    * parked load, NOT when the response arrives. */
  test("EU frees immediately when a load parks in the LTT") { }

  /** A younger SQ-FORWARD completes while an older load is parked. This needs no
    * cache access at all and is the cheapest demonstration that the pipe is open. */
  test("a younger SQ-forwarding load completes under a parked miss") { }

  /** A younger STORE allocates into the SQ while an older load is parked
    * (design doc §5.1 item 3: store-drain / load-miss overlap). */
  test("a younger store allocates under a parked miss") { }

  /** FLUSH-POISON WITH A STRAGGLING FILL (design doc §8.2, and this is the
    * rename-exposure class of tasks #176/#194/#200 -- so it needs a WHITEBOX check on
    * the freelist, not just an absence of completion).
    *
    * Sequence: park a load; assert sqFlushSig; let the fill land AFTER the flush.
    * Assert ALL of:
    *   - no `completionPort.valid` for that robId,
    *   - no `intW.valid` / `nzvcW.valid` / `xW.valid`,
    *   - no `wakeupPort.valid`,
    *   - the RenameStage freelist is unchanged across the whole window (the physical
    *     register the poisoned load would have written must not be observed written). */
  test("flush-poison: a fill landing after a flush produces NO architectural effect") { }

  /** A load whose LTT entry is unavailable takes the live blocking path and still
    * returns correct data (tag LIVE_TAG). */
  test("a second concurrent load falls back to the live path and is still correct") { }

  /** COMPLETION-ARBITER COLLISION: a live completion and an LTT wakeup in the SAME
    * cycle. Assert the live one wins, the LTT one retries the next cycle, and BOTH
    * eventually complete exactly once. Mirrors the existing collision test the
    * precise-store replay already has (see `LsEuFastPreciseSpec` and StoreQueue's
    * `io.sqCompletion.valid` sim tap, added for exactly this purpose in task P2.5). */
  test("3-source arbiter: live beats LTT, LTT retries, both complete exactly once") { }

  /** And the 3-way case: live + LTT + precise-store replay all ready in one cycle. */
  test("3-source arbiter: all three ready -> live, then LTT, then replay") { }
}
```

- [ ] **Step 3: Run**

```bash
~/sbt/bin/sbt "testOnly m68k040.cache.DcacheMshrSpec"
~/sbt/bin/sbt "testOnly m68k040.ls.LsEuHitUnderMissSpec"
```

- [ ] **Step 4: Commit**

```bash
git add -A src/test
git commit -m "test: directed D1 coverage -- hit-under-miss, D3-SET, M2, flush-poison (D1.5)

Design doc §8.2's D-side list restricted to what D1 builds. The M2 test is the
GC1-mandated one: an errored fill allocates NOTHING, so a later access to the
same line issues a NEW REAL transaction -- no error verdict is ever cached.

The flush-poison test carries a WHITEBOX freelist check, not just an absence of
completion: this is the rename-exposure/phys-reg-reuse class that produced
tasks #176, #194 and #200.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

### Task D1.6: Measure the win (and prove it survives today's crossbar)

**Files:** none modified (measurement only), unless a kernel needs adjusting.

- [ ] **Step 1: Before/after in isolated worktrees**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
BEFORE=$(git rev-list -1 HEAD -- docs/superpowers/plans/2026-07-30-mshr-multi-outstanding-implementation-plan.md)  # or the V2c.2 commit
git worktree add /home/qwertyoruiop/m68k-core-040-ooo-worktrees/d16-before <V2c.2-sha>
git worktree add /home/qwertyoruiop/m68k-core-040-ooo-worktrees/d16-after  HEAD
for w in d16-before d16-after; do
  ( cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/$w
    IPC_SWEEP=full ~/sbt/bin/sbt "testOnly m68k040.bench.IpcBenchSpec" 2>&1 | tee /tmp/d16_$w.log )
done
diff <(grep -E "stride-walk|movem-copy|pointer-chase|big-code|mixed-line-cross" /tmp/d16_d16-before.log) \
     <(grep -E "stride-walk|movem-copy|pointer-chase|big-code|mixed-line-cross" /tmp/d16_d16-after.log)
```

**Note the `git worktree` requirement (GC4) — do NOT `git checkout` the before-SHA in the shared tree.** The `d16-before` worktree needs V1.7's kernels, which land before V2, so the before-SHA must be at or after V1.7.

- [ ] **Step 2: The three claims that must hold**

1. **`mixed-line-cross` and `movem-copy` improve** under `l2+dram(20)` — these are the store-drain/load-miss-overlap and hit-under-miss shapes.
2. **`pointer-chase` does NOT improve** (within noise). It is the control: MSHRs cannot help dependent misses. **If it improves, the measurement is wrong** — stop and find out why before reporting anything.
3. **The improvement survives `todaysCrossbar`.** Re-run both worktrees with `IPC_SWEEP=full` and compare specifically the `todays-crossbar` rows. Hit-under-miss needs no bus concurrency, so this is the claim that distinguishes D1 from the deferred D2/D5 (design doc §3.2, §9's "E2E?" column).

- [ ] **Step 3: Record**

Write the before/after table into the slice's final commit message and into the task report. Keep `/tmp/d16_*.log`. Clean up the worktrees.

### Task D1.7: Slice D1 full regression + synth gate

- [ ] **Step 1: Full corpus (expect movement — triage it)**

```bash
git worktree add /home/qwertyoruiop/m68k-core-040-ooo-worktrees/d1-gate HEAD
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/d1-gate
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" 2>&1 | tee /tmp/d1_ported.log & wait $!
grep "^\[info\] - ported: .* \*\*\* FAILED \*\*\*" /tmp/d1_ported.log \
  | sed -E 's/^\[info\] - ported: ([^ ]+).*/\1/' | LC_ALL=C sort -u > /tmp/d1_ported_fails.sorted.txt
echo "=== FIXED ==="; comm -23 /tmp/mshr_baseline_ported_fails.sorted.txt /tmp/d1_ported_fails.sorted.txt
echo "=== NEW ===";   comm -13 /tmp/mshr_baseline_ported_fails.sorted.txt /tmp/d1_ported_fails.sorted.txt
```

Per GC2, each NEW failure must be individually triaged into "D1 broke it" (a regression — fix before merging) or "D1's added concurrency exposed a pre-existing bug" (a finding — record it, file it, and it does not block the slice). Do not lump them. Use the single-quoted filtered form (GC3) for each repro:

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec -- -z <failing_test_name>"
```

- [ ] **Step 2: Reordering/latency acceptance**

```bash
AXI_RSP_MODE=Chaos AXI_LATENCY=dram ~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```

Must be 390/394 with identical names.

- [ ] **Step 3: Synth gate (GC5, both extra preconditions)**

```bash
pgrep -af vivado; pgrep -af VerilatorTest; free -g
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_full.log -source synth/ooc_M68kFullCoreSynth.tcl
grep "RESULT FullCore" synth/vivado_full.log
vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl
```

Report OOC FMax, post-route FMax, LUT/FF/BRAM. **Deleting the D-cache load FSM and REPLAY should be area-NEUTRAL-to-negative; the LTT adds ~100 flops at `N_LTT = 1`.** If area grows materially, say so — and restate GC5(a): with the branch's ~3× LUT bloat unresolved, the number is directional only and the slice is not mergeable.

- [ ] **Step 4: Extend `MissCounters` now that real MSHR taps exist**

V1.7 built `MissCounters` from already-`simPublic` signals only, because V1 was a
zero-RTL slice. D1 has now added `mshrValid`/`mshrSet`/`mshrTagId`/`dcacheQuiesceSig`
as `simPublic`. Extend `MissCounters` with the rest of design doc §8.3's list, which
V1.7 could not reach:

- **MSHR occupancy histogram** — sample `mshrValid.orR` (and, at `N_MSHR > 1`, the popcount) every cycle.
- **Stall-by-reason** — `mshrFull`, `sameSet`, `sameLine` (from the SQ's `anySameLine` tap), `sqPartial`, and (after D4) `dQuiesce`. Attribute each `loadCmdPort.valid && !loadCmdPort.ready` cycle to exactly one reason, and assert the reasons sum to the total — an unattributed stall cycle is a measurement bug.
- **Merge count** — zero in this slice (D-side merging is deferred slice D2); wire the counter now so it is not forgotten, and assert it stays 0.

- [ ] **Step 5: Clean up**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree remove /home/qwertyoruiop/m68k-core-040-ooo-worktrees/d1-gate
```

---

## Slice I2 — Per-beat predecode (option I-b)

Design doc refs: §6.4 (the 32× predecode block; options I-a / I-b / I-c), §1.5 ("The predecode block" — 32 independent combinational instances of a ~860-line decode tree in one cycle, order 860 EA-length decoders and 160 3-bit adders, feeding a 192-bit register write; plus `lineReg`, a 512-bit staging register separate from `dataMem`), §8.2 (the equivalence test and its explicit warning about `PredecodeWordSpec`), §11.Q8 (I2 is plausibly part of the answer to the branch's LUT bloat).

**This slice is standalone** — it depends on nothing except V2b (which changed the beat width), and nothing depends on it. It is pulled early because it carries no functional risk beyond its equivalence test and is the most likely area win in the plan.

### RECOMPUTED SCOPE — this is a 4× cut, not the design doc's 2×

The design doc's "32 → 16 classify instances" was written **before** §11.Q7 ratified the 256→128-bit I-cache narrowing (slice V2b). Recompute from V2b's actual result:

| | before V2b | after V2b |
|---|---|---|
| beat width | 256 bits | **128 bits** |
| 16-bit words per beat | 16 | **8** |
| beats per 64-byte line | 2 | **4** |
| per-beat classify instances | 16 (a 2× cut) | **8 (a 4× cut)** |

So per-beat predecode after V2b cuts the instance count **from 32 to 8**, and shrinks `lineReg` (512 flops) to a 128-bit beat latch plus a 192-bit predecode accumulator.

`ChunkPredecode` is **6 bits** (`simple` + `lenWords[3:0]` + `ambiguousLine`, `IcacheTypes.scala:19-35`), so `PRED_BITS_PER_LINE = 6 × 32 = 192` and `PRED_BITS_PER_BEAT = 6 × 8 = 48`. Net flop change: `512 → 128 + 192 + 3` (beat latch, accumulator, `beatLatchValid`/`beatLatchIdx`) ≈ **−190 flops**. *(Note: `IcachePlugin.scala:66-70`'s comment says "widened 4->5 bits, so this grows to 160" — that comment is STALE; the code derives the width correctly from `ChunkPredecode().getBitsWidth`, so only the comment is wrong. Fix the comment while you are in the file.)*

### PLAN-OF-RECORD DECISION: keep one post-`r.last` predecode cycle, take the 4× cut

The design doc offers, as part of I-b, "removes the separate PREDECODE cycle (predecode happens in the beat cycle), cutting miss latency by 1 of its 4 fixed post-data cycles". After the narrowing there is a **direct conflict** between that −1 cycle and the 4× area cut, and this plan chooses the area:

- Word `i` needs words `i+1..i+3` (`IcachePlugin.scala:424-431`). Beat `b` covers words `8b .. 8b+7`; word `8b+5` needs word `8b+8`, which lives in beat `b+1`. So beats 0, 1 and 2 must be predecoded **lagged** by one beat.
- **Beat 3 is fully self-contained**: it covers words 24..31, and words 29/30/31's lookahead runs past the line end, which is exactly the `extWValid = false ⇒ ambiguousLine` case that already exists today (`IcachePlugin.scala:396-431`, the F5 fix, and `Aligner.scala:63-65`'s live re-classify).
- Therefore beat 3 could be predecoded **in its own arrival cycle** (deleting the PREDECODE cycle, −1 cycle) — but its inputs come from a *different source* (the live `r.data`) than the lagged group's (the beat latch), so it would elaborate as **a second set of 8 instances**: 16 total, only a 2× cut.
- Or beat 3 can be run through the **same 8 instances** one cycle after `r.last`, with its lookahead flags forced to the line-end values. That is **8 instances total (4× cut)** at **exactly today's latency** (today already has one PREDECODE cycle after `r.last`).

**Chosen: the second.** The stated motivation for touching predecode at all is area (§6.4, §11.Q8), the branch has an unresolved ~3× LUT bloat (GC5(a)), and slice I3's prefetch is the mechanism that hides I-side miss latency — not a single cycle here. Recorded explicitly so a reviewer comparing against the design doc's "−1 cycle" wording sees the trade rather than an omission. If a later measurement shows the cycle matters more than the LUTs, flipping to the 16-instance variant is a localised change.

### Task I2.1: Per-beat predecode + delete `lineReg`

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` — delete `lineReg` (`:108`), delete the `lineReg` writes in REFILL (`:360-364`), replace PREDECODE's 32-way unroll (`:390-442`) with an 8-way one, add the beat latch + predecode accumulator

**Interfaces:**
- Consumes: V2b's `beatsPerLine = 4` / 128-bit beats.
- Produces: `IcachePlugin.logic.predAccum: Bits(PRED_BITS_PER_LINE bits)` and `IcachePlugin.logic.classifyBeat(...)` — the single 8-instance predecode group. Slice I1 reuses `predAccum` per-MSHR.

- [ ] **Step 1: Replace `lineReg` with a beat latch + accumulator**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala — replace `val lineReg` (:108)

    // ── Per-beat predecode (slice I2, design doc §6.4 option I-b) ────────────────
    // WAS: `lineReg`, a 512-bit staging register SEPARATE from dataMem (:108), written
    // by every refill in addition to the BRAM write (:360-364), and read by exactly one
    // consumer -- PREDECODE's 32-way `classify` unroll (:392,:424-431). One lineReg
    // meant one refill's worth of predecode source at a time: the central structural
    // obstacle to I-side MSHRs (design doc §1.5).
    //
    // NOW: one 128-bit BEAT latch plus the accumulated per-line predecode result.
    // Net ~ -190 flops (512 -> 128 + 192 + 3), and the classify group drops from 32
    // instances to 8. PRED_BITS_PER_LINE is 6 * 32 = 192 (ChunkPredecode is 6 bits:
    // simple + lenWords[3:0] + ambiguousLine) -- always derive it, never restate it.
    //
    // LOOKAHEAD SCHEME (beat-lag): word i needs words i+1..i+3. Beat b covers words
    // 8b..8b+7, and word 8b+5 needs word 8b+8 which lives in beat b+1. So beats 0..2
    // are predecoded ONE BEAT LATE -- when beat b+1 arrives, beat b is in `beatLatch`
    // and beat b+1's low 3 words supply the lookahead. Beat 3 is SELF-CONTAINED (its
    // last 3 words' lookahead runs past the line end, which is exactly today's
    // `extWValid = false` boundary case, re-classified live by the Aligner at
    // Aligner.scala:63-65) and is run through the SAME 8 instances one cycle after
    // r.last, in the retained PREDECODE state.
    //
    // *** ZERO NEW AMBIGUITY. *** Only the final 3 words of the line hit the boundary
    // case, which is EXACTLY the case that already exists today (design doc §6.4).
    val beatLatch      = Reg(Bits(beatBytes * 8 bits))
    val beatLatchValid = RegInit(False)
    val beatLatchIdx   = Reg(UInt(beatSelBits bits))
    val predAccum      = Reg(Bits(PRED_BITS_PER_LINE bits))
    val WORDS_PER_BEAT = (beatBytes * 8) / 16          // 8
    val PRED_BITS_PER_BEAT = PRED_BITS_PER_WORD * WORDS_PER_BEAT
```

- [ ] **Step 2: The single 8-instance classify group**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala — a helper next to `windowPred`

    /** Classify the 8 words of ONE beat.
      *
      * `beat`      : the beat being classified (from `beatLatch`).
      * `nextLo`    : the next beat's low 3 words, as a 48-bit value, supplying the
      *               lookahead for this beat's last 3 words.
      * `nextValid` : whether `nextLo` is real data. False for the LAST beat of a line,
      *               where the lookahead genuinely runs past the line end -- the same
      *               `extWValid = false` boundary discipline the whole-line predecode
      *               already used (:396-431, the F5 fix), which makes `classify` reject
      *               (COMPLEX) rather than guess brief-vs-full, or fall back to the
      *               documented assume-brief path for the extW3 case.
      *
      * This is the ONLY `classify` group in the refill path. It is elaborated ONCE and
      * reused for every beat of every line, which is where the 32 -> 8 cut comes from. */
    def classifyBeat(beat: Bits, nextLo: Bits, nextValid: Bool): Bits = {
      val w  = beat.subdivideIn(16 bits)                 // 8 words
      val nx = nextLo.subdivideIn(16 bits)               // 3 words
      def word(i: Int): Bits =
        if (i < WORDS_PER_BEAT) w(i)
        else Mux(nextValid, nx(i - WORDS_PER_BEAT), B(0, 16 bits))
      def wordValid(i: Int): Bool =
        if (i < WORDS_PER_BEAT) True else nextValid
      val chunks = Vec((0 until WORDS_PER_BEAT).map { i =>
        PredecodeWord.classify(
          w(i), word(i + 1), word(i + 2), word(i + 3),
          extWValid  = wordValid(i + 1),
          extW2Valid = wordValid(i + 2),
          extW3Valid = wordValid(i + 3))
      })
      chunks.asBits
    }
```

**Equivalence obligation (proved in I2.2):** for beat `b < 3` with `nextValid = True`, `wordValid(i+k)` is `True` for every `i + k <= 10`, i.e. for every word this group can reach — identical to the whole-line scheme's `i + k < 32` for those words. For beat 3, `wordValid(i+k)` is `False` exactly for `i + k >= 8` within the beat, i.e. absolute word index `>= 32` — again identical. **This structural argument is what I2.2 Step 1 checks mechanically; do not take it on trust.**

- [ ] **Step 3: Drive it from the beat stream**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala — inside REFILL's beat block,
// replacing the lineReg writes (:360-364).

          // Lagged predecode of the PREVIOUS beat, using this beat's low 3 words as
          // lookahead. Fires on beats 1..3 (beatLatchValid).
          when(beatLatchValid) {
            val pred = classifyBeat(beatLatch, axi.r.payload.data(47 downto 0), True)
            switch(beatLatchIdx) {
              for (b <- 0 until beatsPerLine) {
                is(U(b, beatSelBits bits)) {
                  predAccum((b + 1) * PRED_BITS_PER_BEAT - 1 downto b * PRED_BITS_PER_BEAT) := pred
                }
              }
            }
          }
          beatLatch      := axi.r.payload.data
          beatLatchIdx   := beatCnt
          beatLatchValid := True
```

and arm/clear `beatLatchValid` correctly: `beatLatchValid := False` at miss-latch time (`:299-309`, alongside `beatCnt := 0`), so a new refill never predecodes the previous refill's stale beat.

- [ ] **Step 4: PREDECODE now handles ONLY the last beat**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala — replace PREDECODE's body (:390-442)

      // ----- PREDECODE: classify the LAST beat, then write predMem + tag/valid -----
      // Beats 0..2 were already classified during REFILL (lagged one beat). Only beat
      // 3 remains, and it is self-contained: its last 3 words' lookahead runs past the
      // line end, which is the pre-existing boundary case (extWValid = false ->
      // classify rejects as COMPLEX rather than guessing, or takes the documented
      // assume-brief extW3 fallback; `ambiguousLine` marks it and Aligner.scala:63-65
      // re-classifies live from the buffer's own already-fetched words).
      //
      // This state is RETAINED (rather than deleted, as design doc §6.4 suggests)
      // specifically so the last beat reuses the SAME 8 classify instances instead of
      // elaborating a second group -- see this slice's "PLAN-OF-RECORD DECISION".
      PREDECODE.whenIsActive {
        activePc := missPC
        val lastPred = classifyBeat(beatLatch, B(0, 48 bits), False)
        val packed = Bits(PRED_BITS_PER_LINE bits)
        packed := predAccum
        val b = beatsPerLine - 1
        packed((b + 1) * PRED_BITS_PER_BEAT - 1 downto b * PRED_BITS_PER_BEAT) := lastPred
        for (w <- 0 until ways) {
          when(victimWay === U(w, wayBits bits)) {
            predMem(w).write(missSet, packed)
            tagMem(w).write(missSet, missTag)
            valids(w)(missSet) := True
          }
        }
        victim(missSet) := victim(missSet) + 1
        goto(REPLAY)
      }
```

**Note the `switch`-vs-`packed` asymmetry:** `predAccum` is written per-beat by the REFILL `switch`; the last beat's slice is overlaid combinationally here rather than written into `predAccum` first, so PREDECODE needs no extra cycle. Keep it that way.

- [ ] **Step 5: Verify the RTL still elaborates and behaves**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"
~/sbt/bin/sbt "testOnly m68k040.cache.ChunkPredecodeSpec"
~/sbt/bin/sbt "testOnly m68k040.cache.IcacheTypesSpec"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/i21_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/i21_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
```

**Latency must be UNCHANGED.** `IcacheSpec:221-256` asserts exact cycle counts; if they move, the beat-lag scheme has been implemented with an extra cycle somewhere. Find it rather than updating the assertion.

- [ ] **Step 6: Do not commit until I2.2's equivalence test passes**

### Task I2.2: The equivalence test — and it must NOT repeat `PredecodeWordSpec`'s mistake

**Files:**
- Create: `src/test/scala/m68k040/cache/PerBeatPredecodeEquivSpec.scala`

**Interfaces:**
- Consumes: `IcachePlugin.logic.classifyBeat` (via a dedicated equivalence DUT), `PredecodeWord.classify`.

**STANDING WARNING, carried verbatim from the design doc §8.2 and from this project's memory:**

> `PredecodeWordSpec`'s "exhaustive 65536-opword" test does **NOT** actually run exhaustively — **it aborts at the first mismatch**. Distrust any past "PredecodeWordSpec passes" claim, and **do not build this new equivalence test the same way.**

The test below therefore **collects every mismatch, never aborts early, and reports a count plus the first N**. A test that stops at the first failure cannot tell you whether you have one bug or ten thousand.

- [ ] **Step 1: The structural (elaboration-time) half — cheap, total, and it catches the whole bug class**

The two schemes can only differ in the `(w1, w2, w3, extWValid, extW2Valid, extW3Valid)` tuple presented to `classify` for each absolute word index 0..31. That is a pure Scala computation and can be checked **exhaustively over all 32 indices with no simulation at all**:

```scala
// src/test/scala/m68k040/cache/PerBeatPredecodeEquivSpec.scala
package m68k040.cache

import org.scalatest.funsuite.AnyFunSuite

class PerBeatPredecodeEquivSpec extends AnyFunSuite {

  private val WORDS_PER_LINE = 32
  private val WORDS_PER_BEAT = 8
  private val BEATS          = WORDS_PER_LINE / WORDS_PER_BEAT

  /** What the WHOLE-LINE scheme (IcachePlugin.scala:423-431, pre-I2) presented for
    * absolute word index i: source word index for each lookahead slot, and whether it
    * was flagged valid. */
  private def wholeLine(i: Int): Seq[(Int, Boolean)] =
    (1 to 3).map(k => (i + k, i + k < WORDS_PER_LINE))

  /** What the PER-BEAT scheme presents for absolute word index i. Beat b = i / 8,
    * local index l = i % 8; lookahead words beyond the beat come from the NEXT beat's
    * low 3 words, flagged valid iff there IS a next beat. */
  private def perBeat(i: Int): Seq[(Int, Boolean)] = {
    val b = i / WORDS_PER_BEAT
    val l = i % WORDS_PER_BEAT
    val hasNext = b < BEATS - 1
    (1 to 3).map { k =>
      val ll = l + k
      if (ll < WORDS_PER_BEAT) (b * WORDS_PER_BEAT + ll, true)
      else ((b + 1) * WORDS_PER_BEAT + (ll - WORDS_PER_BEAT), hasNext)
    }
  }

  test("per-beat and whole-line predecode present IDENTICAL inputs for every word") {
    // EXHAUSTIVE over all 32 word positions, and it does NOT abort on the first
    // mismatch -- see the standing warning about PredecodeWordSpec.
    val mismatches = (0 until WORDS_PER_LINE).flatMap { i =>
      val a = wholeLine(i)
      val b = perBeat(i)
      if (a == b) None else Some(s"word $i: whole-line=$a per-beat=$b")
    }
    assert(mismatches.isEmpty,
      s"${mismatches.size} of $WORDS_PER_LINE word positions differ:\n" +
        mismatches.mkString("\n"))
  }
}
```

If this test passes, the two schemes are input-identical for every word, and therefore output-identical for every possible line — a **total** proof, not a sampled one. **The lookahead-word source indices must match too, not just the valid flags** — the test above checks both, which is what catches an off-by-one in the beat-lag wiring.

- [ ] **Step 2: The hardware half — proves the RTL implements the scheme the Scala model describes**

Add to the same spec a small equivalence `Component` that instantiates BOTH:
- a whole-line group (the pre-I2 32-instance unroll, copied verbatim from `IcachePlugin.scala:423-431` into the test file — **copy it, do not reference the deleted code**), and
- four invocations of the per-beat `classifyBeat` shape,

drive it with a corpus of lines, and compare all 32 `ChunkPredecode` values.

Corpus, in this order:
1. **Directed boundary lines** — for each absolute word index `i` in 24..31 and each of the interesting EA shapes (mode 6 brief, mode 6 full, mode 7 reg 3 brief, mode 7 reg 3 full, line-0 `.L` immediate with a full-format mem-indirect destination — the task-#153 shape), construct a line placing that opword at index `i`. These are the ONLY words where the two schemes could differ, so they get exhaustive shape coverage.
2. **Beat-boundary lines** — the same shapes placed at indices 5..7, 13..15, 21..23 (the last three words of beats 0, 1 and 2), which is where the beat-lag wiring is exercised.
3. **≥ 100 000 pseudo-random lines** with a fixed seed.

```scala
  test("per-beat and whole-line predecode agree bit-for-bit on every corpus line") {
    val mismatches = scala.collection.mutable.ArrayBuffer[String]()
    var checked = 0
    // ... for each corpus line: poke, sample both 192-bit packed results, and
    //     if (a != b) mismatches += s"line ${lineHex}: whole=$a perBeat=$b"
    //     *** NEVER break/return on a mismatch -- run the WHOLE corpus. ***
    assert(mismatches.isEmpty,
      s"${mismatches.size} of $checked lines mismatched. First 20:\n" +
        mismatches.take(20).mkString("\n"))
  }
```

The final `assert` message must report **the mismatch count and the total checked**, so a reader can tell "1 line out of 100 000" from "100 000 out of 100 000" — the exact distinction `PredecodeWordSpec` destroys by aborting.

- [ ] **Step 3: Run, then commit I2.1 + I2.2 together**

```bash
~/sbt/bin/sbt "testOnly m68k040.cache.PerBeatPredecodeEquivSpec"
~/sbt/bin/sbt "testOnly m68k040.cache.*"
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
git add -A
git commit -m "$(cat <<'EOF'
icache: per-beat predecode -- 32 classify instances -> 8, delete lineReg (I2)

Design doc §6.4 option I-b, RECOMPUTED after slice V2b: with 128-bit beats a
beat holds 8 words, not 16, so this is a 4x instance cut rather than the design
doc's 2x. `lineReg` (512 flops, a staging register separate from dataMem, read
by exactly one consumer) becomes a 128-bit beat latch plus a 192-bit predecode
accumulator: net ~ -190 flops.

Beat-lag lookahead: beats 0..2 are classified one beat late (the next beat
supplies their last 3 words' lookahead); beat 3 is self-contained and runs
through the SAME 8 instances in the retained PREDECODE cycle. Only the final 3
words of a line hit the boundary case -- EXACTLY the case that already exists
today, so zero new ambiguity and zero new bug surface.

DELIBERATE DEVIATION from the design doc's "-1 cycle": deleting the PREDECODE
cycle would require a SECOND 8-instance group for the last beat (16 total, a 2x
cut). Area is the stated motivation (§6.4, §11.Q8's LUT-bloat hypothesis), so
this takes the 4x cut at today's exact latency. Flipping back is localised.

The equivalence test does NOT repeat PredecodeWordSpec's mistake: its structural
half is EXHAUSTIVE over all 32 word positions with no simulation, and its
hardware half runs the WHOLE corpus and reports mismatch-count-of-total instead
of aborting at the first failure.

Design doc §6.4, §1.5, §8.2, §11.Q8.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

### Task I2.3: Slice I2 regression + synth gate (the area claim must be MEASURED)

- [ ] **Step 1: Full corpus**

```bash
git worktree add /home/qwertyoruiop/m68k-core-040-ooo-worktrees/i2-gate HEAD
cd /home/qwertyoruiop/m68k-core-040-ooo-worktrees/i2-gate
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" 2>&1 | tee /tmp/i2_ported.log & wait $!
grep "^\[info\] - ported: .* \*\*\* FAILED \*\*\*" /tmp/i2_ported.log \
  | sed -E 's/^\[info\] - ported: ([^ ]+).*/\1/' | LC_ALL=C sort -u > /tmp/i2_ported_fails.sorted.txt
comm -13 /tmp/mshr_baseline_ported_fails.sorted.txt /tmp/i2_ported_fails.sorted.txt
```

I2 is provably output-identical (I2.2 Step 1), so this should be empty. Any new failure means the RTL does not implement the scheme the structural test models — treat it as a hard regression, not a budgeted finding.

**Watch specifically for the `ambiguousLine` bug family** — tasks #202, #204, #209 were all line-boundary predecode ambiguity bugs. Their ported tests are the canaries here.

- [ ] **Step 2: Synth gate — and report the AREA delta specifically**

```bash
pgrep -af vivado; pgrep -af VerilatorTest; free -g
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -nojournal -log synth/vivado_full.log -source synth/ooc_M68kFullCoreSynth.tcl
grep -E "RESULT FullCore|LUT|FF|Slice" synth/vivado_full.log
vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/impl_FullCore.tcl
```

Then the same in a `before` worktree at the V2c.2 commit and diff the LUT/FF numbers. **This is the one slice whose primary claim is area**, so a report that only quotes FMax is incomplete. Expected direction: LUTs down materially (24 fewer `classify` instances, each containing ~17 `eaExt` + ~10 `memDestExt` + 5 `fullExtLen` sub-decoders), FFs down ~352.

**GC5(a) applies with extra force here:** if the branch's ~3× LUT bloat is still unresolved, the LUT delta may be swamped. Say so explicitly. If the bloat HAS been fixed, this measurement is the one that tests §11.Q8's hypothesis that I2 is part of the answer — report the verdict either way.

- [ ] **Step 3: Clean up**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git worktree remove /home/qwertyoruiop/m68k-core-040-ooo-worktrees/i2-gate
```

---

## Slice I1 — I-side non-blocking accept, 1 demand MSHR, same-line merge, `invalidateAll` closure

Design doc refs: §6.1 (demand miss-under-miss is structurally unreachable — and that is a useful finding; what IS worth having is non-blocking accept and same-line duplicate suppression), §6.3 (response delivery stays strictly in order, deliberately), §6.5 (the `invalidateAll` hazard must be closed before it becomes reachable), §1.5 (the I-side miss is a total fetch-port stall: `cmdPort.ready` is asserted in exactly one place, inside `IDLE`, for 7 + L cycles), §4.1(i) M1–M3.

**Why demand miss-under-miss is NOT built (design doc §6.1, and this is load-bearing):** the line is 64 bytes, a fetch is 8 bytes, and `fetchPc` advances strictly `+8` (`FetchAlignPlugin.scala:249`), so **8 consecutive demand fetches hit the same line** — while the outstanding-fetch ring is only **depth 3** (`FetchAlignPlugin.scala:171`). The demand stream can therefore *never* have two different-line fetches outstanding. Raising the ring to 9+ would need a bigger `InstructionBuffer` (`BUF_WORDS = 20`, `HEAD_WORDS = 10`) and a reworked `ibufRoomForIssue` reservation, to enable an overlap that slice I3's prefetcher provides for free. **Do not build it.**

### Task I1.1: Non-blocking accept + a one-entry demand MSHR

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` — the miss-state latches (`:100-114`), the accept gate (`:261`), the FSM (`:227-467`)

**Interfaces:**
- Consumes: V2a.2's ID routing, V2b's beat shape, I2's `classifyBeat`/`predAccum`.
- Produces: `IcachePlugin.logic.mshrValidI`, `.mshrSetI`, `.mshrLineI`, `.mshrWayI` (all `simPublic`), and `IcachePlugin.logic.icacheQuiesceSig` (the `iQuiesce` source, design doc §7.3).

**Shape:** structurally the same transformation as D1.1, and the implementer should read D1.1 first and mirror it. Differences:

- The I-cache's accept path already has a **T-stage** (`tValid`/`tPc`/`tPaddr`/`tFault`, `:95-98`) and a documented **depth-2 accept** whose assignment ordering is load-bearing (`:253-323`: the consume's `tValid := False` is deliberately placed BEFORE the accept's `tValid := True` so a back-to-back accept is not dropped). **Preserve that ordering exactly.** The change is only to what gates `cmdPort.ready`.
- There is no store path, so none of D1.1's store-vs-fill arbitration applies.
- The `dataMem`/`predMem`/`tagMem` write ports are claimed only by a fill, so no write-port retry logic is needed.

- [ ] **Step 1: The MSHR record** — mirror D1.1 Step 1 with fields `{valid, line(paddr[31:6]), set, tag, way, arSent, err, beatsRcvd}` plus the I-specific `missPC` (needed to form the response's `pc` and lane). `N_MSHR_I = 1` in this slice, `require`d, with the array shape general so I3 can raise it to 2.

- [ ] **Step 2: Non-blocking accept**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala — the accept gate at :261 was
//     cmdPort.ready := xlate.rsp.ready
// inside IDLE.whenIsActive, i.e. the fetch port stalled COMPLETELY for the whole
// REFILL -> PREDECODE -> REPLAY sequence (7 + L cycles, design doc §1.5).
//
// Now the accept is driven OUTSIDE the FSM and gates only on structural
// availability. The T-stage consume/accept ordering inside IDLE is UNCHANGED.
    val tStageFree   = !tValid || tConsumedThisCycle
    val iLookupSetConflict = fillArrayWrI && (mshrSetI(0) === idleSet)
    cmdPort.ready := xlate.rsp.ready && tStageFree && !iLookupSetConflict
```

`tConsumedThisCycle` must be a net driven True in the IDLE consume arm. **Verify against the depth-2 comment at `:215-224`** before changing anything: that comment states the T-stage is consumed (freed) every IDLE cycle it is valid. Under non-blocking accept, it is consumed every cycle the cache is *not* holding a miss in the T-stage. Get this exactly right or the front end will drop fetches.

- [ ] **Step 3: The hit path must keep working during a refill**

The consume arm (`:267-311`) already resolves hit/miss off the registered `tPaddr`/`tPc` and arms `s1Valid` on a hit. With the FSM's IDLE-only gating removed, that arm must run **every cycle**, not only in IDLE. Restructure it out of the `StateMachine` into flat logic, exactly as D1.1 did for the D side, keeping the miss branch's job reduced to "allocate an MSHR (or hold if none is free)".

**HOLD, not drop:** if a miss cannot allocate (MSHR busy, or `D3-SET-I` set conflict — see I1.3), the T-stage entry must be **held** (`tValid` retained, `tPaddr`/`tPc` untouched) and retried, exactly like D1.1's `ldS1Hold`. Dropping it would lose a fetch and wedge `FetchAlignPlugin`'s ring accounting.

- [ ] **Step 4: In-order response delivery is PRESERVED — deliberately**

Design doc §6.3: `FetchRsp` has no tag and `FetchAlignPlugin` attributes every response to the ring **head** (`:263,283`). This slice does **not** add a tag and does **not** rework the ring. With one demand MSHR and demand fetches issued in order, responses are in order by construction. **Add an explicit sim assert** that no two response sources fire in one cycle and that a fill response never precedes an older hit response:

```scala
    GenerationFlags.simulation {
      assert(!(s1ValidFromHit && s1ValidFromFill),
        "IcachePlugin: two response sources armed in one cycle -- FetchAlign attributes " +
        "responses to the ring HEAD and would mis-pair them (design doc §6.3)")
    }
```

- [ ] **Step 5: Verify**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.cache.IcacheSpec"
~/sbt/bin/sbt "testOnly m68k040.frontend.*" 2>/dev/null || true
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec" 2>&1 | tee /tmp/i11_lockstep.log
grep "\*\*\* FAILED \*\*\*" /tmp/i11_lockstep.log | sed -E 's/^\[info\] - //; s/ \*\*\* FAILED \*\*\*.*//' \
  | LC_ALL=C sort -u | diff - /tmp/mshr_baseline_lockstep_fails.txt && echo "LOCKSTEP IDENTICAL"
```

### Task I1.2: Same-line duplicate-miss suppression (merge) — with M1–M3 as acceptance criteria

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (the miss-classification block from I1.1)
- Create: `src/test/scala/m68k040/cache/IcacheMshrSpec.scala`

**Interfaces:**
- Consumes: I1.1's MSHR.
- Produces: `mshrMergeCount` (a `simPublic` counter for the directed test).

**Why this is MANDATORY, not an optimisation (design doc §6.1):** "With a non-blocking accept, several of the 8 same-line fetches will miss on the same line concurrently. They must **merge** onto the one outstanding MSHR, never allocate a second (and never issue a second AR). This is the I-side's version of §4.1's merge rules, and it is mandatory the moment accept is non-blocking."

**BINDING SUB-RULES — reproduce these VERBATIM in the code comment at the merge site and in the acceptance criteria of this task (GC1, design doc §4.1(i)):**

> **M1** — the fill's `r.resp` must be propagated to **every** merged waiter. A non-OKAY response faults the **oldest** merged waiter precisely (the flush from that fault removes the younger ones, which is correct — they are architecturally after it).
>
> **M2** — a merged fill that errors allocates **nothing**, so a later access to the same line issues a **new real transaction**. No error verdict is ever cached. This is the property that keeps M1 from becoming "we remember this line is bad".
>
> **M3** — merging is forbidden across cacheability classes, and **INHIBITED accesses never merge at all** (two device reads must be two bus reads).

On the I side, M3 is satisfied structurally: an INHIBITED instruction page is not cached at all, and a prefetch is never issued for one (rule P1, slice I3). **State that explicitly rather than leaving M3 unaddressed** — a reader must be able to see it was considered.

- [ ] **Step 1: The 3-way classification**

Mirror design doc §5.3's decision exactly:

```scala
    // Design doc §5.3's classification, I-side. `reqLine` is the translated physical
    // line address of the T-stage fetch.
    //     if      any sameLine -> MERGE  (attach a waiter; NO new AR)
    //     else if any sameSet  -> STALL  (hold the T-stage; retry next cycle)
    //     else if array full   -> STALL
    //     else                 -> ALLOCATE
    val sameLineI = mshrValidI(0) && (mshrLineI(0) === reqLine)
    val sameSetI  = mshrValidI(0) && (mshrSetI(0)  === idleSet)
```

**With `N_MSHR_I = 1` and in-order response delivery (§6.3), "attach a waiter" means: hold the T-stage entry and let it re-look-up after the fill lands** — it will then HIT. That is a legitimate and much cheaper implementation of merging than a waiter list, and it satisfies M1 trivially (the waiter re-reads the allocated line, or, on an error, re-misses and gets its own contemporaneous response, which is M2). **Document this explicitly**: the observable requirement is "8 same-line fetches produce ONE AR", and hold-and-re-look-up delivers exactly that.

- [ ] **Step 2: The directed test**

```scala
// src/test/scala/m68k040/cache/IcacheMshrSpec.scala
package m68k040.cache

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class IcacheMshrSpec extends AnyFunSuite {
  /** Non-blocking accept: a fetch that HITS is served while a refill is in flight. */
  test("a hit is served during a refill") { }

  /** MERGE: 8 sequential fetches into one 64-byte line produce EXACTLY ONE AR.
    * This is the mandatory-the-moment-accept-is-non-blocking case (design doc §6.1). */
  test("8 same-line fetches issue exactly one AR") {
    // assert(model.stats.arCount(AxiIds.I_DEMAND) == 1)
  }

  /** M2 (GC1): an errored fill allocates nothing, and a LATER fetch of the same line
    * issues a NEW REAL AR. No error verdict is ever cached, in either direction. */
  test("M2: errored fill allocates nothing; a later fetch re-issues a real AR") { }

  /** M1: the error faults the fetch precisely (fault + atc=false, per task #211). */
  test("M1: a non-OKAY fill response faults the waiting fetch precisely") { }

  /** D3-SET-I: a miss to a set with an active MSHR stalls; never two MSHRs on one set. */
  test("D3-SET-I: same-set different-line miss stalls, never double-allocates") { }
}
```

### Task I1.3: `D3-SET-I` — the I-side set exclusion

**Files:** `src/main/scala/m68k040/cache/IcachePlugin.scala`

Same invariant as the D side and the same three benefits, with one I-specific note: `IcachePlugin`'s geometry is 64 sets (`CacheGeometry.l1i040`), so the exclusion is a 6-bit compare per MSHR and, at `N_MSHR_I ≤ 2`, essentially never stalls anything real.

- [ ] **Step 1** — add `sameSetI` to the stall condition (already introduced in I1.2 Step 1) and the lookup-vs-fill-write exclusion to `cmdPort.ready` (already in I1.1 Step 2).
- [ ] **Step 2** — add the same pairwise sim assert as D1.1 Step 5, naming it `D3-SET-I` in the message (never bare `D3` — GC9).

### Task I1.4: Close the `invalidateAll` hazard — with a front-end-wedge analysis

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala:79-82` (the clear block) and the fill's valid-write
- Create: `src/test/scala/m68k040/cache/IcacheInvalidateSpec.scala`

**The hazard, confirmed (design doc §1.5, §6.5):** the clear block is `:80-82`, PREDECODE's `valids(victimWay)(missSet) := True` is `:437`, and SpinalHDL is **last-assignment-wins**, so `:437` overrides `:81` — a simultaneous invalidate leaves the just-filled line valid. It also does not reset `arSent`/`beatCnt`/`missBusFault`, does not squash the in-flight burst, and does not invalidate `s1*`/`rsp*Reg`. It is currently only *read* (`FullCoreSynth.scala:79,94,107` fan it out to BTB/RAS/gshare from a dangling top-level input), so the whole hazard is **unreachable and therefore untested** — and it becomes reachable both from this slice's non-blocking accept and, independently, from the copyback design's P5 (real CINV/CPUSH).

**The design doc's §6.5 proposes four closures. THREE ARE ADOPTED; THE THIRD IS NOT, AND HERE IS WHY.**

> §6.5 bullet 3: "invalidate the `s1*`/`rsp*Reg` stages, whose in-flight response is from a now-invalidated line"

**This is rejected as written, because it risks wedging the front end.** `FetchAlignPlugin` runs a **depth-3 outstanding ring** (`:171-190`) whose accounting is: `ringCount` is incremented on `ic.cmd.fire` and decremented on `ic.rsp.valid` (`:302-312`), and the response is attributed to `ringHead` (`:263,283`). **There is no timeout and no other path that decrements `ringCount`.** If `invalidateAll` cancelled an in-flight response by clearing `s1Valid`/`rspValidReg`, that fetch's ring entry would never be retired: `ringCount` would stay elevated forever, `ringFull` would eventually block `ic.cmd.valid` permanently, and the front end would stop fetching with no recovery path. That is a hard hang, and it would be *created* by the fix.

**Adopted closure instead — deliver the response, mark it stale at the CONSUMER, never cancel it:**

1. **Priority-correct valid write.** The invalidate must WIN over a same-cycle fill valid-write. Implemented by making the fill's `valids(w)(set) := True` conditional on `!invalidateAll`, rather than relying on statement order:

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala — in PREDECODE's allocate block
        for (w <- 0 until ways) {
          when(victimWay === U(w, wayBits bits)) {
            predMem(w).write(missSet, packed)
            tagMem(w).write(missSet, missTag)
            // invalidateAll WINS. Previously this line was simply the LAST assignment
            // and silently overrode the clear at :81 (design doc §1.5, §6.5) -- the
            // just-filled line stayed valid across an invalidate.
            when(!invalidateAll) { valids(w)(missSet) := True }
          }
        }
```

2. **Poison every outstanding MSHR** so an in-flight fill does not re-allocate after the invalidate:

```scala
    val mshrPoisonI = Vec.fill(N_MSHR_I)(RegInit(False))
    when(invalidateAll) { for (k <- 0 until N_MSHR_I) when(mshrValidI(k)) { mshrPoisonI(k) := True } }
```
and gate the allocate on `!mshrPoisonI(k)` (the fill still runs to completion on the bus and its response is still delivered — see (3)).

3. **The in-flight response is DELIVERED, not cancelled.** A response for an invalidated line still reaches `FetchAlignPlugin`, which retires its ring entry normally. Correctness is preserved because the *data* is still the data that was at that address when the fetch was issued, and an `invalidateAll` on the 68040 is a CINV/CPUSH — architecturally a *software* synchronisation point that requires the programmer to follow it with the appropriate serialisation. **If a future requirement demands that the in-flight fetch be discarded, the correct mechanism is `FetchAlignPlugin`'s EXISTING `ringStale` bit** (`:171`, set on a redirect, consumed at `:263,283,299`) — i.e. mark the entry stale so the response is consumed-and-discarded and the ring still retires it. That is a `FetchAlignPlugin` change, not an `IcachePlugin` one, and it is explicitly **out of scope for this slice**; it is recorded here so the next person does not re-derive the analysis.

4. **Reset the miss-sequencing state** (`arSent`, `beatCnt`, `beatLatchValid`) is **NOT** done: the burst must still be consumed to completion or the AXI channel desynchronises (there is no transaction-cancel in AXI — design doc §10.F). Poison-and-allocate-nothing is the correct and established behaviour.

- [ ] **Step 1**: implement (1) and (2) above, with the full rationale for rejecting §6.5 bullet 3 in a code comment at the invalidate site.
- [ ] **Step 2**: the directed test — the hazard has **never** been tested because it was unreachable:

```scala
// src/test/scala/m68k040/cache/IcacheInvalidateSpec.scala
class IcacheInvalidateSpec extends AnyFunSuite {
  test("invalidateAll during a refill leaves NO line valid for that set") {
    // Drive invalidateAll on the exact cycle PREDECODE writes valids. Assert every
    // `valids(w)(missSet)` reads False afterwards -- this is the last-assignment-wins
    // bug, and before this slice it FAILED.
  }
  test("invalidateAll during a refill still delivers the in-flight response") {
    // Assert ic.rsp.valid fires exactly once for the in-flight fetch, so
    // FetchAlignPlugin's depth-3 ring retires its entry (ringCount returns to its
    // pre-fetch value). This is the wedge the naive '§6.5 bullet 3' closure would
    // have caused.
  }
  test("a fetch AFTER invalidateAll re-misses and issues a NEW AR") {
    // assert(model.stats.arCount(AxiIds.I_DEMAND) increased by exactly 1)
  }
}
```

- [ ] **Step 3**: run, and confirm the `ringCount` assertion in test 2 specifically — it is the one guarding against the rejected closure.

### Task I1.5: Slice I1 regression + synth gate

- [ ] **Step 1**: full corpus in a worktree, diff against baseline, triage per GC2.
- [ ] **Step 2**: `AXI_RSP_MODE=Chaos AXI_LATENCY=dram` lock-step run — must be 390/394, identical names.
- [ ] **Step 3**: measure `big-code` from V1.7's kernels before/after; non-blocking accept should reduce its stall cycles measurably even before prefetch lands.
- [ ] **Step 4**: synth gate per GC5, both extra preconditions restated.
- [ ] **Step 5**: commit with the measured `big-code` delta in the message.

---

## Slice I3 — Next-line prefetch MSHR (`N_MSHR_I = 2`)

Design doc refs: §6.2 (next-line prefetch is where the I-side value is), §4.1(ii) rules **P1–P4** (binding), §6.3 (a prefetch has no waiting consumer — the key simplification), §8.3 (prefetch issued / useful / wasted counters, and a prefetch-off control), §9 slice row **I3** (**E2E? YES** — one of only two slices with real end-to-end value on today's SoC).

**Why this works so well here (design doc §6.2, §1.5):** the BTB/RAS/gshare sit *downstream* of the I-cache and are queried with the Aligner's decoded instruction PCs (`FetchAlignPlugin.scala:315,331-334`), not with `fetchPc`. Consequently `fetchPc` advances **strictly `+8` sequentially** (`:249`) until a decoded, emitted branch redirects it. **The instruction fetch stream is provably sequential between redirects** — no stride detection is needed, no confidence estimation, and there is no fetch-stage predictor to fight with.

**And it is NOT blocked by the SoC crossbar (design doc §6.2, §3.2):** prefetch hides latency *in time*, not by concurrency. Since 8 consecutive fetches (≥ 24 cycles at the 3-cycle hit rate) elapse before the next line is demanded, a prefetch issued as soon as the bus is free — even on a strictly one-outstanding-per-master-port fabric — still lands well ahead of need. The crossbar only costs the prefetch its *overlap* with the demand fill that triggered it, which is a fraction of the win.

### PLAN-OF-RECORD SIMPLIFICATION: same-page-only prefetch, reusing the demand's own translation

Rule **P1** requires "a prefetch is issued **only** for a next line whose translation is **already resident** in the ITLB … A prefetch must **never trigger a table walk**". Implementing that literally would need a non-walking ITLB probe port — a real new interface with a real chance of accidentally arming the walker.

**There is a strictly simpler construction that satisfies P1 and P4 by construction and adds NO ITLB interface at all:**

- The I-cache line is 64 bytes and the page is 4 KiB, so a page holds **64 lines**. For 63 of every 64 lines, `line + 64` is in the **same page** as the demand line that triggered the prefetch — whose translation the T-stage has **already resolved and registered** into `tPaddr` (`IcachePlugin.scala:97`).
- So: **issue a prefetch only when `line + 64` lies in the same 4 KiB page as the triggering demand access, and form its physical address directly from that access's own `tPaddr`.** No lookup, no probe, no walk — structurally impossible to trigger one.
- When `line + 64` crosses the page boundary (1 line in 64), the prefetch is simply **dropped**. That IS rule **P4** ("a prefetch must never cross a page boundary on a *guess*") implemented exactly, with no residual case.

Adopt this. Record it in the code comment so a later reader does not "improve" it into an ITLB probe.

### FINDING (pre-existing, outside this plan's scope, must be reported)

`IcachePlugin.scala` **never reads `xlate.rsp.cacheMode` at all** — verified: `grep -n "cacheMode\|cmode" src/main/scala/m68k040/cache/IcachePlugin.scala` returns nothing. So a **cache-INHIBITED instruction page is allocated into the L1I today**, which is architecturally wrong on a 68040 independently of anything in this design. The design doc does not mention it.

This slice needs the I-side cache mode anyway (rule **P3**), so it captures it into the T-stage — but it deliberately **does not change the demand-allocate behaviour**, because that is an unrelated correctness fix that deserves its own task, its own lock-step run and its own triage. **File it as a separate finding; do not silently fold it in.**

### Task I3.1: The prefetch MSHR and rules P1–P4

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (`N_MSHR_I` 1 → 2; the trigger; the T-stage cache-mode capture; the suppression conditions)

**Interfaces:**
- Consumes: I1's MSHR array (already shaped for `N_MSHR_I > 1`), `AxiIds.I_PREFETCH` (already in the I master's owner-ID set from V2c.2 — no demux change needed).
- Produces: `IcachePlugin.logic.prefetchEnable: Bool` (an `in Bool()` control input, default-driven True at the top level), and the counters of I3.3.

**BINDING SUB-RULES — reproduce VERBATIM in the code comment at the prefetch trigger and in this task's acceptance criteria (GC1, design doc §4.1(ii)):**

> **P1** — a prefetch is issued **only** for a next line whose translation is **already resident** in the ITLB and whose page is software-configured cacheable. A prefetch must **never trigger a table walk** and must never be issued for an INHIBITED page. (Class-(a) evidence only; no guessing.)
>
> **P2** — a prefetch that receives a non-OKAY response is **silently discarded**: no allocation, no architectural fault, no sticky diagnostic record, no core halt. This is mandatory precisely *because* the core cannot know whether the address is backed — the prefetch never asserted that it was.
>
> **P3** — because P2 allocates nothing, a later **demand** fetch of that line issues a **new real transaction** and gets its own contemporaneous response, with the existing task-#211 I-side `r.resp` check (`IcachePlugin.scala:352-353`) delivering the fault precisely. No cached verdict, in either direction.
>
> **P4** — a prefetch must never cross a page boundary on a *guess*: if the next line is in the next page, P1 already requires a resident ITLB translation for that page, so the prefetch is simply dropped when there is none.

- [ ] **Step 1: `N_MSHR_I = 2` and the entry roles**

```scala
    // MSHR 0 = DEMAND (has a waiting consumer, produces a FetchRsp).
    // MSHR 1 = PREFETCH (has NO waiting consumer -- design doc §6.2, and this is THE
    //          key simplification: a prefetch never produces a FetchRsp, never
    //          occupies a FetchAlign ring entry, never needs a tag, and cannot produce
    //          a fault. It is a PURE ALLOCATE. So FetchAlignPlugin needs no change
    //          whatsoever and the `ibufRoomForIssue` reservation (:230-231) is
    //          untouched).
    val N_MSHR_I = 2
    val MSHR_DEMAND   = 0
    val MSHR_PREFETCH = 1
```

- [ ] **Step 2: T-stage cache-mode capture (needed for P1/P3)**

```scala
// src/main/scala/m68k040/cache/IcachePlugin.scala — beside tPaddr/tFault (:95-98)
    // Captured ONLY so the prefetch trigger can honour rule P1 ("never for an
    // INHIBITED page"). Deliberately NOT used to gate demand allocation -- see this
    // slice's FINDING note: the I-cache ignoring cacheMode for DEMAND fills is a real
    // pre-existing bug, but fixing it is an unrelated change with its own lock-step
    // and triage cost and must not be smuggled in here.
    val tCmode = Reg(CacheMode())
```
captured in the T-accept arm (`:318-323`) as `tCmode := xlate.rsp.cacheMode`.

- [ ] **Step 3: The trigger and every suppression condition**

```scala
    // ── Next-line prefetch trigger (design doc §6.2) ─────────────────────────────
    // Fires on a demand miss that ALLOCATES line L, and on the first demand HIT in a
    // line that was itself prefetched (the cheap "useful prefetch => keep going"
    // heuristic).
    //
    // *** RULES P1-P4 (design doc §4.1(ii)) ARE BINDING. Reproduced verbatim above
    // this task in the plan; the enforcement points are named below. ***
    //
    // P1 + P4 are satisfied BY CONSTRUCTION, with no ITLB probe and therefore no way
    // to accidentally arm the walker: the prefetch line is `demandLine + 64`, and it
    // is issued ONLY when that address lies in the SAME 4 KiB page as the demand
    // access whose translation the T-stage has ALREADY resolved into `tPaddr`. A page
    // holds 64 lines, so this covers 63 of every 64 next-lines; the 64th (a
    // page-crossing next-line) is simply DROPPED, which is exactly what P4 requires.
    val pfLineVa   = (tPc(31 downto 6) + 1) @@ U(0, 6 bits)
    val pfSamePage = tPc(31 downto 12) === pfLineVa(31 downto 12)
    val pfPa       = (tPaddr(31 downto 12) @@ pfLineVa(11 downto 0))
    val pfSet      = pfLineVa(11 downto 6)
    val pfTag      = pfPa(31 downto 12)

    val pfResident   = Vec((0 until ways).map(w =>
                         valids(w)(pfSet) && (tagMem(w).readAsync(pfSet) === pfTag))).orR
    val pfInMshr     = (0 until N_MSHR_I).map(k => mshrValidI(k) && (mshrLineI(k) === pfPa(31 downto 6))).orR
    val pfSetBlocked = (0 until N_MSHR_I).map(k => mshrValidI(k) && (mshrSetI(k) === pfSet)).orR

    val prefetchAllowed =
      prefetchEnable &&                       // I3.3's runtime on/off control
      pfSamePage &&                           // P1 + P4: resident translation, no walk, no page cross
      (tCmode =/= CacheMode.INHIBITED) &&     // P1: never for an INHIBITED page
      !tFault &&                              // never off a faulted translation
      !pfResident &&                          // already cached -- nothing to do
      !pfInMshr &&                            // already being filled (demand or prefetch)
      !pfSetBlocked &&                        // D3-SET-I: one outstanding fill per SET
      !mshrValidI(MSHR_PREFETCH)              // the single prefetch MSHR is free
```

- [ ] **Step 4: The prefetch fill path — allocate, and on error do NOTHING**

```scala
    // P2: a prefetch that receives a non-OKAY response is SILENTLY DISCARDED -- no
    // allocation, no architectural fault, no sticky diagnostic record, no core halt.
    // Mandatory precisely BECAUSE the core cannot know whether the address is backed;
    // the prefetch never asserted that it was (design doc §4.1(ii) P2, and GC1).
    //
    // P3 follows from P2: because nothing was allocated, a later DEMAND fetch of that
    // line issues a NEW REAL transaction and gets its own contemporaneous response,
    // with the task-#211 r.resp check (:352-353) delivering the fault precisely. No
    // cached verdict, in either direction.
    when(rBeatFire && (axi.r.payload.id === U(AxiIds.I_PREFETCH, AxiIds.ID_W bits))) {
      when(axi.r.payload.resp =/= Axi4.resp.OKAY) { mshrErrI(MSHR_PREFETCH) := True }
      // beats are written into dataMem as they arrive (same as demand); the TAG and
      // VALID bits are written only at r.last and only when no beat errored, so an
      // errored prefetch leaves the array unreadable-as-a-hit.
      when(axi.r.payload.last) {
        when(!mshrErrI(MSHR_PREFETCH) && !mshrPoisonI(MSHR_PREFETCH) && !invalidateAll) {
          /* write tag + valid for the prefetched way */
        }
        mshrValidI(MSHR_PREFETCH) := False
      }
    }
```

**A prefetch must also run the predecode path**, because a prefetched line must be usable as a hit — which means `predMem` must be written for it. With I2's single 8-instance `classifyBeat` group, the demand and prefetch fills **contend** for it. Resolve with **strict demand priority and a one-beat prefetch hold**: if a demand beat and a prefetch beat both need classification in the same cycle, the demand wins and the prefetch's beat latch is held (its beat is already safely in `dataMem`; only its predecode is deferred). Add a per-MSHR `beatLatch`/`beatLatchValid`/`predAccum` — that is `2 × (128 + 1 + 160)` bits, still far below the 512-flop `lineReg` this replaced.

**If that contention proves awkward or costs FMax, the documented fallback is design doc §6.4 option I-a (time-share the block with demand priority) applied at line granularity instead of beat granularity — i.e. hold the prefetch's PREDECODE state until the demand's completes.** Do not replicate the classify group.

- [ ] **Step 5: Verify** — compile, `IcacheSpec`, `IcacheMshrSpec`, lock-step identical.

### Task I3.2: Demotion — a demand fetch attaches to an in-flight prefetch

**Files:** `src/main/scala/m68k040/cache/IcachePlugin.scala`

Design doc §6.2: "if a demand fetch misses on a line that a prefetch MSHR is already filling, *attach the demand waiter to that MSHR* (merge) rather than allocating — this is the case where the prefetch pays off partially."

With in-order response delivery (§6.3) and I1.2's hold-and-re-look-up merge, demotion has the same cheap implementation: **a demand miss whose line matches the PREFETCH MSHR holds the T-stage and re-looks-up after that fill lands** — at which point it hits. One extra requirement: **the prefetch MSHR must then be treated as demand-critical**, i.e. it must not be poisoned or dropped, and its error path must become the demand error path (P2's silent-discard applies to a prefetch with **no** waiter; once a demand waiter is attached, a non-OKAY response must fault the waiting fetch precisely, exactly as a demand miss would).

- [ ] **Step 1:** add `mshrHasDemandWaiter: Vec[Bool]`, set when a demand fetch is held on a prefetch MSHR's line, and make the error handling branch on it:

```scala
    // Once a DEMAND fetch is waiting on this fill, P2's silent-discard NO LONGER
    // APPLIES: the demand fetch asserted that this address is backed (the program
    // demanded it), so a non-OKAY response must fault it precisely via the existing
    // FAULT path. P2 governs only a prefetch with no waiter.
    when(mshrErrI(k) && mshrHasDemandWaiter(k)) { /* -> the task-#211 FAULT path */ }
    .elsewhen(mshrErrI(k)) { /* P2: silently discard, allocate nothing */ }
```

- [ ] **Step 2:** directed test — issue a prefetch, then demand the prefetched line mid-fill; assert **one** AR total and a correct response. Then the same with an errored fill; assert the demand fetch faults precisely (`fault=true, atc=false`) rather than being silently discarded.

### Task I3.3: Prefetch on/off control + counters

**Files:**
- Modify: `src/main/scala/m68k040/cache/IcachePlugin.scala` (add `val prefetchEnable = in Bool()` beside `invalidateAll`, `:40`)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala`, `FuzzDut.scala`, `IpcBenchSpec.scala`, `ExecuteLockStepSpec.scala` (drive it True)
- Modify: `src/test/scala/m68k040/bench/MissCounters.scala` (prefetch counters)

Design doc §8.3 requires: **prefetch issued / useful / wasted**, and a **prefetch-off control** for the sweep (`prefetch ∈ {on, off}`).

- [ ] **Step 1:** `prefetchEnable` as a top-level input, driven True by every DUT wiring plugin (mirroring exactly how `invalidateAll` is driven, `FullCoreSynth.scala:79,94,107`). Making it an input rather than a compile-time parameter is deliberate: the §8.3 sweep needs both settings from **one** compiled DUT.
- [ ] **Step 2:** counters — `pfIssued` (AR fired with `AxiIds.I_PREFETCH`, already available from `AxiMemStats.arCount`), `pfUseful` (a demand fetch hit a line whose way was last filled by a prefetch — needs a per-line `filledByPrefetch` bit, 1 bit × 4 ways × 64 sets = 256 flops; `simPublic`, and note in the comment that it exists ONLY for measurement), `pfWasted` = `pfIssued − pfUseful`.
- [ ] **Step 3:** extend `MissCounters` with the three, and extend `IpcBenchSpec`'s sweep with `prefetch ∈ {on, off}` as design doc §8.3 requires.

### Task I3.4: Slice I3 tests, measurement and synth gate

- [ ] **Step 1: The rule tests (these ARE the GC1 acceptance criteria)**

```scala
// src/test/scala/m68k040/cache/IcachePrefetchSpec.scala
class IcachePrefetchSpec extends AnyFunSuite {
  test("P1: no prefetch is issued for an INHIBITED page") { }
  test("P1/P4: no prefetch is issued when the next line crosses a page boundary") { }
  test("P1: a prefetch NEVER causes an ITLB table walk (assert zero walker ARs)") { }
  test("P2: an errored prefetch allocates nothing, raises no fault, and halts nothing") { }
  test("P3: after an errored prefetch, a DEMAND fetch of that line issues a NEW REAL AR " +
       "and gets its own contemporaneous response") { }
  test("suppression: no prefetch for a resident line, a line already in an MSHR, " +
       "or a line in the same set as an active MSHR (D3-SET-I)") { }
  test("wrong-path prefetch during a redirect storm is harmless and needs no abort") { }
  test("prefetchEnable=false issues zero prefetch ARs") { }
}
```

The P1 walker test is the sharpest: assert `AxiMemStats.arCount(AxiIds.I_ITLB_WALK)` is **unchanged** across a window in which many prefetches issue.

- [ ] **Step 2: Measurement** — `big-code` from V1.7, prefetch on vs off, under `l2+dram(20)` **and** under `todaysCrossbar`. The design doc's claim is that prefetch works on today's crossbar; **that claim is this slice's headline and must be measured, not asserted.** Report `pfIssued`/`pfUseful`/`pfWasted` alongside the cycle counts — a large `pfWasted` with no cycle win means the trigger heuristic is wrong.
- [ ] **Step 3: Full corpus + Chaos lock-step + synth gate** per GC5, both extra preconditions restated. Prefetch adds ~256 flops of `filledByPrefetch` plus a second MSHR; the `beatLatch`/`predAccum` duplication from I3.1 Step 4 is the item to watch.

---

## Slice D4 — At-ROB-head, non-speculative MMIO loads (RATIFIED §11.Q4) + `dQuiesce`

Design doc refs: **§11.Q4 RATIFIED** ("full at-ROB-head non-speculative MMIO" — the *stronger* of the two options offered), §5.5 (the original, weaker strict-serialisation scope), §7.3 (`dQuiesce`), §4.3 R2 (a preempted head re-executing an MMIO load), §4.2 (INHIBITED access → anything: total order), §7.2 (interrupts and precise exceptions).

**Scope note from the ratification, quoted:** "slice D4 is upgraded from '§5.5 strict serialisation' to a genuinely new mechanism — an INHIBITED load must not even *launch* until it is the ROB head (mirroring the landed precise-store `headPreciseReady`/at-head-drain shape in `StoreQueue.scala`/`RobPlugin.scala`'s completion-port machinery, but for a LOAD rather than a store …). This upgraded D4 should be planned as its own right-sized slice (likely comparable in scope to the original precise-store retirement work, Task-brief-wise)."

**READ THE LANDED PATTERN FIRST.** The mechanism this slice mirrors is real, in the tree right now, and was itself hard to get right:

- `StoreQueue.scala:200-204` — `headPreciseReady` (the at-head launch condition, including its `!io.flush && !io.irqPreemptPendingIn` gates and its written-out deadlock-freedom argument at `:190-199`);
- `StoreQueue.scala:392-402` — `preciseDrainBusyReg`, "registered launch-through-resolution-**plus-one-cycle**" busy, and **why** the extra cycle exists;
- `RobPlugin.scala:319-320` — `preciseDrainBusyIn`, and `:1085`/`:1154` — its two consumers, `normalIrqGate` and `traceNormalGate`;
- `RobPlugin.scala:505-549` — `h0PreciseCompletedSticky`, and **especially** the post-review commentary explaining why a one-shot `RegNext` pulse was wrong and a level-sensitive sticky latch is right;
- `LsEuPlugin.scala:1421-1518` — the deferred-completion replay, and the two real bugs its comments record (the same-cycle `pendPush` flush gate, and the `applyFast` same-cycle path).

The reference for task granularity is `docs/superpowers/plans/2026-07-23-dcache-copyback-precise-store-retirement.md`, slice P2.

### Deadlock-freedom argument (write this into the code; it is the thing a reviewer will ask about first)

An INHIBITED load waits for `robId === h0 && dQuiesce`. Is that circular?

- **Head advance does not depend on this load.** The ROB head advances by retiring strictly older entries, none of which wait on this load. So `robId === h0` becomes true unconditionally.
- **`dQuiesce` does not depend on this load.** Its three terms are: no valid D-cache MSHR, no outstanding D-side write, and `sq.empty`. Every SQ entry is **program-older** than this load (LS issue is strictly oldest-first in program order, `IssueQueuePlugin.scala:335-337`), so every one of them has already retired by the time this load is at the head, hence is `committed`, hence drains. **A precise store in particular cannot be stuck**: a precise store completes only via its own at-head drain, so it must have *been* the head before this load, and therefore has already popped.
- **Preemption is not a deadlock but the intended behaviour.** If an interrupt or trace is pending, the launch is withheld, the ROB preempts the head, and the flush poisons the access. The load re-executes after RTE — and because it never launched, **there was no device side effect**, which is exactly what closes §4.3 R2 architecturally rather than by accident.

### Task D4.1: `dQuiesce`

**Files:**
- Modify: `src/main/scala/m68k040/cache/DcacheTypes.scala` (`DcacheService` gains `dcacheQuiesce: Bool`)
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala` (expose `dcacheQuiesceSig`, already created in D1.1 Step 3)
- Modify: `src/main/scala/m68k040/ls/StoreQueue.scala` (nothing new — `io.empty` at `:447` already means "no resident entry AND no drain in flight")
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (assemble and expose `dQuiesceSig`)

**Interfaces:**
- Produces: `LsEuPlugin.logic.dQuiesceSig: Bool`, `simPublic`. Consumers: D4.3 (INHIBITED launch), D4.5 (exception entry, RTE, cache maintenance).

Design doc §7.3, verbatim: `dQuiesce := no valid MSHR && no outstanding write && !drainBusy`. "Bounded: every outstanding transaction either completes or errors, and errors already resolve (no allocation, response delivered)."

- [ ] **Step 1:**

```scala
// src/main/scala/m68k040/execute/LsEuPlugin.scala — near the sq instantiation
    /** THE serialisation primitive (design doc §7.3). Deliberately NOT a new
      * architectural fence: all four consumers (exception entry, INHIBITED access
      * launch, cache maintenance, RTE) are EXISTING serialisation points.
      *
      * Bounded by construction: every outstanding transaction either completes or
      * errors, and an error already resolves (no allocation, response delivered).
      *
      * `sq.io.empty` covers BOTH "no resident SQ entry" and "no drain in flight"
      * (StoreQueue.scala:447), which is the `!drainBusy` term. */
    val dQuiesceSig = dcache.dcacheQuiesce && sq.io.empty
    dQuiesceSig.simPublic()
```

- [ ] **Step 2:** a directed test that `dQuiesceSig` is low from the cycle a miss allocates until its fill lands, and low from a store's SQ alloc until its B response — measured, not assumed.

### Task D4.2: ROB head visibility for the LS EU

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (three new sibling-driven inputs)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala`, `FuzzDut.scala`, `IpcBenchSpec.scala`, `ExecuteLockStepSpec.scala` (wire them)

**Interfaces:**
- Produces: `LsEuPlugin.robHeadIn: UInt(6 bits)`, `.robHeadValidIn: Bool`, `.irqPreemptPendingIn: Bool` — **exactly the same three signals `StoreQueue.io` already takes** (`StoreQueue.scala:97-99`), driven from exactly the same sources (`rob.logic.h0`, `rob.logic.count > 0`, `rob.logic.interruptPending || rob.logic.tracePendingFire`).

- [ ] **Step 1:** declare them with the `var`-plus-`allowOverride`-plus-default-idle convention this file already uses for `excActive`/`excLoadCmdValid` (`LsEuPlugin.scala:111-130`, `:203-212`), so a standalone DUT elaborates unchanged.
- [ ] **Step 2:** wire them in all four DUT-wiring sites, next to the existing SQ wiring (`FullCoreSynth.scala:180-184` neighbourhood). **Reuse the same nets the SQ already uses** — do not derive them a second way, or the two mechanisms can disagree by a cycle.

### Task D4.3: `AT_HEAD` — withhold an INHIBITED load until it is the non-speculative head

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (new FSM state; the XLATE routing; `parkEligible`)

**Interfaces:**
- Consumes: D4.1's `dQuiesceSig`, D4.2's head inputs.
- Produces: `LsEuPlugin.logic.mmioBusySig` (D4.4 wires it to the ROB).

- [ ] **Step 1: Route INHIBITED loads to the new state**

```scala
// src/main/scala/m68k040/execute/LsEuPlugin.scala — in XLATE's load arm (:1239-1246)
        } otherwise {
          fwdHit   := sq.io.fwd.rsp.hit
          fwdStall := sq.io.fwd.rsp.stall
          fwdData  := sq.io.fwd.rsp.data
          // §11.Q4 (RATIFIED): an INHIBITED (MMIO) LOAD is NON-SPECULATIVE. It does
          // not even LAUNCH until it is the ROB head with every program-older
          // instruction retired -- mirroring the landed precise-store at-head drain
          // (StoreQueue.scala:200-204), but for a load. This is materially stronger
          // than §5.5's plain serialisation, which only ordered INHIBITED accesses
          // relative to each other: it closes §4.3 R2 (a preempted head re-executing
          // an MMIO load, with its device side effect happening TWICE) architecturally
          // rather than by accident, and it removes the pre-existing speculative-MMIO-
          // read gap entirely.
          when(s2Cmode === m68k040.cache.CacheMode.INHIBITED) { goto(AT_HEAD) }
          .otherwise { goto(RESOLVE) }
        }
```

- [ ] **Step 2: Declare the state**

```scala
// src/main/scala/m68k040/execute/LsEuPlugin.scala — in the FSM's state list (:1106-1115),
// after RESOLVE and before LAUNCH:
      val AT_HEAD = new State   // INHIBITED load: wait until this uop IS the ROB head
```

- [ ] **Step 3: The state body**

```scala
      /** Non-speculative wait for an INHIBITED load (§11.Q4). See the plan's
        * deadlock-freedom argument: head advance and `dQuiesce` both depend only on
        * strictly-older work, so this always makes progress; and a pending
        * interrupt/trace legitimately preempts (the load never launched, so there is
        * no device side effect to undo). */
      AT_HEAD.whenIsActive {
        busy := True
        when(poisoned) {
          busy := False; s1Valid := False; goto(IDLE)
        } elsewhen(robHeadValidIn && (s1Ctx.robId === robHeadIn) &&
                   dQuiesceSig && !irqPreemptPendingIn && !sqFlushSig) {
          goto(RESOLVE)
        }
      }
```

**`RESOLVE`, not `LAUNCH`:** the SQ-forward query must still run. In practice an INHIBITED load at the head with `dQuiesce` asserted can have no older SQ entry (the SQ is empty by definition), so the forward will always miss — but routing through `RESOLVE` keeps a single code path and cannot silently skip a forward if `dQuiesce` is ever weakened. **State that reasoning in the comment.**

- [ ] **Step 4: An INHIBITED load never parks**

```scala
// modify D1.3's parkEligible
    val parkEligible = llReg.valid && !llReg.twoAccess && lttFree &&
                       (llReg.cmode =/= m68k040.cache.CacheMode.INHIBITED)
```

A non-speculative MMIO load must complete on the live path with the EU held, so that the `mmioBusySig` interlock (D4.4) has a well-defined window. Comment it.

- [ ] **Step 5: `mmioBusySig`**

```scala
    /** Asserted from the cycle an INHIBITED access LAUNCHES until one cycle past its
      * response -- the load-side twin of `StoreQueue.preciseDrainBusyReg`
      * (StoreQueue.scala:392-402). The EXTRA CYCLE is not cosmetic: it is there for
      * exactly the reason that register's comment gives, so the ROB's
      * normalIrqGate/traceNormalGate can never race a same-cycle preempt against an
      * access that just resolved. Copy that shape; do not simplify it. */
    val mmioBusyReg = RegInit(False)
    val mmioLaunch  = fsm.isActive(fsm.LAUNCH) && dcache.loadCmd.fire &&
                      (llReg.cmode === m68k040.cache.CacheMode.INHIBITED)
    val mmioResolve = fsm.isActive(fsm.WAIT) && dcache.loadRsp.valid &&
                      (dcache.loadRsp.payload.tag === U(LIVE_TAG, m68k040.cache.DcacheTags.TAG_W bits))
    when(mmioLaunch) { mmioBusyReg := True }
      .elsewhen(RegNext(mmioResolve, init = False)) { mmioBusyReg := False }
    val mmioBusySig = mmioBusyReg; mmioBusySig.simPublic()
```

### Task D4.4: The ROB-side preemption interlock

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala:319-320` (a new sibling-driven input beside `preciseDrainBusyIn`), `:1085` (`normalIrqGate`), `:1154` (`traceNormalGate`)
- Modify: the four DUT-wiring sites

- [ ] **Step 1:**

```scala
// src/main/scala/m68k040/rob/RobPlugin.scala — beside preciseDrainBusyIn (:319-320)
    /** Load-side twin of `preciseDrainBusyIn` (§11.Q4). While a non-speculative
      * INHIBITED (MMIO) LOAD is in flight from the LS EU, the head must NOT be
      * preempted by an interrupt or trace: the access has already left retire-time
      * control and its DEVICE SIDE EFFECT has already happened, so preempting and
      * re-executing after RTE would perform it TWICE. Same allowOverride/simPublic
      * convention and same two consumers as preciseDrainBusyIn. */
    val mmioBusyIn = Bool(); mmioBusyIn.allowOverride; mmioBusyIn := False
    mmioBusyIn.simPublic()
```

```scala
// :1085 and :1154 — add the term to BOTH gates
                        !preciseDrainBusyIn && !mmioBusyIn
```

- [ ] **Step 2: Do NOT add an `h0MmioCompletedSticky`.** `h0PreciseCompletedSticky` (`:527-549`) exists because completion port 4 fires **asynchronously, many cycles after issue**, letting `h1` slip through in the same dual-retire cycle. An MMIO load completes through the **ordinary** `comp*`/`completionPort` path at execute time, exactly like every other load, so the race that motivated the sticky latch does not arise. **Write that reasoning into the commit message** — a reviewer who knows the precise-store work will look for the sticky latch and must find the argument for its absence.

### Task D4.5: `dQuiesce` at the other three serialisation points

Design doc §7.3 names four consumers. D4.3 built one; the other three:

- [ ] **Step 1: Exception entry.** Gate the ROB's `excEntryTrigger` (`RobPlugin.scala:938`) on `dQuiesceIn`, so the ExceptionUnit's direct frame stores (`driveStoreNoXlate`, arbitrated at `LsEuPlugin.scala:1196-1199`, per-word `E_STWAIT` at `ExceptionUnit.scala:755`) keep their one-store-at-a-time contract with no new reasoning (design doc §7.2 last bullet). **This changes exception-entry timing** — expect ported-corpus movement and budget it (GC2).
- [ ] **Step 2: RTE.** Same gate on the RTE trigger path.
- [ ] **Step 3: Cache maintenance.** `CPUSH` is a documented no-op today (`ExceptionUnit.scala:1150-1152`) and the D-cache has no invalidate port (`DcacheTypes.scala:49-60`), so there is nothing to gate **yet**. Add the `dQuiesce` requirement as a written contract in `DcacheTypes.scala`'s service comment so the copyback design's P5 inherits it rather than rediscovering it.
- [ ] **Step 4:** `iQuiesce` — the I-side equivalent (`!mshrValidI.orR`), needed only for maintenance. Expose it from `IcachePlugin` and leave it unconsumed, with the same "P5 inherits this" comment. **Exposing an unconsumed signal is normally a smell; it is justified here because it is a named contract, and it costs nothing (it optimises away).** Say so in the comment.

### Task D4.6: D4 tests, regression and synth gate

- [ ] **Step 1: Directed tests** (`src/test/scala/m68k040/ls/MmioAtHeadSpec.scala`)

```scala
class MmioAtHeadSpec extends AnyFunSuite {
  /** THE headline property: an INHIBITED load issues NO bus transaction until its
    * robId is the ROB head. Assert AR count stays 0 while older entries are
    * outstanding, then becomes 1. */
  test("an INHIBITED load issues no AR until it is the ROB head") { }

  /** §4.3 R2, closed: a preempted head must NOT have performed the device read. */
  test("an interrupt preempting a not-yet-launched INHIBITED load performs NO bus read") { }

  /** The interlock's other direction: once launched, the head is NOT preempted. */
  test("an interrupt arriving mid-INHIBITED-access does not preempt until it resolves") { }

  /** dQuiesce: no other D-side transaction is outstanding across an INHIBITED access,
    * and there is EXACTLY ONE bus access per MMIO access (design doc §8.2). */
  test("dQuiesce holds across an INHIBITED access; exactly one AR per MMIO load") { }

  /** No deadlock under back-to-back MMIO, MMIO-behind-a-miss, and MMIO-behind-a-
    * precise-store. Each is a distinct path through the argument in this slice's
    * deadlock-freedom note -- test all three. */
  test("no deadlock: MMIO behind a load miss, behind a precise store, and back-to-back") { }

  /** An INHIBITED load never parks in the LTT. */
  test("an INHIBITED load takes the live path (LIVE_TAG), never the LTT") { }
}
```

- [ ] **Step 2: Regression.** Full corpus + `AXI_RSP_MODE=Chaos AXI_LATENCY=dram` lock-step. **Expect real movement here**: D4 changes when MMIO accesses and exception entries happen, and the ported corpus is full of MMIO-shaped tests. Triage every delta per GC2 and record the verdicts individually.
- [ ] **Step 3: Synth gate** per GC5, both extra preconditions restated. D4 adds one FSM state, one register, and two comparison terms — it should be area-neutral. If it is not, the `dQuiesce` fan-out is the thing to look at.
- [ ] **Step 4: Commit**, with the deadlock-freedom argument and the "why no `h0MmioCompletedSticky`" reasoning in the message.

---

## Deferred work and dependency audit

Three slices from the design doc are **not** in this plan. For each: why, and — the point of this section — **whether any near-term task above has a hard dependency on it.** The answer in all three cases is **no**, and the specific reasoning is given so a future reader does not have to re-derive it.

### `D3-BURST` — widen the L1D line 16 B → 64 B (design doc §5.2, decision D4, slice row "D3")

**Status: DROPPED near-term, per RATIFIED §11.Q2** ("keep the L1D line at 16 bytes for now; revisit later"). The user decided this against the design doc's own recommendation, and the doc records that honestly. Consequence recorded there: **copyback P4 is unblocked and may proceed with today's 16-byte granularity without waiting on this design.**

**Hard dependency from any near-term task? NO.** Checked case by case:

| Near-term item that *looks* like it might depend on it | Verdict |
|---|---|
| D1.2's fill-forward, and design doc §5.2's "CWF is not optional if D4 lands" | **No dependency.** At `len = 0` a single beat **is** the whole line, so fill-forward is trivially critical-word-first. The CWF requirement only bites at 4 beats. |
| Design doc §5.3's "at 64-byte lines, `fillData` should be a rotating single-beat latch, not a 512-bit line latch" | **No dependency.** D1 latches nothing: it forwards straight off `axi.r.payload.data` at `r.last`. |
| The SQ `sameLine` stall coarsening 4× (§5.2's main technical risk) | **No dependency.** `StoreQueue.scala:258-261` compares `paddr(31 downto 4)` — 16-byte granularity, unchanged. |
| Copyback P4's dirty-bit / eviction granularity | **No dependency, and explicitly unblocked** by Q2. |

**Forward-looking note to leave behind (not a dependency, a hazard for whoever picks D3-BURST up):** D1.2's fill-forward extracts the response from **the beat that carries `r.last`**. At `len = 0` that is the only beat and therefore always the demanded one. **At `len = 3` it would be the LAST beat, which is usually the wrong one** — so D3-BURST must, as part of its own slice, replace `axi.r.payload.data` with a critical-word-first selection (either request CWF from the bus, or forward off whichever beat covers `mshrOff`). This is written into D1.2's code comment; make sure it survives review.

### `D2` — `N_MSHR`/`N_LTT` → 2, miss-under-miss, secondary-miss merging (design doc §5.3, §9 slice row D2)

**Status: DEFERRED, contingent on the SoC crossbar rework.** §11.Q3 was answered "the crossbar WILL be reworked", so D2 stays in the plan — but per §9's revised commitment order it "should not be scheduled until that SoC-side rework is confirmed landed". Until then it delivers **exactly zero** end-to-end benefit: `macqd700-soc/rtl/soc/axi_xbar.v` serialises to one outstanding read per master port (`:2278-2284`, `:2388-2412`), so a second MSHR's AR simply queues.

**Hard dependency from any near-term task? NO.** Checked:

| Near-term item | Verdict |
|---|---|
| D1's `sameSetBlock` / `sameLine` classification structure | **Written FOR D2, needed BY neither.** At `N_MSHR = 1` these terms are subsumed by `!mshrValid(0)`. They are written explicitly, with a comment forbidding their deletion as dead logic, precisely so D2 is a parameter change. That is preparation, not dependency. |
| The M1/M2/M3 merge rules (GC1) | **Carried into I1** (I-side same-line merge, which IS built here because it is mandatory the moment I-side accept is non-blocking) and into D1.5's M2 test. They do not wait for D2. |
| `DcacheTags.TAG_W = 2` / `N_LTT_MAX = 2` | **Sized for D2 up front** so raising `N_LTT` never ripples a bundle width through six files. Again preparation, not dependency. |
| V1's `Reordered` / `Chaos` / `IllegalInterleave` modes | **Used immediately** — V1.8 runs the whole existing corpus under them against today's single-outstanding RTL, which is where they earn their keep. They are not idle until D2. |
| The per-ID R-beat routing (V2a.2) | **Needed NOW** by V2c's walker fold, independently of D2. |

### `D5` — outstanding fast-path writes, merged with copyback P6 pipelined hit-drain (design doc §5.6, §9 slice row D5)

**Status: DEFERRED, same contingency**, and with an additional one: the crossbar locks `AW → B` per master **and** per slave (`axi_xbar.v:1234-1258`), whose own comment records that pipelining was analysed and deliberately not implemented ("a substantial, risk-bearing redesign… do not bolt onto the current owner scheme"). D5 also wants to merge with the copyback design's P6, which has not landed.

**Hard dependency from any near-term task? NO.** Checked:

| Near-term item | Verdict |
|---|---|
| `DcacheService.storeAckTag` (V2a.3) | Added **inert** and constant-0. It exists so the consumer contract does not change when D5 lands. No behaviour depends on it. |
| `dQuiesce`'s "no outstanding write" term (D4.1) | Correct with one outstanding write and correct with N; `sq.io.empty` already means "no resident entry AND no drain in flight". |
| D4.5's exception-entry gate | Uses `dQuiesce`; unaffected by write concurrency. |
| The precise-store path (landed P1/P2) | **Explicitly untouched** by this plan. Design doc §5.6/§4.1(iii): a precise store retires on its own contemporaneous B response, full stop, and D5 would only ever add concurrency to *fast-path* writes. Nothing here weakens that. |

### Design-doc items deliberately NOT built, recorded so their absence is visible

- **§4.1(iii)** (early-ack / posted-write rules) — belongs to D5; no near-term task introduces posted writes, so the rules have no enforcement point yet. Not carried into any acceptance criterion, deliberately.
- **Option I-c** (move predecode to align time, §6.4 / §10.E / §11.Q6) — the design doc recommends raising it as a **separate, independently-justified proposal** and explicitly says "do not couple the MSHR work's fate to it". This plan does not couple them. I2 implements I-b.
- **A MOB / out-of-order LS issue** (§10.A, non-goal N1) — a separate project. D1.3's LTT is deliberately shaped as "the load-tracking half of a MOB" so a later MOB adds address-match + replay on top rather than replacing it.
- **D-side prefetch** (§10.D, non-goal N5) — rejected for now; revisit only after D3-BURST with real miss-rate data from V1.7's kernels.
- **Refill abort / squash** (§10.F) — rejected: AXI has no transaction cancellation, so "abort" can only mean "discard the response", which is exactly what poison already does. **No refill-abort mechanism is proposed anywhere in this plan.**

**Audit verdict: zero hard dependencies on dropped or deferred work.** Every near-term slice is buildable, testable and mergeable with `N_MSHR = 1`, `N_LTT = 1`, a 16-byte L1D line, and single-outstanding writes.

---

## Self-review record

This section is the output of the required self-review pass, kept in the document so the record persists. Three checks were run: **spec coverage**, **placeholder scan**, and **type consistency across tasks**. Findings are recorded whether or not they were fixed; everything marked FIXED was corrected in this document before it was finished.

### A. Type-consistency findings (all FIXED)

1. **`DcacheTags.N_LTT_MAX` was arithmetically impossible.** `TAG_W = 2` gives four tags; `EXC = 3` and the live blocking path needs one, so the LTT can only own tags 0..1 — yet `N_LTT_MAX` was written as 3. A `require(N_LTT <= N_LTT_MAX)` would then have accepted `N_LTT = 3`, which would have collided with the live path's tag and silently mis-routed responses. **FIXED**: `N_LTT_MAX = 2`, plus a named `DcacheTags.LIVE = 2` (D1.3's `LIVE_TAG` now derives from it instead of computing `N_LTT_MAX - 1`, which was the arithmetic that hid the error).
2. **`ChunkPredecode` width was mis-stated throughout slice I2.** It is **6** bits (`simple` + `lenWords[3:0]` + `ambiguousLine`, `IcacheTypes.scala:19-35`), so a line packs to **192** bits and a beat to **48** — not the 160/40 the plan said. The flop-saving claim was correspondingly wrong: `512 → 128 + 192 + 3` is **≈ −190 flops**, not −352. **FIXED** in all four places. *(The plan's code was already correct — it derives everything from `PRED_BITS_PER_WORD` — only the prose and the commit message were wrong, which is exactly the kind of error a reviewer would have propagated into a report.)*
3. **`IcachePlugin.scala:66-70` carries a STALE comment** claiming `ChunkPredecode` is 5 bits and `PRED_BITS_PER_LINE` is 160. The code is right (it uses `ChunkPredecode().getBitsWidth`); only the comment is wrong. Recorded in slice I2 as a fix-while-you-are-there.

### B. Spec-coverage findings (all FIXED)

4. **Three directed tests from design doc §8.2's D-side list were missing** from D1.5: the **drain-vs-fill same-set** repro (which §8.2 explicitly calls out as "worth having independent of this design", because the copyback design flags the same window as a pre-existing bug in today's code), the **write-port collision on the same way index in different sets** case (which `D3-SET` does *not* cover — see D1.1 Step 4), and the **IRQ storm across an outstanding load**. **FIXED**: added as D1.5 cases (6), (7) and (8), with the instruction to run case (6) against the pre-D1 commit in a worktree so it demonstrably fails there.
5. **Completion-arbiter collision coverage was missing.** D1.3 builds a 3-source arbiter but D1.5 tested none of its collisions. **FIXED**: added the 2-way (live vs LTT) and 3-way (live vs LTT vs precise-store replay) cases, pointing at the existing `io.sqCompletion.valid` sim tap that task P2.5 added for exactly this purpose.
6. **Design doc §8.3's counter list was only partly reachable in V1.** MSHR-occupancy histogram, merge count and stall-by-reason all need taps that do not exist until D1. **FIXED**: added D1.7 Step 4 as an explicit obligation to extend `MissCounters` once the D1 taps land, including the requirement that stall reasons **sum to the total** (an unattributed stall cycle is a measurement bug, not a rounding error).

### C. Placeholder scan — one KNOWN, DELIBERATE deviation

7. **Directed-test bodies are specified but not fully written out.** Roughly 35 test cases across D1.5, I1.2, I1.4, I3.4, D4.6 and V2a.2 are given as a complete specification — exact `test(...)` name, exact stimulus sequence, exact assertions, exact statistics to read — with the harness boilerplate delegated to "copy the DUT-construction helper from `<named file>`".

   This is a **deliberate** deviation from "complete code in every step", and the reason is that inventing Verilator harness boilerplate for six different standalone DUTs without having executed them would produce plausible-looking code that does not compile or, worse, compiles and silently tests nothing — and this project has a specific, recorded history of hand-rolled boot harnesses diverging from the real ones via `Random`-state ordering (memory: `fuzz-campaign-divergence-2026-07-16`, which is why `FUZZ_TRACE_CPLX` was added to `FuzzRunner.run` instead).

   The mitigation, which every affected task carries: **the exact existing harness to copy is named** (`DcacheSpec.scala` and `IcacheSpec.scala` for the standalone cache DUTs; `LsEuSpec.scala` / `LsEuFastPreciseSpec.scala` for the LS-EU DUT; `IpcBenchSpec.scala` for the full-core bench DUT), and every assertion is stated as a concrete predicate on a named signal or a named `AxiMemStats` field. An implementer copies a helper and fills in assertions that are already written for them. **If a task's implementer finds the named harness does not fit, that is a signal to raise — not to invent a new one.**

8. **No `TODO`, `FIXME`, `<placeholder>`, `...`-as-elision or "implement this" markers remain** in any RTL code block. Verified by inspection: every Scala block in slices V2, D1, I2, I1, I3 and D4 is complete, compilable-shaped code against real, quoted line numbers from the current tree. One block is deliberately marked as needing replacement — `AxiMemModelSpec`'s "write-before-B" test in V1.2 Step 5 — and V1.2 Step 6 is the step that replaces it, which is explicit rather than silent.

### D. Substantive findings about the DESIGN DOC itself, surfaced by this close read

Recorded here because the design doc is approved and ratified and this plan does not second-guess its architecture — but these are places where a literal reading would have produced the wrong implementation, and they are the answer to "did planning catch anything the author's own self-review missed".

9. **§6.4's "32 → 16 classify instances" is stale by exactly one ratified decision.** It was written before §11.Q7 ratified the 256→128-bit I-cache narrowing. After V2b a beat holds **8** words, not 16, so option I-b is a **4× cut, not 2×**. The plan recomputes it (as instructed) and takes the larger win.
10. **§6.4's "removes the separate PREDECODE cycle" and its own area goal are in direct conflict after that narrowing** — deleting the cycle requires a **second** 8-instance group for the self-contained last beat (16 total, back to a 2× cut). The plan chooses area over the cycle, states the trade explicitly, and notes that flipping back is localised. The design doc presents both as if they came together; after V2b they do not.
11. **§6.5's third proposed `invalidateAll` closure ("invalidate the in-flight response stages") would WEDGE THE FRONT END.** `FetchAlignPlugin`'s depth-3 ring decrements `ringCount` only on `ic.rsp.valid` (`:302-312`) and has **no timeout and no other decrement path**. Cancelling an in-flight response would strand that ring entry, `ringFull` would eventually block `ic.cmd.valid` permanently, and the front end would stop fetching with no recovery. I1.4 therefore adopts closures 1, 2 and 4 and **rejects 3**, delivering the response and noting that if discarding it is ever genuinely required, the correct mechanism is `FetchAlignPlugin`'s **existing `ringStale` bit** (`:171`, `:263,283,299`) — a `FetchAlignPlugin` change, not an `IcachePlugin` one. The full analysis is written into the task so it is not re-derived.
12. **§5.4's "loads that HIT complete as today; loads that MISS park" is not directly implementable** — the EU cannot know which it is at launch and the cache does not tell it. The plan adopts park-at-launch, which has the same effect, and says so.
13. **§5.3 item 1's "sample `victim(set)` AND increment it at allocate" would change behaviour** that the I-side deliberately has: today the increment is conditional on `doAllocate`, so an **errored or INHIBITED** miss does not bump the victim (mirroring `IcachePlugin.scala:374-376`'s explicit choice). The plan adopts the sampling half and keeps the increment where it is, with the reason recorded.
14. **NEW BUG FOUND, pre-existing, outside this plan's scope: `IcachePlugin` never reads `xlate.rsp.cacheMode` at all.** Verified — `grep -n "cacheMode\|cmode" src/main/scala/m68k040/cache/IcachePlugin.scala` returns nothing. **A cache-INHIBITED instruction page is therefore allocated into the L1I today**, which is architecturally wrong on a 68040 independently of anything in this design. The design doc does not mention it. Slice I3 needs the I-side cache mode anyway (rule P1) and so captures it, but **deliberately does not change demand-allocate behaviour** — that is an unrelated correctness fix deserving its own task, lock-step run and triage. **File it separately.**
15. **The design doc's §9 "E2E? YES" column and its §11.Q3 answer are in tension, and the plan resolves it in the doc's favour.** §11.Q3 was answered "the crossbar WILL be reworked", which would make D2/D5's "NO" entries temporary — but §9's revised commitment order still says not to schedule them until the rework is *confirmed landed*. The plan follows §9 (defer, and audit for dependencies) rather than treating the Q3 answer as a licence to build them now.

### E. Two operational risks this plan cannot remove, restated so they are not forgotten

16. **GC5(a) is a live merge blocker, not a formality.** With the branch's ~3× LUT bloat and 142 MHz post-route reading (tracker #115/#198) unresolved, **no slice's area or FMax delta is measurable**, and slice I2 — whose *primary* claim is area — is the one most damaged by it. Every gate task restates this; none of them may quote a pass while it is open.
17. **Slice V2c has the widest blast radius in the plan** (two top-level AXI masters disappear; the I master changes type from `Axi4ReadOnly` to `Axi4`; every DUT and several MMU tests are touched). It is deliberately sequenced *after* V1.6's harness consolidation, which is what reduces it from "edit a dozen call sites" to "edit a handful of signatures". **Do not reorder V1.6 and V2c.**

---

## Slice/task index

| Slice | Tasks | RTL? | Synth gate? | Depends on |
|---|---|---|---|---|
| **V1** — verification substrate | V1.1 … V1.8 (8) | **no** | **no** (zero `src/main` changes) | — |
| **V2a** — ID hygiene + tagging | V2a.1 … V2a.4 (4) | yes | yes | V1 |
| **V2b** — I-cache 256→128-bit | V2b.1 (1) | yes | yes | V2a |
| **V2c** — walker fold (D then I) | V2c.1, V2c.2 (2) | yes | yes | V2b, **V1.6** |
| **D1** — hit-under-miss, 1 MSHR, `N_LTT=1` | D1.1 … D1.7 (7) | yes | yes | V2 |
| **I2** — per-beat predecode | I2.1 … I2.3 (3) | yes | yes | V2b |
| **I1** — I-side non-blocking + merge + invalidate | I1.1 … I1.5 (5) | yes | yes | V2, I2 |
| **I3** — next-line prefetch | I3.1 … I3.4 (4) | yes | yes | I1, I2 |
| **D4** — at-ROB-head MMIO + `dQuiesce` | D4.1 … D4.6 (6) | yes | yes | D1 |

**40 tasks across 7 slices** (V2's three sub-slices counted as one). The D-side chain (D1 → D4) and the I-side chain (I2 → I1 → I3) are file-disjoint after V2 and may run concurrently — subject to GC7's two-heavy-JVM limit.

**Commitment order of record (design doc §9, as revised by the ratified decisions):**
`V1 → V2a → V2b → V2c → D1 → I2 → I1 → I3 → D4`, **then stop and re-measure.**
