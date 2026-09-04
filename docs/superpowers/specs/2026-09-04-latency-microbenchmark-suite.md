# 68040 OoO latency microbenchmark suite — first measured latencies

**Date:** 2026-09-04
**Branch:** `bench/hw-microbench-suite` (worktree `m68k-core-040-ooo-worktrees/hw-microbench`)
**Base commit:** `bb3bca1` — *record this: the DTLB/MMU figures are a "before" baseline for
the in-flight change that routes table walks through the L1D.*
**Scope:** SIMULATION ONLY. No board, no JTAG lease, no SD card was touched.

---

## 0. Scope change and what it means

This campaign was originally scoped to run on the FPGA. It was re-scoped mid-flight to
**simulation only**, so the SD card holding the verified calibration-fix-only ROM and the
Happy-Mac boot volume was **never accessed**, no bitstream was loaded, and the JTAG lease
was never taken. The board-preservation constraint is satisfied trivially and completely.

One hardware finding was already established before the re-scope and is retained here
because it decides whether this suite can ever be ported to silicon:

> **There is no working free-running cycle counter on the current hardware build.**
> `OFF_CYCLE_LO/HI` (`0x5090_1000/1004`) are declared in the debug register map but are
> **Stage 7**, while `M68kSocketTop` is built at `debugStage = 5`. They are absent from
> `DebugCtrlPlugin`'s read mux entirely and return `0x00000000`. The core honestly
> self-reports this: `OFF_FEATURES` bit 12 (`perf_counters`) reads 0.
> A real free-running 64-bit **retired-macro-instruction** counter *does* exist and is
> live at `OFF_INST_LO/HI` (`0x5090_1008/100C`), readable by a plain CPU load or over
> JTAG. `inst-count` in the REPL reads a *different*, **halt-captured** register
> (`OFF_HALT_HIT_INST_LO/HI`, `0x5090_0048/004C`), which is why it returns a stale value
> — often `0` — on a running CPU. That is the documented cause of its unreliability.

So a future hardware port of this suite needs a cycle counter added (two `is()` cases in
`DebugCtrlPlugin`'s read mux plus a 64-bit free-running `Reg`), and would then reuse the
kernels and the differential method here unchanged. **That work was explicitly taken out
of scope and was not done.**

---

## 1. Timing source, and how it was validated

**Source used:** the simulation clock, counted by the existing bench harness's per-cycle
commit histogram (`CoreBenchHarness.runKernel`). A kernel's `windowCycles` is the number
of clock edges between its **first** and **last** macro-instruction commit — pipeline fill
and drain are outside the window by construction. This is not a CSR and not a wall clock;
in Verilator the testbench counts edges directly.

**Validation.** The suite refuses to report anything until three checks pass, and it
`assert`s on each, so a broken timing source fails the test rather than producing numbers:

| Check | What it proves | Result |
|---|---|---|
| **V1 linearity** — marginal cost of a NOP measured at three different (short,long) pairs: (200,400), (400,800), (200,800) | Cost is strictly proportional to N. A counter that saturated, wrapped, double-counted or dropped cycles cannot give the same slope at three lengths. | **0.500, 0.500, 0.500** cyc/NOP. Spread **0.0000**. |
| **V2 known cost** — dependent ALU chain | Anchors the *absolute* scale, which linearity alone cannot. Documented cost is 1 cycle (single-cycle ALU with result forwarding). | **1.000 ± 0.000** cyc/op — exact. |
| **V3 dual retire** — independent ALU chain | The counter tracks real 2-wide retire, not instruction count. | **0.500 ± 0.000** cyc/op — exactly 2/cycle. |

All three are exact to three decimals with zero variance across seeds. **The timing source
is sound.**

---

## 2. Method

### 2.1 Differential (marginal-cost) timing

Every latency is a **difference between two runs of the same kernel** differing only in the
length of a dependency chain:

```
cyclesPerStep = (cycles(nLong) - cycles(nShort)) / (nLong - nShort)
```

Everything constant cancels *exactly*: pipeline fill, drain, register setup, loop preamble,
the window's own edges, and the cold-start misses of a first pass. No overhead constant is
ever hand-estimated — the subtraction is structural. Where a chain step contains work
besides the operation under test, a **matched control** kernel with only that operation
replaced is measured and subtracted; both halves are always printed so the subtraction is
auditable.

### 2.2 Latency vs throughput

This is an out-of-order core: it hides EU latency by issuing independent work. Every kernel
is labelled **DEP** (serial dependency chain → measures **latency**) or **IND** (independent
ops → measures **throughput**). For the long-latency EUs both are reported, because the gap
between them is the result.

### 2.3 Variance

Each figure is the mean over **3 seeds** (5 where noted). SpinalHDL randomises the AXI
agents' ready-throttling per seed, so a single reading is one draw from a distribution, not
a measurement. Mean, standard deviation, min/max **and the raw per-seed samples** are
printed for every row — a mean plus sigma can hide a bimodal or degenerate sample, and in
one case below it did.

### 2.4 What is real RTL and what is a model — read before quoting any memory number

The core, its L1I/L1D, the store queue, the TLBs and the table walker are **real RTL**.
Beyond the core's AXI ports there is **no RTL**; `m68k040.sim.AxiMemModel` stands in for
the SoC. Its own header is explicit about its provenance:

- `hitCycles = 5` (L2 hit round trip) is **calibrated** against the real L2
  (`macqd700-soc/docs/l2c_spec.md:668-679`). L2-hit numbers are meaningful.
- `dramCycles` is ***explicitly UNMEASURED*** — "real DDR4/MIG latency is undocumented in
  BOTH repos. This MUST be swept, never assumed." **Any absolute DDR figure from this suite
  is a model input echoed back, not a property of the machine.**
- The model's L2 has **unbounded capacity** (`l2Lines` is a `mutable.Set` that never
  evicts). An L2 **capacity** miss cannot be produced at all — only a **cold, first-touch**
  miss. The "DRAM" row below is therefore a cold-miss measurement.

### 2.5 Cache control

Stated per row. The L1D is 8 KiB, 4-way, 128 sets, 16-byte lines, round-robin replacement.
Levels are selected by **footprint**, not by flushing: a 4 KiB working set fits in L1D and
hits; a 32 KiB working set is 4× the cache and misses essentially every access while
remaining L2-resident from the previous pass; a straight-line stride-64 walk touches a
never-before-seen 64-byte line every step and is cold to the model's DRAM path.

---

## 3. Measured results

Memory rows: `IPC_MEM=l2:5:70`. All other rows: zero-latency memory (isolates the core).
All values in **core clock cycles**.

### 3.1 Store-to-load forwarding — the headline

| Measurement | Cycles | Method |
|---|---|---|
| **store → load → use round trip (DEP)** | **10.000 ± 0.000** | DEP; differential; COPYBACK; 2 macros/step |
| reference: load removed (IND) | 2.000 ± 0.000 | throughput-bound reference, **not** a subtraction control |

The kernel stores `d0` to an address and immediately reloads it into `d0`, so the loaded
value feeds the next store: a strict serial chain in which every load's address matches a
store still resident in the 8-deep store queue and must be satisfied by the SQ forward path.

**Two methodology points that changed the answer:**

1. **The first version measured the wrong thing.** Under the MMU-off default every store is
   WRITETHROUGH and must reach AXI; store drain (~10.6 cyc/store) dominated and *hid* the
   forwarding latency. Test and control came out at 10.873 vs 10.597 — a difference of
   0.277 that would have been reported as "forwarding cost" and would have been meaningless.
   Re-running under **COPYBACK** (match-all transparent translation, CM=01), where a store
   hit resolves entirely on-chip with zero AXI traffic, gives the clean 10.000 above.
2. **The "control" is not subtractable and is not subtracted.** Removing the load also
   removes the only RAW edge — the remaining store-reads-`d0`/move-writes-`d0` pair is a
   WAR dependency that register renaming eliminates. That kernel is throughput-bound
   (2.000 cyc/pair = 1 cycle/store issue) while the forwarding kernel is latency-bound.
   The 5× gap is what demonstrates the forwarding chain is genuinely latency-bound; it is
   *not* an overhead to subtract.

**On the m68k-ooo comparison:** this measures that the forward path exists and resolves a
dependent store→load→use chain in 10 cycles. It does **not** measure m68k-ooo, and no
speedup ratio against it is claimed here — that would require running the same kernel on
that core, which was not done.

### 3.2 Memory hierarchy (`IPC_MEM=l2:5:70`)

| Level | Marginal cost per dependent load | Method / cache control |
|---|---|---|
| **L1D hit** | **11.000 ± 0.159** | 4 KiB footprint < 8 KiB L1D; differential over passes; /64 accesses; minus matched no-load control |
| **L1D miss → L2 hit** | **20.139 ± 0.533** | 32 KiB footprint = 4× L1D → misses every access; L2-resident from prior pass |
| **cold miss → model DRAM** | **98.242 ± 1.858** | straight-line stride-64; a fresh 64 B line every step |

These are **marginal** costs: the cost of adding one more dependent load to the chain, with
the matched no-load control already subtracted. The control replaces the load with a
register move, which itself costs 1 cycle, so **absolute load-to-use is these values +1**
(L1D hit ≈ 12 cycles). The chain is a zero-load pointer chase — the D-side memory is
zero-filled so the loaded value is 0 under any byte order, and the address register is made
to depend on it (`adda.l %d1,%a0` with `d1==0`), giving a true serial load-to-use dependency
without needing the memory model's endianness to be correct.

**The DRAM row is a model artefact and must not be quoted as this machine's DDR latency.**
`dramCycles=70` is an unmeasured parameter; the 98.242 figure largely echoes it back. The
`--dram-sweep` mode of the runner exists precisely to report the *slope* (how much memory
latency the core fails to hide on a dependent chain) and the *intercept* (core-side fixed
miss-handling cost) instead. **The sweep was not completed** — see §4.

### 3.3 Branch misprediction

| Measurement | Cycles | Method |
|---|---|---|
| **recovery: flush → next commit** | **13.002 ± 0.003** | DIRECT per-event instrumentation, 181 events/run |
| loop iteration, branch predicted | 11.200 ± 0.000 | DEP; differential over iterations |
| loop iteration, branch toggling | 10.010 ± 0.000 | DEP; differential over iterations |

The recovery figure is **measured, not inferred from pipeline depth**: the harness records,
for every retire-gated pipeline flush, the number of cycles to the next committed
macro-instruction. The histogram for one seed is `{13:181}` — **all 181 events are exactly
13 cycles**, with no spread at all.

**A differential I deliberately did not report.** `(toggling − predictable)` looks like a
misprediction penalty and is not one: the conditional guards an ADD, so the toggling loop
skips it on half its iterations while the predictable loop executes it every time. The two
loops do not retire the same instruction mix, and the difference mixes real recovery cost
with a −0.5-instruction-per-iteration accounting artefact — it comes out **negative
(−1.19)**. It is omitted rather than dressed up. The direct instrumentation needs no such
control and is the number above.

### 3.4 Long-latency execution units

| Op | LATENCY (DEP) | THROUGHPUT (IND) | Ratio |
|---|---|---|---|
| `divu.w` | **70.000 ± 0.000** | **70.000 ± 0.000** | **1.00×** |
| `divs.w` | **70.000 ± 0.000** | **70.000 ± 0.000** | **1.00×** |
| `mulu.w` | **12.000 ± 0.000** | **1.850 ± 0.000** | **6.5×** |
| `muls.w` | **12.000 ± 0.000** | **1.850 ± 0.000** | **6.5×** |

This is the clearest demonstration in the suite of why latency and throughput must be
measured separately on an OoO core:

- **The divider is confirmed single-outstanding by measurement.** Six *independent* divides
  are exactly as expensive as six *dependent* ones — issuing independent work buys nothing.
  This was previously an assumption; it is now a number.
- **The multiplier is pipelined and the OoO engine hides it almost completely.** A
  dependent chain pays 12 cycles per multiply; independent multiplies retire at 1.85
  cycles apiece. A throughput-only benchmark would have reported the multiplier as ~6.5×
  cheaper than its true latency.

**These were previously unmeasurable.** The bench harness never observed `divEu`'s
writeback port, so any kernel containing MUL/DIV died with `commit robId=N with no
writeback observed` — which is exactly why the IPC kernel corpus carries the standing
restriction *"NO DIV/MUL/CHK"*. `divEu` is the CPLX EU and retires MULU/MULS, DIVU/DIVS
**and** the FPU. Adding one line (`snapWb(dut.divEu.logic.wbObs)`) unblocked the whole
class. This is a real gap that was closed, not a workaround.

### 3.5 MMU

| Measurement | Cycles | Status |
|---|---|---|
| page-strided cold access, TTR-translated (no walk possible) | 104.567 ± 3.227 (n=5) | stable, reported |
| **DTLB miss + 3-level table walk** | — | **NOT MEASURED — see §4** |

The TTR row is a real, stable measurement: TC.E=1 with a match-all D-side transparent
translation, 32 KiB stride, cold lines, so it is the cold-miss cost with the walker
guaranteed not to run.

**Baseline context for the sibling walker change:** at `bb3bca1` the D-side table walker
has its **own dedicated AXI port** and does **not** go through the L1D
(`TableWalker.scala`; `DtlbPlugin.scala:76-83` binds `walkerAxi`). A walk is **3 dependent
single-beat reads, single-outstanding**. Descriptor reads are therefore entirely uncached
today. That is the structural "before" state; the *cost* number to go with it is the piece
I could not produce.

### 3.6 Aggregate IPC

Re-ran the existing `IpcBenchSpec` at `bb3bca1`, zero-latency memory:

```
AGGREGATE   5848 macro-instructions / 8928 cycles = IPC 0.655
```

Per-kernel range: `load/store` 0.251 … `independent-ALU` 1.995 (99.5% dual-retire).

Against the **~0.53** aggregate figure carried in the project's memory, current HEAD
measures **0.655**. Two cautions before treating that as a +24% improvement: the historical
0.53 was recorded under a different kernel corpus and predates the FPU landing and the
frontend restructure, and **I did not re-derive it under matched conditions**. The honest
statement is: *0.655 is the aggregate IPC of the current corpus at this commit*; it is not a
like-for-like delta against 0.53.

This run also served as the **regression check on my refactor** — `IpcBenchSpec` passes
unchanged after the harness was split into a shared trait and `divEu` observation was added.

---

## 4. What I could not measure, and why

Reported as plainly as the successes.

### 4.1 DTLB / ITLB table-walk cost — attempted twice, both invalid

**Attempt 1** (looped chase, differential over outer passes): unusable, spread larger than
the mean — `342.365 ± 484.177`, range `[0.000 … 1027.094]`.

**Attempt 2** (rebuilt as a byte-identical matched pair differing *only* in whether a
match-all D-side TTR is installed, so the difference isolates the walker): the no-walk half
became stable (`104.567 ± 3.227`), but the walk half is **bimodal**, raw samples over 5
seeds:

```
page-stride access, real walk :  {0.00, 0.00, 567.25, 556.43, 0.00}
```

Three of five seeds return **exactly 0.00**, meaning the `n=60` and `n=120` runs terminated
at the *same* cycle — the differential measured nothing. Two seeds return a consistent
~560. Averaging these would produce a confident-looking number that is meaningless, so
**no walk latency is reported.**

**This is itself a lead worth chasing.** A run whose end point is independent of chain
length points at the MMU-on / real-page-table configuration terminating on something other
than the chase — a wedge, or a fault path — in a majority of seeds. Given a sibling agent is
actively changing this exact walker, that instability should be understood before the change
lands, and it may be a real defect rather than a benchmark artefact. I did not have budget to
root-cause it. The kernels and page-table builder are committed and ready
(`kTlbChase`, `buildIdentityTables`), so reproducing it is one command.

Consequently the requested **"before" baseline for the walker change is only partially
delivered**: the structural baseline is documented (§3.5) and the no-walk control is
measured, but the walk cost itself is not.

**ITLB was not attempted at all.** It needs a code image spanning tens of 4 KiB pages to
force ITLB set eviction, which is a materially different kernel generator from anything
here. No ITLB number exists in this suite.

### 4.2 FPU — blocked by a harness limitation

`fadd.x` / `fmul.x` / `fdiv.x` all fail with `commit robId=N with no writeback observed`,
including on the *setup* instruction. The FPU is reachable in principle — it lives inside
`DivEuPlugin`, which this DUT does instantiate — but FP results are written to the FP
register file, and the bench harness's macro-classification (`WhiteboxCapture`) models
**integer** writebacks only. Adding `divEu.wbObs` fixed MUL/DIV because those write the
integer PRF; it does not cover FP destinations. Measuring FPU latency needs FP writeback
observation plumbed into the capture, which is a larger change than this task's budget.
Note also that the immediate-`<ea>` form of `fmove` is documented as deliberately
unimplemented in this core even though it assembles, so FP kernels must source operands
through integer registers.

### 4.3 I-cache hierarchy — not attempted

L1I hit / miss → L2 / miss → DRAM has **no kernel in this suite**. A defensible I-side
measurement needs a straight-line image larger than the 16 KiB L1I with the instruction mix
held constant so the backend never becomes the limiter, and the differential taken across
memory configurations rather than across chain length. I ran out of budget before building
it. The D-side numbers in §3.2 say nothing about the I-side.

### 4.4 DRAM sensitivity sweep — built but not run

`tools/run_microbench.sh --dram-sweep` is committed and re-runs the memory group at
`dramCycles ∈ {20, 40, 70}` so the slope and intercept can be extracted. **It did not run:**
the script's own host guard correctly refused to start because two sibling agents' heavy
JVMs were already up (the cap is 2 across all agents). Only the `dram=70` point exists.
Until the sweep runs, §3.2's DRAM row remains a single point tracking an unmeasured
parameter.

### 4.5 Hardware

Nothing was measured on silicon; that was the re-scope. §0 records what a port would need.

---

## 5. Deliverables

| Path | What |
|---|---|
| `src/test/scala/m68k040/bench/CoreBenchHarness.scala` | Harness extracted from `IpcBenchSpec` into a shared trait so the IPC suite and this suite drive the *same* proven DUT and cycle accounting. Adds: seed parameter on `runKernel`; `Kernel.prepMem` (preload D-memory / page tables before the CPU runs); `Kernel.mmu` (explicit MMU programming); SQ forward-hit counter; `flushToCommit` exposed on the result; **`snapWb(divEu.logic.wbObs)`** — the one-line fix that makes MUL/DIV measurable. |
| `src/test/scala/m68k040/bench/IpcBenchSpec.scala` | Reduced to its test body; mixes in the trait. Behaviour unchanged, verified by re-running it. |
| `src/test/scala/m68k040/bench/MicrobenchSpec.scala` | The suite: differential measurement core, `Stat` with raw per-seed samples, validation gate, and all kernels. Groups selectable via `MB_ONLY`, seed count via `MB_SEEDS`. |
| `tools/run_microbench.sh` | Committed re-runnable driver with host guards (refuses ≥2 heavy JVMs, refuses during a Vivado build via `flock` — never `pgrep`, which self-matches) and the `--dram-sweep` mode. Logs to `bench_logs/`. |

Reproduce:

```sh
tools/run_microbench.sh                              # core groups, ideal memory
IPC_MEM=l2:5:70 MB_ONLY=memory tools/run_microbench.sh l2:5:70
MB_ONLY=validation tools/run_microbench.sh           # timing-source gate only
tools/run_microbench.sh --dram-sweep                 # the sweep that still needs running
```

The measurement logic is deliberately separated from the timing source: kernels are plain
68k assembly strings and the analysis is differential arithmetic over cycle counts. When a
hardware cycle counter exists (§0), the same kernels and the same differential method port
across with only the counter read replaced.

---

## 6. Standing cautions for anyone quoting these numbers

1. **The DRAM row is a model input, not a measurement.** `dramCycles` is unmeasured in both
   repos. Quote the L1D and L2 rows; qualify the DRAM row or run the sweep first.
2. **No L2 capacity miss can be produced** by this model — its L2 never evicts. "L2 miss"
   here always means *cold, first touch*.
3. **Latency ≠ throughput on this core.** `mulu.w` is 12 cycles latency and 1.85 cycles
   throughput. Always state which a number is.
4. **Aggregate IPC 0.655 is not a like-for-like delta against the historical 0.53.**
5. **There is no DTLB walk number.** If you need one, §4.1 has the reproduction and the
   open question.
